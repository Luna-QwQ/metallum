package com.metallum.client.metal.render;

import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.systems.GpuSurfaceBackend;
import com.mojang.blaze3d.systems.SurfaceException;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.sun.jna.Pointer;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.NonNull;

@Environment(EnvType.CLIENT)
final class MetalSurface implements GpuSurfaceBackend {
	private static final Set<GpuSurface.PresentMode> SUPPORTED_PRESENT_MODES = EnumSet.of(GpuSurface.PresentMode.FIFO, GpuSurface.PresentMode.MAILBOX);
	private final MetalDevice device;
	private final MetalCocoaBootstrap.BootstrapContext bootstrap;
	private GpuSurface.Configuration configuration;
	private Pointer drawable;
	private MetalCommandEncoder pendingPresentEncoder;

	MetalSurface(final long ignoredWindowHandle, final MetalDevice device, final MetalCocoaBootstrap.BootstrapContext bootstrap) {
		this.device = device;
		this.bootstrap = bootstrap;
	}

	@Override
	public void configure(final GpuSurface.Configuration config) throws SurfaceException {
		if (config.width() <= 0 || config.height() <= 0) {
			throw new SurfaceException("Metal surface configuration must be positive, got " + config.width() + "x" + config.height());
		}

		MetalNativeBridge.INSTANCE.metallum_configure_layer(
			this.bootstrap.metalLayer(),
			config.width(),
			config.height(),
			config.presentMode() == GpuSurface.PresentMode.MAILBOX ? 1 : 0
		);

		this.configuration = config;
	}

	@Override
	public boolean isSuboptimal() {
		return false;
	}

	@Override
	public void acquireNextTexture() throws SurfaceException {
		if (this.configuration == null) {
			throw new SurfaceException("Metal surface must be configured before acquire");
		}
		if (!MetalProbe.isNullPointer(this.drawable)) {
			throw new SurfaceException("Metal drawable is already acquired");
		}
		this.drawable = MetalNativeBridge.INSTANCE.CAMetalLayer_nextDrawable(this.bootstrap.metalLayer());
		if (MetalProbe.isNullPointer(this.drawable)) {
			throw new SurfaceException("Failed to acquire Metal drawable");
		}
	}

	@Override
	public void blitFromTexture(final @NonNull CommandEncoderBackend commandEncoder, final @NonNull GpuTextureView textureView) {
		if (!(commandEncoder instanceof MetalCommandEncoder metalEncoder)) {
			throw new IllegalArgumentException("Metal surface requires MetalCommandEncoder");
		}
		if (MetalProbe.isNullPointer(this.drawable)) {
			throw new IllegalStateException("Metal surface has no acquired drawable");
		}

		metalEncoder.flushPendingTextureViewClear(textureView);
		metalEncoder.submitRenderPass();
		metalEncoder.endBlitEncoder();
		metalEncoder.endRenderEncoder();
		MetalGpuTexture source = (MetalGpuTexture)textureView.texture();
		MetalNativeBridge.INSTANCE.MTLCommandBuffer_encodePresentTextureToDrawable(
			metalEncoder.commandBuffer(),
			this.drawable,
			source.nativeHandle()
		);

		this.pendingPresentEncoder = metalEncoder;
	}

	@Override
	public void present() {
		if (this.pendingPresentEncoder == null || MetalProbe.isNullPointer(this.drawable)) {
			throw new IllegalStateException("Metal surface has no pending drawable present");
		}
		Pointer presentedDrawable = this.drawable;
		this.drawable = null;
		MetalCommandEncoder encoder = this.pendingPresentEncoder;
		this.pendingPresentEncoder = null;
		encoder.submit();

		Pointer presentCommandBuffer = MetalNativeBridge.INSTANCE.MTLCommandQueue_makeCommandBuffer(
			this.device.commandQueue(),
			this.device.useLabels() ? "Metallum present" : null
		);
		if (MetalProbe.isNullPointer(presentCommandBuffer)) {
			MetalNativeBridge.INSTANCE.metallum_release_object(presentedDrawable);
			throw new IllegalStateException("Failed to create Metal present command buffer");
		}

		MetalNativeBridge.INSTANCE.MTLCommandBuffer_presentDrawable(presentCommandBuffer, presentedDrawable);
		MetalNativeBridge.INSTANCE.MTLCommandBuffer_commit(presentCommandBuffer);
		MetalNativeBridge.INSTANCE.metallum_release_object(presentCommandBuffer);
		MetalNativeBridge.INSTANCE.metallum_release_object(presentedDrawable);
	}

	@Override
	public void close() {
		if (!MetalProbe.isNullPointer(this.drawable)) {
			MetalNativeBridge.INSTANCE.metallum_release_object(this.drawable);
			this.drawable = null;
		}
	}

	@Override
	public @NonNull Collection<GpuSurface.PresentMode> supportedPresentModes() {
		return SUPPORTED_PRESENT_MODES;
	}
}
