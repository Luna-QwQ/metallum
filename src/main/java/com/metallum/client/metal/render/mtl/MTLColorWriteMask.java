package com.metallum.client.metal.render.mtl;

import com.mojang.blaze3d.pipeline.ColorTargetState;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public enum MTLColorWriteMask {
    None(0L),
    Alpha(1L),
    Blue(2L),
    Green(4L),
    Red(8L),
    All(15L);

    public final long value;

    MTLColorWriteMask(final long value) {
        this.value = value;
    }

    public static long from(@ColorTargetState.WriteMask final int blazeMask) {
        long mask = 0L;
        if ((blazeMask & ColorTargetState.WRITE_RED) != 0) mask |= Red.value;
        if ((blazeMask & ColorTargetState.WRITE_GREEN) != 0) mask |= Green.value;
        if ((blazeMask & ColorTargetState.WRITE_BLUE) != 0) mask |= Blue.value;
        if ((blazeMask & ColorTargetState.WRITE_ALPHA) != 0) mask |= Alpha.value;
        return mask;
    }
}
