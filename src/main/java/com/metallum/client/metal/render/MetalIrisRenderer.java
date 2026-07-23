package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLPrimitiveType;
import com.metallum.client.metal.render.mtl.MTLRenderCommandEncoder;
import com.metallum.client.metal.render.mtl.MTLRenderStages;
import com.metallum.client.metal.render.mtl.MTLStorageMode;
import com.metallum.client.metal.render.mtl.MTLTextureUsage;
import com.metallum.client.metal.render.mtl.MTLPixelFormat;
import com.metallum.mixin.accessor.MetallumGpuDeviceAccessor;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Public entry point for Iris shader rendering on the Metal backend.
 *
 * <p>This class lives in {@code com.metallum.client.metal.render} so it can
 * access the package-private {@link MetalDevice}, {@link MetalCommandEncoder},
 * {@link MetalGpuTexture}, and {@link MetalGpuTextureView} classes directly.
 * It is called by {@link com.metallum.client.metal.iris.MetalIrisRenderingPipeline}
 * (in a different package) to perform actual Metal rendering of Iris shader
 * programs.
 *
 * <h2>M4b — Fullscreen triangle rendering</h2>
 * The {@link #renderFullscreenPass} method renders a fullscreen triangle using
 * a given {@link MetalIrisPipeline}. The triangle is generated entirely in the
 * vertex shader (using {@code vertex_id}) — no vertex buffers are needed. This
 * is the standard technique for Iris composite/deferred/final passes.
 *
 * <h2>M4c — Final pass</h2>
 * The {@link #renderFinalPass} method creates an offscreen RGBA8 render target,
 * renders the {@code final} shader program to it via a fullscreen triangle, and
 * logs the result. The offscreen texture is not yet presented to the screen —
 * that integration is future work.
 */
public final class MetalIrisRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger("MetalUniversal");

    /** Stage mask: vertex + fragment (used for buffer/texture binding). */
    private static final int STAGE_ALL = (int) MTLRenderStages.VertexAndFragment.value;

    /** Maximum number of texture slots to bind dummy textures to. */
    private static final int MAX_TEXTURE_SLOTS = 8;

    /** Cache of MetalIrisPipeline objects, keyed by program name. */
    private static final Map<String, MetalIrisPipeline> pipelineCache = new HashMap<>();

    /** Cached 1x1 dummy texture handle (created lazily, reused across frames). */
    private static MemorySegment dummyTextureHandle = MemorySegment.NULL;

    /**
     * The final-pass render target view produced by {@link #renderFinalPass},
     * handed off to {@link MetalSurface#blitFromTexture} for presentation.
     *
     * <p>Set during {@code finalizeLevelRendering} (when the final pass is
     * rendered to an offscreen RGBA8 texture), consumed during vanilla's
     * present call. If no final pass ran this frame (or the view was already
     * consumed), this is {@code null} and {@code blitFromTexture} presents
     * vanilla's main render target as normal.
     */
    private static final AtomicReference<MetalGpuTextureView> pendingFinalPassView = new AtomicReference<>();

    private MetalIrisRenderer() {
    }

    /**
     * Retrieves the active {@link MetalDevice} from Mojang's {@link GpuDevice}.
     *
     * @return the Metal device, or {@code null} if the backend is not Metal
     */
    private static MetalDevice getMetalDevice() {
        try {
            GpuDevice gpuDevice = com.mojang.blaze3d.systems.RenderSystem.getDevice();
            GpuDeviceBackend backend = ((MetallumGpuDeviceAccessor) gpuDevice).metallum$getBackend();
            if (backend instanceof MetalDevice metalDevice) {
                return metalDevice;
            }
        } catch (Throwable t) {
            LOGGER.error("[MetalUniversal] Failed to get MetalDevice", t);
        }
        return null;
    }

    /**
     * Gets or creates a cached {@link MetalIrisPipeline} for the given program.
     */
    private static MetalIrisPipeline getOrCreatePipeline(
            final MetalDevice device,
            final String name,
            final String vertexMsl,
            final String fragmentMsl,
            final MTLPixelFormat colorFormat,
            final boolean hasDepth
    ) {
        return pipelineCache.computeIfAbsent(name, n -> new MetalIrisPipeline(
                device, n, vertexMsl, fragmentMsl, colorFormat, hasDepth
        ));
    }

    /**
     * Gets or creates the cached 1x1 dummy texture (for binding to unused
     * sampler slots so Metal doesn't read garbage).
     */
    private static MemorySegment getDummyTexture(final MetalDevice device) {
        if (MetalNativeBridge.isNullHandle(dummyTextureHandle)) {
            dummyTextureHandle = MetalNativeBridge.metallum_create_texture_2d(
                    device.metalDeviceHandle(),
                    MTLPixelFormat.RGBA8Unorm,
                    1, 1, 1, 1,
                    0,
                    MTLTextureUsage.ShaderRead.value,
                    MTLStorageMode.Private,
                    "iris_dummy"
            );
            if (MetalNativeBridge.isNullHandle(dummyTextureHandle)) {
                LOGGER.warn("[MetalUniversal] Failed to create dummy texture");
            }
        }
        return dummyTextureHandle;
    }

    /**
     * Renders a fullscreen triangle using the given Iris pipeline to the given
     * color attachment.
     *
     * <p>Uses {@link MetalCommandEncoder#renderCommandEncoder} to obtain an
     * {@link MTLRenderCommandEncoder}, which properly handles ending any
     * previously active encoder and fence synchronization.
     *
     * @param device          the Metal device
     * @param pipeline        the compiled Iris pipeline
     * @param colorView       the color attachment texture view
     * @param width           viewport width
     * @param height          viewport height
     * @return {@code true} if the draw call was issued successfully
     */
    private static boolean renderFullscreenPass(
            final MetalDevice device,
            final MetalIrisPipeline pipeline,
            final MetalGpuTextureView colorView,
            final int width,
            final int height
    ) {
        try {
            MetalCommandEncoder encoder = device.createCommandEncoder();

            // Obtain a render command encoder targeting our color attachment.
            // renderCommandEncoder() handles ending any previous encoder and
            // fence synchronization. We clear to black.
            MTLRenderCommandEncoder renderEnc = encoder.renderCommandEncoder(
                    colorView, null,
                    width, height,
                    true, // clearColorEnabled
                    0.0f, 0.0f, 0.0f, 0.0f,
                    false, 0.0 // no depth
            );

            // Set the Iris pipeline state (no depth for final/composite passes).
            renderEnc.setRenderPipelineState(pipeline.pipelineState(false));

            // Bind dummy textures to slots 0–7. Iris shaders reference
            // colortex0–7 samplers; without bound textures Metal would
            // read garbage or crash.
            MemorySegment dummyTex = getDummyTexture(device);
            if (!MetalNativeBridge.isNullHandle(dummyTex)) {
                for (int i = 0; i < MAX_TEXTURE_SLOTS; i++) {
                    renderEnc.setTexture(dummyTex, i, STAGE_ALL);
                }
            }

            // Draw a fullscreen triangle: 3 vertices, 1 instance.
            // The vertex shader generates positions from vertex_id.
            renderEnc.drawPrimitives(MTLPrimitiveType.Triangle, 0, 3, 1, 0);

            // Do NOT call endEncoding — MetalCommandEncoder tracks the active
            // encoder and will end it when a new encoder is needed or when
            // submit() is called. This is the same pattern used by MetalRenderPass.

            LOGGER.info("[MetalUniversal] Rendered fullscreen pass '{}' ({}x{})",
                    pipeline.name(), width, height);
            return true;
        } catch (Throwable t) {
            LOGGER.error("[MetalUniversal] Failed to render fullscreen pass '{}'", pipeline.name(), t);
            return false;
        }
    }

    /**
     * Renders the Iris {@code final} pass to an offscreen RGBA8 render target,
     * then hands the target view to {@link MetalSurface#blitFromTexture} for
     * on-screen presentation.
     *
     * <p>This is the M4f entry point called by
     * {@link com.metallum.client.metal.iris.MetalIrisRenderingPipeline#finalizeLevelRendering()}.
     * It:
     * <ol>
     *   <li>Gets the active MetalDevice</li>
     *   <li>Creates (or retrieves from cache) a {@link MetalIrisPipeline} from
     *       the final program's MSL</li>
     *   <li>Creates an RGBA8 offscreen render target texture</li>
     *   <li>Renders a fullscreen triangle to it</li>
     *   <li>Stores the resulting view in {@link #pendingFinalPassView} for
     *       {@code MetalSurface.blitFromTexture} to consume at present time</li>
     * </ol>
     *
     * <p>The offscreen texture is <b>not</b> closed here — ownership transfers
     * to {@code MetalSurface}, which presents it and then closes it via the
     * device's deferred-release queue (the native texture survives until the
     * present command buffer completes, 3 submits later). If a previous frame's
     * view was never consumed (e.g. present didn't run), it is released here.
     *
     * <p>The present path samples the source texture via a dedicated present
     * pipeline (see {@code metallum_MTLCommandBuffer_encodePresentTextureToDrawable}
     * in the native layer), so the RGBA8 source format need not match the
     * BGRA8 drawable format.
     *
     * @param finalVertexMsl   compiled MSL vertex source for the {@code final} program
     * @param finalFragmentMsl compiled MSL fragment source for the {@code final} program
     * @param width            screen/framebuffer width
     * @param height           screen/framebuffer height
     * @return {@code true} if the final pass was rendered successfully
     */
    public static boolean renderFinalPass(
            final String finalVertexMsl,
            final String finalFragmentMsl,
            final int width,
            final int height
    ) {
        MetalDevice device = getMetalDevice();
        if (device == null) {
            LOGGER.warn("[MetalUniversal] Cannot render final pass: MetalDevice not available");
            return false;
        }

        // Get or create the pipeline (cached — MTLFunction compilation is
        // expensive, MTLRenderPipelineState creation is moderately expensive).
        MetalIrisPipeline pipeline;
        try {
            pipeline = getOrCreatePipeline(
                    device, "final",
                    finalVertexMsl, finalFragmentMsl,
                    MTLPixelFormat.RGBA8Unorm, false
            );
        } catch (Exception e) {
            LOGGER.error("[MetalUniversal] Failed to create MetalIrisPipeline for 'final'", e);
            return false;
        }

        // Release any previously pending final-pass view that was never
        // consumed (e.g. if blitFromTexture wasn't called last frame).
        MetalGpuTextureView stale = pendingFinalPassView.getAndSet(null);
        if (stale != null) {
            closePendingView(stale);
        }

        // Create an RGBA8 render target texture via the public GpuDevice API.
        GpuTexture renderTargetTex;
        MetalGpuTextureView renderTargetView;
        try {
            renderTargetTex = device.createTexture(
                    "iris_final_target",
                    GpuTexture.USAGE_RENDER_ATTACHMENT,
                    GpuFormat.RGBA8_UNORM,
                    width, height, 1, 1
            );
            GpuTextureView view = device.createTextureView(renderTargetTex);
            renderTargetView = (MetalGpuTextureView) view;
        } catch (Exception e) {
            LOGGER.error("[MetalUniversal] Failed to create render target texture for final pass", e);
            return false;
        }

        // Render the fullscreen triangle.
        boolean success = renderFullscreenPass(
                device, pipeline, renderTargetView, width, height
        );

        if (success) {
            // Hand ownership to MetalSurface.blitFromTexture, which will
            // present this texture to the drawable and defer-release it after
            // the present command buffer completes.
            pendingFinalPassView.set(renderTargetView);
        } else {
            renderTargetTex.close();
            renderTargetView.close();
        }

        return success;
    }

    /**
     * Returns the pending final-pass render target view (set by
     * {@link #renderFinalPass}), or {@code null} if none is pending.
     *
     * <p>The caller ({@link MetalSurface#blitFromTexture}) takes ownership of
     * the returned view and must close it (via deferred release) after
     * presenting it. Package-private — only accessible within the render
     * package.
     */
    static MetalGpuTextureView consumePendingFinalPassView() {
        return pendingFinalPassView.getAndSet(null);
    }

    /**
     * Closes a pending final-pass view and its underlying texture. The native
     * texture handle is released via the device's deferred-release queue
     * (3-submit delay), so this is safe to call even if the texture was just
     * used by an in-flight command buffer.
     */
    private static void closePendingView(final MetalGpuTextureView view) {
        try {
            GpuTexture tex = view.texture();
            view.close();
            tex.close();
        } catch (Exception e) {
            LOGGER.warn("[MetalUniversal] Error closing stale final-pass view", e);
        }
    }

    /**
     * Gets or creates a cached {@link MetalIrisPipeline} with multiple color
     * attachments (MRT).
     */
    private static MetalIrisPipeline getOrCreatePipelineMulti(
            final MetalDevice device,
            final String name,
            final String vertexMsl,
            final String fragmentMsl,
            final MTLPixelFormat[] colorFormats,
            final boolean hasDepth
    ) {
        return pipelineCache.computeIfAbsent(name, n -> new MetalIrisPipeline(
                device, n, vertexMsl, fragmentMsl, colorFormats, hasDepth
        ));
    }

    /**
     * Renders a fullscreen triangle to multiple color attachments (MRT).
     *
     * <p>Uses {@link MetalCommandEncoder#renderCommandEncoderMulti} to create
     * a render pass with up to 8 color attachments. Dummy textures are bound
     * to sampler slots 0–7 so reads from unbound colortex samplers don't read
     * garbage.
     *
     * @param device      the Metal device
     * @param pipeline    the compiled Iris pipeline (must have been created
     *                    with matching {@code colorFormats})
     * @param colorViews  array of color attachment views (entries may be
     *                    {@code null} to leave that slot unbound)
     * @param colorCount  number of entries in {@code colorViews}
     * @param width       viewport width
     * @param height      viewport height
     * @return {@code true} if the draw call was issued successfully
     */
    private static boolean renderFullscreenPassMulti(
            final MetalDevice device,
            final MetalIrisPipeline pipeline,
            final MetalGpuTextureView[] colorViews,
            final int colorCount,
            final int width,
            final int height
    ) {
        try {
            MetalCommandEncoder encoder = device.createCommandEncoder();
            MTLRenderCommandEncoder renderEnc = encoder.renderCommandEncoderMulti(
                    colorViews, colorCount, null,
                    width, height,
                    true, // clearColorEnabled — clear all attachments to black
                    0.0f, 0.0f, 0.0f, 0.0f,
                    false, 0.0 // no depth
            );

            renderEnc.setRenderPipelineState(pipeline.pipelineState(false));

            // Bind dummy textures to sampler slots 0–7. Composite/deferred
            // passes read colortex0–7; without bound textures Metal would
            // read garbage or crash. (Real read-back of previous pass output
            // is future work — this validates the MRT write path.)
            MemorySegment dummyTex = getDummyTexture(device);
            if (!MetalNativeBridge.isNullHandle(dummyTex)) {
                for (int i = 0; i < MAX_TEXTURE_SLOTS; i++) {
                    renderEnc.setTexture(dummyTex, i, STAGE_ALL);
                }
            }

            renderEnc.drawPrimitives(MTLPrimitiveType.Triangle, 0, 3, 1, 0);

            LOGGER.info("[MetalUniversal] Rendered MRT pass '{}' ({} attachments, {}x{})",
                    pipeline.name(), colorCount, width, height);
            return true;
        } catch (Throwable t) {
            LOGGER.error("[MetalUniversal] Failed to render MRT pass '{}'", pipeline.name(), t);
            return false;
        }
    }

    /**
     * Renders an Iris composite/deferred pass to a set of HDR MRT render
     * targets.
     *
     * <p>This is the M4e entry point for fullscreen passes that write to
     * multiple colortex outputs (composite1, deferred1, ...). It:
     * <ol>
     *   <li>Creates {@code colorAttachmentCount} RGBA16F render target textures</li>
     *   <li>Gets/creates a cached {@link MetalIrisPipeline} with those formats</li>
     *   <li>Renders a fullscreen triangle to all attachments</li>
     * </ol>
     *
     * <p>The render targets are released immediately after the draw call
     * (deferred-release is safe — the GPU finishes using them before they're
     * freed). Real composite chaining (reading previous pass output as
     * sampler input, ping-ponging between target sets) is future work; this
     * implementation validates that the MRT draw call executes without crash.
     *
     * @param name                program name (e.g. "composite1", "deferred1")
     * @param vertexMsl           compiled MSL vertex source
     * @param fragmentMsl         compiled MSL fragment source
     * @param width               viewport width
     * @param height              viewport height
     * @param colorAttachmentCount number of MRT color attachments (1-8)
     * @return {@code true} if the pass rendered successfully
     */
    public static boolean renderCompositePass(
            final String name,
            final String vertexMsl,
            final String fragmentMsl,
            final int width,
            final int height,
            final int colorAttachmentCount
    ) {
        MetalDevice device = getMetalDevice();
        if (device == null) {
            LOGGER.warn("[MetalUniversal] Cannot render composite pass '{}': MetalDevice not available", name);
            return false;
        }

        int count = Math.max(1, Math.min(colorAttachmentCount, MAX_TEXTURE_SLOTS));
        MTLPixelFormat[] formats = new MTLPixelFormat[count];
        Arrays.fill(formats, MTLPixelFormat.RGBA16Float);

        MetalIrisPipeline pipeline;
        try {
            pipeline = getOrCreatePipelineMulti(device, name, vertexMsl, fragmentMsl, formats, false);
        } catch (Exception e) {
            LOGGER.error("[MetalUniversal] Failed to create MetalIrisPipeline for '{}'", name, e);
            return false;
        }

        GpuTexture[] textures = new GpuTexture[count];
        MetalGpuTextureView[] colorViews = new MetalGpuTextureView[count];
        boolean success;
        try {
            for (int i = 0; i < count; i++) {
                textures[i] = device.createTexture(
                        "iris_" + name + "_colortex" + i,
                        GpuTexture.USAGE_RENDER_ATTACHMENT,
                        GpuFormat.RGBA16_FLOAT,
                        width, height, 1, 1
                );
                GpuTextureView view = device.createTextureView(textures[i]);
                colorViews[i] = (MetalGpuTextureView) view;
            }
            success = renderFullscreenPassMulti(device, pipeline, colorViews, count, width, height);
        } finally {
            for (GpuTexture t : textures) {
                if (t != null) {
                    // Deferred-release: safe to close immediately, the GPU
                    // retains the texture until the current command buffer
                    // finishes execution.
                    t.close();
                }
            }
        }
        return success;
    }

    /**
     * Clears the pipeline cache. Called when the MetalIrisRenderingPipeline is
     * destroyed (shaderpack reload) to free cached pipeline states.
     */
    public static void clearCache() {
        for (MetalIrisPipeline pipeline : pipelineCache.values()) {
            try {
                pipeline.close();
            } catch (Exception e) {
                LOGGER.warn("[MetalUniversal] Error closing pipeline '{}'", pipeline.name(), e);
            }
        }
        pipelineCache.clear();
    }
}
