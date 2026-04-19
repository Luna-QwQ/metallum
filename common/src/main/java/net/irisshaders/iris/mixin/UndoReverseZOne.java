package net.irisshaders.iris.mixin;

import com.mojang.blaze3d.opengl.GlDevice;
import org.lwjgl.opengl.GLCapabilities;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(GlDevice.class)
public class UndoReverseZOne {
	@Redirect(method = "<init>", at = @At(value = "FIELD", target = "Lorg/lwjgl/opengl/GLCapabilities;GL_ARB_clip_control:Z"))
	private boolean iris$fakeClipControl(GLCapabilities instance) {
		return false;
	}
}
