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
import java.util.ArrayList;
import java.util.Comparator;
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
 * {@code MetalRenderPass.bindDrawState} consumes the reflected UBO bindings to
 * bind Iris uniform buffers (M5d-2) and the reflected texture/sampler bindings
 * to bind Iris samplers (M5d-3) when the pipeline swap is active.
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
     * Matches a vertex function {@code [[stage_in]]} parameter, capturing the
     * struct type name (e.g. {@code main0_in}) and the parameter name
     * (e.g. {@code in}). Used to reflect stage_in vertex attributes for
     * fullscreen Iris passes.
     */
    private static final Pattern STAGE_IN_PARAM_PATTERN =
            Pattern.compile("(\\w+)\\s+(\\w+)\\s*\\[\\[stage_in\\]\\]");

    /**
     * Matches an MSL struct member with an {@code [[attribute(N)]]} attribute,
     * capturing the type name, member name, and attribute location. Used to
     * reflect the vertex attributes of the stage_in struct.
     */
    private static final Pattern MSL_ATTRIBUTE_PATTERN =
            Pattern.compile("(\\w+)\\s+(\\w+)\\s*\\[\\[attribute\\((\\d+)\\)\\]\\]");

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

    /**
     * A reflected MSL {@code [[stage_in]]} vertex attribute. Used to auto-build
     * a {@link MTLVertexDescriptor} for fullscreen Iris passes (composite/
     * deferred/final) whose vertex shaders declare vertex attribute inputs
     * (e.g. {@code Position}, {@code UV0}) that Iris's {@code patchComposite}
     * injects. Without a matching descriptor, Metal rejects the pipeline with
     * {@code "Function requires stage_in attributes but no descriptor was set."}
     *
     * <p>{@code offset} is the byte offset within the packed vertex buffer
     * (all attributes share buffer slot 0, packed contiguously by location).
     */
    record IrisVertexAttribute(String name, String typeName, int location, MTLVertexFormat format, long byteSize, long offset) {
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
    /**
     * Reflected {@code [[stage_in]]} vertex attributes for fullscreen passes
     * (composite/deferred/final). Non-empty only when {@code vertexFormats}
     * was {@code null} (fullscreen path) and the vertex MSL declares
     * {@code [[stage_in]]} inputs. Consumed by {@code MetalIrisRenderer} to
     * bind a fullscreen quad vertex buffer matching the descriptor.
     */
    private final List<IrisVertexAttribute> fullscreenAttributes;
    /**
     * Packed vertex stride (sum of all {@link #fullscreenAttributes} byte
     * sizes). Zero when no stage_in attributes were reflected.
     */
    private final long fullscreenVertexStride;
    /**
     * The Metal buffer slot used by the auto-built fullscreen vertex
     * descriptor layout (and where the renderer must bind the fullscreen quad
     * vertex buffer). Computed as {@code maxVertexStageUboIndex + 1} so the
     * quad buffer never collides with a vertex-stage UBO argument
     * {@code [[buffer(N)]]} — Iris's {@code wrapLooseUniformsInUbo} injects a
     * binding-less UBO that SPIRV-Cross assigns sequential indices from 0, so
     * without this offset the quad buffer and the first UBO would share slot 0.
     * Zero when there are no vertex-stage UBOs (slot 0 is then safe) or when
     * no fullscreen descriptor is in use.
     */
    private final int fullscreenVertexBufferSlot;
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
        this.bindings = reflectBindings(vertexMsl, fragmentMsl);

        // Reflect [[stage_in]] vertex attributes for fullscreen passes
        // (composite/deferred/final). Iris's patchComposite injects
        // `in vec3 Position;` and `in vec2 UV0;` which SPIRV-Cross compiles
        // to [[stage_in]] inputs with [[attribute(N)]] decorations. Without a
        // matching vertex descriptor, Metal rejects the pipeline with
        // "Function requires stage_in attributes but no descriptor was set."
        // We auto-build a packed descriptor from the reflected attributes so
        // fullscreen passes work without an explicit VertexFormat[].
        final List<IrisVertexAttribute> reflectedAttrs = reflectStageInAttributes(vertexMsl);
        final boolean useFullscreenDescriptor = vertexFormats == null && !reflectedAttrs.isEmpty();
        this.fullscreenAttributes = useFullscreenDescriptor ? reflectedAttrs : List.of();
        this.fullscreenVertexStride = useFullscreenDescriptor
                ? reflectedAttrs.stream().mapToLong(IrisVertexAttribute::byteSize).sum()
                : 0L;
        this.vertexBufferCount = useFullscreenDescriptor ? 1 : countVertexBuffers(vertexFormats);
        // Place the fullscreen quad vertex buffer AFTER any vertex-stage UBO
        // so its buffer slot doesn't collide with a [[buffer(N)]] UBO argument.
        // Iris's wrapLooseUniformsInUbo injects a binding-less UBO that
        // SPIRV-Cross assigns [[buffer(0)]] — without this offset the quad
        // buffer (slot 0) and the first UBO would share the same vertex-stage
        // buffer table entry, causing the shader to read quad vertex bytes as
        // uniform data.
        this.fullscreenVertexBufferSlot = useFullscreenDescriptor
                ? computeFullscreenVertexBufferSlot(this.bindings)
                : 0;

        this.pipelineWithDepth = hasDepth
                ? createPipelineState(device, vertexFn, fragmentFn, colorFormats, MTLPixelFormat.Depth32Float, vertexFormats, this.firstAvailableVertexBufferSlot, this.fullscreenAttributes, this.fullscreenVertexBufferSlot)
                : MemorySegment.NULL;
        this.pipelineWithoutDepth = createPipelineState(device, vertexFn, fragmentFn, colorFormats, MTLPixelFormat.Invalid, vertexFormats, this.firstAvailableVertexBufferSlot, this.fullscreenAttributes, this.fullscreenVertexBufferSlot);

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
            final int firstMetalVertexBufferSlot,
            final List<IrisVertexAttribute> fullscreenAttributes,
            final int fullscreenVertexBufferSlot
    ) {
        try (MTLRenderPipelineDescriptor desc = new MTLRenderPipelineDescriptor()) {
            desc.setCompiledFunctions(vertexFn, fragmentFn);
            // Build the vertex descriptor: empty for fullscreen passes (no
            // vertex buffers — vertex_id generates positions), or a real
            // descriptor matching the terrain vertex format for gbuffers/
            // shadow passes (M5a). For fullscreen passes whose vertex MSL
            // declares [[stage_in]] inputs (composite/deferred/final), a
            // packed descriptor is auto-built from reflected attributes.
            try (MTLVertexDescriptor vertexDesc = buildVertexDescriptor(vertexFormats, firstMetalVertexBufferSlot, fullscreenAttributes, fullscreenVertexBufferSlot)) {
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
     * Computes the Metal buffer slot for the fullscreen quad vertex descriptor
     * layout, placed strictly after the highest vertex-stage UBO binding index
     * so the quad buffer never collides with a {@code [[buffer(N)]]} UBO
     * argument. Mirrors {@code MetalCompiledRenderPipeline.firstAvailableVertexBufferSlot}
     * — that method exists for the gbuffers path but the Iris fullscreen path
     * needs its own because fullscreen pipelines don't go through
     * {@code MetalRenderPass.bindDrawState}.
     *
     * <p>Returns 0 when there are no vertex-stage UBOs (slot 0 is then safe).
     */
    private static int computeFullscreenVertexBufferSlot(final List<IrisResourceBinding> bindings) {
        int maxVertexUboIndex = -1;
        for (final IrisResourceBinding b : bindings) {
            if (b.kind() == IrisResourceKind.UNIFORM_BUFFER
                    && (b.stageMask() & MetalCompiledRenderPipeline.STAGE_VERTEX) != 0
                    && b.bindingIndex() > maxVertexUboIndex) {
                maxVertexUboIndex = b.bindingIndex();
            }
        }
        return maxVertexUboIndex + 1;
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
            final int firstMetalVertexBufferSlot,
            final List<IrisVertexAttribute> fullscreenAttributes,
            final int fullscreenVertexBufferSlot
    ) {
        MTLVertexDescriptor vertexDesc = new MTLVertexDescriptor();
        if (vertexFormats == null) {
            // Fullscreen pass: if the vertex MSL declares [[stage_in]] inputs
            // (reflected as fullscreenAttributes), build a packed descriptor
            // matching them so Metal doesn't reject the pipeline. All
            // attributes share the computed buffer slot (placed after any
            // vertex-stage UBOs to avoid collision), packed contiguously by
            // location.
            if (!fullscreenAttributes.isEmpty()) {
                long stride = 0;
                for (IrisVertexAttribute attr : fullscreenAttributes) {
                    stride += attr.byteSize();
                }
                vertexDesc.setLayout(fullscreenVertexBufferSlot, stride, MTLVertexStepFunction.PerVertex, 1);
                for (IrisVertexAttribute attr : fullscreenAttributes) {
                    vertexDesc.setAttribute(attr.location(), attr.format().value, attr.offset(), fullscreenVertexBufferSlot);
                }
            }
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

    /**
     * Reflects {@code [[stage_in]]} vertex attributes from the compiled vertex
     * MSL. SPIRV-Cross emits a struct (e.g. {@code main0_in}) whose members
     * carry {@code [[attribute(N)]]} decorations, and the vertex function
     * takes it as a {@code [[stage_in]]} parameter.
     *
     * <p>This is needed for fullscreen Iris passes (composite/deferred/final):
     * Iris's {@code patchComposite} injects {@code in vec3 Position;} and
     * {@code in vec2 UV0;} which become {@code [[stage_in]]} inputs in MSL.
     * Metal requires a matching {@link MTLVertexDescriptor} or pipeline
     * creation fails with {@code "Function requires stage_in attributes but
     * no descriptor was set."}
     *
     * <p>The returned list is sorted by attribute location, with packed byte
     * offsets assigned sequentially (all sharing buffer slot 0).
     *
     * @param vertexMsl the compiled vertex MSL source
     * @return non-empty list of reflected attributes, or empty if the vertex
     *         function has no {@code [[stage_in]]} parameter
     */
    private static List<IrisVertexAttribute> reflectStageInAttributes(final String vertexMsl) {
        final Matcher stageInMatcher = STAGE_IN_PARAM_PATTERN.matcher(vertexMsl);
        if (!stageInMatcher.find()) {
            return List.of();
        }
        final String structType = stageInMatcher.group(1);

        final Pattern structPattern = Pattern.compile(
                "struct\\s+" + Pattern.quote(structType) + "\\s*\\{([^}]*)\\}");
        final Matcher structMatcher = structPattern.matcher(vertexMsl);
        if (!structMatcher.find()) {
            return List.of();
        }
        final String body = structMatcher.group(1);

        final List<IrisVertexAttribute> attrs = new ArrayList<>();
        final Matcher attrMatcher = MSL_ATTRIBUTE_PATTERN.matcher(body);
        while (attrMatcher.find()) {
            final String typeName = attrMatcher.group(1);
            final String name = attrMatcher.group(2);
            final int location = Integer.parseInt(attrMatcher.group(3));
            final MTLVertexFormat format = mslTypeToVertexFormat(typeName);
            if (format == MTLVertexFormat.Invalid) {
                LOGGER.warn("[MetalUniversal] Unmapped MSL stage_in type '{}' for attribute '{}' (location {}); skipping", typeName, name, location);
                continue;
            }
            final long byteSize = mslTypeByteSize(typeName);
            attrs.add(new IrisVertexAttribute(name, typeName, location, format, byteSize, 0));
        }

        if (attrs.isEmpty()) {
            return List.of();
        }
        attrs.sort(Comparator.comparingInt(IrisVertexAttribute::location));
        final List<IrisVertexAttribute> result = new ArrayList<>(attrs.size());
        long offset = 0;
        for (final IrisVertexAttribute a : attrs) {
            result.add(new IrisVertexAttribute(a.name(), a.typeName(), a.location(), a.format(), a.byteSize(), offset));
            offset += a.byteSize();
        }
        return List.copyOf(result);
    }

    private static MTLVertexFormat mslTypeToVertexFormat(final String typeName) {
        return switch (typeName) {
            case "float" -> MTLVertexFormat.Float;
            case "float2" -> MTLVertexFormat.Float2;
            case "float3" -> MTLVertexFormat.Float3;
            case "float4" -> MTLVertexFormat.Float4;
            case "half" -> MTLVertexFormat.Half;
            case "half2" -> MTLVertexFormat.Half2;
            case "half3" -> MTLVertexFormat.Half3;
            case "half4" -> MTLVertexFormat.Half4;
            case "int" -> MTLVertexFormat.Int;
            case "int2" -> MTLVertexFormat.Int2;
            case "int3" -> MTLVertexFormat.Int3;
            case "int4" -> MTLVertexFormat.Int4;
            case "uint" -> MTLVertexFormat.UInt;
            case "uint2" -> MTLVertexFormat.UInt2;
            case "uint3" -> MTLVertexFormat.UInt3;
            case "uint4" -> MTLVertexFormat.UInt4;
            default -> MTLVertexFormat.Invalid;
        };
    }

    private static long mslTypeByteSize(final String typeName) {
        final long perComponent = typeName.startsWith("half") ? 2L : 4L;
        final int components = switch (typeName) {
            case "float", "half", "int", "uint" -> 1;
            case "float2", "half2", "int2", "uint2" -> 2;
            case "float3", "half3", "int3", "uint3" -> 3;
            case "float4", "half4", "int4", "uint4" -> 4;
            default -> 0;
        };
        return perComponent * components;
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

    /**
     * Returns the reflected {@code [[stage_in]]} vertex attributes for this
     * pipeline. Non-empty only for fullscreen passes (composite/deferred/final)
     * whose vertex MSL declares {@code [[stage_in]]} inputs. The renderer binds
     * a fullscreen quad vertex buffer matching these attributes.
     */
    List<IrisVertexAttribute> fullscreenAttributes() {
        return this.fullscreenAttributes;
    }

    /**
     * Returns the packed vertex stride (bytes) for the fullscreen quad buffer.
     * Zero when no stage_in attributes were reflected.
     */
    long fullscreenVertexStride() {
        return this.fullscreenVertexStride;
    }

    /**
     * Returns the Metal buffer slot where the renderer must bind the fullscreen
     * quad vertex buffer. Computed as {@code maxVertexStageUboIndex + 1} so it
     * never collides with a vertex-stage UBO {@code [[buffer(N)]]} argument.
     */
    int fullscreenVertexBufferSlot() {
        return this.fullscreenVertexBufferSlot;
    }

    /**
     * Returns {@code true} if this pipeline has reflected {@code [[stage_in]]}
     * vertex attributes (i.e. the renderer must bind a fullscreen quad vertex
     * buffer before drawing).
     */
    boolean hasStageInAttributes() {
        return !this.fullscreenAttributes.isEmpty();
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
