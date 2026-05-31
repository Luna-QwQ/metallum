package com.metallum.client.metal.render.mtl;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public enum MTLTextureUsage {
    Unknown(0L),
    ShaderRead(1L),
    ShaderWrite(2L),
    RenderTarget(4L),
    PixelFormatView(8L),
    ShaderAtomic(16L);

    public final long value;

    MTLTextureUsage(final long value) {
        this.value = value;
    }
}
