package com.metallum.client.metal.render.mtl;

import com.mojang.blaze3d.textures.FilterMode;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public enum MTLSamplerMinMagFilter {
    Nearest(0L),
    Linear(1L);

    public final long value;

    MTLSamplerMinMagFilter(final long value) {
        this.value = value;
    }

    public static MTLSamplerMinMagFilter from(final FilterMode filterMode) {
        return switch (filterMode) {
            case NEAREST -> Nearest;
            case LINEAR -> Linear;
        };
    }
}
