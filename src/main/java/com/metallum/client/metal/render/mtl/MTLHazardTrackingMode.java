package com.metallum.client.metal.render.mtl;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public enum MTLHazardTrackingMode {
    Default(0L),
    Untracked(1L << 8),
    Tracked(2L << 8);

    public final long value;

    MTLHazardTrackingMode(final long value) {
        this.value = value;
    }
}
