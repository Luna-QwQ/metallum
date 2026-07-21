package com.metallum.client.metal.render;

import com.mojang.blaze3d.buffers.GpuBuffer;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class Stats {
    private static final AtomicLong CREATED_BUFFERS = new AtomicLong();

    private static final ConcurrentHashMap<Integer, UsageStats> USAGE_STATS = new ConcurrentHashMap<>();

    private static final class UsageStats {
        final AtomicLong count = new AtomicLong();
        final AtomicLong requestedBytes = new AtomicLong();
        final AtomicLong allocatedBytes = new AtomicLong();
    }

    public static void recordUsage(int usage, long requestedSize, long allocatedSize) {
        UsageStats stats = USAGE_STATS.computeIfAbsent(usage, k -> new UsageStats());

        stats.count.incrementAndGet();
        stats.requestedBytes.addAndGet(requestedSize);
        stats.allocatedBytes.addAndGet(allocatedSize);

        CREATED_BUFFERS.incrementAndGet();
    }
}
