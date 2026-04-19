package net.irisshaders.iris.mixin;

import com.mojang.blaze3d.opengl.GlCommandEncoder;
import com.mojang.blaze3d.opengl.GlConst;
import com.mojang.blaze3d.platform.CompareOp;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GLCapabilities;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(GlConst.class)
public class UndoReverseZThree {
	@Overwrite
	public static int toGl(final CompareOp compareOp) {
		return switch (compareOp) {
			case ALWAYS_PASS -> 519;
			case LESS_THAN -> GL43.GL_GREATER;
			case LESS_THAN_OR_EQUAL -> GL43.GL_GEQUAL;
			case EQUAL -> 514;
			case NOT_EQUAL -> 517;
			case GREATER_THAN_OR_EQUAL -> GL43.GL_LEQUAL;
			case GREATER_THAN -> GL43.GL_LESS;
			case NEVER_PASS -> 512;
		};
	}
}
