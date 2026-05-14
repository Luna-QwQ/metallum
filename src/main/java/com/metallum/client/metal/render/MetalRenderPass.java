package com.metallum.client.metal.render;

import com.metallum.client.metal.optimization.MetalTerrainFaceCulling;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.PolygonMode;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.SharedConstants;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.util.*;
import java.util.function.Supplier;

@Environment(EnvType.CLIENT)
final class MetalRenderPass implements RenderPassBackend {
    static final boolean VALIDATION = SharedConstants.IS_RUNNING_IN_IDE;
    static final int MAX_VERTEX_BUFFERS = RenderPass.MAX_VERTEX_BUFFERS;
    private final MetalDevice device;
    private final MetalCommandEncoder encoder;
    @Nullable
    private final String label;
    private final GpuTextureView colorTexture;
    @Nullable
    private final GpuTextureView depthTexture;
    private final RenderPass.RenderArea renderArea;
    private final Optional<MetalCommandEncoder.ClearColor> clearColor;
    private final OptionalDouble clearDepth;
    private final ScissorState scissorState = new ScissorState();
    private final GpuBufferSlice[] vertexBuffers = new GpuBufferSlice[MAX_VERTEX_BUFFERS];
    private final HashMap<String, GpuBufferSlice> uniforms = new HashMap<>();
    private final HashMap<String, TextureViewAndSampler> samplers = new HashMap<>();
    private final Set<MetalCompiledRenderPipeline.ResourceBinding> dirtyBindings = new HashSet<>();
    @Nullable
    private RenderPipeline pipeline;
    @Nullable
    private MetalCompiledRenderPipeline compiledPipeline;
    @Nullable
    private GpuBuffer indexBuffer;
    private IndexType indexType = IndexType.SHORT;
    private MemorySegment nativePipeline = MemorySegment.NULL;
    private int pushedDebugGroups = 0;
    private boolean renderEncoderStarted;
    private boolean pipelineDirty = true;
    private boolean depthStateDirty = true;
    private boolean vertexBuffersDirty = true;
    private boolean scissorDirty = true;

    MetalRenderPass(
            final MetalDevice device,
            final MetalCommandEncoder encoder,
            final Supplier<String> label,
            final GpuTextureView colorTexture,
            @Nullable final GpuTextureView depthTexture,
            final RenderPass.RenderArea renderArea,
            final Optional<MetalCommandEncoder.ClearColor> clearColor,
            final OptionalDouble clearDepth
    ) {
        this.device = device;
        this.encoder = encoder;
        this.label = device.useLabels() ? label.get() : null;
        this.colorTexture = colorTexture;
        this.depthTexture = depthTexture;
        this.renderArea = renderArea;
        this.clearColor = clearColor;
        this.clearDepth = clearDepth;
    }

    @Override
    public void pushDebugGroup(final Supplier<String> label) {
        this.pushedDebugGroups++;
        if (this.device.useLabels()) {
            MetalNativeBridge.INSTANCE.MTLCommandBuffer_pushDebugGroup(this.encoder.commandBuffer(), label.get());
        }
    }

    @Override
    public void popDebugGroup() {
        if (this.pushedDebugGroups == 0) {
            throw new IllegalStateException("Can't pop more debug groups than was pushed!");
        }
        this.pushedDebugGroups--;
        if (this.device.useLabels()) {
            MetalNativeBridge.INSTANCE.MTLCommandBuffer_popDebugGroup(this.encoder.commandBuffer());
        }
    }

    @Override
    public void setPipeline(final RenderPipeline pipeline) {
        if (this.pipeline != pipeline) {
            this.dirtyBindings.clear();
            this.pipelineDirty = true;
            this.depthStateDirty = true;
            this.vertexBuffersDirty = true;
        }

        MetalCompiledRenderPipeline compiled = this.device.getOrCompilePipeline(pipeline);
        this.pipeline = pipeline;
        this.compiledPipeline = compiled;
    }

    @Override
    public void bindTexture(final String name, @Nullable final GpuTextureView textureView, @Nullable final GpuSampler sampler) {
        if (textureView != null && sampler != null) {
            this.samplers.put(name, new TextureViewAndSampler(textureView, sampler));
            this.markBindingDirty(name);
        } else if (textureView == null && sampler == null) {
            this.samplers.remove(name);
        } else {
            throw new IllegalArgumentException();
        }
    }

