package com.metallum.mixin.iris;

import com.metallum.client.metal.iris.MetalIrisBridge;
import net.irisshaders.iris.gl.IrisRenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Diverts IrisRenderSystem's OpenGL-dependent paths on non-OpenGL backends.
 *
 * <p>IrisRenderSystem has two crash points on a Metal backend:
 * <ol>
 *   <li><b>Static initializer ({@code <clinit>})</b> &mdash; the field
 *       {@code emptyArray} is initialized via
 *       {@code new int[SamplerLimits.get().getMaxTextureUnits()]}.
 *       {@code SamplerLimits}'s constructor calls
 *       {@link IrisRenderSystem#supportsSSBO()} which reads
 *       {@code GL.getCapabilities().OpenGL44} &mdash; this throws
 *       {@code IllegalStateException: No GLCapabilities instance set}
 *       because there is no OpenGL context on Metal. The {@code <clinit>}
 *       runs before any method body, so a HEAD injection on
 *       {@code initRenderer()} alone cannot prevent it.</li>
 *   <li><b>{@code initRenderer()} method body</b> &mdash; queries
 *       {@code GL.getCapabilities()} five times for DSA, multibind, compute,
 *       and tessellation support, and allocates GL sampler state.</li>
 * </ol>
 *
 * <p>This mixin handles both:
 * <ul>
 *   <li>{@code supportsSSBO()} is redirected to return {@code false} on
 *       non-GL backends. This allows {@code <clinit>} to complete safely:
 *       {@code SamplerLimits} sets {@code maxShaderStorageUnits = 0} and
 *       {@code emptyArray} is allocated with a valid size.</li>
 *   <li>{@code initRenderer()} is canceled at HEAD on non-GL backends,
 *       preventing the five {@code GL.getCapabilities()} reads and GL
 *       resource allocation in the method body.</li>
 *   <li>{@code supportsImageLoadStore()} is redirected to return {@code false}.
 *       Called by {@code FeatureFlags.CUSTOM_IMAGES.hardwareRequirement}
 *       during {@code ShaderPack} construction (feature detection).</li>
 *   <li>{@code supportsBufferBlending()} is redirected to return {@code false}.
 *       Called by {@code FeatureFlags.PER_BUFFER_BLENDING.hardwareRequirement}
 *       and {@code ShaderProperties} during {@code ShaderPack} construction.</li>
 *   <li>{@code getMaxImageUnits()} is redirected to return {@code 0}.
 *       Called by {@code ImageLimits} (lazy, but defensive).</li>
 * </ul>
 *
 * <p>The fields that {@code initRenderer()} would have set
 * ({@code dsaState}, {@code hasMultibind}, {@code supportsCompute},
 * {@code supportsTesselation}, {@code samplers}) remain at their default
 * values ({@code null}/{@code false}). {@code supportsCompute()} and
 * {@code supportsTesselation()} return these default fields (safe).
 *
 * <p>{@code remap = false} because these are Iris's own methods (not Mojang
 * obfuscated methods).
 */
@Mixin(IrisRenderSystem.class)
public class MixinIrisRenderSystem {
    @Inject(method = "supportsSSBO", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metallum$redirectSupportsSSBOOnNonGl(CallbackInfoReturnable<Boolean> cir) {
        if (MetalIrisBridge.isNonGlBackend()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "initRenderer", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metallum$cancelInitRendererOnNonGl(CallbackInfo ci) {
        if (MetalIrisBridge.isNonGlBackend()) {
            ci.cancel();
        }
    }

    @Inject(method = "supportsImageLoadStore", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metallum$redirectSupportsImageLoadStoreOnNonGl(CallbackInfoReturnable<Boolean> cir) {
        if (MetalIrisBridge.isNonGlBackend()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "supportsBufferBlending", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metallum$redirectSupportsBufferBlendingOnNonGl(CallbackInfoReturnable<Boolean> cir) {
        if (MetalIrisBridge.isNonGlBackend()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "getMaxImageUnits", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metallum$redirectGetMaxImageUnitsOnNonGl(CallbackInfoReturnable<Integer> cir) {
        if (MetalIrisBridge.isNonGlBackend()) {
            cir.setReturnValue(0);
        }
    }
}
