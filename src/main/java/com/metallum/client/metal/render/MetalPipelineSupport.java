package com.metallum.client.metal.render;

import com.metallum.client.metal.render.mtl.MTLPixelFormat;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

@Environment(EnvType.CLIENT)
final class MetalPipelineSupport {
    static final long TRIANGLE_PRIMITIVE = 0L;
    static final long TRIANGLE_FAN_PRIMITIVE = 5L;

    private MetalPipelineSupport() {
    }

    static boolean sameHandle(@Nullable final MemorySegment left, @Nullable final MemorySegment right) {
        long leftValue = left == null ? 0L : left.address();
        long rightValue = right == null ? 0L : right.address();
        return leftValue == rightValue;
    }

    static List<String> vertexAttributeNames(final RenderPipeline pipeline) {
        List<String> names = new ArrayList<>();
        for (VertexFormat binding : pipeline.getVertexFormatBindings()) {
            if (binding != null) {
                for (VertexFormatElement element : binding.getElements()) {
                    names.add(element.name());
                }
            }
        }
        return names;
    }

    static MTLPixelFormat toMtlPixelFormat(final GpuFormat format) {
        return switch (format) {
            case R8_UNORM -> MTLPixelFormat.R8Unorm;
            case R8_SNORM -> MTLPixelFormat.R8Snorm;
            case R8_UINT -> MTLPixelFormat.R8Uint;
            case R8_SINT -> MTLPixelFormat.R8Sint;
            case R16_UNORM -> MTLPixelFormat.R16Unorm;
            case R16_SNORM -> MTLPixelFormat.R16Snorm;
            case R16_UINT -> MTLPixelFormat.R16Uint;
            case R16_SINT -> MTLPixelFormat.R16Sint;
            case R16_FLOAT -> MTLPixelFormat.R16Float;
            case RG8_UNORM -> MTLPixelFormat.RG8Unorm;
            case RG8_SNORM -> MTLPixelFormat.RG8Snorm;
            case RG8_UINT -> MTLPixelFormat.RG8Uint;
            case RG8_SINT -> MTLPixelFormat.RG8Sint;
            case R32_UINT -> MTLPixelFormat.R32Uint;
            case R32_SINT -> MTLPixelFormat.R32Sint;
            case R32_FLOAT -> MTLPixelFormat.R32Float;
            case RG16_UNORM -> MTLPixelFormat.RG16Unorm;
            case RG16_SNORM -> MTLPixelFormat.RG16Snorm;
            case RG16_UINT -> MTLPixelFormat.RG16Uint;
            case RG16_SINT -> MTLPixelFormat.RG16Sint;
            case RG16_FLOAT -> MTLPixelFormat.RG16Float;
            case RGBA8_UNORM -> MTLPixelFormat.RGBA8Unorm;
            case RGBA8_SNORM -> MTLPixelFormat.RGBA8Snorm;
            case RGBA8_UINT -> MTLPixelFormat.RGBA8Uint;
            case RGBA8_SINT -> MTLPixelFormat.RGBA8Sint;
            case RGB10A2_UNORM -> MTLPixelFormat.RGB10A2Unorm;
            case RG11B10_FLOAT -> MTLPixelFormat.RG11B10Float;
            case RG32_UINT -> MTLPixelFormat.RG32Uint;
            case RG32_SINT -> MTLPixelFormat.RG32Sint;
            case RG32_FLOAT -> MTLPixelFormat.RG32Float;
            case RGBA16_UNORM -> MTLPixelFormat.RGBA16Unorm;
            case RGBA16_SNORM -> MTLPixelFormat.RGBA16Snorm;
            case RGBA16_UINT -> MTLPixelFormat.RGBA16Uint;
            case RGBA16_SINT -> MTLPixelFormat.RGBA16Sint;
            case RGBA16_FLOAT -> MTLPixelFormat.RGBA16Float;
            case RGBA32_UINT -> MTLPixelFormat.RGBA32Uint;
            case RGBA32_SINT -> MTLPixelFormat.RGBA32Sint;
            case RGBA32_FLOAT -> MTLPixelFormat.RGBA32Float;
            case D16_UNORM -> MTLPixelFormat.Depth16Unorm;
            case D32_FLOAT -> MTLPixelFormat.Depth32Float;
            case S8_UINT -> MTLPixelFormat.Stencil8;
            case D24_UNORM_S8_UINT -> MTLPixelFormat.Depth24Unorm_Stencil8;
            case D32_FLOAT_S8_UINT -> MTLPixelFormat.Depth32Float_Stencil8;
            default -> throw new IllegalStateException("Unsupported Metal texel buffer format: " + format);
        };
    }

