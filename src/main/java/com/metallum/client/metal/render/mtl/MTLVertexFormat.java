package com.metallum.client.metal.render.mtl;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public enum MTLVertexFormat {
    Invalid(0L),
    UChar2(1L),
    UChar3(2L),
    UChar4(3L),
    Char2(4L),
    Char3(5L),
    Char4(6L),
    UChar2Normalized(7L),
    UChar3Normalized(8L),
    UChar4Normalized(9L),
    Char2Normalized(10L),
    Char3Normalized(11L),
    Char4Normalized(12L),
    UShort2(13L),
    UShort3(14L),
    UShort4(15L),
    Short2(16L),
    Short3(17L),
    Short4(18L),
    UShort2Normalized(19L),
    UShort3Normalized(20L),
    UShort4Normalized(21L),
    Short2Normalized(22L),
    Short3Normalized(23L),
    Short4Normalized(24L),
    Half2(25L),
    Half3(26L),
    Half4(27L),
    Float(28L),
    Float2(29L),
    Float3(30L),
    Float4(31L),
    Int(32L),
    Int2(33L),
    Int3(34L),
    Int4(35L),
    UInt(36L),
    UInt2(37L),
    UInt3(38L),
    UInt4(39L),
    Int1010102Normalized(40L),
    UInt1010102Normalized(41L),
    UChar4Normalized_bgra(42L),
    UChar(45L),
    Char(46L),
    UCharNormalized(47L),
    CharNormalized(48L),
    UShort(49L),
    Short(50L),
    UShortNormalized(51L),
    ShortNormalized(52L),
    Half(53L),
    FloatRG11B10(54L),
    FloatRGB9E5(55L);

    public final long value;

    MTLVertexFormat(final long value) {
        this.value = value;
    }

    public static MTLVertexFormat from(final com.mojang.blaze3d.GpuFormat format) {
        return switch (format) {
            case R32_FLOAT -> Float;
            case RG32_FLOAT -> Float2;
            case RGB32_FLOAT -> Float3;
            case RGBA32_FLOAT -> Float4;
            case RGBA8_UNORM -> UChar4Normalized;
            case RGBA8_UINT -> UChar4;
            case RG16_UINT -> UShort2;
            case RG16_UNORM -> UShort2Normalized;
            case RG16_SINT -> Short2;
            case RG16_SNORM -> Short2Normalized;
            case RGBA16_UINT -> UShort4;
            case RGBA16_SINT -> Short4;
            case RGBA16_UNORM -> UShort4Normalized;
            case RGBA16_SNORM -> Short4Normalized;
            case R32_UINT -> UInt;
            case RG32_UINT -> UInt2;
            case RGB32_UINT -> UInt3;
            case RGBA32_UINT -> UInt4;
            case R32_SINT -> Int;
            case RG32_SINT -> Int2;
            case RGB32_SINT -> Int3;
            case RGBA32_SINT -> Int4;
            case R16_FLOAT -> Half;
            case R16_UINT -> UShort;
            case R16_SINT -> Short;
            case R16_UNORM -> UShortNormalized;
            case R16_SNORM -> ShortNormalized;
            case R8_UINT -> UChar;
            case R8_SINT -> Char;
            case R8_UNORM -> UCharNormalized;
            case R8_SNORM -> CharNormalized;
            case RG16_FLOAT -> Half2;
            case RGBA16_FLOAT -> Half4;
            case RGBA8_SNORM -> Char4Normalized;
            case RGBA8_SINT -> Char4;
            case RGB8_UNORM -> UChar3Normalized;
            case RGB8_SNORM -> Char3Normalized;
            case RGB8_UINT -> UChar3;
            case RGB8_SINT -> Char3;
            case RGB16_UINT -> UShort3;
            case RGB16_SINT -> Short3;
            case RGB16_UNORM -> UShort3Normalized;
            case RGB16_SNORM -> Short3Normalized;
            case RGB16_FLOAT -> Half3;
            default -> Invalid;
        };
    }
}
