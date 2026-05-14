package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

@Environment(EnvType.CLIENT)
final class MetalBufferPool {
    private final Map<Key, ArrayDeque<MemorySegment>> available = new HashMap<>();

    @Nullable
    MemorySegment acquire(final long size, final long resourceOptions) {
        Key key = new Key(normalizeSize(size), resourceOptions);
        ArrayDeque<MemorySegment> queue = this.available.get(key);
        if (queue == null) {
            return null;
        }
        MemorySegment handle = queue.pollFirst();
        if (queue.isEmpty()) {
            this.available.remove(key);
        }
        return handle;
    }

    void recycle(final MemorySegment handle, final long size, final long resourceOptions) {
        this.available.computeIfAbsent(new Key(normalizeSize(size), resourceOptions), ignored -> new ArrayDeque<>()).addFirst(handle);
    }

    long allocationSize(final long requestedSize) {
        return normalizeSize(requestedSize);
    }

    void close() {
        for (ArrayDeque<MemorySegment> queue : this.available.values()) {
            for (MemorySegment handle : queue) {
                MetalNativeBridge.INSTANCE.metallum_release_object(handle);
            }
        }
        this.available.clear();
    }

    private record Key(long size, long resourceOptions) {
    }

    private static long normalizeSize(final long size) {
        if (size <= 0L) {
            return 0L;
        }
        if (size <= 64L * 1024L) {
            return nextPowerOfTwo(Math.max(size, 4L * 1024L));
        }
        if (size <= 1024L * 1024L) {
            return roundUp(size, 64L * 1024L);
        }
        return roundUp(size, 1024L * 1024L);
    }

    private static long nextPowerOfTwo(final long value) {
        long result = 1L;
        while (result < value) {
            result <<= 1;
        }
        return result;
    }

    private static long roundUp(final long value, final long alignment) {
        return ((value + alignment - 1L) / alignment) * alignment;
    }
}
