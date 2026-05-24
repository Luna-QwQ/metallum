package com.metallum.client.metal.render.mtl;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public enum MTLStorageMode {
    Shared(0L),
    Managed(1L << 4),
    Private(2L << 4);

    public final long value;

    MTLStorageMode(final long value) {
        this.value = value;
    }
}