    @Override
    public void setUniform(final String name, final GpuBuffer value) {
        this.setUniform(name, value.slice());
    }

    @Override
    public void setUniform(final String name, final GpuBufferSlice value) {
        GpuBufferSlice oldValue = this.uniforms.put(name, value);
        if (!sameSlice(oldValue, value)) {
            this.markBindingDirty(name);
        }
    }

    @Override
    public void enableScissor(final int x, final int y, final int width, final int height) {
        if (this.scissorState.enabled()
                && this.scissorState.x() == x
                && this.scissorState.y() == y
                && this.scissorState.width() == width
                && this.scissorState.height() == height) {
            return;
        }
        this.scissorState.enable(x, y, width, height);
        this.scissorDirty = true;
    }

    @Override
    public void disableScissor() {
        if (!this.scissorState.enabled()) {
            return;
        }
        this.scissorState.disable();
        this.scissorDirty = true;
    }

    @Override
    public void setVertexBuffer(final int slot, @Nullable final GpuBufferSlice vertexBuffer) {
        if (slot < 0 || slot >= MAX_VERTEX_BUFFERS) {
            throw new IllegalArgumentException("Unsupported Metal vertex buffer slot: " + slot);
        }

        if (!sameNullableSlice(this.vertexBuffers[slot], vertexBuffer)) {
            this.vertexBuffers[slot] = vertexBuffer;
            this.vertexBuffersDirty = true;
        }
    }

    @Override
    public void setIndexBuffer(@Nullable final GpuBuffer indexBuffer, final IndexType indexType) {
        if (this.indexBuffer != indexBuffer || this.indexType != indexType) {
            this.indexBuffer = indexBuffer;
            this.indexType = indexType;
        }
    }

    @Override
    public void drawIndexed(final int indexCount, final int instanceCount, final int firstIndex, final int vertexOffset, final int firstInstance) {
        MetalGpuTexture colorAttachment = MetalCommandEncoder.castTexture(this.colorTexture.texture());
        MetalGpuBuffer nativeIndexBuffer = this.resolveIndexBuffer();
        MemorySegment renderPass = this.renderEncoder();

        this.bindDrawState(renderPass, colorAttachment);
        this.drawIndexedNative(renderPass, nativeIndexBuffer, firstIndex, indexCount, vertexOffset, instanceCount, this.indexType);
    }

    @Override
    public void drawIndexedIndirect(final GpuBufferSlice commands, final int drawCount) {
        throw new UnsupportedOperationException("Metal backend does not support indirect indexed draws yet");
    }

    @Override
    public <T> void drawMultipleIndexed(
            final Collection<RenderPass.Draw<T>> draws,
            @Nullable final GpuBuffer defaultIndexBuffer,
            @Nullable final IndexType defaultIndexType,
            final Collection<String> dynamicUniforms,
            final T uniformArgument
    ) {
        IndexType fallbackIndexType = defaultIndexType == null ? IndexType.SHORT : defaultIndexType;
        MetalGpuTexture colorAttachment = MetalCommandEncoder.castTexture(this.colorTexture.texture());
        MemorySegment renderPass = this.renderEncoder();

        for (RenderPass.Draw<T> draw : draws) {
            IndexType drawIndexType = draw.indexType() == null ? fallbackIndexType : draw.indexType();
            GpuBuffer currentIndexBuffer = draw.indexBuffer() == null ? defaultIndexBuffer : draw.indexBuffer();

            this.setIndexBuffer(currentIndexBuffer, drawIndexType);
            this.setVertexBuffer(draw.slot(), draw.vertexBuffer().slice());

            if (draw.uniformUploaderConsumer() != null) {
                draw.uniformUploaderConsumer().accept(uniformArgument, this::setUniform);
            }

            if (this.needsDrawStateBinding()) {
                this.bindDrawState(renderPass, colorAttachment);
            }
            MetalGpuBuffer nativeIndexBuffer = this.resolveIndexBuffer();
            MetalTerrainFaceCulling.VisibleRanges visibleRanges = MetalTerrainFaceCulling.takeVisibleRanges(draw, currentIndexBuffer);
            if (visibleRanges != null) {
                for (int range = 0; range < visibleRanges.rangeCount(); range++) {
                    int indexCount = visibleRanges.indexCount(range);
                    if (indexCount > 0) {
                        this.drawIndexedNative(renderPass, nativeIndexBuffer, draw.firstIndex() + visibleRanges.firstIndex(range), indexCount, draw.baseVertex(), 1, drawIndexType);
                    }
                }
                continue;
            }
            this.drawIndexedNative(renderPass, nativeIndexBuffer, draw.firstIndex(), draw.indexCount(), draw.baseVertex(), 1, drawIndexType);
        }
    }

