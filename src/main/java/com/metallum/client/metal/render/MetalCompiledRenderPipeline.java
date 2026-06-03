package com.metallum.client.metal.render;

import com.metallum.client.metal.optimization.MetalTerrainVertexPacking;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.*;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.PolygonMode;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.Map;

@Environment(EnvType.CLIENT)
final class MetalCompiledRenderPipeline implements CompiledRenderPipeline, AutoCloseable {
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

    private final List<ResourceBinding> resources;
    private final Map<String, ResourceBinding> resourcesByName;
    private final int firstAvailableVertexBufferSlot;
    private final MTLCullMode cullMode;
    private final MTLTriangleFillMode fillMode;
    private final float depthBiasScaleFactor;
    private final float depthBiasConstant;
    private final MTLPrimitiveType topology;
    private final int vertexBufferCount;

    private final MemorySegment depthStencilState;
    private final MemorySegment withDepthPipeline;
    private final MemorySegment withoutDepthPipeline;

    MetalCompiledRenderPipeline(
            final MetalDevice device,
            final RenderPipeline info,
            final String vertexMsl,
            final String fragmentMsl,
            final String vertexEntryPoint,
            final String fragmentEntryPoint,
            final List<ResourceBinding> resources
    ) {
        this.resources = resources;
        this.resourcesByName = resources.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(ResourceBinding::name, binding -> binding));

        this.firstAvailableVertexBufferSlot = firstAvailableVertexBufferSlot(resources);
        this.cullMode = info.isCull() ? MTLCullMode.Back : MTLCullMode.None;
        this.fillMode = info.getPolygonMode() == PolygonMode.WIREFRAME ? MTLTriangleFillMode.Lines : MTLTriangleFillMode.Fill;
        this.topology = MTLPrimitiveType.from(info.getPrimitiveTopology());
        this.vertexBufferCount = info.getVertexFormatBindings().length;

        long depthCompareOp;
        int depthWrite;
        var depthStencilState = info.getDepthStencilState();
        if (depthStencilState == null) {
            depthCompareOp = 1L;
            depthWrite = 0;
            this.depthBiasScaleFactor = 0.0f;
            this.depthBiasConstant = 0.0f;
        } else {
            depthCompareOp = MetalPipelineSupport.toCompareOpCode(depthStencilState.depthTest());
            depthWrite = depthStencilState.writeDepth() ? 1 : 0;
            this.depthBiasScaleFactor = depthStencilState.depthBiasScaleFactor();
            this.depthBiasConstant = depthStencilState.depthBiasConstant();
        }

        this.depthStencilState = MetalNativeBridge.INSTANCE.MTLDevice_makeDepthStencilState(
                device.metalDeviceHandle(),
                depthCompareOp,
                depthWrite
        );

        var colorTarget = info.getColorTargetState();
        long colorFormat = colorTarget != null ? MTLPixelFormat.from(colorTarget.format()).value : MTLPixelFormat.BGRA8Unorm.value;

