package com.metallum.client.metal.render.mtl;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public enum MTLCullMode {
    None(0),
    Front(1),
    Back(2);

    public final int value;

    MTLCullMode(final int value) {
        this.value = value;
    }
}