    @Override
    public void draw(final int vertexCount, final int instanceCount, final int firstVertex, final int firstInstance) {
        MetalGpuTexture colorAttachment = MetalCommandEncoder.castTexture(this.colorTexture.texture());
        PrimitiveTopology primitiveTopology = this.primitiveTopology();
        long primitiveType = MetalPipelineSupport.primitiveTypeCode(primitiveTopology);
        if (primitiveType < 0L) {
            throw new IllegalStateException("Unsupported primitive type: " + primitiveTopology);
        }

        MemorySegment renderPass = this.renderEncoder();

        this.bindDrawState(renderPass, colorAttachment);

        if (primitiveType == MetalPipelineSupport.TRIANGLE_FAN_PRIMITIVE) {
            try (MetalGpuBuffer fanIndexBuffer = this.newTriangleFanBuffer(vertexCount)) {
                MetalNativeBridge.INSTANCE.MTLRenderCommandEncoder_drawPrimitivesTriangleFan(
                        renderPass,
                        fanIndexBuffer.nativeHandle(),
                        firstVertex,
                        vertexCount,
                        Math.max(1, instanceCount)
                );
            }
        } else {
            MetalNativeBridge.INSTANCE.MTLRenderCommandEncoder_drawPrimitives(renderPass, primitiveType, firstVertex, vertexCount, Math.max(1, instanceCount));
        }
    }

    @Override
    public void drawIndirect(final GpuBufferSlice commands, final int drawCount) {
        throw new UnsupportedOperationException("Metal backend does not support indirect draws yet");
    }

    @Override
    public void writeTimestamp(final GpuQueryPool pool, final int index) {
        if (pool instanceof MetalGpuQueryPool metalPool && index >= 0 && index < pool.size()) {
            metalPool.setValue(index, this.device.getTimestampNow());
        }
    }

    long colorAttachmentFormat() {
        return ((MetalGpuTexture) this.colorTexture.texture()).mtlPixelFormat();
    }

    long depthAttachmentFormat() {
        if (this.depthTexture == null) {
            return 0L;
        }
        return ((MetalGpuTexture) this.depthTexture.texture()).mtlPixelFormat();
    }

    long stencilAttachmentFormat() {
        if (this.depthTexture == null) {
            return 0L;
        }
        return ((MetalGpuTexture) this.depthTexture.texture()).mtlStencilPixelFormat();
    }

    private MemorySegment renderEncoder() {
        if (!this.renderEncoderStarted) {
            for (TextureViewAndSampler textureBinding : this.samplers.values()) {
                this.encoder.flushPendingTextureViewClear(textureBinding.textureView());
            }
        }

        MetalGpuTextureView colorTextureView = (MetalGpuTextureView) this.colorTexture;
        MetalGpuTextureView depthTextureView = this.depthTexture == null ? null : (MetalGpuTextureView) this.depthTexture;
        Optional<MetalCommandEncoder.ClearColor> loadColor = this.renderEncoderStarted ? Optional.empty() : this.clearColor;
        OptionalDouble loadDepth = this.renderEncoderStarted ? OptionalDouble.empty() : this.clearDepth;
        MemorySegment handle = this.encoder.renderCommandEncoder(
                colorTextureView,
                depthTextureView,
                this.colorTexture.getWidth(0),
                this.colorTexture.getHeight(0),
                loadColor,
                loadDepth
        );
        if (MetalProbe.isNullHandle(handle)) {
            throw new IllegalStateException("Native render pass is unavailable");
        }

        this.renderEncoderStarted = true;
        return handle;
    }

