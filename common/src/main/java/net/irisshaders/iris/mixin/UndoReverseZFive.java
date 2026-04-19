package net.irisshaders.iris.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.opengl.GlCommandEncoder;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;

import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.function.Supplier;

@Mixin(GlCommandEncoder.class)
public class UndoReverseZFive {
	@WrapMethod(method = "clearColorAndDepthTextures(Lcom/mojang/blaze3d/textures/GpuTexture;ILcom/mojang/blaze3d/textures/GpuTexture;D)V")
	private void iris$change(GpuTexture colorTexture, int clearColor, GpuTexture depthTexture, double clearDepth, Operation<Void> original) {
		original.call(colorTexture, clearColor, depthTexture, 1.0 - clearDepth);
	}
	@WrapMethod(method = "clearColorAndDepthTextures(Lcom/mojang/blaze3d/textures/GpuTexture;ILcom/mojang/blaze3d/textures/GpuTexture;DIIII)V")
	private void iris$change3(GpuTexture colorTexture, int clearColor, GpuTexture depthTexture, double clearDepth, int regionX, int regionY, int regionWidth, int regionHeight, Operation<Void> original) {
		original.call(colorTexture, clearColor, depthTexture, 1.0 - clearDepth, regionX, regionY, regionWidth, regionHeight);
	}
	@WrapMethod(method = "clearDepthTexture")
	private void iris$change2(GpuTexture depthTexture, double clearDepth, Operation<Void> original) {
		original.call(depthTexture, 1.0 - clearDepth);
	}
	@WrapMethod(method = "createRenderPass(Ljava/util/function/Supplier;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/OptionalInt;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/OptionalDouble;)Lcom/mojang/blaze3d/systems/RenderPassBackend;")
	private RenderPassBackend iris$change4(Supplier<String> label, GpuTextureView colorTexture, OptionalInt clearColor, @Nullable GpuTextureView depthTexture, OptionalDouble clearDepth, Operation<RenderPassBackend> original) {
		return original.call(label, colorTexture, clearColor, depthTexture, clearDepth.isPresent() ? OptionalDouble.of(1.0 - clearDepth.getAsDouble()) : clearDepth);
	}
}
