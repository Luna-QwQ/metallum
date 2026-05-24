package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLPixelFormat;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTexture;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;

@Environment(EnvType.CLIENT)
final class MetalGpuTexture extends GpuTexture {
    private final MetalDevice device;
    private final MTLPixelFormat mtlPixelFormat;
    private boolean closed;
    private int views = 1;
    @Nullable
    private MemorySegment nativeHandle;

    MetalGpuTexture(
            final MetalDevice device,
            @GpuTexture.Usage final int usage,
            final String label,
            final GpuFormat format,
            final int width,
            final int height,
            final int depthOrLayers,
            final int mipLevels
    ) {
        super(usage, label, format, width, height, depthOrLayers, mipLevels);
        this.device = device;
        if (usePresentCompatibleBgra(usage) && format == GpuFormat.RGBA8_UNORM) {
            this.mtlPixelFormat = MTLPixelFormat.BGRA8Unorm;
        } else {
            this.mtlPixelFormat = MetalPipelineSupport.toMtlPixelFormat(format);
        }
        this.nativeHandle = MetalNativeBridge.INSTANCE.metallum_create_texture_2d(
                device.metalDeviceHandle(),
                this.mtlPixelFormat.value,
                width,
                height,
                depthOrLayers,
                mipLevels,
                (usage & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0 ? 1L : 0L,
                toMtlTextureUsage(usage),
                2L,
                label
        );
    }

    int pixelSize() {
        return this.getFormat().pixelSize();
    }

    MemorySegment nativeHandle() {
        if (this.nativeHandle == null) {
            throw new IllegalStateException("Native Metal texture is closed");
        }
        return this.nativeHandle;
    }

    void queueNativeRelease(final MemorySegment handle) {
        this.device.queueResourceRelease(handle);
    }

    void addView() {
        this.views++;
    }

    void removeView() {
        this.views--;
        if (this.views < 0) {
            throw new IllegalStateException("Too many views removed from texture");
        }
        if (this.closed && this.views == 0 && this.nativeHandle != null) {
            MemorySegment handle = this.nativeHandle;
            this.nativeHandle = null;
            this.device.queueResourceRelease(handle);
        }
    }

    MTLPixelFormat mtlPixelFormat() {
        return this.mtlPixelFormat;
    }

    MTLPixelFormat mtlStencilPixelFormat() {
        return this.mtlPixelFormat.hasStencil() ? this.mtlPixelFormat : MTLPixelFormat.Invalid;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.removeView();
    }

    @Override
    public boolean isClosed() {
        return this.closed;
    }

    private static boolean usePresentCompatibleBgra(@GpuTexture.Usage final int usage) {
        // CAMetalLayer presents BGRA. Minecraft may still mark the main render target
        // as COPY_DST, so key only off render-attachment usage to keep present on blit.
        return (usage & GpuTexture.USAGE_RENDER_ATTACHMENT) != 0;
    }

    private static long toMtlTextureUsage(@GpuTexture.Usage final int usage) {
        long result = 0L;
        if ((usage & GpuTexture.USAGE_TEXTURE_BINDING) != 0 || (usage & GpuTexture.USAGE_COPY_DST) != 0 || (usage & GpuTexture.USAGE_COPY_SRC) != 0) {
            result |= 1L; // MTLTextureUsageShaderRead
        }
        if ((usage & GpuTexture.USAGE_RENDER_ATTACHMENT) != 0) {
            result |= 4L; // MTLTextureUsageRenderTarget
            result |= 1L; // Render targets are sampled in presentation/composite paths.
        }
        return result == 0L ? 1L : result;
    }
}