    void end() {
        if (!this.renderEncoderStarted
                && (this.clearColor.isPresent() || this.clearDepth.isPresent())
                && !this.encoder.deferRenderPassClear(this.colorTexture, this.clearColor, this.depthTexture, this.clearDepth)) {
            this.renderEncoder();
        }

        this.renderEncoderStarted = false;
        this.nativePipeline = MemorySegment.NULL;
    }

    private void pushVertexBuffers(final MemorySegment renderPass) {
        if (this.compiledPipeline == null) {
            throw new IllegalStateException("Pipeline is missing");
        }

        for (int slot = 0; slot < MAX_VERTEX_BUFFERS; slot++) {
            int metalSlot = this.compiledPipeline.metalSlotForVertexBinding(slot);
            if (metalSlot < 0) {
                continue;
            }

            GpuBufferSlice vertexBuffer = this.vertexBuffers[slot];
            if (vertexBuffer == null) {
                throw new IllegalStateException("Missing vertex buffer at slot " + slot);
            }
            if (VALIDATION && vertexBuffer.buffer().isClosed()) {
                throw new IllegalStateException("Vertex buffer at slot " + slot + " has been closed");
            }

            MetalGpuBuffer nativeVertexBuffer = MetalCommandEncoder.castBuffer(vertexBuffer.buffer());
            MetalNativeBridge.INSTANCE.MTLRenderCommandEncoder_setVertexBuffer(
                    renderPass,
                    nativeVertexBuffer.nativeHandle(),
                    vertexBuffer.offset(),
                    metalSlot
            );
        }
    }

    private MetalGpuBuffer resolveIndexBuffer() {
        if (this.indexBuffer == null) {
            throw new IllegalStateException("Missing index buffer");
        }
        if (VALIDATION && this.indexBuffer.isClosed()) {
            throw new IllegalStateException("Index buffer has been closed");
        }
        return MetalCommandEncoder.castBuffer(this.indexBuffer);
    }

    private void drawIndexedNative(
            final MemorySegment renderPass,
            final MetalGpuBuffer nativeIndexBuffer,
            final int firstIndex,
            final int indexCount,
            final int baseVertex,
            final int instanceCount,
            final IndexType indexType
    ) {
        PrimitiveTopology primitiveTopology = this.primitiveTopology();
        long primitiveType = MetalPipelineSupport.primitiveTypeCode(primitiveTopology);
        if (primitiveType < 0L) {
            throw new IllegalStateException("Unsupported primitive type: " + primitiveTopology);
        }

        int safeInstanceCount = Math.max(1, instanceCount);
        long indexOffsetBytes = (long) firstIndex * indexType.bytes;
        long nativeIndexType = indexType == IndexType.INT ? 1L : 0L;
        if (primitiveType == MetalPipelineSupport.TRIANGLE_FAN_PRIMITIVE)
            try (MetalGpuBuffer fanIndexBuffer = this.newTriangleFanBuffer(indexCount)) {
                MetalNativeBridge.INSTANCE.MTLRenderCommandEncoder_drawIndexedPrimitivesTriangleFan(
                        renderPass,
                        nativeIndexBuffer.nativeHandle(),
                        fanIndexBuffer.nativeHandle(),
                        nativeIndexType,
                        indexOffsetBytes,
                        indexCount,
                        baseVertex,
                        instanceCount
                );
            }
        else {
            MetalNativeBridge.INSTANCE.MTLRenderCommandEncoder_drawIndexedPrimitives(
                    renderPass,
                    primitiveType,
                    indexCount,
                    nativeIndexType,
                    nativeIndexBuffer.nativeHandle(),
                    indexOffsetBytes,
                    safeInstanceCount,
                    baseVertex
            );
        }
    }

    private MetalGpuBuffer newTriangleFanBuffer(final int sourceCount) {
        long byteSize = Math.multiplyExact(Math.multiplyExact((long) sourceCount - 2L, 3L), Integer.BYTES);

        return new MetalGpuBuffer(
                this.device,
                GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_INDEX,
                byteSize
        );
    }

