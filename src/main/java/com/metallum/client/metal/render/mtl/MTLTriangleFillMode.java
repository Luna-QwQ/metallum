package com.metallum.client.metal.render.mtl;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public enum MTLTriangleFillMode {
    Fill(0),
    Lines(1);

    public final int value;

    MTLTriangleFillMode(final int value) {
        this.value = value;
    }
}
