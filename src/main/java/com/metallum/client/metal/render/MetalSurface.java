package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.systems.GpuSurfaceBackend;
import com.mojang.blaze3d.systems.SurfaceException;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.NonNull;

import java.lang.foreign.MemorySegment;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

@Environment(EnvType.CLIENT)
final class MetalSurface implements GpuSurfaceBackend {
    private static final Set<GpuSurface.PresentMode> SUPPORTED_PRESENT_MODES = EnumSet.of(GpuSurface.PresentMode.FIFO, GpuSurface.PresentMode.MAILBOX);
    private final MetalDevice device;
    private final MemorySegment metalLayer;
    private GpuSurface.Configuration configuration;
    private MemorySegment drawable = MemorySegment.NULL;
    private MetalCommandEncoder pendingPresentEncoder;

    MetalSurface(final MetalDevice device, final MemorySegment metalLayer) {
        this.device = device;
        this.metalLayer = metalLayer;
    }

    @Override
    public void configure(final GpuSurface.Configuration config) throws SurfaceException {
        if (config.width() <= 0 || config.height() <= 0) {
            throw new SurfaceException("Metal surface configuration must be positive, got " + config.width() + "x" + config.height());
        }

        MetalNativeBridge.INSTANCE.metallum_configure_layer(
                this.metalLayer,
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
        if (this.hasDrawable()) {
            throw new SurfaceException("Metal drawable is already acquired");
        }
        this.drawable = MetalNativeBridge.INSTANCE.CAMetalLayer_nextDrawable(this.metalLayer);
        if (!this.hasDrawable()) {
            throw new SurfaceException("Failed to acquire Metal drawable");
        }
    }

    @Override
    public void blitFromTexture(final @NonNull CommandEncoderBackend commandEncoder, final @NonNull GpuTextureView textureView) {
        if (!(commandEncoder instanceof MetalCommandEncoder metalEncoder)) {
            throw new IllegalArgumentException("Metal surface requires MetalCommandEncoder");
        }
        if (!this.hasDrawable()) {
            throw new IllegalStateException("Metal surface has no acquired drawable");
        }

        metalEncoder.flushPendingClear(MetalCommandEncoder.castTexture(textureView.texture()));
        metalEncoder.submitRenderPass();
        metalEncoder.endEncoder();
        MetalGpuTexture source = (MetalGpuTexture) textureView.texture();
        var commandBuffer = metalEncoder.commandBuffer();
        commandBuffer.encodePresentTextureToDrawable(this.drawable, source.nativeHandle());
        commandBuffer.presentDrawable(this.drawable);

        this.pendingPresentEncoder = metalEncoder;
    }

    @Override
    public void present() {
        if (this.pendingPresentEncoder == null || !this.hasDrawable()) {
            throw new IllegalStateException("Metal surface has no pending drawable present");
        }

        drawable = MemorySegment.NULL;
        pendingPresentEncoder.submit();
        pendingPresentEncoder = null;
    }

    @Override
    public void close() {
        if (this.hasDrawable()) {
            MetalNativeBridge.INSTANCE.metallum_release_object(this.drawable);
            this.drawable = MemorySegment.NULL;
        }
    }

    private boolean hasDrawable() {
        return !MetalNativeBridge.isNullHandle(this.drawable);
    }

    @Override
    public @NonNull Collection<GpuSurface.PresentMode> supportedPresentModes() {
        return SUPPORTED_PRESENT_MODES;
    }
}