    private boolean needsDrawStateBinding() {
        return this.pipelineDirty
                || this.depthStateDirty
                || this.vertexBuffersDirty
                || this.scissorDirty
                || !this.dirtyBindings.isEmpty()
                || MetalProbe.isNullHandle(this.nativePipeline);
    }

    private void bindDrawState(
            final MemorySegment renderPass,
            final MetalGpuTexture colorAttachment
    ) {
        if (this.compiledPipeline == null) {
            throw new IllegalStateException("Pipeline is missing");
        }

        MemorySegment pipelineHandle = this.nativePipeline;
        boolean reboundPipeline = this.pipelineDirty;
        if (reboundPipeline || MetalProbe.isNullHandle(pipelineHandle)) {
            pipelineHandle = this.compiledPipeline.getOrCreateNativePipeline(
                    this.device,
                    this.colorAttachmentFormat(),
                    this.depthAttachmentFormat(),
                    this.stencilAttachmentFormat()
            );
            if (MetalProbe.isNullHandle(pipelineHandle)) {
                throw new IllegalStateException("Native pipeline is unavailable");
            }
            reboundPipeline = this.pipelineDirty || !MetalPipelineSupport.sameHandle(this.nativePipeline, pipelineHandle);
        }

        if (reboundPipeline) {
            MetalNativeBridge.INSTANCE.MTLRenderCommandEncoder_setRenderPipelineState(renderPass, pipelineHandle);
            this.nativePipeline = pipelineHandle;
            this.pipelineDirty = false;
        }

        if (reboundPipeline || this.depthStateDirty) {
            if (this.depthAttachmentFormat() != 0L) {
                MemorySegment depthState = MetalNativeBridge.INSTANCE.MTLDevice_makeDepthStencilState(
                        this.device.metalDeviceHandle(),
                        this.compiledPipeline.depthCompareOp(),
                        this.compiledPipeline.depthWrite()
                );
                if (MetalProbe.isNullHandle(depthState)) {
                    throw new IllegalStateException("Native depth state is unavailable");
                }
                MetalNativeBridge.INSTANCE.MTLRenderCommandEncoder_setDepthStencilState(renderPass, depthState);
                MetalNativeBridge.INSTANCE.MTLRenderCommandEncoder_setDepthBias(
                        renderPass,
                        this.compiledPipeline.depthBiasConstant(),
                        this.compiledPipeline.depthBiasScaleFactor(),
                        0.0
                );
            }
            this.depthStateDirty = false;
        }

        if (reboundPipeline) {
            RenderPipeline pipelineInfo = this.compiledPipeline.info();
            MetalNativeBridge.INSTANCE.MTLRenderCommandEncoder_setFrontFacingWinding(renderPass, 1);
            MetalNativeBridge.INSTANCE.MTLRenderCommandEncoder_setCullMode(renderPass, pipelineInfo.isCull() ? 2L : 0L);
            MetalNativeBridge.INSTANCE.MTLRenderCommandEncoder_setTriangleFillMode(
                    renderPass,
                    pipelineInfo.getPolygonMode() == PolygonMode.WIREFRAME ? 1 : 0
            );
        }

        if (reboundPipeline || this.scissorDirty) {
            EffectiveScissor effectiveScissor = this.effectiveScissor();
            MetalNativeBridge.INSTANCE.MTLRenderCommandEncoder_setScissorRect(
                    renderPass,
                    effectiveScissor.enabled() ? effectiveScissor.x() : 0L,
                    effectiveScissor.enabled() ? effectiveScissor.y() : 0L,
                    effectiveScissor.enabled() ? effectiveScissor.width() : this.colorTexture.getWidth(0),
                    effectiveScissor.enabled() ? effectiveScissor.height() : this.colorTexture.getHeight(0)
            );
            this.scissorDirty = false;
        }

        if (this.vertexBuffersDirty) {
            this.pushVertexBuffers(renderPass);
            this.vertexBuffersDirty = false;
        }

        this.pushDescriptors(renderPass, colorAttachment, reboundPipeline);
    }