    static long primitiveTypeCode(final PrimitiveTopology mode) {
        return switch (mode) {
            case TRIANGLES, QUADS, LINES -> TRIANGLE_PRIMITIVE;
            case TRIANGLE_STRIP -> 1L;
            case TRIANGLE_FAN -> TRIANGLE_FAN_PRIMITIVE;
            case DEBUG_LINES -> 2L;
            case DEBUG_LINE_STRIP -> 3L;
            case POINTS -> 4L;
            default -> -1L;
        };
    }

    static long toBlendFactorCode(final com.mojang.blaze3d.platform.BlendFactor factor) {
        return switch (factor) {
            case ZERO -> 0L;
            case ONE -> 1L;
            case SRC_COLOR -> 2L;
            case ONE_MINUS_SRC_COLOR -> 3L;
            case SRC_ALPHA -> 4L;
            case ONE_MINUS_SRC_ALPHA -> 5L;
            case DST_COLOR -> 6L;
            case ONE_MINUS_DST_COLOR -> 7L;
            case DST_ALPHA -> 8L;
            case ONE_MINUS_DST_ALPHA -> 9L;
            case SRC_ALPHA_SATURATE -> 10L;
            case CONSTANT_COLOR -> 11L;
            case ONE_MINUS_CONSTANT_COLOR -> 12L;
            case CONSTANT_ALPHA -> 13L;
            case ONE_MINUS_CONSTANT_ALPHA -> 14L;
        };
    }

    static long toBlendOpCode(final com.mojang.blaze3d.platform.BlendOp op) {
        return switch (op) {
            case ADD -> 0L;
            case SUBTRACT -> 1L;
            case REVERSE_SUBTRACT -> 2L;
            case MIN -> 3L;
            case MAX -> 4L;
        };
    }

    static long toCompareOpCode(final com.mojang.blaze3d.platform.CompareOp op) {
        return switch (op) {
            case ALWAYS_PASS -> 1L;
            case LESS_THAN -> 2L;
            case LESS_THAN_OR_EQUAL -> 3L;
            case EQUAL -> 4L;
            case NOT_EQUAL -> 5L;
            case GREATER_THAN_OR_EQUAL -> 6L;
            case GREATER_THAN -> 7L;
            case NEVER_PASS -> 8L;
        };
    }

    static long vertexAttributeFormatCode(final GpuFormat format) {
        return switch (format) {
            case R32_FLOAT -> 1L;
            case RG32_FLOAT -> 2L;
            case RGB32_FLOAT -> 3L;
            case RGBA32_FLOAT -> 4L;
            case RGBA8_UNORM -> 5L;
            case RGBA8_UINT -> 6L;
            case RG16_UINT -> 7L;
            case RG16_UNORM -> 8L;
            case RG16_SINT -> 9L;
            case RG16_SNORM -> 10L;
            case RGBA16_UINT -> 11L;
            case RGBA16_SINT -> 12L;
            case RGBA16_UNORM -> 13L;
            case RGBA16_SNORM -> 14L;
            case R32_UINT -> 15L;
            case RG32_UINT -> 16L;
            case RGB32_UINT -> 17L;
            case RGBA32_UINT -> 18L;
            case R32_SINT -> 19L;
            case RG32_SINT -> 20L;
            case RGB32_SINT -> 21L;
            case RGBA32_SINT -> 22L;
            case R16_FLOAT -> 23L;
            case RG16_FLOAT -> 24L;
            case RGBA16_FLOAT -> 25L;
            case RGBA8_SNORM -> 26L;
            case RGBA8_SINT -> 27L;
            case RGB8_UNORM -> 28L;
            case RGB8_SNORM -> 29L;
            case RGB8_UINT -> 30L;
            case RGB8_SINT -> 31L;
            case RGB16_UINT -> 32L;
            case RGB16_SINT -> 33L;
            case RGB16_UNORM -> 34L;
            case RGB16_SNORM -> 35L;
            case RGB16_FLOAT -> 36L;
            default -> 0L;
        };
    }
}
