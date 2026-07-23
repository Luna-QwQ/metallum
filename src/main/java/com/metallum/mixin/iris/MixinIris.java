package com.metallum.mixin.iris;

import com.metallum.client.metal.iris.MetalIrisBridge;
import net.irisshaders.iris.Iris;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Cancels Iris's OpenGL-dependent initialization entry points on non-OpenGL
 * backends (Metal / Vulkan).
 *
 * <p>Iris's own {@code MixinRenderSystem} injects at {@code @At("RETURN")} of
 * {@code RenderSystem.initRenderer} and calls a sequence of init methods:
 * <ol>
 *   <li>{@code Iris.duringRenderSystemInit()} &rarr; calls {@code setDebug()}
 *       which calls {@code IrisRenderSystem.getGlDevice()} &mdash; this casts
 *       the backend to {@code GlDevice} and throws {@code ClassCastException}
 *       on a Metal backend.</li>
 *   <li>{@code Iris.onRenderSystemInit()} &rarr; calls {@code loadShaderpack()}
 *       which compiles shaders via raw OpenGL &mdash; crashes with no GL
 *       context.</li>
 * </ol>
 *
 * <p>This mixin cancels these methods at their HEAD when
 * {@link MetalIrisBridge#isNonGlBackend()} returns {@code true}:
 * <ul>
 *   <li>{@code duringRenderSystemInit} and {@code onRenderSystemInit} &mdash;
 *       init-time entry points (see above).</li>
 *   <li>{@code loadExternalShaderpack} &mdash; the user-triggered shaderpack
 *       loading path. When the user clicks "Apply" in the ShaderPackScreen,
 *       {@code Iris.reload()} is called, which calls {@code loadShaderpack()},
 *       which calls {@code loadExternalShaderpack(name)}. This method calls
 *       {@code StandardMacros.getGlVersion()} &rarr;
 *       {@code GlStateManager._getString()} &rarr; {@code GL11C.glGetString()}
 *       &mdash; a native GL call that causes a <b>SIGSEGV</b> on Metal (invalid
 *       function pointer, no OpenGL context). Canceling it at HEAD to return
 *       {@code false} makes {@code loadShaderpack()} fall back to
 *       {@code setShadersDisabled()} (which is safe &mdash; just sets
 *       {@code currentPack = null}), and the pipeline is recreated as
 *       {@code VanillaRenderingPipeline}. This covers all entry points:
 *       ShaderPackScreen, keybinds, and {@code reload()}.</li>
 * </ul>
 *
 * <p>The remaining init calls in Iris's sequence are handled by companion
 * mixins: {@code GLDebug.reloadDebugState()} is redirected by
 * {@link MixinGLDebug}, and {@code IrisRenderSystem.initRenderer()} (whose
 * {@code <clinit>} and method body both make GL calls) is handled by
 * {@link MixinIrisRenderSystem}. {@code IrisSamplers.initRenderer()} is empty
 * and safe.
 *
 * <p>On a real OpenGL backend this mixin is a no-op: {@code isNonGlBackend()}
 * returns {@code false} and Iris initializes normally.
 *
 * <p>{@code remap = false} because these are Iris's own methods (not Mojang
 * obfuscated methods).
 */
@Mixin(Iris.class)
public class MixinIris {
    @Inject(method = "duringRenderSystemInit", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metallum$cancelDuringInitOnNonGl(CallbackInfo ci) {
        if (MetalIrisBridge.isNonGlBackend()) {
            ci.cancel();
        }
    }

    @Inject(method = "onRenderSystemInit", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metallum$cancelOnRenderSystemInitOnNonGl(CallbackInfo ci) {
        if (MetalIrisBridge.isNonGlBackend()) {
            ci.cancel();
        }
    }

    @Inject(method = "loadExternalShaderpack", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metallum$cancelLoadExternalShaderpackOnNonGl(String name, CallbackInfoReturnable<Boolean> cir) {
        if (MetalIrisBridge.isNonGlBackend()) {
            cir.setReturnValue(false);
        }
    }
}
