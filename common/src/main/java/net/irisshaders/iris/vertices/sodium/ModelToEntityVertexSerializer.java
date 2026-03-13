package net.irisshaders.iris.vertices.sodium;

import net.caffeinemc.mods.sodium.api.memory.MemoryIntrinsics;
import net.caffeinemc.mods.sodium.api.util.NormI8;
import net.caffeinemc.mods.sodium.api.vertex.format.common.EntityVertex;
import net.caffeinemc.mods.sodium.api.vertex.serializer.VertexSerializer;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.vertices.IrisVertexFormats;
import net.irisshaders.iris.vertices.NormalHelper;
import org.lwjgl.system.MemoryUtil;

public class ModelToEntityVertexSerializer implements VertexSerializer {
	private static final int MIDCOORD = IrisVertexFormats.ENTITY.getOffset(IrisVertexFormats.MID_TEXTURE_ELEMENT);
	private static final int TANGENT = IrisVertexFormats.ENTITY.getOffset(IrisVertexFormats.TANGENT_ELEMENT);

	@Override
	public void serialize(long src, long dst, int vertexCount) {
		// Only accept quads, to be safe
		int quadCount = vertexCount / 4;
		for (int i = 0; i < quadCount; i++) {
			int normal = MemoryUtil.memGetInt(src + 32);
			int tangent = NormalHelper.computeTangent(null, NormI8.unpackX(normal), NormI8.unpackY(normal), NormI8.unpackZ(normal), MemoryUtil.memGetFloat(src), MemoryUtil.memGetFloat(src + 4), MemoryUtil.memGetFloat(src + 8), MemoryUtil.memGetFloat(src + 16), MemoryUtil.memGetFloat(src + 20),
				MemoryUtil.memGetFloat(src + EntityVertex.STRIDE), MemoryUtil.memGetFloat(src + 4 + EntityVertex.STRIDE), MemoryUtil.memGetFloat(src + 8 + EntityVertex.STRIDE), MemoryUtil.memGetFloat(src + 16 + EntityVertex.STRIDE), MemoryUtil.memGetFloat(src + 20 + EntityVertex.STRIDE),
				MemoryUtil.memGetFloat(src + EntityVertex.STRIDE + EntityVertex.STRIDE), MemoryUtil.memGetFloat(src + 4 + EntityVertex.STRIDE + EntityVertex.STRIDE), MemoryUtil.memGetFloat(src + 8 + EntityVertex.STRIDE + EntityVertex.STRIDE), MemoryUtil.memGetFloat(src + 16 + EntityVertex.STRIDE + EntityVertex.STRIDE), MemoryUtil.memGetFloat(src + 20 + EntityVertex.STRIDE + EntityVertex.STRIDE));
			float midU = 0, midV = 0;
			for (int vertex = 0; vertex < 4; vertex++) {
				midU += MemoryUtil.memGetFloat(src + 16 + (EntityVertex.STRIDE * vertex));
				midV += MemoryUtil.memGetFloat(src + 20 + (EntityVertex.STRIDE * vertex));
			}

			midU /= 4;
			midV /= 4;

			for (int j = 0; j < 4; j++) {
				MemoryIntrinsics.copyMemory(src, dst, 36);
				MemoryUtil.memPutShort(dst + 36, (short) CapturedRenderingState.INSTANCE.getCurrentRenderedEntity());
				MemoryUtil.memPutShort(dst + 38, (short) CapturedRenderingState.INSTANCE.getCurrentRenderedBlockEntity());
				MemoryUtil.memPutShort(dst + 40, (short) CapturedRenderingState.INSTANCE.getCurrentRenderedItem());
				MemoryUtil.memPutFloat(dst + MIDCOORD, midU);
				MemoryUtil.memPutFloat(dst + MIDCOORD + 4, midV);
				MemoryUtil.memPutInt(dst + TANGENT, tangent);

				src += EntityVertex.STRIDE;
				dst += IrisVertexFormats.ENTITY.getVertexSize();
			}
		}
	}
}