        try (MTLVertexDescriptor vertexDescriptor = buildVertexDescriptor(info, this.firstAvailableVertexBufferSlot)) {
            this.withoutDepthPipeline = createPipeline(device, info, vertexMsl, fragmentMsl, vertexEntryPoint, fragmentEntryPoint, vertexDescriptor, colorFormat, MTLPixelFormat.Invalid.value);
            if (info.getDepthStencilState() != null) {
                this.withDepthPipeline = createPipeline(device, info, vertexMsl, fragmentMsl, vertexEntryPoint, fragmentEntryPoint, vertexDescriptor, colorFormat, MTLPixelFormat.Depth32Float.value);
            } else {
                this.withDepthPipeline = MemorySegment.NULL;
            }
        }
    }

    private static MemorySegment createPipeline(
            final MetalDevice device,
            final RenderPipeline info,
            final String vertexMsl,
            final String fragmentMsl,
            final String vertexEntryPoint,
            final String fragmentEntryPoint,
            final MTLVertexDescriptor vertexDescriptor,
            final long colorFormat,
            final long depthFormat
    ) {
        var colorTarget = info.getColorTargetState();
        var blendFunction = colorTarget.blendFunction();

        try (MTLRenderPipelineDescriptor pipelineDesc = new MTLRenderPipelineDescriptor()) {
            boolean success = pipelineDesc.setFunctions(
                    device.metalDeviceHandle(),
                    vertexMsl,
                    fragmentMsl,
                    vertexEntryPoint,
                    fragmentEntryPoint
            );
            if (!success) {
                return MemorySegment.NULL;
            }

            pipelineDesc.setVertexDescriptor(vertexDescriptor);
            pipelineDesc.setAttachmentFormats(colorFormat, depthFormat, MTLPixelFormat.Invalid.value);

            if (blendFunction.isPresent()) {
                var function = blendFunction.get();
                pipelineDesc.setBlendState(
                        MTLBlendFactor.from(function.color().sourceFactor()),
                        MTLBlendFactor.from(function.color().destFactor()),
                        MTLBlendOperation.from(function.color().op()),
                        MTLBlendFactor.from(function.alpha().sourceFactor()),
                        MTLBlendFactor.from(function.alpha().destFactor()),
                        MTLBlendOperation.from(function.alpha().op()),
                        colorTarget.writeMask()
                );
            } else {
                pipelineDesc.disableBlending(colorTarget.writeMask());
            }

            return MetalNativeBridge.INSTANCE.metallum_MTLDevice_makeRenderPipelineState(
                    device.metalDeviceHandle(),
                    pipelineDesc.handle()
            );
        }
    }

    @Override
    public boolean isValid() {
        return !MetalNativeBridge.isNullHandle(this.withoutDepthPipeline);
    }

    List<ResourceBinding> resources() {
        return this.resources;
    }

    @Nullable
    ResourceBinding resource(final String name) {
        return this.resourcesByName.get(name);
    }

    int firstAvailableVertexBufferSlot() {
        return this.firstAvailableVertexBufferSlot;
    }

    float depthBiasScaleFactor() {
        return this.depthBiasScaleFactor;
    }

    float depthBiasConstant() {
        return this.depthBiasConstant;
    }

    MemorySegment getDepthStencilState() {
        return this.depthStencilState;
    }

    MemorySegment getNativePipeline(final boolean useDepth) {
        return useDepth && !MetalNativeBridge.isNullHandle(this.withDepthPipeline) ? this.withDepthPipeline : this.withoutDepthPipeline;
    }

    MTLCullMode cullMode() {
        return this.cullMode;
    }

    MTLTriangleFillMode fillMode() {
        return this.fillMode;
    }

    MTLPrimitiveType topology() {
        return this.topology;
    }

    int vertexBufferCount() {
        return this.vertexBufferCount;
    }

    private static MTLVertexDescriptor buildVertexDescriptor(
            final RenderPipeline pipeline,
            final int firstMetalVertexBufferSlot
    ) {
        VertexFormat[] bindings = pipeline.getVertexFormatBindings();
        boolean packedTerrain = MetalTerrainVertexPacking.isPackedTerrainPipeline(pipeline.getLocation().toString());

        MTLVertexDescriptor vertexDesc = new MTLVertexDescriptor();
        long attrIndex = 0;

        for (int i = 0; i < bindings.length; i++) {
            VertexFormat binding = bindings[i];
            if (binding == null || binding.getElements().isEmpty()) {
                continue;
            }

            int metalSlot = firstMetalVertexBufferSlot + i;

            long stride = packedTerrain && i == MAIN_VERTEX_BINDING_INDEX ? MetalTerrainVertexPacking.PACKED_TERRAIN_VERTEX_SIZE : binding.getVertexSize();
            long stepRate = binding.getStepRate();
            MTLVertexStepFunction stepFunction = stepRate > 0 ? MTLVertexStepFunction.PerInstance : MTLVertexStepFunction.PerVertex;
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
                    MTLVertexFormat format = MTLVertexFormat.from(element.format());
                    if (format == MTLVertexFormat.Invalid) {
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

    @Override
    public void close() {
        if (!MetalNativeBridge.isNullHandle(this.withDepthPipeline)) {
            MetalNativeBridge.INSTANCE.metallum_release_object(this.withDepthPipeline);
        }
        if (!MetalNativeBridge.isNullHandle(this.withoutDepthPipeline)) {
            MetalNativeBridge.INSTANCE.metallum_release_object(this.withoutDepthPipeline);
        }
    }
}