    private PrimitiveTopology primitiveTopology() {
        if (this.pipeline == null) {
            throw new IllegalStateException("Pipeline is missing");
        }
        return this.pipeline.getPrimitiveTopology();
    }

    private void pushDescriptors(final MemorySegment renderPass, final MetalGpuTexture colorAttachment, final boolean bindAll) {
        if (this.compiledPipeline == null) {
            throw new IllegalStateException("Pipeline is missing");
        }

        if (!bindAll) {
            if (this.dirtyBindings.isEmpty()) {
                return;
            }

            for (MetalCompiledRenderPipeline.ResourceBinding binding : this.dirtyBindings) {
                this.pushDescriptor(renderPass, colorAttachment, binding);
            }
            this.dirtyBindings.clear();
            return;
        }

        for (MetalCompiledRenderPipeline.ResourceBinding binding : this.compiledPipeline.resources()) {
            this.pushDescriptor(renderPass, colorAttachment, binding);
        }
        this.dirtyBindings.clear();
    }

    private EffectiveScissor effectiveScissor() {
        int areaLeft = this.renderArea.x();
        int areaTop = this.renderArea.y();
        int areaRight = areaLeft + this.renderArea.width();
        int areaBottom = areaTop + this.renderArea.height();
        if (!this.scissorState.enabled()) {
            return this.renderArea.fillsTexture(this.colorTexture)
                    ? EffectiveScissor.disabled()
                    : new EffectiveScissor(true, areaLeft, areaTop, this.renderArea.width(), this.renderArea.height());
        }

        int left = Math.max(areaLeft, this.scissorState.x());
        int top = Math.max(areaTop, this.scissorState.y());
        int right = Math.min(areaRight, this.scissorState.x() + this.scissorState.width());
        int bottom = Math.min(areaBottom, this.scissorState.y() + this.scissorState.height());
        if (right <= left || bottom <= top) {
            return new EffectiveScissor(true, 0, 0, 0, 0);
        }
        return new EffectiveScissor(true, left, top, right - left, bottom - top);
    }

    private record EffectiveScissor(boolean enabled, int x, int y, int width, int height) {
        static EffectiveScissor disabled() {
            return new EffectiveScissor(false, 0, 0, 0, 0);
        }
    }

    private void markBindingDirty(final String name) {
        if (this.compiledPipeline == null) {
            return;
        }

        MetalCompiledRenderPipeline.ResourceBinding binding = this.compiledPipeline.resource(name);
        if (binding != null) {
            this.dirtyBindings.add(binding);
        }
    }

    private void pushDescriptor(
            final MemorySegment renderPass,
            final MetalGpuTexture colorAttachment,
            final MetalCompiledRenderPipeline.ResourceBinding binding
    ) {
        if (binding.kind() == MetalCompiledRenderPipeline.ResourceKind.SAMPLED_IMAGE) {
            TextureViewAndSampler textureBinding = this.samplers.get(binding.name());
            if (textureBinding == null) {
                throw new IllegalStateException("Missing sampler " + binding.name());
            }

            if (VALIDATION && textureBinding.textureView().isClosed()) {
                throw new IllegalStateException("Sampler " + binding.name() + " texture view has been closed");
            }

            MetalGpuTexture texture = MetalCommandEncoder.castTexture(textureBinding.textureView().texture());
            MetalGpuTextureView textureView = (MetalGpuTextureView) textureBinding.textureView();
            if (texture == colorAttachment) {
                throw new IllegalStateException("Feedback sampler is not allowed for binding " + binding.name());
            }

            MetalGpuSampler sampler = (MetalGpuSampler) textureBinding.sampler();
            if ((binding.stageMask() & 1) != 0) {
                MetalNativeBridge.INSTANCE.MTLRenderCommandEncoder_setVertexTexture(renderPass, textureView.nativeHandle(), binding.bindingIndex());
                MetalNativeBridge.INSTANCE.MTLRenderCommandEncoder_setVertexSamplerState(renderPass, sampler.nativeHandle(), binding.bindingIndex());

            }
            if ((binding.stageMask() & 2) != 0) {
                MetalNativeBridge.INSTANCE.MTLRenderCommandEncoder_setFragmentTexture(renderPass, textureView.nativeHandle(), binding.bindingIndex());
                MetalNativeBridge.INSTANCE.MTLRenderCommandEncoder_setFragmentSamplerState(renderPass, sampler.nativeHandle(), binding.bindingIndex());

            }

            return;
        }

        if (binding.kind() == MetalCompiledRenderPipeline.ResourceKind.TEXEL_BUFFER) {
            this.pushTexelBufferDescriptor(renderPass, binding);
            return;
        }

        GpuBufferSlice uniformSlice = this.uniforms.get(binding.name());
        if (uniformSlice == null) {
            throw new IllegalStateException("Missing uniform " + binding.name());
        }
        if (VALIDATION && uniformSlice.buffer().isClosed()) {
            throw new IllegalStateException("Uniform " + binding.name() + " buffer has been closed");
        }

        MetalGpuBuffer uniformBuffer = MetalCommandEncoder.castBuffer(uniformSlice.buffer());
        if ((binding.stageMask() & 1) != 0) {
            MetalNativeBridge.INSTANCE.MTLRenderCommandEncoder_setVertexBuffer(renderPass, uniformBuffer.nativeHandle(), uniformSlice.offset(), binding.bindingIndex());
        }
        if ((binding.stageMask() & 2) != 0) {
            MetalNativeBridge.INSTANCE.MTLRenderCommandEncoder_setFragmentBuffer(renderPass, uniformBuffer.nativeHandle(), uniformSlice.offset(), binding.bindingIndex());
        }
    }

