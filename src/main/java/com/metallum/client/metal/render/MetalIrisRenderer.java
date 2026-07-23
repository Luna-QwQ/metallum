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
 * <h2>M4f — Final pass screen presentation</h2>
 * The {@link #renderFinalPass} method renders the {@code final} shader to an
 * offscreen RGBA8 target, then hands the view to
 * {@link MetalSurface#blitFromTexture} via {@link #consumePendingFinalPassView}
 * for on-screen presentation (the present path samples the texture, so RGBA8
 * need not match the BGRA8 drawable).
 *
 * <h2>M4g — Composite chaining</h2>
 * Composite/deferred passes ({@link #renderCompositePass}) use a persistent
 * two-set ping-pong pool of RGBA16F MRT targets. Each pass reads the current
 * "read set" as samplers, writes the "write set", then swaps — so
 * composite1 → composite2 → … → final chain correctly. The final pass reads
 * the last composite output as its samplers.
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

    /**
     * Number of MRT color attachments in the composite target pool. Matches
     * {@code MetalIrisRenderingPipeline.COMPOSITE_MRT_COUNT}. Most shaderpacks
     * use colortex0–3 in composite/deferred passes.
     */
    private static final int COMPOSITE_TARGET_COUNT = 4;

    /**
     * Two ping-pong sets of HDR render targets for composite/deferred passes
     * (M4g). Each composite pass reads from the current "read set" (bound as
     * samplers) and writes to the "write set" (MRT color attachments), then
     * the sets swap. This is how Iris chains composite1 → composite2 → … →
     * final.
     *
     * <p>The textures are persistent across passes and frames (temporal data
     * survives), recreated only when the screen size changes.
     */
    private static GpuTexture[] compositeSetA = null;
    private static GpuTexture[] compositeSetB = null;
    private static MetalGpuTextureView[] compositeViewsA = null;
    private static MetalGpuTextureView[] compositeViewsB = null;
    private static boolean compositeReadIsA = true;
    private static int compositeTargetWidth = 0;
    private static int compositeTargetHeight = 0;

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
     * @param device     the Metal device
     * @param pipeline   the compiled Iris pipeline
     * @param colorView  the color attachment texture view
     * @param readViews  optional sampler textures to bind to slots 0..readCount-1
     *                   (e.g. the composite read set for the final pass); may be
     *                   {@code null} to bind only dummy textures
     * @param readCount  number of entries in {@code readViews} to bind
     * @param width      viewport width
     * @param height     viewport height
     * @return {@code true} if the draw call was issued successfully
     */
    private static boolean renderFullscreenPass(
            final MetalDevice device,
            final MetalIrisPipeline pipeline,
            final MetalGpuTextureView colorView,
            final MetalGpuTextureView[] readViews,
            final int readCount,
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

            // Bind sampler textures: read views (previous pass output) to the
            // low slots, dummy texture to the rest.
            bindSamplerTextures(renderEnc, device, readViews, readCount);

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

        // Render the fullscreen triangle. Bind the current composite read set
        // (the last composite/deferred pass's output) as samplers so the final
        // pass can tonemap/grade the composited scene. If no composite pass
        // ran, the read set is null and only dummy textures are bound.
        MetalGpuTextureView[] compositeReadViews = getCompositeReadViews();
        boolean success = renderFullscreenPass(
                device, pipeline, renderTargetView,
                compositeReadViews, COMPOSITE_TARGET_COUNT,
                width, height
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

    // ---- Composite ping-pong target pool (M4g) ----

    /**
     * Ensures the composite ping-pong target pool exists and matches the given
     * dimensions, recreating the textures if the size changed. Creates two
     * sets of {@link #COMPOSITE_TARGET_COUNT} RGBA16F textures (render-target
     * + shader-read), each with a view.
     */
    private static void ensureCompositeTargets(final MetalDevice device, final int width, final int height) {
        if (compositeSetA != null && compositeTargetWidth == width && compositeTargetHeight == height) {
            return;
        }
        releaseCompositeTargets();
        compositeSetA = new GpuTexture[COMPOSITE_TARGET_COUNT];
        compositeSetB = new GpuTexture[COMPOSITE_TARGET_COUNT];
        compositeViewsA = new MetalGpuTextureView[COMPOSITE_TARGET_COUNT];
        compositeViewsB = new MetalGpuTextureView[COMPOSITE_TARGET_COUNT];
        int usage = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING;
        for (int i = 0; i < COMPOSITE_TARGET_COUNT; i++) {
            compositeSetA[i] = device.createTexture(
                    "iris_composite_A_colortex" + i, usage,
                    GpuFormat.RGBA16_FLOAT, width, height, 1, 1);
            compositeViewsA[i] = (MetalGpuTextureView) device.createTextureView(compositeSetA[i]);
            compositeSetB[i] = device.createTexture(
                    "iris_composite_B_colortex" + i, usage,
                    GpuFormat.RGBA16_FLOAT, width, height, 1, 1);
            compositeViewsB[i] = (MetalGpuTextureView) device.createTextureView(compositeSetB[i]);
        }
        compositeTargetWidth = width;
        compositeTargetHeight = height;
        compositeReadIsA = true;
    }

    /**
     * Returns the current composite read set views (the previous pass's
     * output, to be bound as samplers), or {@code null} if the pool is not
     * initialized.
     */
    private static MetalGpuTextureView[] getCompositeReadViews() {
        if (compositeViewsA == null) {
            return null;
        }
        return compositeReadIsA ? compositeViewsA : compositeViewsB;
    }

    /**
     * Returns the current composite write set views (the MRT targets to render
     * into this pass). The pool must be initialized.
     */
    private static MetalGpuTextureView[] getCompositeWriteViews() {
        return compositeReadIsA ? compositeViewsB : compositeViewsA;
    }

    /**
     * Swaps the read and write sets (ping-pong). Called after each composite
     * pass so the next pass reads this pass's output.
     */
    private static void swapCompositeSets() {
        compositeReadIsA = !compositeReadIsA;
    }

    /**
     * Releases all composite pool textures and views (called on resize or
     * cache clear). The device's deferred-release queue reclaims native
     * handles safely.
     */
    private static void releaseCompositeTargets() {
        closeViewSet(compositeViewsA, compositeSetA);
        closeViewSet(compositeViewsB, compositeSetB);
        compositeViewsA = null;
        compositeViewsB = null;
        compositeSetA = null;
        compositeSetB = null;
        compositeTargetWidth = 0;
        compositeTargetHeight = 0;
    }

    private static void closeViewSet(final MetalGpuTextureView[] views, final GpuTexture[] textures) {
        if (views != null) {
            for (MetalGpuTextureView v : views) {
                if (v != null) {
                    try {
                        v.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        if (textures != null) {
            for (GpuTexture t : textures) {
                if (t != null) {
                    try {
                        t.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    /**
     * Binds sampler textures to slots 0–7: the given read views to slots
     * 0..readCount-1 (the previous pass's colortex output), and the dummy
     * texture to any remaining slots so unbound samplers don't read garbage.
     */
    private static void bindSamplerTextures(
            final MTLRenderCommandEncoder renderEnc,
            final MetalDevice device,
            final MetalGpuTextureView[] readViews,
            final int readCount
    ) {
        int bound = 0;
        if (readViews != null) {
            for (int i = 0; i < readCount && i < MAX_TEXTURE_SLOTS; i++) {
                if (readViews[i] != null) {
                    renderEnc.setTexture(readViews[i].nativeHandle(), i, STAGE_ALL);
                    bound++;
                }
            }
        }
        MemorySegment dummyTex = getDummyTexture(device);
        if (!MetalNativeBridge.isNullHandle(dummyTex)) {
            for (int i = bound; i < MAX_TEXTURE_SLOTS; i++) {
                renderEnc.setTexture(dummyTex, i, STAGE_ALL);
            }
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
     * a render pass with up to 8 color attachments. The given read views are
     * bound to sampler slots 0..readCount-1 (the previous pass's output for
     * composite chaining), and the dummy texture to any remaining slots.
     *
     * @param device      the Metal device
     * @param pipeline    the compiled Iris pipeline (must have been created
     *                    with matching {@code colorFormats})
     * @param colorViews  array of color attachment views (entries may be
     *                    {@code null} to leave that slot unbound)
     * @param colorCount  number of entries in {@code colorViews}
     * @param readViews   optional sampler textures to bind to slots 0..readCount-1
     *                    (the composite read set); may be {@code null}
     * @param readCount   number of entries in {@code readViews} to bind
     * @param width       viewport width
     * @param height      viewport height
     * @return {@code true} if the draw call was issued successfully
     */
    private static boolean renderFullscreenPassMulti(
            final MetalDevice device,
            final MetalIrisPipeline pipeline,
            final MetalGpuTextureView[] colorViews,
            final int colorCount,
            final MetalGpuTextureView[] readViews,
            final int readCount,
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

            // Bind sampler textures: read views (previous pass output) to the
            // low slots, dummy texture to the rest.
            bindSamplerTextures(renderEnc, device, readViews, readCount);

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
     * Renders an Iris composite/deferred pass using the persistent ping-pong
     * target pool (M4g).
     *
     * <p>This is the entry point for fullscreen passes that write to multiple
     * colortex outputs (composite1, deferred1, ...). It:
     * <ol>
     *   <li>Ensures the composite target pool exists at the right size</li>
     *   <li>Binds the current read set (previous pass's output) as samplers</li>
     *   <li>Renders a fullscreen triangle to the write set (MRT)</li>
     *   <li>Swaps read/write sets so the next pass reads this pass's output</li>
     * </ol>
     *
     * <p>The {@code colorAttachmentCount} parameter is clamped to
     * {@link #COMPOSITE_TARGET_COUNT}; the pool always allocates that many
     * targets so every pass sees a consistent MRT layout.
     *
     * @param name                program name (e.g. "composite1", "deferred1")
     * @param vertexMsl           compiled MSL vertex source
     * @param fragmentMsl         compiled MSL fragment source
     * @param width               viewport width
     * @param height              viewport height
     * @param colorAttachmentCount requested MRT color attachments (clamped to pool size)
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

        // The pool size is fixed at COMPOSITE_TARGET_COUNT; ignore a larger
        // request (and clamp a smaller one up so the MRT layout is consistent
        // across passes — the pipeline is cached per name+format-array).
        int count = COMPOSITE_TARGET_COUNT;
        MTLPixelFormat[] formats = new MTLPixelFormat[count];
        Arrays.fill(formats, MTLPixelFormat.RGBA16Float);

        MetalIrisPipeline pipeline;
        try {
            pipeline = getOrCreatePipelineMulti(device, name, vertexMsl, fragmentMsl, formats, false);
        } catch (Exception e) {
            LOGGER.error("[MetalUniversal] Failed to create MetalIrisPipeline for '{}'", name, e);
            return false;
        }

        // Ensure the ping-pong pool exists (creates/recreates on size change).
        try {
            ensureCompositeTargets(device, width, height);
        } catch (Exception e) {
            LOGGER.error("[MetalUniversal] Failed to allocate composite targets for '{}'", name, e);
            return false;
        }

        MetalGpuTextureView[] readViews = getCompositeReadViews();
        MetalGpuTextureView[] writeViews = getCompositeWriteViews();

        boolean success = renderFullscreenPassMulti(
                device, pipeline, writeViews, count, readViews, count, width, height
        );

        // Ping-pong: the next pass (or the final pass) reads what this pass
        // just wrote.
        if (success) {
            swapCompositeSets();
        }
        return success;
    }

    /**
     * Clears the pipeline cache and composite target pool. Called when the
     * MetalIrisRenderingPipeline is destroyed (shaderpack reload) to free
     * cached pipeline states and HDR render targets.
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
        releaseCompositeTargets();
    }
}
