package com.metallum.mixin.iris;

import com.metallum.client.metal.iris.MetalIrisBridge;
import net.irisshaders.iris.gl.GLDebug;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels {@link GLDebug#reloadDebugState()} on non-OpenGL backends.
 *
 * <p>Iris's {@code MixinRenderSystem.iris$onRendererInit} calls
 * {@code GLDebug.reloadDebugState()} as the second step of its init sequence
 * (after {@code Iris.duringRenderSystemInit()}, which is canceled by
 * {@link MixinIris}). {@code reloadDebugState()} reads
 * {@code GL.getCapabilities().GL_KHR_debug} and
 * {@code GL.getCapabilities().OpenGL43} &mdash; both throw
 * {@code IllegalStateException} on a Metal backend where no OpenGL context
 * exists.
 *
 * <p>Canceling this method at HEAD leaves {@code GLDebug.debugState} as
 * {@code null}. This is safe because {@link MixinIris} also cancels
 * {@code Iris.onRenderSystemInit()}, which prevents shaderpack loading &mdash;
 * so Iris's rendering pipeline never activates and the {@code debugState}
 * field is never dereferenced.
 *
 * <p>{@code remap = false} because this is Iris's own method (not a Mojang
 * obfuscated method).
 */
@Mixin(GLDebug.class)
public class MixinGLDebug {
    @Inject(method = "reloadDebugState", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metallum$cancelReloadDebugStateOnNonGl(CallbackInfo ci) {
        if (MetalIrisBridge.isNonGlBackend()) {
            ci.cancel();
        }
    }
}
