package com.metallum.client.metal.render.mtl;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public enum MTLWinding {
    CounterClockwise(0),
    Clockwise(1);

    public final int value;

    MTLWinding(final int value) {
        this.value = value;
    }
}
