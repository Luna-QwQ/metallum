package com.metallum.client.metal.render;

import com.metallum.client.metal.render.mtl.MTLBlendFactor;
import com.metallum.client.metal.render.mtl.MTLPixelFormat;
import com.metallum.client.metal.render.mtl.MTLPrimitiveType;
import com.metallum.client.metal.render.mtl.MTLVertexFormat;
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

    public static final long TRIANGLE_PRIMITIVE = 3L;
    public static final long TRIANGLE_FAN_PRIMITIVE = 5L;

    static MTLPrimitiveType primitiveTypeCode(final PrimitiveTopology mode) {
        return switch (mode) {
            case TRIANGLES, QUADS, LINES -> MTLPrimitiveType.Triangle;
            case TRIANGLE_STRIP -> MTLPrimitiveType.TriangleStrip;
            case DEBUG_LINES -> MTLPrimitiveType.Line;
            case DEBUG_LINE_STRIP -> MTLPrimitiveType.LineStrip;
            case POINTS -> MTLPrimitiveType.Point;
            case TRIANGLE_FAN -> MTLPrimitiveType.TriangleFan;
        };
    }

    static MTLBlendFactor toBlendFactorCode(final com.mojang.blaze3d.platform.BlendFactor factor) {
        return switch (factor) {
            case ZERO -> MTLBlendFactor.Zero;
            case ONE -> MTLBlendFactor.One;
            case SRC_COLOR -> MTLBlendFactor.SourceColor;
            case ONE_MINUS_SRC_COLOR -> MTLBlendFactor.OneMinusSourceColor;
            case SRC_ALPHA -> MTLBlendFactor.SourceAlpha;
            case ONE_MINUS_SRC_ALPHA -> MTLBlendFactor.OneMinusSourceAlpha;
            case DST_COLOR -> MTLBlendFactor.DestinationColor;
            case ONE_MINUS_DST_COLOR -> MTLBlendFactor.OneMinusDestinationColor;
            case DST_ALPHA -> MTLBlendFactor.DestinationAlpha;
            case ONE_MINUS_DST_ALPHA -> MTLBlendFactor.OneMinusDestinationAlpha;
            case SRC_ALPHA_SATURATE -> MTLBlendFactor.SourceAlphaSaturated;
            case CONSTANT_COLOR -> MTLBlendFactor.BlendColor;
            case ONE_MINUS_CONSTANT_COLOR -> MTLBlendFactor.OneMinusBlendColor;
            case CONSTANT_ALPHA -> MTLBlendFactor.BlendAlpha;
            case ONE_MINUS_CONSTANT_ALPHA -> MTLBlendFactor.OneMinusBlendAlpha;
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

    static MTLVertexFormat vertexAttributeFormat(final GpuFormat format) {
        return switch (format) {
            case R32_FLOAT -> MTLVertexFormat.Float;
            case RG32_FLOAT -> MTLVertexFormat.Float2;
            case RGB32_FLOAT -> MTLVertexFormat.Float3;
            case RGBA32_FLOAT -> MTLVertexFormat.Float4;
            case RGBA8_UNORM -> MTLVertexFormat.UChar4Normalized;
            case RGBA8_UINT -> MTLVertexFormat.UChar4;
            case RG16_UINT -> MTLVertexFormat.UShort2;
            case RG16_UNORM -> MTLVertexFormat.UShort2Normalized;
            case RG16_SINT -> MTLVertexFormat.Short2;
            case RG16_SNORM -> MTLVertexFormat.Short2Normalized;
            case RGBA16_UINT -> MTLVertexFormat.UShort4;
            case RGBA16_SINT -> MTLVertexFormat.Short4;
            case RGBA16_UNORM -> MTLVertexFormat.UShort4Normalized;
            case RGBA16_SNORM -> MTLVertexFormat.Short4Normalized;
            case R32_UINT -> MTLVertexFormat.UInt;
            case RG32_UINT -> MTLVertexFormat.UInt2;
            case RGB32_UINT -> MTLVertexFormat.UInt3;
            case RGBA32_UINT -> MTLVertexFormat.UInt4;
            case R32_SINT -> MTLVertexFormat.Int;
            case RG32_SINT -> MTLVertexFormat.Int2;
            case RGB32_SINT -> MTLVertexFormat.Int3;
            case RGBA32_SINT -> MTLVertexFormat.Int4;
            case R16_FLOAT -> MTLVertexFormat.Half;
            case RG16_FLOAT -> MTLVertexFormat.Half2;
            case RGBA16_FLOAT -> MTLVertexFormat.Half4;
            case RGBA8_SNORM -> MTLVertexFormat.Char4Normalized;
            case RGBA8_SINT -> MTLVertexFormat.Char4;
            case RGB8_UNORM -> MTLVertexFormat.UChar3Normalized;
            case RGB8_SNORM -> MTLVertexFormat.Char3Normalized;
            case RGB8_UINT -> MTLVertexFormat.UChar3;
            case RGB8_SINT -> MTLVertexFormat.Char3;
            case RGB16_UINT -> MTLVertexFormat.UShort3;
            case RGB16_SINT -> MTLVertexFormat.Short3;
            case RGB16_UNORM -> MTLVertexFormat.UShort3Normalized;
            case RGB16_SNORM -> MTLVertexFormat.Short3Normalized;
            case RGB16_FLOAT -> MTLVertexFormat.Half3;
            default -> MTLVertexFormat.Invalid;
        };
    }
}
