package net.irisshaders.iris.vertices;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.irisshaders.iris.Iris;

public class IrisVertexFormats {
	public static final VertexFormat TERRAIN;
	public static final VertexFormat ENTITY;
	public static final VertexFormat GLYPH;
	public static final VertexFormat CLOUDS;

	static {
		//ENTITY_ELEMENT = VertexFormatElement.register(getNextVertexFormatElementId(), 0, GpuFormat.RG16_SINT);
		//ENTITY_ID_ELEMENT = VertexFormatElement.register(getNextVertexFormatElementId(), 3, GpuFormat.RGBA16_UINT);
		//MID_TEXTURE_ELEMENT = VertexFormatElement.register(getNextVertexFormatElementId(), 0, GpuFormat.RG32_FLOAT);
		//TANGENT_ELEMENT = VertexFormatElement.register(getNextVertexFormatElementId(), 0, GpuFormat.RGBA8_SNORM);
		//MID_BLOCK_ELEMENT = VertexFormatElement.register(getNextVertexFormatElementId(), 0, GpuFormat.RGB8_SINT);

		TERRAIN = VertexFormat.builder(0)
			.addAttribute("Position", GpuFormat.RGB32_FLOAT)
			.addAttribute("Color", GpuFormat.RGBA8_UNORM)
			.addAttribute("UV0", GpuFormat.RG32_FLOAT)
			.addAttribute("UV2", GpuFormat.RG16_SINT)
			.addAttribute("Normal", GpuFormat.RGBA8_SNORM)
			.addAttribute("mc_Entity", GpuFormat.RG16_SINT)
			.addAttribute("mc_midTexCoord", GpuFormat.RG32_FLOAT)
			.addAttribute("at_tangent", GpuFormat.RGBA8_SNORM)
			.addAttribute("at_midBlock", GpuFormat.RGB8_SINT)
			.build();

		ENTITY = VertexFormat.builder(0)
			.addAttribute("Position", GpuFormat.RGB32_FLOAT)
			.addAttribute("Color", GpuFormat.RGBA8_UNORM)
			.addAttribute("UV0", GpuFormat.RG32_FLOAT)
			.addAttribute("UV1", GpuFormat.RG16_SINT)
			.addAttribute("UV2", GpuFormat.RG16_SINT)
			.addAttribute("Normal", GpuFormat.RGBA8_SNORM)
			.addAttribute("iris_Entity", GpuFormat.RGBA16_UINT)
			.addAttribute("mc_midTexCoord", GpuFormat.RG32_FLOAT)
			.addAttribute("at_tangent", GpuFormat.RGBA8_SNORM)
			.build();

		GLYPH = VertexFormat.builder(0)
			.addAttribute("Position", GpuFormat.RGB32_FLOAT)
			.addAttribute("UV0", GpuFormat.RG32_FLOAT)
			.addAttribute("UV2", GpuFormat.RG16_SINT)
			.addAttribute("Color", GpuFormat.RGBA8_UNORM)
			.addAttribute("Normal", GpuFormat.RGBA8_SNORM)
			.addAttribute("iris_Entity", GpuFormat.RGBA16_UINT)
			.addAttribute("mc_midTexCoord", GpuFormat.RG32_FLOAT)
			.addAttribute("at_tangent", GpuFormat.RGBA8_SNORM)
			.build();

		CLOUDS = VertexFormat.builder(0)
			.addAttribute("Position", GpuFormat.RGB32_FLOAT)
			.addAttribute("Color", GpuFormat.RGBA8_UNORM)
			.addAttribute("Normal", GpuFormat.RGBA8_SNORM)
			.build();
	}

	private static void debug(VertexFormat format) {
		Iris.logger.info("Vertex format: " + format + " with byte size " + format.getVertexSize());
		int byteIndex = 0;
		for (VertexFormatElement element : format.getElements()) {
			Iris.logger.info(element.name() + " @ " + byteIndex + " is " + element.format());
			byteIndex += element.format().pixelSize();
		}
	}
}
