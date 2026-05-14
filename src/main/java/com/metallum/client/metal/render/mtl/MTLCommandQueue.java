package com.metallum.client.metal.render.mtl;

import com.metallum.client.metal.render.MetalProbe;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;

@Environment(EnvType.CLIENT)
public final class MTLCommandQueue implements AutoCloseable {
    private MemorySegment handle;

    private MTLCommandQueue(final MemorySegment handle) {
        this.handle = handle;
    }

    public static MTLCommandQueue create(final MemorySegment device) {
        MemorySegment handle = MetalNativeBridge.INSTANCE.MTLDevice_makeCommandQueue(device);
        if (MetalProbe.isNullHandle(handle)) {
            throw new IllegalStateException("Failed to create Metal command queue");
        }
        return new MTLCommandQueue(handle);
    }

    public MemorySegment makeCommandBuffer(@Nullable final String label) {
        MemorySegment commandBuffer = MetalNativeBridge.INSTANCE.MTLCommandQueue_makeCommandBuffer(this.handle, label);
        if (MetalProbe.isNullHandle(commandBuffer)) {
            throw new IllegalStateException("Failed to create MTLCommandBuffer");
        }
        return commandBuffer;
    }

    @Override
    public void close() {
        if (MetalProbe.isNullHandle(this.handle)) {
            return;
        }
        MetalNativeBridge.INSTANCE.metallum_release_object(this.handle);
        this.handle = MemorySegment.NULL;
    }
}
