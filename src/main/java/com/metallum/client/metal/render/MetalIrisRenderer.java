package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLHazardTrackingMode;
import com.metallum.client.metal.render.mtl.MTLIndexType;
import com.metallum.client.metal.render.mtl.MTLPixelFormat;
import com.metallum.client.metal.render.mtl.MTLPrimitiveType;
import com.metallum.client.metal.render.mtl.MTLRenderCommandEncoder;
import com.metallum.client.metal.render.mtl.MTLRenderStages;
import com.metallum.client.metal.render.mtl.MTLSamplerAddressMode;
import com.metallum.client.metal.render.mtl.MTLSamplerMinMagFilter;
import com.metallum.client.metal.render.mtl.MTLSamplerMipFilter;
import com.metallum.client.metal.render.mtl.MTLResourceOptions;
import com.metallum.client.metal.render.mtl.MTLStorageMode;
import com.metallum.client.metal.render.mtl.MTLTextureUsage;
import com.metallum.mixin.accessor.MetallumGpuDeviceAccessor;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
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
 *
 * <h2>M4h — Geometry &amp; shadow pass targets</h2>
 * Sets up the render-target half of Iris geometry passes:
 * <ul>
 *   <li>A persistent gbuffer MRT pool ({@link #ensureGbufferTargets}) of
 *       {@value #GBUFFER_MRT_COUNT} RGBA8 color attachments (colortex0–3) plus
 *       a depth32Float attachment, recreated on screen-size change.</li>
 *   <li>A persistent shadow-map depth target ({@link #ensureShadowTarget}) at
 *       {@value #SHADOW_MAP_SIZE}×{@value #SHADOW_MAP_SIZE}.</li>
 *   <li>{@link #beginGeometryPass} / {@link #endGeometryPass} and
 *       {@link #beginShadowPass} / {@link #endShadowPass} which create a
 *       render command encoder with the Iris gbuffers/shadow pipeline bound,
 *       ready for the caller to issue vertex-buffer draws.</li>
 * </ul>
 * The actual scene-draw redirection (intercepting vanilla terrain/entity
 * draws to route them through these encoders) is a separate, larger effort
 * — this milestone provides the render targets and encoder API that such a
 * redirect would plug into. The composite passes can already sample the
 * gbuffer color/depth and shadow map via {@link #getGbufferColorViews},
 * {@link #getGbufferDepthView}, and {@link #getShadowMapView}.
 */
public final class MetalIrisRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger("MetalUniversal");

    /** Stage mask: vertex + fragment (used for buffer/texture binding). */
    private static final int STAGE_ALL = (int) MTLRenderStages.VertexAndFragment.value;

    /** Maximum number of texture slots to bind dummy textures to. */
    private static final int MAX_TEXTURE_SLOTS = 8;

    /** Stage mask: vertex only (for vertex-buffer/uniform binding). */
    private static final int STAGE_VERTEX = (int) MTLRenderStages.Vertex.value;

    /** Stage mask: fragment only (for fragment-buffer/uniform binding). */
    private static final int STAGE_FRAGMENT = (int) MTLRenderStages.Fragment.value;

    /**
     * Number of MRT color attachments in the gbuffer target pool (M4h).
     * Matches the Iris default gbuffer layout: colortex0 (albedo),
     * colortex1 (normals), colortex2 (specular), colortex3 (lightmap).
     */
    private static final int GBUFFER_MRT_COUNT = 4;

    /**
     * Shadow map render target dimension (M4h). Iris renders the shadow map
     * to a square depth texture at this resolution; shaderpacks may request a
     * different size, but this is a reasonable default for the scaffolding.
     */
    private static final int SHADOW_MAP_SIZE = 2048;

    /** Cache of MetalIrisPipeline objects, keyed by program name. */
    private static final Map<String, MetalIrisPipeline> pipelineCache = new HashMap<>();

    /** Cached 1x1 dummy texture handle (created lazily, reused across frames). */
    private static MemorySegment dummyTextureHandle = MemorySegment.NULL;

    /**
     * Cached default {@code MTLSamplerState} handle (M5d-3). Bound alongside
     * the dummy texture to reflected Iris sampler slots that have no provided
     * texture/sampler, so Metal never reads unbound sampler state. Created
     * lazily, reused across frames (like {@link #dummyTextureHandle}).
     */
    private static MemorySegment dummySamplerHandle = MemorySegment.NULL;

    /**
     * Cached fullscreen quad vertex buffer (M5d-2 fix). Bound to vertex slot 0
     * for fullscreen Iris passes (composite/deferred/final) whose vertex MSL
     * declares {@code [[stage_in]]} inputs ({@code Position}, {@code UV0}).
     * Iris's {@code patchComposite} injects these attributes, so the pipeline
     * requires a matching vertex buffer or pipeline creation fails with
     * {@code "Function requires stage_in attributes but no descriptor was set."}
     *
     * <p>The buffer holds 4 vertices forming a fullscreen quad in NDC space
     * (-1..1), packed according to the reflected attribute layout. It is
     * rebuilt only if a different pipeline's reflected stride differs from
     * {@link #fullscreenQuadStride}; in practice all fullscreen Iris passes
     * share the same {@code (vec3 Position, vec2 UV0)} layout, so the buffer
     * is created once and reused across passes.
     */
    private static MemorySegment fullscreenQuadBuffer = MemorySegment.NULL;

    /**
     * Cached fullscreen quad index buffer (6 UInt16 indices for 2 triangles).
     * Bound alongside {@link #fullscreenQuadBuffer} when issuing indexed draws.
     */
    private static MemorySegment fullscreenQuadIndexBuffer = MemorySegment.NULL;

    /**
     * The vertex stride (bytes) the cached {@link #fullscreenQuadBuffer} was
     * built for. Zero means no buffer has been created yet. When a pipeline's
     * {@link MetalIrisPipeline#fullscreenVertexStride()} differs, the buffer
     * is released and rebuilt.
     */
    private static long fullscreenQuadStride = 0L;

    /**
     * The active Iris gbuffers program name for the current rendering phase
     * (M5c). Set by {@link com.metallum.client.metal.iris.MetalIrisRenderingPipeline#setPhase}
     * / {@code setOverridePhase} (which resolve the phase → program name), read
     * by {@link MetalCommandEncoder#createRenderPass} to decide whether to
     * redirect the render target to the gbuffer MRT pool.
     *
     * <p>{@code null} means no gbuffers phase is active (e.g. {@code NONE},
     * {@code PARTICLES}, {@code CLOUDS}, ...) — vanilla render passes target
     * the screen as normal.
     */
    private static volatile String activeGbuffersProgram = null;

    /**
     * Sets the active gbuffers program name (M5c). Called by
     * {@link MetalIrisRenderingPipeline} when the phase changes. Pass
     * {@code null} to clear (no gbuffers redirect).
     */
    public static void setActiveGbuffersProgram(final String name) {
        activeGbuffersProgram = name;
    }

    /**
     * Returns the active gbuffers program name, or {@code null} if no
     * gbuffers phase is active (M5c). When non-null,
     * {@link MetalCommandEncoder#createRenderPass} redirects the render target
     * to the gbuffer MRT pool so vanilla scene draws render into the gbuffer
     * instead of the screen.
     */
    public static String getActiveGbuffersProgram() {
        return activeGbuffersProgram;
    }

    /**
     * Whether {@code MetalRenderPass} should substitute the Iris gbuffers
     * pipeline for vanilla's when a gbuffers phase is active (M5d-1).
     *
     * <p>Flipped to {@code true} in
     * {@code MetalIrisRenderingPipeline.beginLevelRendering} (M5d-3): by that
     * point the Iris UBO binding (M5d-2) and texture/sampler binding (M5d-3)
     * are both in place, so the Iris MSL never runs with unbound arguments.
     * Reset to {@code false} by {@link #clearCache()} (shaderpack reload). When
     * no gbuffers phase is active, {@link #getActiveIrisPipeline()} returns
     * {@code null} and vanilla rendering is unaffected even with this flag on.
     */
    private static volatile boolean pipelineSwapEnabled = false;

    public static boolean isPipelineSwapEnabled() {
        return pipelineSwapEnabled;
    }

    public static void setPipelineSwapEnabled(final boolean enabled) {
        pipelineSwapEnabled = enabled;
    }

    /**
     * Returns the cached {@link MetalIrisPipeline} for the active gbuffers
     * program, or {@code null} if no gbuffers phase is active or the program
     * has not been cached yet (M5d-1).
     *
     * <p>{@code MetalRenderPass.setPipeline} calls this (when
     * {@link #isPipelineSwapEnabled()} is true) to obtain the Iris pipeline
     * whose native state {@code bindDrawState} will substitute for vanilla's.
     * A {@code null} return leaves the vanilla pipeline in effect.
     */
    static MetalIrisPipeline getActiveIrisPipeline() {
        final String name = activeGbuffersProgram;
        return name == null ? null : pipelineCache.get(name);
    }

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

    // ---- Gbuffer MRT target pool (M4h) ----

    /**
     * Persistent gbuffer MRT color attachments (M4h). colortex0–3 as RGBA8,
     * matching the Iris default gbuffer layout. Recreated on screen-size
     * change. Used both as render targets (during gbuffers passes) and as
     * sampler inputs (during composite/deferred passes).
     */
    private static GpuTexture[] gbufferColorTextures = null;
    private static MetalGpuTextureView[] gbufferColorViews = null;
    private static GpuTexture gbufferDepthTexture = null;
    private static MetalGpuTextureView gbufferDepthView = null;
    private static int gbufferTargetWidth = 0;
    private static int gbufferTargetHeight = 0;

    // ---- Shadow map target (M4h) ----

    /**
     * Persistent shadow-map depth target (M4h). A square depth32Float texture
     * at {@link #SHADOW_MAP_SIZE}, rendered to during the shadow pass and
     * sampled during composite/deferred passes. Color writes are disabled for
     * the shadow pass, so no color attachment is needed — a tiny 1×1 dummy
     * color texture is used only because the render-encoder wrapper requires
     * a non-null color attachment.
     */
    private static GpuTexture shadowDepthTexture = null;
    private static MetalGpuTextureView shadowDepthView = null;
    private static GpuTexture shadowDummyColorTexture = null;
    private static MetalGpuTextureView shadowDummyColorView = null;

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
     * sampler slots so Metal doesn't read garbage). Package-private so
     * {@code MetalRenderPass.pushIrisTextureBindings} (M5d-3) can reuse the
     * same handle for reflected Iris texture slots that have no provided value.
     */
    static MemorySegment getDummyTexture(final MetalDevice device) {
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
     * Gets or creates the cached default sampler state (M5d-3). Bound alongside
     * the dummy texture to reflected Iris sampler slots that have no provided
     * texture/sampler, so Metal never reads unbound sampler state. Uses safe
     * defaults (clamp-to-edge, linear filter, no mipmapping).
     */
    static MemorySegment getDummySampler(final MetalDevice device) {
        if (MetalNativeBridge.isNullHandle(dummySamplerHandle)) {
            dummySamplerHandle = MetalNativeBridge.metallum_create_sampler(
                    device.metalDeviceHandle(),
                    MTLSamplerAddressMode.ClampToEdge,
                    MTLSamplerAddressMode.ClampToEdge,
                    MTLSamplerMinMagFilter.Linear,
                    MTLSamplerMinMagFilter.Linear,
                    MTLSamplerMipFilter.NotMipmapped,
                    1,
                    1000.0
            );
            if (MetalNativeBridge.isNullHandle(dummySamplerHandle)) {
                LOGGER.warn("[MetalUniversal] Failed to create dummy sampler");
            }
        }
        return dummySamplerHandle;
    }

    /**
     * Ensures the cached fullscreen quad vertex + index buffers exist and
     * match the given pipeline's reflected {@code [[stage_in]]} attribute
     * layout. Called by {@link #renderFullscreenPass} /
     * {@link #renderFullscreenPassMulti} before issuing an indexed draw when
     * {@link MetalIrisPipeline#hasStageInAttributes()} is true.
     *
     * <p>The quad uses 4 vertices in NDC space covering the screen:
     * <pre>
     *   vertex 0: Position=(-1,-1,0)  UV0=(0,0)
     *   vertex 1: Position=( 1,-1,0)  UV0=(1,0)
     *   vertex 2: Position=( 1, 1,0)  UV0=(1,1)
     *   vertex 3: Position=(-1, 1,0)  UV0=(0,1)
     * </pre>
     * drawn as 2 triangles via 6 UInt16 indices: {@code 0,1,2, 0,2,3}.
     *
     * <p>Vertex data is packed contiguously by attribute location (matching
     * {@link MetalIrisPipeline#fullscreenAttributes()}). Attributes named
     * {@code Position} get the NDC coordinates above; attributes named
     * {@code UV0} get the UV coordinates above; any other attribute is
     * filled with zeros (safe default for unused inputs).
     *
     * @return {@code true} if the buffers are ready to bind; {@code false} on
     *         allocation failure (caller should fall back to drawPrimitives)
     */
    private static boolean ensureFullscreenQuadBuffer(
            final MetalDevice device,
            final MetalIrisPipeline pipeline
    ) {
        final List<MetalIrisPipeline.IrisVertexAttribute> attrs = pipeline.fullscreenAttributes();
        final long stride = pipeline.fullscreenVertexStride();
        if (attrs.isEmpty() || stride <= 0L) {
            return false;
        }

        // Fast path: cached buffer already matches this layout.
        if (!MetalNativeBridge.isNullHandle(fullscreenQuadBuffer)
                && !MetalNativeBridge.isNullHandle(fullscreenQuadIndexBuffer)
                && fullscreenQuadStride == stride) {
            return true;
        }

        // Layout changed (or first creation): release any stale buffers.
        releaseFullscreenQuadBuffer();

        final int vertexCount = 4;
        final long vertexDataSize = stride * vertexCount;
        // Shared storage (CPU-accessible) so we can memcpy the quad data in.
        final long resourceOptions = MTLResourceOptions.of(MTLStorageMode.Shared, MTLHazardTrackingMode.Untracked);

        final MemorySegment vbuf = MetalNativeBridge.metallum_create_buffer(
                device.metalDeviceHandle(), vertexDataSize, resourceOptions);
        if (MetalNativeBridge.isNullHandle(vbuf)) {
            LOGGER.warn("[MetalUniversal] Failed to create fullscreen quad vertex buffer (stride={}, size={})", stride, vertexDataSize);
            return false;
        }
        final MemorySegment vbufContents = MetalNativeBridge.metallum_get_buffer_contents(vbuf);
        if (MetalNativeBridge.isNullHandle(vbufContents)) {
            MetalNativeBridge.metallum_release_object(vbuf);
            LOGGER.warn("[MetalUniversal] Failed to map fullscreen quad vertex buffer contents");
            return false;
        }

        // NDC quad positions and UVs (per vertex 0..3 above).
        final float[] positions = {
                -1.0f, -1.0f, 0.0f,
                1.0f, -1.0f, 0.0f,
                1.0f, 1.0f, 0.0f,
                -1.0f, 1.0f, 0.0f
        };
        final float[] uvs = {
                0.0f, 0.0f,
                1.0f, 0.0f,
                1.0f, 1.0f,
                0.0f, 1.0f
        };

        final ByteBuffer vbufView = MetalNativeBridge.nativeByteBufferView(vbufContents, vertexDataSize)
                .order(ByteOrder.nativeOrder());
        for (int v = 0; v < vertexCount; v++) {
            for (final MetalIrisPipeline.IrisVertexAttribute attr : attrs) {
                final String name = attr.name();
                final String type = attr.typeName();
                if ("Position".equals(name)) {
                    putFloats(vbufView, type, positions, v * 3);
                } else if ("UV0".equals(name)) {
                    putFloats(vbufView, type, uvs, v * 2);
                } else {
                    // Unknown stage_in attribute: zero-fill its byte size so the
                    // descriptor layout stays valid (Metal still reads it).
                    for (long b = 0; b < attr.byteSize(); b++) {
                        vbufView.put((byte) 0);
                    }
                }
            }
        }
        vbufView.flip();

        // Index buffer: 6 UInt16 indices for two triangles.
        final long indexDataSize = 6L * MTLIndexType.UInt16.bytes;
        final MemorySegment ibuf = MetalNativeBridge.metallum_create_buffer(
                device.metalDeviceHandle(), indexDataSize, resourceOptions);
        if (MetalNativeBridge.isNullHandle(ibuf)) {
            MetalNativeBridge.metallum_release_object(vbuf);
            LOGGER.warn("[MetalUniversal] Failed to create fullscreen quad index buffer");
            return false;
        }
        final MemorySegment ibufContents = MetalNativeBridge.metallum_get_buffer_contents(ibuf);
        if (MetalNativeBridge.isNullHandle(ibufContents)) {
            MetalNativeBridge.metallum_release_object(vbuf);
            MetalNativeBridge.metallum_release_object(ibuf);
            LOGGER.warn("[MetalUniversal] Failed to map fullscreen quad index buffer contents");
            return false;
        }
        final ByteBuffer ibufView = MetalNativeBridge.nativeByteBufferView(ibufContents, indexDataSize)
                .order(ByteOrder.nativeOrder()); // UInt16 indices in platform native order
        ibufView.putShort((short) 0);
        ibufView.putShort((short) 1);
        ibufView.putShort((short) 2);
        ibufView.putShort((short) 0);
        ibufView.putShort((short) 2);
        ibufView.putShort((short) 3);
        ibufView.flip();

        fullscreenQuadBuffer = vbuf;
        fullscreenQuadIndexBuffer = ibuf;
        fullscreenQuadStride = stride;
        LOGGER.info("[MetalUniversal] Created fullscreen quad buffers (stride={}, vertices={}, indices=6, attrs={})",
                stride, vertexCount, attrs.size());
        return true;
    }

    /**
     * Writes a vertex attribute value into {@code buf} as floats, sized to the
     * MSL type. {@code float3} writes 3 floats, {@code half2} writes 2 shorts
     * (converted from float), etc. Used by {@link #ensureFullscreenQuadBuffer}
     * to pack the quad's {@code Position}/{@code UV0} data per attribute.
     *
     * @param buf        the vertex buffer byte view, positioned at the attribute
     * @param typeName   the MSL type name (e.g. {@code float3}, {@code half2})
     * @param values     the float source array (positions or UVs)
     * @param offset     the starting index in {@code values} for this vertex
     */
    private static void putFloats(final ByteBuffer buf, final String typeName, final float[] values, final int offset) {
        final boolean isHalf = typeName.startsWith("half");
        final int components = switch (typeName) {
            case "float", "half", "int", "uint" -> 1;
            case "float2", "half2", "int2", "uint2" -> 2;
            case "float3", "half3", "int3", "uint3" -> 3;
            case "float4", "half4", "int4", "uint4" -> 4;
            default -> 0;
        };
        for (int c = 0; c < components; c++) {
            final float v = (offset + c < values.length) ? values[offset + c] : 0.0f;
            if (isHalf) {
                buf.putShort(floatToHalf(v));
            } else {
                buf.putFloat(v);
            }
        }
    }

    /**
     * Converts an IEEE-754 {@code float} to an IEEE-754 {@code half} (binary16)
     * bit pattern stored in a {@code short}. Used by {@link #putFloats} when a
     * reflected stage_in attribute uses an MSL {@code halfN} type.
     *
     * <p>Handles zero, denormals (flushed to zero), normal range, overflow
     * (clamped to ±Inf), and Inf/NaN.
     */
    private static short floatToHalf(final float f) {
        final int bits = Float.floatToIntBits(f);
        final int sign = (bits >>> 16) & 0x8000;
        final int exponent = (bits >>> 23) & 0xff;
        final int mantissa = bits & 0x7fffff;
        if (exponent == 0xff) {
            // Inf or NaN
            return (short) (sign | 0x7c00 | (mantissa != 0 ? 1 : 0));
        }
        if (exponent < 113) {
            // Too small for half (denormal or zero) → flush to zero with sign
            return (short) sign;
        }
        if (exponent > 142) {
            // Overflow → Inf with sign
            return (short) (sign | 0x7c00);
        }
        final int newExponent = exponent - 112;
        return (short) (sign | (newExponent << 10) | (mantissa >> 13));
    }

    /**
     * Releases the cached fullscreen quad buffers (vertex + index). Called on
     * layout change and from {@link #clearCache}.
     */
    private static void releaseFullscreenQuadBuffer() {
        if (!MetalNativeBridge.isNullHandle(fullscreenQuadBuffer)) {
            MetalNativeBridge.metallum_release_object(fullscreenQuadBuffer);
            fullscreenQuadBuffer = MemorySegment.NULL;
        }
        if (!MetalNativeBridge.isNullHandle(fullscreenQuadIndexBuffer)) {
            MetalNativeBridge.metallum_release_object(fullscreenQuadIndexBuffer);
            fullscreenQuadIndexBuffer = MemorySegment.NULL;
        }
        fullscreenQuadStride = 0L;
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

            // If the vertex MSL declares [[stage_in]] inputs (Iris's
            // patchComposite injects `in vec3 Position; in vec2 UV0;`), bind
            // a fullscreen quad vertex buffer + index buffer and issue an
            // indexed draw. Otherwise fall back to the vertex_id-generated
            // fullscreen triangle (3 vertices, no buffers).
            if (pipeline.hasStageInAttributes() && ensureFullscreenQuadBuffer(device, pipeline)) {
                renderEnc.setBuffer(fullscreenQuadBuffer, 0L, pipeline.fullscreenVertexBufferSlot(), STAGE_VERTEX);
                renderEnc.drawIndexedPrimitives(
                        MTLPrimitiveType.Triangle,
                        6, MTLIndexType.UInt16, fullscreenQuadIndexBuffer, 0L,
                        1, 0, 0);
            } else {
                renderEnc.drawPrimitives(MTLPrimitiveType.Triangle, 0, 3, 1, 0);
            }

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
     * attachments (MRT). Uses an empty vertex descriptor (fullscreen path).
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
     * Gets or creates a cached {@link MetalIrisPipeline} with multiple color
     * attachments (MRT) and a real vertex descriptor (M5a). Used by gbuffers/
     * shadow passes that bind vertex buffers.
     *
     * <p>The cache key is {@code name} alone — if the same program name is
     * first created as a fullscreen pipeline (no vertex format) and later
     * requested with a vertex format, the cached fullscreen pipeline is
     * returned. Callers should use distinct program names for fullscreen vs
     * geometry pipelines (Iris already does: "composite1" vs
     * "gbuffers_terrain").
     *
     * @param vertexFormats  the vertex format bindings, or {@code null} for
     *                       an empty vertex descriptor (fullscreen path)
     */
    private static MetalIrisPipeline getOrCreatePipelineMulti(
            final MetalDevice device,
            final String name,
            final String vertexMsl,
            final String fragmentMsl,
            final MTLPixelFormat[] colorFormats,
            final boolean hasDepth,
            final com.mojang.blaze3d.vertex.VertexFormat[] vertexFormats
    ) {
        return pipelineCache.computeIfAbsent(name, n -> new MetalIrisPipeline(
                device, n, vertexMsl, fragmentMsl, colorFormats, hasDepth, vertexFormats
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

            // If the vertex MSL declares [[stage_in]] inputs (composite/deferred
            // passes patched by Iris's patchComposite), bind the fullscreen quad
            // vertex + index buffers and issue an indexed draw. Otherwise fall
            // back to the vertex_id-generated fullscreen triangle.
            if (pipeline.hasStageInAttributes() && ensureFullscreenQuadBuffer(device, pipeline)) {
                renderEnc.setBuffer(fullscreenQuadBuffer, 0L, pipeline.fullscreenVertexBufferSlot(), STAGE_VERTEX);
                renderEnc.drawIndexedPrimitives(
                        MTLPrimitiveType.Triangle,
                        6, MTLIndexType.UInt16, fullscreenQuadIndexBuffer, 0L,
                        1, 0, 0);
            } else {
                renderEnc.drawPrimitives(MTLPrimitiveType.Triangle, 0, 3, 1, 0);
            }

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

    // ---- Gbuffer MRT target pool (M4h) ----

    /**
     * Ensures the gbuffer MRT target pool exists and matches the given
     * dimensions, recreating the textures if the size changed. Creates
     * {@link #GBUFFER_MRT_COUNT} RGBA8 color attachments (colortex0–3) plus a
     * depth32Float depth attachment, each with render-attachment + shader-read
     * usage and a view.
     */
    private static void ensureGbufferTargets(final MetalDevice device, final int width, final int height) {
        if (gbufferColorTextures != null && gbufferTargetWidth == width && gbufferTargetHeight == height) {
            return;
        }
        releaseGbufferTargets();
        gbufferColorTextures = new GpuTexture[GBUFFER_MRT_COUNT];
        gbufferColorViews = new MetalGpuTextureView[GBUFFER_MRT_COUNT];
        int usage = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING;
        for (int i = 0; i < GBUFFER_MRT_COUNT; i++) {
            gbufferColorTextures[i] = device.createTexture(
                    "iris_gbuffer_colortex" + i, usage,
                    GpuFormat.RGBA8_UNORM, width, height, 1, 1);
            gbufferColorViews[i] = (MetalGpuTextureView) device.createTextureView(gbufferColorTextures[i]);
        }
        gbufferDepthTexture = device.createTexture(
                "iris_gbuffer_depth", usage,
                GpuFormat.D32_FLOAT, width, height, 1, 1);
        gbufferDepthView = (MetalGpuTextureView) device.createTextureView(gbufferDepthTexture);
        gbufferTargetWidth = width;
        gbufferTargetHeight = height;
    }

    /**
     * Releases the gbuffer MRT pool (color attachments + depth). Native handles
     * are reclaimed via the device's deferred-release queue.
     */
    private static void releaseGbufferTargets() {
        closeViewSet(gbufferColorViews, gbufferColorTextures);
        if (gbufferDepthView != null) {
            try {
                gbufferDepthView.close();
            } catch (Exception ignored) {
            }
            gbufferDepthView = null;
        }
        if (gbufferDepthTexture != null) {
            try {
                gbufferDepthTexture.close();
            } catch (Exception ignored) {
            }
            gbufferDepthTexture = null;
        }
        gbufferColorViews = null;
        gbufferColorTextures = null;
        gbufferTargetWidth = 0;
        gbufferTargetHeight = 0;
    }

    /**
     * Returns the gbuffer color attachment views (colortex0–3), or {@code null}
     * if the pool is not initialized. Composite/deferred passes bind these as
     * samplers to read the scene's gbuffer output.
     */
    static MetalGpuTextureView[] getGbufferColorViews() {
        return gbufferColorViews;
    }

    /**
     * Returns the gbuffer depth attachment view, or {@code null} if the pool is
     * not initialized. Composite/deferred passes sample this to reconstruct
     * view-space position.
     */
    static MetalGpuTextureView getGbufferDepthView() {
        return gbufferDepthView;
    }

    // ---- Shadow map target (M4h) ----

    /**
     * Ensures the shadow-map depth target exists. Creates a square
     * depth32Float texture at {@link #SHADOW_MAP_SIZE} plus a 1×1 RGBA8 dummy
     * color attachment (the render-encoder wrapper requires a non-null color
     * attachment; the shadow pass disables color writes, so the dummy is
     * never written to).
     */
    private static void ensureShadowTarget(final MetalDevice device) {
        if (shadowDepthTexture != null) {
            return;
        }
        int usage = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING;
        shadowDepthTexture = device.createTexture(
                "iris_shadow_depth", usage,
                GpuFormat.D32_FLOAT, SHADOW_MAP_SIZE, SHADOW_MAP_SIZE, 1, 1);
        shadowDepthView = (MetalGpuTextureView) device.createTextureView(shadowDepthTexture);
        shadowDummyColorTexture = device.createTexture(
                "iris_shadow_dummy_color", GpuTexture.USAGE_RENDER_ATTACHMENT,
                GpuFormat.RGBA8_UNORM, 1, 1, 1, 1);
        shadowDummyColorView = (MetalGpuTextureView) device.createTextureView(shadowDummyColorTexture);
    }

    /**
     * Releases the shadow-map depth target and dummy color attachment.
     */
    private static void releaseShadowTarget() {
        if (shadowDepthView != null) {
            try {
                shadowDepthView.close();
            } catch (Exception ignored) {
            }
            shadowDepthView = null;
        }
        if (shadowDepthTexture != null) {
            try {
                shadowDepthTexture.close();
            } catch (Exception ignored) {
            }
            shadowDepthTexture = null;
        }
        if (shadowDummyColorView != null) {
            try {
                shadowDummyColorView.close();
            } catch (Exception ignored) {
            }
            shadowDummyColorView = null;
        }
        if (shadowDummyColorTexture != null) {
            try {
                shadowDummyColorTexture.close();
            } catch (Exception ignored) {
            }
            shadowDummyColorTexture = null;
        }
    }

    /**
     * Returns the shadow-map depth view, or {@code null} if not initialized.
     * Composite/deferred passes sample this to compute shadow occlusion.
     */
    static MetalGpuTextureView getShadowMapView() {
        return shadowDepthView;
    }

    /**
     * Public entry point for {@link com.metallum.client.metal.iris.MetalIrisRenderingPipeline#beginLevelRendering}
     * to ensure the gbuffer MRT pool and shadow-map target exist (and match
     * the current screen size) before the scene is rendered. Safe to call
     * every frame — recreates the gbuffer pool only on size change, and the
     * shadow target only once.
     *
     * @param width  screen/framebuffer width
     * @param height screen/framebuffer height
     */
    public static void ensureGbufferAndShadowTargets(final int width, final int height) {
        MetalDevice device = getMetalDevice();
        if (device == null) {
            return;
        }
        try {
            ensureGbufferTargets(device, width, height);
        } catch (Exception e) {
            LOGGER.error("[MetalUniversal] Failed to ensure gbuffer targets", e);
        }
        try {
            ensureShadowTarget(device);
        } catch (Exception e) {
            LOGGER.error("[MetalUniversal] Failed to ensure shadow target", e);
        }
    }

    // ---- Geometry / shadow pass encoder API (M4h) ----

    /**
     * Begins an Iris gbuffers geometry pass: creates (or retrieves from cache)
     * the gbuffers {@link MetalIrisPipeline} targeting the gbuffer MRT pool,
     * then returns a render command encoder with the pipeline bound and the
     * color/depth attachments cleared.
     *
     * <p>The returned encoder is ready for the caller to bind vertex/index
     * buffers ({@link MTLRenderCommandEncoder#setBuffer}) and issue indexed
     * draws ({@link MTLRenderCommandEncoder#drawIndexedPrimitives}). The
     * caller is responsible for all per-draw state (vertex buffers, uniforms,
     * textures, cull mode, etc.).
     *
     * <p>The encoder lifecycle is managed by {@link MetalCommandEncoder} — it
     * is ended automatically when the next encoder is created or the command
     * buffer is submitted. {@link #endGeometryPass} is provided for symmetry
     * but is a no-op.
     *
     * @param name         gbuffers program name (e.g. "gbuffers_terrain")
     * @param vertexMsl    compiled MSL vertex source for the gbuffers program
     * @param fragmentMsl  compiled MSL fragment source for the gbuffers program
     * @param width        viewport width (gbuffer target width)
     * @param height       viewport height (gbuffer target height)
     * @return the render command encoder, or {@code null} on failure
     */
    public static MTLRenderCommandEncoder beginGeometryPass(
            final String name,
            final String vertexMsl,
            final String fragmentMsl,
            final int width,
            final int height
    ) {
        return beginGeometryPass(name, vertexMsl, fragmentMsl, width, height, null);
    }

    /**
     * Begins an Iris gbuffers geometry pass with an explicit vertex format
     * (M5a). When {@code vertexFormats} is non-null, the pipeline is created
     * with a real {@code MTLVertexDescriptor} matching the terrain vertex
     * layout, so the caller can bind vertex buffers and issue indexed draws.
     *
     * @param vertexFormats  the vertex format bindings (one per vertex buffer
     *                       slot), or {@code null} for an empty vertex
     *                       descriptor (no vertex attributes — not useful for
     *                       geometry passes but kept for API symmetry)
     * @see #beginGeometryPass(String, String, String, int, int)
     */
    public static MTLRenderCommandEncoder beginGeometryPass(
            final String name,
            final String vertexMsl,
            final String fragmentMsl,
            final int width,
            final int height,
            final com.mojang.blaze3d.vertex.VertexFormat[] vertexFormats
    ) {
        MetalDevice device = getMetalDevice();
        if (device == null) {
            LOGGER.warn("[MetalUniversal] Cannot begin geometry pass '{}': MetalDevice not available", name);
            return null;
        }

        MTLPixelFormat[] colorFormats = new MTLPixelFormat[GBUFFER_MRT_COUNT];
        Arrays.fill(colorFormats, MTLPixelFormat.RGBA8Unorm);

        MetalIrisPipeline pipeline;
        try {
            pipeline = getOrCreatePipelineMulti(device, name, vertexMsl, fragmentMsl, colorFormats, true, vertexFormats);
        } catch (Exception e) {
            LOGGER.error("[MetalUniversal] Failed to create gbuffers pipeline for '{}'", name, e);
            return null;
        }

        try {
            ensureGbufferTargets(device, width, height);
        } catch (Exception e) {
            LOGGER.error("[MetalUniversal] Failed to allocate gbuffer targets for '{}'", name, e);
            return null;
        }

        try {
            MetalCommandEncoder encoder = device.createCommandEncoder();
            MTLRenderCommandEncoder renderEnc = encoder.renderCommandEncoderMulti(
                    gbufferColorViews, GBUFFER_MRT_COUNT, gbufferDepthView,
                    width, height,
                    true, // clearColorEnabled — clear all color attachments to black
                    0.0f, 0.0f, 0.0f, 0.0f,
                    true, 1.0 // clearDepthEnabled — clear depth to far
            );

            renderEnc.setRenderPipelineState(pipeline.pipelineState(true));
            if (pipeline.hasDepth()) {
                renderEnc.setDepthStencilState(pipeline.depthStencilState());
            }

            LOGGER.info("[MetalUniversal] Began geometry pass '{}' ({} MRT attachments, {}x{}, vertexBuffers={})",
                    name, GBUFFER_MRT_COUNT, width, height, pipeline.vertexBufferCount());
            return renderEnc;
        } catch (Throwable t) {
            LOGGER.error("[MetalUniversal] Failed to begin geometry pass '{}'", name, t);
            return null;
        }
    }

    /**
     * Ends the current geometry pass. This is a no-op — the render command
     * encoder is ended automatically by {@link MetalCommandEncoder} when the
     * next encoder is created or the command buffer is submitted. Provided for
     * API symmetry with {@link #beginGeometryPass}.
     */
    public static void endGeometryPass() {
        // Encoder lifecycle is managed by MetalCommandEncoder.
    }

    /**
     * Begins an Iris shadow pass: creates (or retrieves from cache) the shadow
     * {@link MetalIrisPipeline} targeting the shadow-map depth texture, then
     * returns a render command encoder with the pipeline bound and depth
     * cleared.
     *
     * <p>The shadow pass is depth-only (color writes disabled). A 1×1 dummy
     * color attachment is bound because the render-encoder wrapper requires a
     * non-null color attachment; the shadow program's fragment shader should
     * not write color.
     *
     * <p>The returned encoder is ready for the caller to bind shadow-camera
     * vertex/index buffers and issue draws. See {@link #beginGeometryPass} for
     * the encoder-lifecycle contract.
     *
     * @param name        shadow program name (e.g. "shadow_solid")
     * @param vertexMsl   compiled MSL vertex source for the shadow program
     * @param fragmentMsl compiled MSL fragment source for the shadow program
     * @return the render command encoder, or {@code null} on failure
     */
    public static MTLRenderCommandEncoder beginShadowPass(
            final String name,
            final String vertexMsl,
            final String fragmentMsl
    ) {
        return beginShadowPass(name, vertexMsl, fragmentMsl, null);
    }

    /**
     * Begins an Iris shadow pass with an explicit vertex format (M5a).
     *
     * @param vertexFormats  the vertex format bindings, or {@code null} for an
     *                       empty vertex descriptor
     * @see #beginShadowPass(String, String, String)
     */
    public static MTLRenderCommandEncoder beginShadowPass(
            final String name,
            final String vertexMsl,
            final String fragmentMsl,
            final com.mojang.blaze3d.vertex.VertexFormat[] vertexFormats
    ) {
        MetalDevice device = getMetalDevice();
        if (device == null) {
            LOGGER.warn("[MetalUniversal] Cannot begin shadow pass '{}': MetalDevice not available", name);
            return null;
        }

        // Shadow pass: single RGBA8 dummy color attachment + depth32Float.
        MetalIrisPipeline pipeline;
        try {
            pipeline = getOrCreatePipelineMulti(
                    device, name, vertexMsl, fragmentMsl,
                    new MTLPixelFormat[]{MTLPixelFormat.RGBA8Unorm}, true, vertexFormats);
        } catch (Exception e) {
            LOGGER.error("[MetalUniversal] Failed to create shadow pipeline for '{}'", name, e);
            return null;
        }

        try {
            ensureShadowTarget(device);
        } catch (Exception e) {
            LOGGER.error("[MetalUniversal] Failed to allocate shadow target for '{}'", name, e);
            return null;
        }

        try {
            MetalCommandEncoder encoder = device.createCommandEncoder();
            MTLRenderCommandEncoder renderEnc = encoder.renderCommandEncoder(
                    shadowDummyColorView, shadowDepthView,
                    SHADOW_MAP_SIZE, SHADOW_MAP_SIZE,
                    true, // clearColorEnabled — clear the dummy color (harmless)
                    0.0f, 0.0f, 0.0f, 0.0f,
                    true, 1.0 // clearDepthEnabled — clear shadow depth to far
            );

            renderEnc.setRenderPipelineState(pipeline.pipelineState(true));
            if (pipeline.hasDepth()) {
                renderEnc.setDepthStencilState(pipeline.depthStencilState());
            }

            LOGGER.info("[MetalUniversal] Began shadow pass '{}' ({}x{}, vertexBuffers={})",
                    name, SHADOW_MAP_SIZE, SHADOW_MAP_SIZE, pipeline.vertexBufferCount());
            return renderEnc;
        } catch (Throwable t) {
            LOGGER.error("[MetalUniversal] Failed to begin shadow pass '{}'", name, t);
            return null;
        }
    }

    /**
     * Ends the current shadow pass. This is a no-op — see {@link #endGeometryPass}.
     */
    public static void endShadowPass() {
        // Encoder lifecycle is managed by MetalCommandEncoder.
    }

    /**
     * Clears the pipeline cache and all Iris render-target pools (composite,
     * gbuffer, shadow). Called when the MetalIrisRenderingPipeline is destroyed
     * (shaderpack reload) to free cached pipeline states and render targets.
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
        releaseGbufferTargets();
        releaseShadowTarget();
        releaseFullscreenQuadBuffer();
        // The pipeline cache is now empty, so any active gbuffers program /
        // swap flag would reference stale state. Clear both (M5d-1).
        activeGbuffersProgram = null;
        pipelineSwapEnabled = false;
        // Release any pending final-pass view that was never presented.
        MetalGpuTextureView stale = pendingFinalPassView.getAndSet(null);
        if (stale != null) {
            closePendingView(stale);
        }
    }
}
