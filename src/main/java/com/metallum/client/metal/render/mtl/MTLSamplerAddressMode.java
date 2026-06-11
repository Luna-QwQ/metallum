package com.metallum.client.metal.render.mtl;

import com.mojang.blaze3d.textures.AddressMode;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public enum MTLSamplerAddressMode {
    ClampToEdge(0L),
    MirrorClampToEdge(1L),
    Repeat(2L),
    MirrorRepeat(3L),
    ClampToZero(4L),
    ClampToBorderColor(5L);

    public final long value;

    MTLSamplerAddressMode(final long value) {
        this.value = value;
    }

    public static MTLSamplerAddressMode from(final AddressMode addressMode) {
        return switch (addressMode) {
            case REPEAT -> Repeat;
            case CLAMP_TO_EDGE -> ClampToEdge;
        };
    }
}
