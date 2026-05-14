package net.irisshaders.iris.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import net.irisshaders.iris.layer.RenderingWrapper;
import net.irisshaders.iris.layer.WrappedPreparedRenderType;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(PreparedRenderType.class)
public class MixinPreparedRenderType implements WrappedPreparedRenderType {
	@Unique
	private RenderingWrapper wrapper;

	@WrapMethod(method = "drawFromBuffer(Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/IndexType;III)V")
	private void iris$wrapBuffer(GpuBuffer vertexBuffer, GpuBuffer indexBuffer, IndexType indexType, int baseVertex, int firstIndex, int indexCount, Operation<Void> original) {
		wrapper.setup();
		original.call(vertexBuffer, indexBuffer, indexType, baseVertex, firstIndex, indexCount);
		wrapper.clear();
	}
	@Override
	public void setRenderWrapper(RenderingWrapper wrapper) {
		this.wrapper = wrapper;
	}
}
