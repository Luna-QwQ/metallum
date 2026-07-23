package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.systems.GpuSurfaceBackend;
import com.mojang.blaze3d.systems.SurfaceException;
import com.mojang.blaze3d.textures.GpuTexture;
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

        MetalNativeBridge.metallum_configure_layer(
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
    public void acquireNextTexture() {
    }

    @Override
    public void blitFromTexture(final @NonNull CommandEncoderBackend commandEncoder, final @NonNull GpuTextureView textureView) {
        if (!(commandEncoder instanceof MetalCommandEncoder metalEncoder)) {
            throw new IllegalArgumentException("Metal surface requires MetalCommandEncoder");
        }

        // If the Iris final pass rendered an offscreen texture this frame,
        // present that instead of vanilla's main render target. The native
        // present path samples the source texture via a dedicated present
        // pipeline (it does not blit directly), so the RGBA8 source format
        // need not match the BGRA8 drawable format. If no final pass ran,
        // fall back to presenting vanilla's texture as normal.
        GpuTextureView effectiveView = textureView;
        MetalGpuTextureView irisView = MetalIrisRenderer.consumePendingFinalPassView();
        if (irisView != null) {
            effectiveView = irisView;
        }

        metalEncoder.presentTextureToDrawable(metalLayer, effectiveView);
        this.pendingPresentEncoder = metalEncoder;

        // The Iris final-pass texture has now been encoded into the present
        // command. Close it — the device's deferred-release queue (3-submit
        // delay) keeps the native texture alive until the present command
        // buffer completes. Vanilla's texture is owned by vanilla and left
        // untouched.
        if (irisView != null) {
            closeIrisView(irisView);
        }
    }

    /**
     * Closes an Iris final-pass view and its underlying texture via the
     * device's deferred-release queue. Mirrors
     * {@link MetalIrisRenderer}'s private {@code closePendingView}, but
     * inlined here so {@code MetalSurface} owns the lifecycle of the view
     * it consumed.
     */
    private void closeIrisView(final MetalGpuTextureView view) {
        try {
            GpuTexture tex = view.texture();
            view.close();
            tex.close();
        } catch (Exception ignored) {
            // Best-effort cleanup; the deferred-release queue will reclaim
            // the native handle regardless.
        }
    }

    @Override
    public void present() {
        pendingPresentEncoder.submit();
    }

    @Override
    public void close() {
    }

    @Override
    public @NonNull Collection<GpuSurface.PresentMode> supportedPresentModes() {
        return SUPPORTED_PRESENT_MODES;
    }
}