    private void pushTexelBufferDescriptor(final MemorySegment renderPass, final MetalCompiledRenderPipeline.ResourceBinding binding) {
        GpuBufferSlice texelSlice = this.uniforms.get(binding.name());
        if (texelSlice == null) {
            throw new IllegalStateException("Missing texel buffer " + binding.name());
        }
        if (VALIDATION && texelSlice.buffer().isClosed()) {
            throw new IllegalStateException("Texel buffer " + binding.name() + " has been closed");
        }

        GpuFormat texelFormat = binding.texelBufferFormat();
        if (texelFormat == null) {
            throw new IllegalStateException("Texel buffer " + binding.name() + " is missing a format");
        }

        MetalGpuBuffer texelBuffer = MetalCommandEncoder.castBuffer(texelSlice.buffer());
        long pixelFormat = MetalPipelineSupport.texelBufferPixelFormatCode(texelFormat);
        int pixelSize = texelFormat.pixelSize();
        long width = 4096L;
        long bytesPerRow = width * pixelSize;
        long height = Math.max(1L, (texelSlice.length() + bytesPerRow - 1L) / bytesPerRow);
        MemorySegment texelTexture = MetalNativeBridge.INSTANCE.metallum_create_buffer_texture_view(
                texelBuffer.nativeHandle(),
                pixelFormat,
                texelSlice.offset(),
                width,
                height,
                bytesPerRow
        );
        if (MetalProbe.isNullHandle(texelTexture)) {
            throw new IllegalStateException("Failed to create Metal texel buffer texture for " + binding.name());
        }

        if ((binding.stageMask() & 1) != 0) {
            MetalNativeBridge.INSTANCE.MTLRenderCommandEncoder_setVertexTexture(renderPass, texelTexture, binding.bindingIndex());
        }
        if ((binding.stageMask() & 2) != 0) {
            MetalNativeBridge.INSTANCE.MTLRenderCommandEncoder_setFragmentTexture(renderPass, texelTexture, binding.bindingIndex());
        }

        this.encoder.queueForDestroy(() -> MetalNativeBridge.INSTANCE.metallum_release_object(texelTexture));
    }

    record TextureViewAndSampler(GpuTextureView textureView, GpuSampler sampler) {
    }

    private static boolean sameNullableSlice(@Nullable final GpuBufferSlice left, @Nullable final GpuBufferSlice right) {
        if (left == null || right == null) {
            return left == right;
        }
        return sameSlice(left, right);
    }

    private static boolean sameSlice(@Nullable final GpuBufferSlice left, final GpuBufferSlice right) {
        return left != null
                && left.buffer() == right.buffer()
                && left.offset() == right.offset()
                && left.length() == right.length();
    }
}
