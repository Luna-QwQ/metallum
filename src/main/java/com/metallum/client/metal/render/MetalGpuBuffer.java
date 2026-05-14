package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@Environment(EnvType.CLIENT)
final class MetalGpuBuffer extends GpuBuffer {
    private static final long MTL_RESOURCE_STORAGE_MODE_PRIVATE = 2L << 4;
    private final MetalDevice device;
    private final boolean cpuAccessible;
    private final long resourceOptions;
    private final long allocationSize;
    @Nullable
    private MemorySegment nativeHandle;
    @Nullable
    private ByteBuffer storage;
    private boolean closed;

    MetalGpuBuffer(final MetalDevice device, @GpuBuffer.Usage final int usage, final long size) {
        super(usage, size);
        this.device = device;

        this.cpuAccessible = isCpuAccessible(usage);
        this.resourceOptions = toMtlResourceOptions(usage);
        this.allocationSize = device.allocationSize(size);
        MemorySegment pooledHandle = device.acquireReusableBuffer(this.allocationSize, this.resourceOptions);
        this.nativeHandle = pooledHandle != null
                ? pooledHandle
                : MetalNativeBridge.INSTANCE.metallum_create_buffer(device.metalDeviceHandle(), this.allocationSize, this.resourceOptions);
        if (MetalProbe.isNullHandle(this.nativeHandle)) {
            throw new IllegalStateException("Failed to create Metal buffer");
        }

        if (this.cpuAccessible) {
            MemorySegment contents = MetalNativeBridge.INSTANCE.metallum_get_buffer_contents(this.nativeHandle);
            if (MetalProbe.isNullHandle(contents)) {
                MetalNativeBridge.INSTANCE.metallum_release_object(this.nativeHandle);
                this.nativeHandle = null;
                throw new IllegalStateException("MTLBuffer.contents returned null");
            }

            this.storage = MetalNativeBridge.INSTANCE.nativeByteBufferView(contents, this.allocationSize).order(ByteOrder.nativeOrder());
        } else {
            this.storage = null;
        }
    }

    ByteBuffer sliceStorage(final long offset, final long length) {
        if (this.storage == null) {
            throw new IllegalStateException("Buffer is not CPU-accessible");
        }

        ByteBuffer duplicate = this.storage.duplicate().order(this.storage.order());
        duplicate.position(Math.toIntExact(offset));
        duplicate.limit(Math.toIntExact(offset + length));
        return duplicate.slice().order(this.storage.order());
    }

    ByteBuffer fullStorageView() {
        if (this.storage == null) {
            throw new IllegalStateException("Buffer is not CPU-accessible");
        }
        return this.storage.duplicate().order(this.storage.order());
    }

    MemorySegment nativeHandle() {
        if (this.nativeHandle == null) {
            throw new IllegalStateException("Native Metal buffer is closed");
        }
        return this.nativeHandle;
    }

    @Override
    public boolean isClosed() {
        return this.closed || this.nativeHandle == null;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.storage = null;
        if (this.nativeHandle != null) {
            MemorySegment handle = this.nativeHandle;
            this.nativeHandle = null;
            this.device.queueBufferRecycle(handle, this.allocationSize, this.resourceOptions);
        }
    }

    @Override
    public GpuBufferSlice.@NonNull MappedView map(final long offset, final long length, final boolean read, final boolean write) {
        if (this.isClosed()) {
            throw new IllegalStateException("Buffer already closed");
        }
        if (!read && !write) {
            throw new IllegalArgumentException("At least read or write must be true");
        }
        if (read && (this.usage() & GpuBuffer.USAGE_MAP_READ) == 0) {
            throw new IllegalStateException("Buffer is not readable");
        }
        if (write && (this.usage() & GpuBuffer.USAGE_MAP_WRITE) == 0) {
            throw new IllegalStateException("Buffer is not writable");
        }
        ByteBuffer mapped = this.sliceStorage(offset, length);
        return new GpuBufferSlice.MappedView(this.slice(offset, length), mapped, () -> {
        });
    }

    private static boolean isCpuAccessible(@GpuBuffer.Usage final int usage) {
        return (usage & GpuBuffer.USAGE_MAP_READ) != 0
                || (usage & GpuBuffer.USAGE_MAP_WRITE) != 0
                || (usage & GpuBuffer.USAGE_HINT_CLIENT_STORAGE) != 0;
    }

    private static long toMtlResourceOptions(@GpuBuffer.Usage final int usage) {
        return isCpuAccessible(usage) ? 0L : MTL_RESOURCE_STORAGE_MODE_PRIVATE;
    }
}
