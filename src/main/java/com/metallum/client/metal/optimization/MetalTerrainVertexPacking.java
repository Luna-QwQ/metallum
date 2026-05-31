package com.metallum.client.metal.optimization;

import com.metallum.client.metal.render.mtl.MTLVertexFormat;
import com.metallum.mixin.optimization.accessor.MeshDataAccessor;
import com.metallum.mixin.optimization.accessor.SectionCompilerResultsAccessor;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Map;

@Environment(EnvType.CLIENT)
public final class MetalTerrainVertexPacking {
    public static final int VANILLA_BLOCK_VERTEX_SIZE = 28;
    public static final int PACKED_TERRAIN_VERTEX_SIZE = 24;

    private static final int SRC_POSITION_X = 0;
    private static final int SRC_COLOR = 12;
    private static final int SRC_UV0_U = 16;
    private static final int SRC_UV0_V = 20;
    private static final int SRC_UV2 = 24;

    private static final int DST_POSITION = 0;
    private static final int DST_COLOR = 12;
    private static final int DST_UV0_U = 16;
    private static final int DST_UV0_V = 18;
    private static final int DST_UV2 = 20;

    private static final long METAL_VERTEX_FORMAT_FLOAT3 = MTLVertexFormat.Float3.value;
    private static final long METAL_VERTEX_FORMAT_UCHAR4_NORMALIZED = MTLVertexFormat.UChar4Normalized.value;
    private static final long METAL_VERTEX_FORMAT_USHORT2_NORMALIZED = MTLVertexFormat.UShort2Normalized.value;
    private static final long METAL_VERTEX_FORMAT_SHORT2 = MTLVertexFormat.Short2.value;

    private static final long[] PACKED_ATTRIBUTE_FORMATS = {
            METAL_VERTEX_FORMAT_FLOAT3,
            METAL_VERTEX_FORMAT_UCHAR4_NORMALIZED,
            METAL_VERTEX_FORMAT_USHORT2_NORMALIZED,
            METAL_VERTEX_FORMAT_SHORT2
    };
    private static final long[] PACKED_ATTRIBUTE_OFFSETS = {
            DST_POSITION,
            DST_COLOR,
            DST_UV0_U,
            DST_UV2
    };

    private MetalTerrainVertexPacking() {
    }

    public static boolean isPackedTerrainPipeline(final String pipelineLocation) {
        return isTerrainPipeline(pipelineLocation);
    }

    public static long[] packedAttributeFormats() {
        return PACKED_ATTRIBUTE_FORMATS;
    }

    public static long[] packedAttributeOffsets() {
        return PACKED_ATTRIBUTE_OFFSETS;
    }

    public static int vertexSizeFor(final VertexFormat format) {
        return format == DefaultVertexFormat.BLOCK ? PACKED_TERRAIN_VERTEX_SIZE : format.getVertexSize();
    }

    public static void optimize(final SectionCompiler.Results results, final SectionBufferBuilderPack sectionBufferBuilderPack) {
        Map<ChunkSectionLayer, MeshData> renderedLayers = ((SectionCompilerResultsAccessor) (Object) results).metallum$getRenderedLayers();
        for (Map.Entry<ChunkSectionLayer, MeshData> entry : renderedLayers.entrySet()) {
            optimizeLayer(entry.getKey(), entry.getValue(), sectionBufferBuilderPack.buffer(entry.getKey()));
        }
    }

