package net.irisshaders.iris.mixin.fabric;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(WeatherEffectRenderer.class)
public class MixinRenderTypes {
	@Redirect(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/state/GameRenderState;useShaderTransparency()Z"))
	private static boolean iris$writeRainAndSnowToDepthBuffer(GameRenderState instance) {
		if (Iris.getPipelineManager().getPipeline().map(WorldRenderingPipeline::shouldWriteRainAndSnowToDepthBuffer).orElse(false)) {
			return true;
		}

		return instance.useShaderTransparency();
	}
}
