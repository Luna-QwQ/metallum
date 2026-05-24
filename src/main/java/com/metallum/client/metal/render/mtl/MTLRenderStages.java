package com.metallum.client.metal.render.mtl;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public enum MTLRenderStages {
    Vertex(1L),
    Fragment(2L),
    VertexAndFragment(3L);

    public final long value;

    MTLRenderStages(final long value) {
        this.value = value;
    }
}
