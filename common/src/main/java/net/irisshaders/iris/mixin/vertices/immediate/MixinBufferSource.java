package net.irisshaders.iris.mixin.vertices.immediate;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.irisshaders.iris.vertices.ImmediateState;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Quick optimization to disable the extended vertex format outside of level rendering if we're using a BufferSource.
 * This is a heuristic that should hopefully work almost always because of how people use BufferSource.
 */
@Mixin(MultiBufferSource.BufferSource.class)
public class MixinBufferSource {
	@WrapOperation(method = "getBuffer",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/StagedVertexBuffer;getVertexBuilder(Lnet/minecraft/client/renderer/StagedVertexBuffer$Draw;)Lcom/mojang/blaze3d/vertex/VertexConsumer;"))
	private VertexConsumer iris$redirectBegin(StagedVertexBuffer instance, StagedVertexBuffer.Draw draw, Operation<VertexConsumer> original) {
		ImmediateState.skipExtension.set(iris$notRenderingLevel());
		VertexConsumer builder = original.call(instance, draw);
		ImmediateState.skipExtension.set(false);

		return builder;
	}

	@Inject(method = "uploadAndDraw",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/StagedVertexBuffer;upload()V"))
	private void iris$beforeFlushBuffer(CallbackInfo ci) {
		if (iris$notRenderingLevel()) {
			ImmediateState.renderWithExtendedVertexFormat = false;
		}
	}

	@Inject(method = "uploadAndDraw",
		at = @At(value = "RETURN",
			shift = At.Shift.AFTER))
	private void iris$afterFlushBuffer(CallbackInfo ci) {
		if (iris$notRenderingLevel()) {
			ImmediateState.renderWithExtendedVertexFormat = true;
		}
	}

	@Unique
	private boolean iris$notRenderingLevel() {
		return !ImmediateState.isRenderingLevel;
	}
}
