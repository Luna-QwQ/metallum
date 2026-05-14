package com.metallum.mixin.optimization.accessor;

import com.mojang.blaze3d.vertex.MeshData;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(SectionCompiler.Results.class)
public interface SectionCompilerResultsAccessor {
    @Accessor("renderedLayers")
    Map<ChunkSectionLayer, MeshData> metallum$getRenderedLayers();
}