    private static void optimizeLayer(final ChunkSectionLayer layer, final MeshData mesh, final ByteBufferBuilder target) {
        if (mesh == null || !isPackableLayer(layer)) {
            return;
        }

        MeshData.DrawState drawState = mesh.drawState();
        if (!isBlockQuadMesh(drawState)) {
            return;
        }

        int vertexCount = drawState.vertexCount();
        int sourceSize = Math.multiplyExact(vertexCount, VANILLA_BLOCK_VERTEX_SIZE);
        ByteBuffer source = mesh.vertexBuffer();
        if (source.remaining() < sourceSize) {
            return;
        }

        if (!source.isDirect()) {
            return;
        }

        long sourceBase = MemoryUtil.memAddress(source);
        MetalTerrainFaceCulling.CullableFaceLayout faceLayout = MetalTerrainFaceCulling.buildCullableFaceLayout(layer, drawState, sourceBase);
        long destinationBase = target.reserve(Math.multiplyExact(vertexCount, PACKED_TERRAIN_VERTEX_SIZE));
        boolean packed = faceLayout == null
                ? packVertices(sourceBase, destinationBase, vertexCount)
                : packVertices(sourceBase, destinationBase, faceLayout);

        ByteBufferBuilder.Result packedVertexBuffer = target.build();
        if (!packed || packedVertexBuffer == null) {
            if (packedVertexBuffer != null) {
                packedVertexBuffer.close();
            }
            return;
        }

        ByteBufferBuilder.Result originalVertexBuffer = mesh.vertexBufferSlice();
        ((MeshDataAccessor) mesh).metallum$setVertexBuffer(packedVertexBuffer);
        originalVertexBuffer.close();
        if (faceLayout != null) {
            MetalTerrainFaceCulling.attachSegments(mesh, faceLayout.segments());
        }
    }

    private static boolean isTerrainPipeline(final String pipelineLocation) {
        return switch (pipelineLocation) {
            case "minecraft:pipeline/solid_terrain",
                 "minecraft:pipeline/cutout_terrain",
                 "minecraft:pipeline/translucent_terrain" -> true;
            default -> false;
        };
    }

    private static boolean isPackableLayer(final ChunkSectionLayer layer) {
        return layer == ChunkSectionLayer.SOLID || layer == ChunkSectionLayer.CUTOUT || layer == ChunkSectionLayer.TRANSLUCENT;
    }

    private static boolean isBlockQuadMesh(final MeshData.DrawState drawState) {
        return drawState.format() == DefaultVertexFormat.BLOCK && drawState.primitiveTopology() == PrimitiveTopology.QUADS;
    }

    private static boolean packVertices(final long sourceBase, final long destinationBase, final int vertexCount) {
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            if (!packVertex(sourceBase, destinationBase, vertex, vertex)) {
                return false;
            }
        }
        return true;
    }

    private static boolean packVertices(
            final long sourceBase,
            final long destinationBase,
            final MetalTerrainFaceCulling.CullableFaceLayout faceLayout
    ) {
        for (int quad = 0; quad < faceLayout.quadCount(); quad++) {
            int sourceVertex = quad * 4;
            int destinationVertex = faceLayout.takeDestinationVertex(quad);
            for (int vertexInQuad = 0; vertexInQuad < 4; vertexInQuad++) {
                if (!packVertex(sourceBase, destinationBase, sourceVertex + vertexInQuad, destinationVertex + vertexInQuad)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean packVertex(final long sourceBase, final long destinationBase, final int sourceVertex, final int destinationVertex) {
        long src = sourceBase + (long) sourceVertex * VANILLA_BLOCK_VERTEX_SIZE;
        long dst = destinationBase + (long) destinationVertex * PACKED_TERRAIN_VERTEX_SIZE;

        float u = MemoryUtil.memGetFloat(src + SRC_UV0_U);
        float v = MemoryUtil.memGetFloat(src + SRC_UV0_V);


        MemoryUtil.memCopy(src + SRC_POSITION_X, dst + DST_POSITION, 12);
        MemoryUtil.memCopy(src + SRC_COLOR, dst + DST_COLOR, Integer.BYTES);
        MemoryUtil.memPutShort(dst + DST_UV0_U, toUnorm16(u));
        MemoryUtil.memPutShort(dst + DST_UV0_V, toUnorm16(v));
        MemoryUtil.memCopy(src + SRC_UV2, dst + DST_UV2, 4);

        return true;
    }

    private static short toUnorm16(final float value) {
        float clamped = Math.clamp(value, 0.0F, 1.0F);
        return (short) Math.round(clamped * 65535.0F);
    }
}
