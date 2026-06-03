package net.irisshaders.iris.compat.sodium.mixin;

import net.caffeinemc.mods.sodium.client.gui.SodiumOptions;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.irisshaders.iris.shadows.ShadowRenderingState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(DefaultChunkRenderer.class)
public class MixinDefaultChunkRenderer {
	@Redirect(method = "render", at = @At(value = "FIELD", target = "Lnet/caffeinemc/mods/sodium/client/gui/SodiumOptions$PerformanceSettings;useBlockFaceCulling:Z"), remap = false)
	private boolean iris$disableBlockFaceCullingInShadowPass(SodiumOptions.PerformanceSettings instance) {
		if (ShadowRenderingState.areShadowsCurrentlyBeingRendered()) return false;
		return instance.useBlockFaceCulling;
	}

	// TODO IMS: Something about this feels... wrong.
	@Redirect(method = "render", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;isTranslucent()Z"), remap = false)
	private boolean doNotSortInShadow(TerrainRenderPass instance) {
		if (ShadowRenderingState.areShadowsCurrentlyBeingRendered()) return false;

		return instance.isTranslucent();
	}
}
