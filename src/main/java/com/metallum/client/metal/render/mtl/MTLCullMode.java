package com.metallum.client.metal.render.mtl;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public enum MTLCullMode {
    None(0L),
    Front(1L),
    Back(2L);

    public final long value;

    MTLCullMode(final long value) {
        this.value = value;
    }
}
