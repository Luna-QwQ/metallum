package com.metallum.mixin.iris;

import net.irisshaders.iris.mixinterface.GpuTextureInterface;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Provides safe implementations of Iris's {@link GpuTextureInterface} methods
 * on {@code MetalGpuTexture}, preventing {@code AssertionError: Why.} when
 * Iris's texture-tracking mixin calls {@code iris$getGlId()} on a Metal texture.
 *
 * <p><b>Background:</b> Iris has three mixins that form its texture-tracking
 * system:
 * <ol>
 *   <li>{@code MixinGpuTexture2} ({@code @Mixin(GpuTexture.class)}) adds
 *       {@code iris$getGlId()} to the base {@code GpuTexture} class, throwing
 *       {@code AssertionError("Why.")} &mdash; a guard that should never be
 *       reached on a concrete texture.</li>
 *   <li>{@code MixinGpuTexture} ({@code @Mixin(GlTexture.class)}) overrides
 *       {@code iris$getGlId()} to return the real OpenGL texture ID.</li>
 *   <li>{@code MixinAbstractTexture} injects at {@code RETURN} of
 *       {@code AbstractTexture.getTexture()} and calls
 *       {@code lastChecked.iris$getGlId()} to register the texture with
 *       {@code TextureTracker}.</li>
 * </ol>
 *
 * <p>On a Metal backend, {@code AbstractTexture.getTexture()} returns a
 * {@code MetalGpuTexture} (not a {@code GlTexture}). Java's virtual dispatch
 * resolves {@code iris$getGlId()} to the base {@code GpuTexture} implementation
 * (the "Why." guard), which throws. This happens during font texture loading
 * ({@code FontTexture.add} &rarr; {@code AbstractTexture.getTexture}), early
 * in the game startup sequence.
 *
 * <p>This mixin adds safe overrides to {@code MetalGpuTexture}:
 * <ul>
 *   <li>{@code iris$getGlId()} returns {@code 0} &mdash; a no-op texture ID.
 *       This is safe because {@link MixinIris} cancels
 *       {@code Iris.onRenderSystemInit()}, preventing shaderpack loading, so
 *       Iris's rendering pipeline never activates and {@code TextureTracker}'s
 *       entries are never used.</li>
 *   <li>{@code iris$markMipmapNonLinear()} is a no-op.</li>
 * </ul>
 *
 * <p>{@code targets} is used (instead of {@code @Mixin(MetalGpuTexture.class)})
 * because {@code MetalGpuTexture} is package-private in
 * {@code com.metallum.client.metal.render} and cannot be referenced from the
 * {@code com.metallum.mixin.iris} package at compile time. The string target
 * is resolved at runtime by the Mixin processor.
 *
 * <p>This mixin is only applied when Iris is loaded (controlled by
 * {@code MetallumMixinConfigPlugin}).
 */
@Mixin(targets = "com.metallum.client.metal.render.MetalGpuTexture")
public class MixinMetalGpuTexture implements GpuTextureInterface {
    @Override
    public int iris$getGlId() {
        return 0;
    }

    @Override
    public void iris$markMipmapNonLinear() {
        // No-op: Iris's mipmap non-linear tracking is irrelevant on Metal.
    }
}
