package net.irisshaders.iris.mixin.gui;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.systems.RenderSystem;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.GLDebug;
import net.irisshaders.iris.gui.screen.HudHideable;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Hud.class)
public class MixinGui {
	@Shadow
	@Final
	private Minecraft minecraft;

	@WrapMethod(method = "extractRenderState")
	public void iris$handleHudHidingScreens(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, Operation<Void> original) {
		Screen screen = this.minecraft.gui.screen();

		if (screen instanceof HudHideable) {
			return;
		}

		GLDebug.pushGroup(1000, "GUI");

		original.call(graphics, deltaTracker);

		GLDebug.popGroup();
	}
}
