package com.metallum.client.metal.render.mtl;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.lang.foreign.MemorySegment;

@Environment(EnvType.CLIENT)
public abstract class MTLCommandEncoder {
    MemorySegment handle;

    MTLCommandEncoder(final MemorySegment handle) {
        this.handle = handle;
    }

    MemorySegment handle() {
        if (MetalNativeBridge.isNullHandle(this.handle)) {
            throw new IllegalStateException(getClass().getSimpleName() + " is closed");
        }
        return this.handle;
    }

    public void endEncoding() {
        if (MetalNativeBridge.isNullHandle(this.handle)) {
            return;
        }
        MetalNativeBridge.INSTANCE.MTLCommandEncoder_endEncoding(this.handle);
        MetalNativeBridge.INSTANCE.metallum_release_object(this.handle);
        this.handle = MemorySegment.NULL;
    }
}
