package com.metallum.client.metal.render;

import com.metallum.client.metal.optimization.MetalTerrainVertexPacking;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLVertexFormat;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Environment(EnvType.CLIENT)
final class MetalCompiledRenderPipeline implements CompiledRenderPipeline, AutoCloseable {
    private static final int MAX_METAL_VERTEX_BUFFER_SLOT = 30;
    private static final int MAIN_VERTEX_BINDING_INDEX = 0;

    enum ResourceKind {
        UNIFORM_BUFFER,
        SAMPLED_IMAGE,
        TEXEL_BUFFER
    }

    static final int STAGE_VERTEX = 1;
    static final int STAGE_FRAGMENT = 2;
    static final int STAGE_ALL = STAGE_VERTEX | STAGE_FRAGMENT;

    record ResourceBinding(ResourceKind kind, String name, int bindingIndex, int stageMask,
                           @Nullable GpuFormat texelBufferFormat) {
    }

    private final RenderPipeline info;
    private final String vertexMsl;
    private final String fragmentMsl;
    private final String vertexEntryPoint;
    private final String fragmentEntryPoint;
    private final List<ResourceBinding> resources;
    private final Map<String, ResourceBinding> resourcesByName;
    private final MetalVertexDescriptor vertexDescriptor;
    private final int[] metalSlotByVertexBinding;
    private final long depthCompareOp;
    private final int depthWrite;
    private final float depthBiasScaleFactor;
    private final float depthBiasConstant;
    private final Map<PipelineVariantKey, MemorySegment> nativePipelines = new ConcurrentHashMap<>();
    private MemorySegment depthStencilState = MemorySegment.NULL;

    MetalCompiledRenderPipeline(
            final RenderPipeline info,
            final String vertexMsl,
            final String fragmentMsl,
            final String vertexEntryPoint,
            final String fragmentEntryPoint,
            final List<ResourceBinding> resources
    ) {
        this.info = info;
        this.vertexMsl = vertexMsl;
        this.fragmentMsl = fragmentMsl;
        this.vertexEntryPoint = vertexEntryPoint;
        this.fragmentEntryPoint = fragmentEntryPoint;
        this.resources = resources;
        this.resourcesByName = resources.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(ResourceBinding::name, binding -> binding));

        int[] metalSlotByVertexBinding = new int[RenderPass.MAX_VERTEX_BUFFERS];
        Arrays.fill(metalSlotByVertexBinding, -1);
        this.vertexDescriptor = buildVertexDescriptor(info, firstAvailableVertexBufferSlot(resources), metalSlotByVertexBinding);
        this.metalSlotByVertexBinding = metalSlotByVertexBinding;

