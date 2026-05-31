package com.metallum.client.metal.render.mtl;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public final class MTLResourceOptions {
    private MTLResourceOptions() {
    }

    public static long of(final MTLStorageMode storageMode, final MTLHazardTrackingMode hazardTrackingMode) {
        return (storageMode.value << 4) | (hazardTrackingMode.value << 8);
    }
}
