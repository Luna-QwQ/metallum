package net.irisshaders.iris.mixin;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shadows.frustum.fallback.NonCullingFrustum;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Camera.class)
public class MixinCamera {
	@Shadow
	private Vec3 position;

	@Inject(method = "extractRenderState", at = @At("RETURN"), cancellable = true)
	private void iris$disableFrustum(CameraRenderState cameraState, float partialTicks, CallbackInfo ci) {
		if (Iris.getPipelineManager().getPipeline().map(WorldRenderingPipeline::shouldDisableFrustumCulling).orElse(false)) {
			NonCullingFrustum f = new NonCullingFrustum();
			f.prepare(position.x, position.y, position.z);

			cameraState.cullFrustum = f;
		}
	}
}
