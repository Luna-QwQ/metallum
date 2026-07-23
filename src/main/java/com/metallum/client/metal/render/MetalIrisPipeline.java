package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLCompareFunction;
import com.metallum.client.metal.render.mtl.MTLCullMode;
import com.metallum.client.metal.render.mtl.MTLPixelFormat;
import com.metallum.client.metal.render.mtl.MTLPrimitiveType;
import com.metallum.client.metal.render.mtl.MTLRenderPipelineDescriptor;
import com.metallum.client.metal.render.mtl.MTLTriangleFillMode;
import com.metallum.client.metal.render.mtl.MTLVertexDescriptor;
import com.metallum.client.metal.render.mtl.MTLVertexFormat;
import com.metallum.client.metal.render.mtl.MTLVertexStepFunction;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.MemorySegment;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Metal render pipeline created from Iris shaderpack MSL source, bypassing
 * Mojang's {@code RenderPipeline} / {@code MetalCrossShaderCompiler.compile}
 * path entirely.
 *
 * <p>This class is in the {@code com.metallum.client.metal.render} package so
 * it can access {@link MetalDevice}'s package-private
 * {@link MetalDevice#getOrCompileFunction(String, String)} method and
 * {@link MetalDevice#metalDeviceHandle()}.
 *
 * <p>Construction steps:
 * <ol>
 *   <li>Compile the vertex and fragment MSL source strings into native
 *       {@code MTLFunction} handles via
 *       {@link MetalDevice#getOrCompileFunction}.</li>
 *   <li>Create an {@link MTLRenderPipelineDescriptor}, set the compiled
 *       functions, a {@link MTLVertexDescriptor}, and the color/depth pixel
 *       formats. The vertex descriptor is empty (no vertex buffers) for
 *       fullscreen passes (composite/final generate positions from
 *       {@code vertex_id}), or a real descriptor matching the terrain vertex
 *       format for gbuffers/shadow passes (M5a).</li>
 *   <li>Call {@link MetalNativeBridge#metallum_MTLDevice_makeRenderPipelineState}
 *       to create the native {@code MTLRenderPipelineState} handle.</li>
 * </ol>
 *
 * <p>Two pipeline-state handles are created: one with depth
 * ({@code Depth32Float}) and one without ({@code Invalid}), matching the
 * pattern in {@link MetalCompiledRenderPipeline}. The correct one is selected
 * at draw time via {@link #pipelineState(boolean)}.
 *
 * <h2>M5a — Vertex descriptor support</h2>
 * The {@link #MetalIrisPipeline(MetalDevice, String, String, String, MTLPixelFormat[], boolean, VertexFormat[])}
 * constructor accepts a {@code VertexFormat[]} and builds a real
 * {@link MTLVertexDescriptor} via {@link #buildVertexDescriptor}, mirroring
 * {@code MetalCompiledRenderPipeline.buildVertexDescriptor}. This lets
 * gbuffers/shadow pipelines bind vertex buffers (terrain, entities) with proper
 * attribute layout. The {@link #vertexBufferCount()} and
 * {@link #firstAvailableVertexBufferSlot()} getters expose the descriptor's
 * layout so the draw path (M5c) can bind vertex buffers to the correct Metal
 * slots.
 *
 * <h2>M5d-1 — Pipeline-state swap surface</h2>
 * Exposes the draw-state getters ({@link #getNativePipeline(boolean)},
 * {@link #depthStencilState()}, {@link #cullMode()}, {@link #fillMode()},
 * {@link #topology()}, {@link #depthBiasConstant()},
 * {@link #depthBiasScaleFactor()}) that {@code MetalRenderPass.bindDrawState}
 * needs to substitute this pipeline's native {@code MTLRenderPipelineState} for
 * vanilla's when the Iris pipeline swap is enabled. Iris gbuffers pipelines
 * carry no {@code RenderPipeline} descriptor, so these return fixed safe
 * defaults (no cull, fill, triangle list, zero depth bias). Uniform/sampler
 * binding for the Iris MSL is added in M5d-2/M5d-3; until then the swap is
 * gated off by {@code MetalIrisRenderer.pipelineSwapEnabled} (default false) so
 * vanilla rendering is unaffected.
 *
 * <h2>M5d-2 — MSL binding reflection</h2>
 * The constructor regex-parses the compiled vertex/fragment MSL source for
 * {@code [[buffer(N)]]} / {@code [[texture(N)]]} / {@code [[sampler(N)]]}
 * attributes (SPIRV-Cross emits these because
 * {@code SPVC_COMPILER_OPTION_MSL_ENABLE_DECORATION_BINDING} is enabled) and
 * builds a list of {@link IrisResourceBinding}s exposed via {@link #bindings()}.
 * Vertex inputs use {@code [[attribute(N)]]} / {@code [[stage_in]]} (see
 * {@code MetalCrossShaderCompiler.registerIntegerInputConversions}), so
 * {@code [[buffer(N)]]} in the MSL is exclusively UBOs and push-constants —
 * vertex buffers (configured via {@link MTLVertexDescriptor}) never appear as
 * {@code [[buffer(N)]]}, which makes the regex parse safe.
 * {@code MetalRenderPass.bindDrawState} uses the reflected UBO bindings to bind
 * Iris uniform buffers when the pipeline swap is active (M5d-2); texture/sampler
 * bindings are reflected now but bound in M5d-3.
 */
final class MetalIrisPipeline implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("MetalUniversal");

    private static final Pattern VERTEX_ENTRY_PATTERN =
            Pattern.compile("\\bvertex\\s+\\w+\\s+(\\w+)\\s*\\(");
    private static final Pattern FRAGMENT_ENTRY_PATTERN =
            Pattern.compile("\\bfragment\\s+\\w+\\s+(\\w+)\\s*\\(");

    /**
     * Matches an MSL resource attribute of the form {@code NAME [[kind(N)]]},
     * capturing the variable name and the binding index. The optional
     * {@code (?:\[[^\]]*\]\s*)*} skips array dimensions between the name and
     * the attribute (e.g. {@code tex[2] [[texture(0)]]}). Used to reflect the
     * {@code [[buffer(N)]]} / {@code [[texture(N)]]} / {@code [[sampler(N)]]}
     * attributes SPIRV-Cross emits when
     * {@code SPVC_COMPILER_OPTION_MSL_ENABLE_DECORATION_BINDING} is on.
     */
    private static final Pattern MSL_BUFFER_BINDING_PATTERN =
            Pattern.compile("(\\w+)\\s*(?:\\[[^\\]]*\\]\\s*)*\\[\\[buffer\\((\\d+)\\)\\]\\]");
    private static final Pattern MSL_TEXTURE_BINDING_PATTERN =
            Pattern.compile("(\\w+)\\s*(?:\\[[^\\]]*\\]\\s*)*\\[\\[texture\\((\\d+)\\)\\]\\]");
    private static final Pattern MSL_SAMPLER_BINDING_PATTERN =
            Pattern.compile("(\\w+)\\s*(?:\\[[^\\]]*\\]\\s*)*\\[\\[sampler\\((\\d+)\\)\\]\\]");

    /**
     * Kind of a reflected MSL resource binding (M5d-2). Mirrors the distinction
     * SPIRV-Cross makes when emitting MSL {@code [[buffer(N)]]} /
     * {@code [[texture(N)]]} / {@code [[sampler(N)]]} attributes.
     */
    enum IrisResourceKind {
        UNIFORM_BUFFER,
        TEXTURE,
        SAMPLER
    }

    /**
     * A single reflected MSL resource binding (M5d-2). {@code stageMask} uses
     * the {@link MetalCompiledRenderPipeline#STAGE_VERTEX} /
     * {@link MetalCompiledRenderPipeline#STAGE_FRAGMENT} bits so the draw path
     * can bind the resource to the correct stage(s). Metal vertex and fragment
     * buffer/texture/sampler index tables are independent, so two resources
     * sharing an index but appearing in different stages are kept as separate
     * bindings (each bound with its own stage mask).
     */
    record IrisResourceBinding(IrisResourceKind kind, String name, int bindingIndex, int stageMask) {
    }

    private final String name;
    private final MemorySegment pipelineWithDepth;
    private final MemorySegment pipelineWithoutDepth;
    private final MemorySegment depthStencilState;
    /**
     * Number of vertex buffer bindings declared by the vertex descriptor
     * (M5a). Zero for fullscreen passes (no vertex buffers); positive for
     * gbuffers/shadow passes that bind a real {@link VertexFormat}.
     */
    private final int vertexBufferCount;
    /**
     * The first Metal buffer index used for vertex-buffer binding (M5a).
     * Vertex buffer {@code i} is bound to Metal slot
     * {@code firstAvailableVertexBufferSlot + i}. For Iris gbuffers this is 0
     * (Iris MSL does not reserve vertex-stage UBO slots before vertex buffers
     * the way vanilla pipelines do).
     */
    private final int firstAvailableVertexBufferSlot;
    /**
     * Fixed draw-state defaults (M5d-1). Iris gbuffers pipelines don't carry
     * a {@code RenderPipeline} descriptor, so these mirror vanilla's safe
     * defaults for opaque scene draws. They are exposed via getters so
     * {@code MetalRenderPass.bindDrawState} can substitute the Iris pipeline
     * state for vanilla's when the pipeline swap is enabled. Cull mode is
     * {@code None} (no culling) as a conservative default — overdraw, but no
     * accidentally-culled geometry while the swap is being brought up.
     */
    private final MTLCullMode cullMode = MTLCullMode.None;
    private final MTLTriangleFillMode fillMode = MTLTriangleFillMode.Fill;
    private final MTLPrimitiveType topology = MTLPrimitiveType.Triangle;
    private final float depthBiasConstant = 0.0f;
    private final float depthBiasScaleFactor = 0.0f;
    /**
     * Reflected MSL resource bindings (M5d-2). Built once at construction by
     * {@link #reflectBindings} regex-parsing the vertex/fragment MSL for
     * {@code [[buffer(N)]]} / {@code [[texture(N)]]} / {@code [[sampler(N)]]}
     * attributes. Consumed by {@code MetalRenderPass.bindDrawState} to bind
     * Iris UBOs (M5d-2) and textures/samplers (M5d-3) when the pipeline swap
     * is enabled.
     */
    private final List<IrisResourceBinding> bindings;
    private boolean closed;

    /**
     * @param device         the active Metal device
     * @param name           debug name (e.g. "final", "composite1")
     * @param vertexMsl      compiled MSL vertex shader source
     * @param fragmentMsl    compiled MSL fragment shader source
     * @param colorFormat    the single color attachment pixel format
     * @param hasDepth       whether a depth attachment will be used
     */
    MetalIrisPipeline(
            final MetalDevice device,
            final String name,
            final String vertexMsl,
            final String fragmentMsl,
            final MTLPixelFormat colorFormat,
            final boolean hasDepth
    ) {
        this(device, name, vertexMsl, fragmentMsl, new MTLPixelFormat[]{colorFormat}, hasDepth);
    }

    /**
     * Multi-render-target constructor. {@code colorFormats} specifies the
     * pixel format of each color attachment (index 0 = colortex0, etc.).
     * Used by Iris gbuffer/composite/deferred passes that write to multiple
     * colortex outputs simultaneously.
     *
     * <p>This constructor uses an <b>empty</b> {@link MTLVertexDescriptor} (no
     * vertex buffers) — appropriate for fullscreen passes (composite/deferred/
     * final) whose vertex shaders generate positions from {@code vertex_id}.
     * For gbuffers/shadow passes that bind real vertex buffers, use
     * {@link #MetalIrisPipeline(MetalDevice, String, String, String, MTLPixelFormat[], boolean, VertexFormat[])}.
     *
     * @param colorFormats   array of color attachment pixel formats (1-8);
     *                       index 0 is the primary attachment
     * @param hasDepth       whether a depth attachment will be used
     */
    MetalIrisPipeline(
            final MetalDevice device,
            final String name,
            final String vertexMsl,
            final String fragmentMsl,
            final MTLPixelFormat[] colorFormats,
            final boolean hasDepth
    ) {
        this(device, name, vertexMsl, fragmentMsl, colorFormats, hasDepth, null);
    }

    /**
     * Multi-render-target constructor with an explicit vertex descriptor
     * (M5a). Used by Iris gbuffers/shadow passes that bind real vertex buffers
     * (terrain, entities) — the vertex shader reads per-vertex attributes
     * (position, color, UV, lightmap, normal) from bound vertex buffers instead
     * of generating positions from {@code vertex_id}.
     *
     * <p>When {@code vertexFormats} is {@code null} or empty, an empty
     * {@link MTLVertexDescriptor} is used (same as the fullscreen path). When
     * non-null, a real descriptor is built matching the vanilla/Sodium terrain
     * vertex layout, mirroring the logic in
     * {@code MetalCompiledRenderPipeline.buildVertexDescriptor}.
     *
     * @param vertexFormats  the vertex format bindings (one per vertex buffer
     *                       slot), or {@code null} for fullscreen passes
     */
    MetalIrisPipeline(
            final MetalDevice device,
            final String name,
            final String vertexMsl,
            final String fragmentMsl,
            final MTLPixelFormat[] colorFormats,
            final boolean hasDepth,
            final VertexFormat[] vertexFormats
    ) {
        this.name = name;

        String vertexEntry = extractEntryPoint(vertexMsl, VERTEX_ENTRY_PATTERN, "main0");
        String fragmentEntry = extractEntryPoint(fragmentMsl, FRAGMENT_ENTRY_PATTERN, "main0");

        MemorySegment vertexFn = device.getOrCompileFunction(vertexMsl, vertexEntry);
        MemorySegment fragmentFn = device.getOrCompileFunction(fragmentMsl, fragmentEntry);

        if (MetalNativeBridge.isNullHandle(vertexFn)) {
            throw new IllegalStateException("Failed to compile Iris vertex MSL function for '" + name + "'");
        }
        if (MetalNativeBridge.isNullHandle(fragmentFn)) {
            throw new IllegalStateException("Failed to compile Iris fragment MSL function for '" + name + "'");
        }

        // Iris MSL (cross-compiled from GLSL via SPIR-V) declares vertex
        // attributes at buffer indices starting from 0 — unlike vanilla
        // pipelines, there are no vertex-stage UBOs reserved before the vertex
        // buffers. So firstAvailableVertexBufferSlot = 0.
        this.firstAvailableVertexBufferSlot = 0;
        this.vertexBufferCount = countVertexBuffers(vertexFormats);
        this.bindings = reflectBindings(vertexMsl, fragmentMsl);

        this.pipelineWithDepth = hasDepth
                ? createPipelineState(device, vertexFn, fragmentFn, colorFormats, MTLPixelFormat.Depth32Float, vertexFormats, this.firstAvailableVertexBufferSlot)
                : MemorySegment.NULL;
        this.pipelineWithoutDepth = createPipelineState(device, vertexFn, fragmentFn, colorFormats, MTLPixelFormat.Invalid, vertexFormats, this.firstAvailableVertexBufferSlot);

        this.depthStencilState = hasDepth
                ? MetalNativeBridge.MTLDevice_makeDepthStencilState(
                        device.metalDeviceHandle(), MTLCompareFunction.Always, 0)
                : MemorySegment.NULL;

        LOGGER.info("[MetalUniversal] MetalIrisPipeline '{}' created (colorAttachments={}, hasDepth={}, vertexBuffers={}, bindings={})",
                name, colorFormats.length, hasDepth, this.vertexBufferCount, this.bindings.size());
    }

    private static MemorySegment createPipelineState(
            final MetalDevice device,
            final MemorySegment vertexFn,
            final MemorySegment fragmentFn,
            final MTLPixelFormat[] colorFormats,
            final MTLPixelFormat depthFormat,
            final VertexFormat[] vertexFormats,
            final int firstMetalVertexBufferSlot
    ) {
        try (MTLRenderPipelineDescriptor desc = new MTLRenderPipelineDescriptor()) {
            desc.setCompiledFunctions(vertexFn, fragmentFn);
            // Build the vertex descriptor: empty for fullscreen passes (no
            // vertex buffers — vertex_id generates positions), or a real
            // descriptor matching the terrain vertex format for gbuffers/
            // shadow passes (M5a).
            try (MTLVertexDescriptor vertexDesc = buildVertexDescriptor(vertexFormats, firstMetalVertexBufferSlot)) {
                desc.setVertexDescriptor(vertexDesc);
            }
            // Attachment 0 + depth + stencil via the existing single call
            // (sets colorAttachments[0].pixelFormat, depthAttachmentPixelFormat,
            // stencilAttachmentPixelFormat in one native call).
            desc.setAttachmentFormats(colorFormats[0], depthFormat, MTLPixelFormat.Invalid);
            desc.disableBlendingForAttachment(0);
            // Additional color attachments (MRT) for colortex1..N-1.
            for (int i = 1; i < colorFormats.length; i++) {
                desc.setColorAttachmentFormat(i, colorFormats[i]);
                desc.disableBlendingForAttachment(i);
            }

            MemorySegment pipeline = MetalNativeBridge.metallum_MTLDevice_makeRenderPipelineState(
                    device.metalDeviceHandle(), desc.handle());
            if (MetalNativeBridge.isNullHandle(pipeline)) {
                throw new IllegalStateException("metallum_MTLDevice_makeRenderPipelineState returned null");
            }
            return pipeline;
        }
    }

    /**
     * Counts the number of non-null, non-empty vertex format bindings (M5a).
     * Returns 0 when {@code vertexFormats} is {@code null} (fullscreen path).
     */
    private static int countVertexBuffers(final VertexFormat[] vertexFormats) {
        if (vertexFormats == null) {
            return 0;
        }
        int count = 0;
        for (VertexFormat format : vertexFormats) {
            if (format != null && !format.getElements().isEmpty()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Builds a {@link MTLVertexDescriptor} from the given vertex format
     * bindings (M5a). Mirrors the logic in
     * {@code MetalCompiledRenderPipeline.buildVertexDescriptor}:
     * <ul>
     *   <li>Each non-null, non-empty {@link VertexFormat} at index {@code i}
     *       gets a Metal buffer layout at slot
     *       {@code firstMetalVertexBufferSlot + i}.</li>
     *   <li>Attribute indices are flat-monotonic across all bindings (they
     *       don't restart per binding).</li>
     *   <li>{@code stepRate > 0} → {@link MTLVertexStepFunction#PerInstance},
     *       otherwise {@link MTLVertexStepFunction#PerVertex}.</li>
     * </ul>
     * Returns an empty descriptor (no attributes, no layouts) when
     * {@code vertexFormats} is {@code null} or empty — for fullscreen passes.
     */
    private static MTLVertexDescriptor buildVertexDescriptor(
            final VertexFormat[] vertexFormats,
            final int firstMetalVertexBufferSlot
    ) {
        MTLVertexDescriptor vertexDesc = new MTLVertexDescriptor();
        if (vertexFormats == null) {
            return vertexDesc;
        }
        long attrIndex = 0;
        for (int i = 0; i < vertexFormats.length; i++) {
            VertexFormat binding = vertexFormats[i];
            if (binding == null || binding.getElements().isEmpty()) {
                continue;
            }

            int metalSlot = firstMetalVertexBufferSlot + i;

            long stride = binding.getVertexSize();
            long stepRate = binding.getStepRate();
            MTLVertexStepFunction stepFunction = stepRate > 0
                    ? MTLVertexStepFunction.PerInstance
                    : MTLVertexStepFunction.PerVertex;
            vertexDesc.setLayout(metalSlot, stride, stepFunction, stepRate > 0 ? stepRate : 1);

            for (VertexFormatElement element : binding.getElements()) {
                MTLVertexFormat format = MTLVertexFormat.from(element.format());
                if (format == MTLVertexFormat.Invalid) {
                    throw new IllegalStateException("Unsupported vertex attribute format: " + element.format());
                }
                vertexDesc.setAttribute(attrIndex, format.value, element.offset(), metalSlot);
                attrIndex++;
            }
        }
        return vertexDesc;
    }

    private static String extractEntryPoint(final String msl, final Pattern pattern, final String fallback) {
        Matcher matcher = pattern.matcher(msl);
        return matcher.find() ? matcher.group(1) : fallback;
    }

    /**
     * Reflects MSL resource bindings by regex-parsing the compiled vertex and
     * fragment MSL source for {@code [[buffer(N)]]} / {@code [[texture(N)]]} /
     * {@code [[sampler(N)]]} attributes (M5d-2).
     *
     * <p>SPIRV-Cross is configured with
     * {@code SPVC_COMPILER_OPTION_MSL_ENABLE_DECORATION_BINDING = true}, so the
     * compiled MSL carries explicit binding indices in these attributes. Vertex
     * inputs use {@code [[attribute(N)]]} / {@code [[stage_in]]} (not
     * {@code [[buffer(N)]]}), so buffer attributes are exclusively UBOs and
     * push-constants — safe to parse without confusing vertex buffers.
     *
     * <p>Bindings are merged by (kind, index, name): if the same resource
     * appears in both stages, the stage masks are OR-ed so it can be bound in
     * a single {@code setBuffer}/{@code setTexture} call. If two different
     * resources share an index (Metal vertex/fragment index tables are
     * independent), they stay separate and are each bound to their own stage.
     *
     * @param vertexMsl   the compiled vertex MSL source
     * @param fragmentMsl the compiled fragment MSL source
     * @return an unmodifiable list of reflected bindings
     */
    private static List<IrisResourceBinding> reflectBindings(final String vertexMsl, final String fragmentMsl) {
        final Map<String, IrisResourceBinding> byKey = new HashMap<>();
        collectBindings(vertexMsl, MetalCompiledRenderPipeline.STAGE_VERTEX, byKey);
        collectBindings(fragmentMsl, MetalCompiledRenderPipeline.STAGE_FRAGMENT, byKey);
        return List.copyOf(byKey.values());
    }

    private static void collectBindings(
            final String msl,
            final int stageFlag,
            final Map<String, IrisResourceBinding> byKey
    ) {
        collectOne(msl, stageFlag, IrisResourceKind.UNIFORM_BUFFER, MSL_BUFFER_BINDING_PATTERN, byKey);
        collectOne(msl, stageFlag, IrisResourceKind.TEXTURE, MSL_TEXTURE_BINDING_PATTERN, byKey);
        collectOne(msl, stageFlag, IrisResourceKind.SAMPLER, MSL_SAMPLER_BINDING_PATTERN, byKey);
    }

    private static void collectOne(
            final String msl,
            final int stageFlag,
            final IrisResourceKind kind,
            final Pattern pattern,
            final Map<String, IrisResourceBinding> byKey
    ) {
        final Matcher matcher = pattern.matcher(msl);
        while (matcher.find()) {
            final String name = matcher.group(1);
            final int index = Integer.parseInt(matcher.group(2));
            final String key = kind.ordinal() + ":" + index + ":" + name;
            final IrisResourceBinding existing = byKey.get(key);
            if (existing == null) {
                byKey.put(key, new IrisResourceBinding(kind, name, index, stageFlag));
            } else {
                byKey.put(key, new IrisResourceBinding(kind, name, index, existing.stageMask() | stageFlag));
            }
        }
    }

    String name() {
        return name;
    }

    MemorySegment pipelineState(final boolean useDepth) {
        return useDepth && pipelineWithDepth != MemorySegment.NULL
                ? pipelineWithDepth
                : pipelineWithoutDepth;
    }

    MemorySegment depthStencilState() {
        return depthStencilState;
    }

    boolean hasDepth() {
        return depthStencilState != MemorySegment.NULL;
    }

    /**
     * Returns the number of vertex buffer bindings declared by the vertex
     * descriptor (M5a). Zero for fullscreen passes; positive for gbuffers/
     * shadow passes with a real {@link VertexFormat}.
     */
    int vertexBufferCount() {
        return this.vertexBufferCount;
    }

    /**
     * Returns the first Metal buffer index used for vertex-buffer binding
     * (M5a). Vertex buffer {@code i} is bound to Metal slot
     * {@code firstAvailableVertexBufferSlot + i}. Always 0 for Iris pipelines.
     */
    int firstAvailableVertexBufferSlot() {
        return this.firstAvailableVertexBufferSlot;
    }

    /**
     * Returns the native pipeline-state handle to bind, selecting the
     * depth-enabled variant when {@code useDepth} is true (M5d-1). Analogous
     * to {@code MetalCompiledRenderPipeline.getNativePipeline}.
     */
    MemorySegment getNativePipeline(final boolean useDepth) {
        return useDepth && pipelineWithDepth != MemorySegment.NULL
                ? pipelineWithDepth
                : pipelineWithoutDepth;
    }

    /**
     * Returns the draw-state defaults (M5d-1) used when this pipeline is
     * substituted for vanilla's in {@code MetalRenderPass.bindDrawState}.
     */
    MTLCullMode cullMode() {
        return cullMode;
    }

    MTLTriangleFillMode fillMode() {
        return fillMode;
    }

    MTLPrimitiveType topology() {
        return topology;
    }

    float depthBiasConstant() {
        return depthBiasConstant;
    }

    float depthBiasScaleFactor() {
        return depthBiasScaleFactor;
    }

    /**
     * Returns the reflected MSL resource bindings (M5d-2). Each entry describes
     * one {@code [[buffer(N)]]} / {@code [[texture(N)]]} /
     * {@code [[sampler(N)]]} attribute in the compiled vertex/fragment MSL,
     * with the stage(s) it appears in. {@code MetalRenderPass.bindDrawState}
     * consumes the {@link IrisResourceKind#UNIFORM_BUFFER} entries to bind Iris
     * UBOs (M5d-2); texture/sampler entries are bound in M5d-3.
     */
    List<IrisResourceBinding> bindings() {
        return this.bindings;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        // Pipeline states and depth-stencil states are managed by MetalNativeBridge.
        // We release them through the device's deferred release queue to ensure
        // they're not in use by the GPU when freed.
        // For simplicity in this beta, we leak them — the pipeline lives for the
        // lifetime of MetalIrisRenderingPipeline which is only destroyed on
        // shaderpack reload (infrequent).
        LOGGER.debug("[MetalUniversal] MetalIrisPipeline '{}' closed (native handles deferred)", name);
    }
}
