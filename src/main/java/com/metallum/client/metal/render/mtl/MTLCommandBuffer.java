package com.metallum.client.metal.render.mtl;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.lang.foreign.MemorySegment;

@Environment(EnvType.CLIENT)
public final class MTLCommandBuffer {
    private MemorySegment handle;

    MTLCommandBuffer(final MemorySegment handle) {
        this.handle = handle;
    }

    public MTLBlitCommandEncoder makeBlitCommandEncoder() {
        MemorySegment encoder = MetalNativeBridge.INSTANCE.MTLCommandBuffer_makeBlitCommandEncoder(handle());
        if (MetalNativeBridge.isNullHandle(encoder)) {
            throw new IllegalStateException("Failed to create MTLBlitCommandEncoder");
        }
        return new MTLBlitCommandEncoder(encoder);
    }

    public MTLRenderCommandEncoder makeRenderCommandEncoder(
            final MemorySegment colorTexture,
            final MemorySegment depthTexture,
            final double viewportWidth,
            final double viewportHeight,
            final int clearColorEnabled,
            final float clearColorRed,
            final float clearColorGreen,
            final float clearColorBlue,
            final float clearColorAlpha,
            final int clearDepthEnabled,
            final double clearDepth
    ) {
        MemorySegment encoder = MetalNativeBridge.INSTANCE.MTLCommandBuffer_makeRenderCommandEncoder(
                handle(),
                colorTexture,
                depthTexture,
                viewportWidth,
                viewportHeight,
                clearColorEnabled,
                clearColorRed,
                clearColorGreen,
                clearColorBlue,
                clearColorAlpha,
                clearDepthEnabled,
                clearDepth
        );
        if (MetalNativeBridge.isNullHandle(encoder)) {
            throw new IllegalStateException("Failed to create MTLRenderCommandEncoder");
        }
        return new MTLRenderCommandEncoder(encoder);
    }

    public void clearColorDepthTexturesRegion(
            final MemorySegment colorTexture,
            final float clearColorRed,
            final float clearColorGreen,
            final float clearColorBlue,
            final float clearColorAlpha,
            final MemorySegment depthTexture,
            final double clearDepth,
            final int regionX,
            final int regionY,
            final int regionWidth,
            final int regionHeight,
            final MemorySegment globalFence
    ) {
        MetalNativeBridge.INSTANCE.MTLCommandBuffer_clearColorDepthTexturesRegion(
                handle(),
                colorTexture,
                clearColorRed,
                clearColorGreen,
                clearColorBlue,
                clearColorAlpha,
                depthTexture,
                clearDepth,
                regionX,
                regionY,
                regionWidth,
                regionHeight,
                globalFence
        );
    }

    public void encodePresentTextureToDrawable(final MemorySegment drawable, final MemorySegment sourceTexture, final MemorySegment globalFence) {
        MetalNativeBridge.INSTANCE.MTLCommandBuffer_encodePresentTextureToDrawable(handle(), drawable, sourceTexture, globalFence);
    }

    public void presentDrawable(final MemorySegment drawable) {
        MetalNativeBridge.INSTANCE.MTLCommandBuffer_presentDrawable(handle(), drawable);
    }

    public void commit() {
        MetalNativeBridge.INSTANCE.MTLCommandBuffer_commit(handle());
    }

    public boolean isCompleted() {
        return MetalNativeBridge.INSTANCE.MTLCommandBuffer_isCompleted(handle()) == 1;
    }

    public boolean waitUntilCompleted(final long timeoutMs) {
        return MetalNativeBridge.INSTANCE.MTLCommandBuffer_waitUntilCompleted(handle(), Math.max(timeoutMs, 0L)) == 0;
    }

    public void pushDebugGroup(final String label) {
        MetalNativeBridge.INSTANCE.MTLCommandBuffer_pushDebugGroup(handle(), label);
    }

    public void popDebugGroup() {
        MetalNativeBridge.INSTANCE.MTLCommandBuffer_popDebugGroup(handle());
    }

    public void close() {
        if (MetalNativeBridge.isNullHandle(handle)) {
            return;
        }
        MetalNativeBridge.INSTANCE.metallum_release_object(handle);
        handle = MemorySegment.NULL;
    }

    private MemorySegment handle() {
        if (MetalNativeBridge.isNullHandle(handle)) {
            throw new IllegalStateException("MTLCommandBuffer is closed");
        }
        return handle;
    }
}
