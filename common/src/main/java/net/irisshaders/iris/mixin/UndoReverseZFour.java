package net.irisshaders.iris.mixin;

import net.minecraft.client.renderer.Projection;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Projection.class)
public class UndoReverseZFour {
	@Redirect(method = "getMatrix", at = @At(value = "INVOKE", target = "Lorg/joml/Matrix4f;setPerspective(FFFFZ)Lorg/joml/Matrix4f;"))
	private Matrix4f iris$setPerspective(Matrix4f instance, float fovy, float aspect, float zNear, float zFar, boolean zZeroToOne) {
		return instance.setPerspective(fovy, aspect, zFar, zNear, zZeroToOne);
	}
	@Redirect(method = "getMatrix", at = @At(value = "INVOKE", target = "Lorg/joml/Matrix4f;setOrtho(FFFFFFZ)Lorg/joml/Matrix4f;"))
	private Matrix4f iris$setOrtho(Matrix4f instance, float left, float right, float bottom, float top, float zNear, float zFar, boolean zZeroToOne) {
		return instance.setOrtho(left, right, bottom, top, zFar, zNear, zZeroToOne);
	}
}

