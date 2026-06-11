package com.metallum.client.metal.render.mtl;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public enum MTLSamplerMipFilter {
    NotMipmapped(0L),
    Nearest(1L),
    Linear(2L);

    public final long value;

    MTLSamplerMipFilter(final long value) {
        this.value = value;
    }
}
