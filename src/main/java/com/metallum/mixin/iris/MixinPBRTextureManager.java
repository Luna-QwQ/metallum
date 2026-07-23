package com.metallum.mixin.iris;

import com.metallum.client.metal.iris.MetalIrisBridge;
import net.irisshaders.iris.pbr.texture.PBRTextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels {@link PBRTextureManager#close()} on non-GL backends.
 *
 * <p>{@code PBRTextureManager.init()} is called from
 * {@code Iris.onRenderSystemInit()}, which is canceled by {@link MixinIris} on
 * non-GL backends. As a result, {@code defaultNormalTexture} and
 * {@code defaultSpecularTexture} are never initialized (remain {@code null}).
 * When the game shuts down, {@code PBRTextureManager.close()} dereferences
 * these null fields, causing a {@code NullPointerException}.
 *
 * <p>Canceling {@code close()} at HEAD is safe because if {@code init()} never
 * ran, there are no resources to release.
 *
 * <p>{@code remap = false} because {@code PBRTextureManager} is an Iris class.
 */
@Mixin(PBRTextureManager.class)
public class MixinPBRTextureManager {
    @Inject(method = "close", at = @At("HEAD"), cancellable = true, remap = false)
    private void metallum$cancelCloseOnNonGl(CallbackInfo ci) {
        if (MetalIrisBridge.isNonGlBackend()) {
            ci.cancel();
        }
    }
}