        var depthStencilState = info.getDepthStencilState();
        if (depthStencilState == null) {
            this.depthCompareOp = 1L;
            this.depthWrite = 0;
            this.depthBiasScaleFactor = 0.0f;
            this.depthBiasConstant = 0.0f;
        } else {
            this.depthCompareOp = MetalPipelineSupport.toCompareOpCode(depthStencilState.depthTest());
            this.depthWrite = depthStencilState.writeDepth() ? 1 : 0;
            this.depthBiasScaleFactor = depthStencilState.depthBiasScaleFactor();
            this.depthBiasConstant = depthStencilState.depthBiasConstant();
        }
    }

    @Override
    public boolean isValid() {
        return true;
    }

    RenderPipeline info() {
        return this.info;
    }

    List<ResourceBinding> resources() {
        return this.resources;
    }

    @Nullable
    ResourceBinding resource(final String name) {
        return this.resourcesByName.get(name);
    }

    int metalSlotForVertexBinding(final int binding) {
        return binding >= 0 && binding < this.metalSlotByVertexBinding.length ? this.metalSlotByVertexBinding[binding] : -1;
    }

    float depthBiasScaleFactor() {
        return this.depthBiasScaleFactor;
    }

    float depthBiasConstant() {
        return this.depthBiasConstant;
    }

    @Nullable
    MemorySegment getOrCreateDepthStencilState(final MetalDevice device) {
        if (!MetalNativeBridge.isNullHandle(this.depthStencilState)) {
            return this.depthStencilState;
        }
        MemorySegment created = MetalNativeBridge.INSTANCE.MTLDevice_makeDepthStencilState(
                device.metalDeviceHandle(),
                this.depthCompareOp,
                this.depthWrite
        );
        if (MetalNativeBridge.isNullHandle(created)) {
            return null;
        }
        this.depthStencilState = created;
        return created;
    }

    private static MetalVertexDescriptor buildVertexDescriptor(
            final RenderPipeline pipeline,
            final int firstMetalVertexBufferSlot,
            final int[] metalSlotByVertexBinding
    ) {
        VertexFormat[] bindings = pipeline.getVertexFormatBindings();
        int nextMetalSlot = firstMetalVertexBufferSlot;
        boolean packedTerrain = MetalTerrainVertexPacking.isPackedTerrainPipeline(pipeline.getLocation().toString());

        MetalVertexDescriptor vertexDesc = new MetalVertexDescriptor();
        long attrIndex = 0;

        for (int i = 0; i < bindings.length; i++) {
            VertexFormat binding = bindings[i];
            if (binding == null || binding.getElements().isEmpty()) {
                continue;
            }

            if (nextMetalSlot > MAX_METAL_VERTEX_BUFFER_SLOT) {
                throw new UnsupportedOperationException("Metal vertex/input buffer slots exceeded for " + pipeline.getLocation());
            }

            int metalSlot = nextMetalSlot++;
            metalSlotByVertexBinding[i] = metalSlot;

            long stride = packedTerrain && i == MAIN_VERTEX_BINDING_INDEX ? MetalTerrainVertexPacking.PACKED_TERRAIN_VERTEX_SIZE : binding.getVertexSize();
            long stepRate = binding.getStepRate();
            long stepFunction = stepRate > 0 ? 1 : 0; // 0 = perVertex, 1 = perInstance
            vertexDesc.setLayout(metalSlot, stride, stepFunction, stepRate > 0 ? stepRate : 1);

            if (packedTerrain && i == MAIN_VERTEX_BINDING_INDEX) {
                long[] packedFormats = MetalTerrainVertexPacking.packedAttributeFormats();
                long[] packedOffsets = MetalTerrainVertexPacking.packedAttributeOffsets();
                for (int k = 0; k < packedFormats.length; k++) {
                    vertexDesc.setAttribute(attrIndex, packedFormats[k], packedOffsets[k], metalSlot);
                    attrIndex++;
                }
            } else {
                for (VertexFormatElement element : binding.getElements()) {
                    MTLVertexFormat format = MetalPipelineSupport.vertexAttributeFormat(element.format());
                    if (format.value == MTLVertexFormat.Invalid.value) {
                        throw new IllegalStateException("Unsupported vertex attribute format: " + element.format());
                    }
                    vertexDesc.setAttribute(attrIndex, format.value, element.offset(), metalSlot);
                    attrIndex++;
                }
            }
        }

        return vertexDesc;
    }

    private static int firstAvailableVertexBufferSlot(final List<ResourceBinding> resources) {
        int maxVertexBufferBinding = -1;
        for (ResourceBinding resource : resources) {
            if (resource.kind() == ResourceKind.UNIFORM_BUFFER && (resource.stageMask() & STAGE_VERTEX) != 0) {
                maxVertexBufferBinding = Math.max(maxVertexBufferBinding, resource.bindingIndex());
            }
        }
        return maxVertexBufferBinding + 1;
    }

    @Nullable
    MemorySegment getOrCreateNativePipeline(final MetalDevice device, final long colorFormat, final long depthFormat, final long stencilFormat) {
        PipelineVariantKey key = new PipelineVariantKey(colorFormat, depthFormat, stencilFormat);
        MemorySegment cached = this.nativePipelines.get(key);
        if (cached != null) {
            return cached;
        }

        var colorTarget = this.info.getColorTargetState();
        var blendFunction = colorTarget.blendFunction();

        try (MetalRenderPipelineDescriptor pipelineDesc = new MetalRenderPipelineDescriptor()) {
            boolean success = pipelineDesc.setFunctions(
                    device,
                    this.vertexMsl,
                    this.fragmentMsl,
                    this.vertexEntryPoint,
                    this.fragmentEntryPoint
            );
            if (!success) {
                return null;
            }

            pipelineDesc.setVertexDescriptor(this.vertexDescriptor);
            pipelineDesc.setAttachmentFormats(colorFormat, depthFormat, stencilFormat);

            if (blendFunction.isPresent()) {
                var function = blendFunction.get();
                pipelineDesc.setBlendState(
                        1,
                        MetalPipelineSupport.toBlendFactorCode(function.color().sourceFactor()).value,
                        MetalPipelineSupport.toBlendFactorCode(function.color().destFactor()).value,
                        MetalPipelineSupport.toBlendOpCode(function.color().op()),
                        MetalPipelineSupport.toBlendFactorCode(function.alpha().sourceFactor()).value,
                        MetalPipelineSupport.toBlendFactorCode(function.alpha().destFactor()).value,
                        MetalPipelineSupport.toBlendOpCode(function.alpha().op()),
                        colorTarget.writeMask()
                );
            } else {
                pipelineDesc.setBlendState(0, 0, 0, 0, 0, 0, 0, colorTarget.writeMask());
            }

            MemorySegment created = MetalNativeBridge.INSTANCE.metallum_MTLDevice_makeRenderPipelineState(
                    device.metalDeviceHandle(),
                    pipelineDesc.handle()
            );

            if (MetalNativeBridge.isNullHandle(created)) {
                return null;
            }

            this.nativePipelines.put(key, created);
            return created;
        }
    }

    @Override
    public void close() {
        if (this.vertexDescriptor != null) {
            this.vertexDescriptor.close();
        }
        for (MemorySegment nativePipeline : this.nativePipelines.values()) {
            if (!MetalNativeBridge.isNullHandle(nativePipeline)) {
                MetalNativeBridge.INSTANCE.metallum_release_object(nativePipeline);
            }
        }
        this.nativePipelines.clear();
        this.depthStencilState = MemorySegment.NULL;
    }

    private record PipelineVariantKey(long colorFormat, long depthFormat,
                                      long stencilFormat) {
    }
}
