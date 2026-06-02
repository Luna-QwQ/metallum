package com.metallum.client.metal.render;

import com.metallum.client.metal.optimization.MetalTerrainFaceCulling;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.*;
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
import org.joml.Vector4fc;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;

import java.lang.foreign.MemorySegment;
import java.nio.IntBuffer;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

@Environment(EnvType.CLIENT)
final class MetalRenderPass implements RenderPassBackend {
    static final boolean VALIDATION = SharedConstants.IS_RUNNING_IN_IDE;
    static final int MAX_VERTEX_BUFFERS = RenderPass.MAX_VERTEX_BUFFERS;
    private final MetalDevice device;
    private final MetalCommandEncoder commandEncoder;
    @Nullable
    private final String label;
    private final GpuTextureView colorTexture;
    @Nullable
    private final GpuTextureView depthTexture;
    private final RenderPass.RenderArea renderArea;
    @Nullable
    private final Vector4fc clearColor;
    private final boolean clearDepthEnabled;
    private final double clearDepthValue;
    private final ScissorState scissorState = new ScissorState();
    private final GpuBufferSlice[] vertexBuffers = new GpuBufferSlice[MAX_VERTEX_BUFFERS];
    private final HashMap<String, GpuBufferSlice> uniforms = new HashMap<>();
    private final HashMap<String, TextureViewAndSampler> samplers = new HashMap<>();
    private final Set<MetalCompiledRenderPipeline.ResourceBinding> dirtyDescriptors = new HashSet<>();
    @Nullable
    private RenderPipeline pipeline;
    @Nullable
    private MetalCompiledRenderPipeline compiledPipeline;
    @Nullable
    private GpuBuffer indexBuffer;
    private IndexType indexType = IndexType.SHORT;
    private MemorySegment nativePipeline = MemorySegment.NULL;
    private int pushedDebugGroups = 0;
    private boolean scissorDirty = true;
    private boolean vertexBuffersDirty = true;

    MetalRenderPass(
            final MetalDevice device,
            final MetalCommandEncoder encoder,
            final Supplier<String> label,
            final GpuTextureView colorTexture,
            @Nullable final GpuTextureView depthTexture,
            final RenderPass.RenderArea renderArea,
            @Nullable final Vector4fc clearColor,
            final boolean clearDepthEnabled,
            final double clearDepthValue
    ) {
        this.device = device;
        this.commandEncoder = encoder;
        this.label = device.useLabels() ? label.get() : null;
        this.colorTexture = colorTexture;
        this.depthTexture = depthTexture;
        this.renderArea = renderArea;
        this.clearColor = clearColor;
        this.clearDepthEnabled = clearDepthEnabled;
        this.clearDepthValue = clearDepthValue;
    }

    @Override
    public void pushDebugGroup(final @NonNull Supplier<String> label) {
        pushedDebugGroups++;
        if (device.useLabels()) {
            commandEncoder.commandBuffer().pushDebugGroup(label.get());
        }
    }

    @Override
    public void popDebugGroup() {
        if (pushedDebugGroups == 0) {
            throw new IllegalStateException("Can't pop more debug groups than was pushed!");
        }
        pushedDebugGroups--;
        if (device.useLabels()) {
            commandEncoder.commandBuffer().popDebugGroup();
        }
    }

    @Override
    public void setPipeline(final @NonNull RenderPipeline pipeline) {
        if (this.pipeline != pipeline) {
            vertexBuffersDirty = true;
            nativePipeline = MemorySegment.NULL;
        }

        MetalCompiledRenderPipeline compiled = device.getOrCompilePipeline(pipeline);
        this.pipeline = pipeline;
        compiledPipeline = compiled;
    }

    @Override
    public void bindTexture(final @NonNull String name, @Nullable final GpuTextureView textureView, @Nullable final GpuSampler sampler) {
        if (textureView != null && sampler != null) {
            samplers.put(name, new TextureViewAndSampler(textureView, sampler));
            commandEncoder.flushPendingClear(MetalCommandEncoder.castTexture(textureView.texture()));
            markDescriptorDirty(name);
        } else if (textureView == null && sampler == null) {
            samplers.remove(name);
        } else {
            throw new IllegalArgumentException();
        }
    }

    @Override
    public void setUniform(final @NonNull String name, final GpuBuffer value) {
        setUniform(name, value.slice());
    }

    @Override
    public void setUniform(final @NonNull String name, final @NonNull GpuBufferSlice value) {
        uniforms.put(name, value);
        markDescriptorDirty(name);
    }

    @Override
    public void enableScissor(final int x, final int y, final int width, final int height) {
        if (scissorState.enabled()
                && scissorState.x() == x
                && scissorState.y() == y
                && scissorState.width() == width
                && scissorState.height() == height) {
            return;
        }
        scissorState.enable(x, y, width, height);
        scissorDirty = true;
    }

    @Override
    public void disableScissor() {
        if (!scissorState.enabled()) {
            return;
        }
        scissorState.disable();
        scissorDirty = true;
    }

    @Override
    public void setVertexBuffer(final int slot, @Nullable final GpuBufferSlice vertexBuffer) {
        if (slot < 0 || slot >= MAX_VERTEX_BUFFERS) {
            throw new IllegalArgumentException("Unsupported Metal vertex buffer slot: " + slot);
        }

        if (!sameSlice(vertexBuffers[slot], vertexBuffer)) {
            vertexBuffers[slot] = vertexBuffer;
            vertexBuffersDirty = true;
        }
    }

    @Override
    public void setIndexBuffer(@Nullable final GpuBuffer indexBuffer, final @NonNull IndexType indexType) {
        if (this.indexBuffer != indexBuffer || this.indexType != indexType) {
            this.indexBuffer = indexBuffer;
            this.indexType = indexType;
        }
    }

    @Override
    public void drawIndexed(final int indexCount, final int instanceCount, final int firstIndex, final int vertexOffset, final int firstInstance) {
        MetalGpuBuffer nativeIndexBuffer = resolveIndexBuffer();
        MTLRenderCommandEncoder enc = renderEncoder();

        bindDrawState(enc);
        drawIndexedNative(enc, nativeIndexBuffer, firstIndex, indexCount, vertexOffset, instanceCount, indexType);
    }

    @Override
    public void multiDrawIndexed(@NonNull IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void multiDrawIndexed(@NonNull PointerBuffer firstIndexOffsets, @NonNull IntBuffer indexCounts, @NonNull IntBuffer vertexOffsets, int drawCount) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void drawIndexedIndirect(final @NonNull GpuBufferSlice commands, final int drawCount) {
        throw new UnsupportedOperationException("Metal backend does not support indirect indexed draws yet");
    }

    @Override
    public <T> void drawMultipleIndexed(
            final Collection<RenderPass.Draw<T>> draws,
            @Nullable final GpuBuffer defaultIndexBuffer,
            @Nullable final IndexType defaultIndexType,
            final @NonNull Collection<String> dynamicUniforms,
            final @NonNull T uniformArgument
    ) {
        IndexType fallbackIndexType = defaultIndexType == null ? IndexType.SHORT : defaultIndexType;
        MTLRenderCommandEncoder enc = renderEncoder();

        for (RenderPass.Draw<T> draw : draws) {
            IndexType drawIndexType = draw.indexType() == null ? fallbackIndexType : draw.indexType();
            GpuBuffer currentIndexBuffer = draw.indexBuffer() == null ? defaultIndexBuffer : draw.indexBuffer();

            setIndexBuffer(currentIndexBuffer, drawIndexType);
            setVertexBuffer(draw.slot(), draw.vertexBuffer().slice());

            if (draw.uniformUploaderConsumer() != null) {
                draw.uniformUploaderConsumer().accept(uniformArgument, this::setUniform);
            }

            if (scissorDirty || vertexBuffersDirty || !dirtyDescriptors.isEmpty() || MetalNativeBridge.isNullHandle(nativePipeline)) {
                bindDrawState(enc);
            }
            MetalGpuBuffer nativeIndexBuffer = resolveIndexBuffer();
            MetalTerrainFaceCulling.VisibleRanges visibleRanges = MetalTerrainFaceCulling.takeVisibleRanges(draw, currentIndexBuffer);
            if (visibleRanges != null) {
                for (int range = 0; range < visibleRanges.rangeCount(); range++) {
                    int indexCount = visibleRanges.indexCount(range);
                    if (indexCount > 0) {
                        drawIndexedNative(enc, nativeIndexBuffer, draw.firstIndex() + visibleRanges.firstIndex(range), indexCount, draw.baseVertex(), 1, drawIndexType);
                    }
                }
                continue;
            }
            drawIndexedNative(enc, nativeIndexBuffer, draw.firstIndex(), draw.indexCount(), draw.baseVertex(), 1, drawIndexType);
        }
    }

    @Override
    public void draw(final int vertexCount, final int instanceCount, final int firstVertex, final int firstInstance) {
        PrimitiveTopology primitiveTopology = primitiveTopology();
        MTLPrimitiveType primitiveType = MetalPipelineSupport.primitiveTypeCode(primitiveTopology);

        MTLRenderCommandEncoder enc = renderEncoder();

        bindDrawState(enc);

        if (primitiveType.value == MetalPipelineSupport.TRIANGLE_FAN_PRIMITIVE) {
            try (MetalGpuBuffer fanIndexBuffer = newTriangleFanBuffer(vertexCount)) {
                enc.drawPrimitivesTriangleFan(fanIndexBuffer.nativeHandle(), firstVertex, vertexCount, Math.max(1, instanceCount));
            }
        } else {
            enc.drawPrimitives(primitiveType, firstVertex, vertexCount, Math.max(1, instanceCount));
        }
    }

    @Override
    public void multiDraw(@NonNull IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void multiDraw(@NonNull IntBuffer firstVertices, @NonNull IntBuffer vertexCounts, int drawCount) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void drawIndirect(final @NonNull GpuBufferSlice commands, final int drawCount) {
        throw new UnsupportedOperationException("Metal backend does not support indirect draws yet");
    }

    @Override
    public void writeTimestamp(final @NonNull GpuQueryPool pool, final int index) {
        if (pool instanceof MetalGpuQueryPool metalPool && index >= 0 && index < pool.size()) {
            metalPool.setValue(index, device.getTimestampNow());
        }
    }

    MTLPixelFormat colorAttachmentFormat() {
        return ((MetalGpuTexture) colorTexture.texture()).mtlPixelFormat();
    }

    MTLPixelFormat depthAttachmentFormat() {
        if (depthTexture == null) {
            return MTLPixelFormat.Invalid;
        }
        return ((MetalGpuTexture) depthTexture.texture()).mtlPixelFormat();
    }

    MTLPixelFormat stencilAttachmentFormat() {
        if (depthTexture == null) {
            return MTLPixelFormat.Invalid;
        }
        return ((MetalGpuTexture) depthTexture.texture()).mtlStencilPixelFormat();
    }

    private MTLRenderCommandEncoder renderEncoder() {
        MetalGpuTextureView colorTextureView = (MetalGpuTextureView) colorTexture;
        MetalGpuTextureView depthTextureView = depthTexture == null ? null : (MetalGpuTextureView) depthTexture;
        return commandEncoder.renderCommandEncoder(
                colorTextureView,
                depthTextureView,
                colorTexture.getWidth(0),
                colorTexture.getHeight(0),
                clearColor != null,
                clearColor != null ? clearColor.x() : 0.0F,
                clearColor != null ? clearColor.y() : 0.0F,
                clearColor != null ? clearColor.z() : 0.0F,
                clearColor != null ? clearColor.w() : 0.0F,
                clearDepthEnabled,
                clearDepthValue
        );
    }

    void end() {
        nativePipeline = MemorySegment.NULL;
    }

    private void pushVertexBuffers(final MTLRenderCommandEncoder enc) {
        for (int slot = 0; slot < MAX_VERTEX_BUFFERS; slot++) {
            int metalSlot = compiledPipeline.metalSlotForVertexBinding(slot);
            if (metalSlot < 0) {
                continue;
            }

            GpuBufferSlice vertexBuffer = vertexBuffers[slot];
            if (vertexBuffer == null) {
                throw new IllegalStateException("Missing vertex buffer at slot " + slot);
            }
            if (VALIDATION && vertexBuffer.buffer().isClosed()) {
                throw new IllegalStateException("Vertex buffer at slot " + slot + " has been closed");
            }

            MetalGpuBuffer nativeVertexBuffer = MetalCommandEncoder.castBuffer(vertexBuffer.buffer());
            enc.setBuffer(nativeVertexBuffer.nativeHandle(), vertexBuffer.offset(), metalSlot, MetalCompiledRenderPipeline.STAGE_VERTEX);
        }
    }

    private MetalGpuBuffer resolveIndexBuffer() {
        if (indexBuffer == null) {
            throw new IllegalStateException("Missing index buffer");
        }
        if (VALIDATION && indexBuffer.isClosed()) {
            throw new IllegalStateException("Index buffer has been closed");
        }
        return MetalCommandEncoder.castBuffer(indexBuffer);
    }

    private void drawIndexedNative(
            final MTLRenderCommandEncoder enc,
            final MetalGpuBuffer nativeIndexBuffer,
            final int firstIndex,
            final int indexCount,
            final int baseVertex,
            final int instanceCount,
            final IndexType indexType
    ) {
        PrimitiveTopology primitiveTopology = primitiveTopology();
        MTLPrimitiveType primitiveType = MetalPipelineSupport.primitiveTypeCode(primitiveTopology);

        int safeInstanceCount = Math.max(1, instanceCount);
        long indexOffsetBytes = (long) firstIndex * indexType.bytes;
        long nativeIndexType = indexType == IndexType.INT ? 1L : 0L;
        if (primitiveType.value == MetalPipelineSupport.TRIANGLE_FAN_PRIMITIVE)
            try (MetalGpuBuffer fanIndexBuffer = newTriangleFanBuffer(indexCount)) {
                enc.drawIndexedPrimitivesTriangleFan(
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
            enc.drawIndexedPrimitives(primitiveType, indexCount, nativeIndexType, nativeIndexBuffer.nativeHandle(), indexOffsetBytes, safeInstanceCount, baseVertex);
        }
    }

    private MetalGpuBuffer newTriangleFanBuffer(final int sourceCount) {
        long byteSize = Math.multiplyExact(Math.multiplyExact((long) sourceCount - 2L, 3L), Integer.BYTES);

        return new MetalGpuBuffer(
                device,
                GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_INDEX,
                byteSize
        );
    }

    private void bindDrawState(
            final MTLRenderCommandEncoder enc
    ) {
        if (compiledPipeline == null) {
            throw new IllegalStateException("Pipeline is missing");
        }

        boolean pipelineChanged = false;
        if (MetalNativeBridge.isNullHandle(nativePipeline)) {
            MemorySegment pipelineHandle = compiledPipeline.getOrCreateNativePipeline(
                    device,
                    colorAttachmentFormat().value,
                    depthAttachmentFormat().value,
                    stencilAttachmentFormat().value
            );
            if (MetalNativeBridge.isNullHandle(pipelineHandle)) {
                throw new IllegalStateException("Native pipeline is unavailable");
            }
            enc.setRenderPipelineState(pipelineHandle);
            nativePipeline = pipelineHandle;

            if (depthAttachmentFormat().value != MTLPixelFormat.Invalid.value) {
                MemorySegment depthState = compiledPipeline.getOrCreateDepthStencilState(device);
                if (MetalNativeBridge.isNullHandle(depthState)) {
                    throw new IllegalStateException("Native depth state is unavailable");
                }
                enc.setDepthStencilState(depthState);
                enc.setDepthBias(
                        compiledPipeline.depthBiasConstant(),
                        compiledPipeline.depthBiasScaleFactor(),
                        0.0f
                );
            }

            RenderPipeline pipelineInfo = compiledPipeline.info();
            enc.setFrontFacingWinding(MTLWinding.Clockwise);
            enc.setCullMode(pipelineInfo.isCull() ? MTLCullMode.Back : MTLCullMode.None);
            enc.setTriangleFillMode(pipelineInfo.getPolygonMode() == PolygonMode.WIREFRAME ? MTLTriangleFillMode.Lines : MTLTriangleFillMode.Fill);

            pipelineChanged = true;
        }

        if (scissorDirty) {
            pushEffectiveScissor(enc);
            scissorDirty = false;
        }

        if (vertexBuffersDirty) {
            pushVertexBuffers(enc);
            vertexBuffersDirty = false;
        }

        if (pipelineChanged) {
            for (MetalCompiledRenderPipeline.ResourceBinding binding : compiledPipeline.resources()) {
                pushDescriptor(enc, binding);
            }
            dirtyDescriptors.clear();
        } else if (!dirtyDescriptors.isEmpty()) {
            for (MetalCompiledRenderPipeline.ResourceBinding binding : dirtyDescriptors) {
                pushDescriptor(enc, binding);
            }
            dirtyDescriptors.clear();
        }
    }

    private PrimitiveTopology primitiveTopology() {
        if (pipeline == null) {
            throw new IllegalStateException("Pipeline is missing");
        }
        return pipeline.getPrimitiveTopology();
    }

    private void pushEffectiveScissor(final MTLRenderCommandEncoder enc) {
        int areaLeft = renderArea.x();
        int areaTop = renderArea.y();
        if (!scissorState.enabled()) {
            if (renderArea.fillsTexture(colorTexture)) {
                enc.setScissorRect(0L, 0L, colorTexture.getWidth(0), colorTexture.getHeight(0));
                return;
            }
            enc.setScissorRect(areaLeft, areaTop, renderArea.width(), renderArea.height());
            return;
        }

        int areaRight = areaLeft + renderArea.width();
        int areaBottom = areaTop + renderArea.height();
        int left = Math.max(areaLeft, scissorState.x());
        int top = Math.max(areaTop, scissorState.y());
        int right = Math.min(areaRight, scissorState.x() + scissorState.width());
        int bottom = Math.min(areaBottom, scissorState.y() + scissorState.height());
        if (right <= left || bottom <= top) {
            enc.setScissorRect(0, 0, 0, 0);
        } else {
            enc.setScissorRect(left, top, right - left, bottom - top);
        }
    }

    private void markDescriptorDirty(final String name) {
        if (compiledPipeline != null) {
            MetalCompiledRenderPipeline.ResourceBinding binding = compiledPipeline.resource(name);
            if (binding != null) {
                dirtyDescriptors.add(binding);
            }
        }
    }

    private void pushDescriptor(
            final MTLRenderCommandEncoder enc,
            final MetalCompiledRenderPipeline.ResourceBinding binding
    ) {
        if (binding.kind() == MetalCompiledRenderPipeline.ResourceKind.SAMPLED_IMAGE) {
            TextureViewAndSampler textureBinding = samplers.get(binding.name());
            if (textureBinding == null) {
                throw new IllegalStateException("Missing sampler " + binding.name());
            }

            if (VALIDATION && textureBinding.textureView().isClosed()) {
                throw new IllegalStateException("Sampler " + binding.name() + " texture view has been closed");
            }

            MetalGpuTextureView textureView = (MetalGpuTextureView) textureBinding.textureView();

            MetalGpuSampler sampler = (MetalGpuSampler) textureBinding.sampler();
            enc.setTextureAndSampler(textureView.nativeHandle(), sampler.nativeHandle(), binding.bindingIndex(), binding.stageMask());

            return;
        }

        if (binding.kind() == MetalCompiledRenderPipeline.ResourceKind.TEXEL_BUFFER) {
            pushTexelBufferDescriptor(enc, binding);
            return;
        }

        GpuBufferSlice uniformSlice = uniforms.get(binding.name());
        if (uniformSlice == null) {
            throw new IllegalStateException("Missing uniform " + binding.name());
        }
        if (VALIDATION && uniformSlice.buffer().isClosed()) {
            throw new IllegalStateException("Uniform " + binding.name() + " buffer has been closed");
        }

        MetalGpuBuffer uniformBuffer = MetalCommandEncoder.castBuffer(uniformSlice.buffer());
        enc.setBuffer(uniformBuffer.nativeHandle(), uniformSlice.offset(), binding.bindingIndex(), binding.stageMask());
    }

    private void pushTexelBufferDescriptor(final MTLRenderCommandEncoder enc, final MetalCompiledRenderPipeline.ResourceBinding binding) {
        GpuBufferSlice texelSlice = uniforms.get(binding.name());
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
        long pixelFormat = MetalPipelineSupport.toMtlPixelFormat(texelFormat).value;
        int pixelSize = texelFormat.pixelSize();
        long texelByteLength = texelSlice.length();
        if (texelByteLength <= 0L || texelByteLength % pixelSize != 0L) {
            throw new IllegalStateException("Texel buffer " + binding.name() + " length " + texelByteLength + " is not a valid " + texelFormat + " range");
        }
        long texelCount = texelByteLength / pixelSize;
        MemorySegment texelTexture = MetalNativeBridge.INSTANCE.metallum_create_buffer_texture_view(
                texelBuffer.nativeHandle(),
                pixelFormat,
                texelSlice.offset(),
                texelCount,
                1L,
                texelByteLength
        );
        if (MetalNativeBridge.isNullHandle(texelTexture)) {
            throw new IllegalStateException("Failed to create Metal texel buffer texture for " + binding.name());
        }

        enc.setTexture(texelTexture, binding.bindingIndex(), binding.stageMask());

        commandEncoder.queueForDestroy(() -> MetalNativeBridge.INSTANCE.metallum_release_object(texelTexture));
    }

    record TextureViewAndSampler(GpuTextureView textureView, GpuSampler sampler) {
    }

    private static boolean sameSlice(@Nullable final GpuBufferSlice left, @Nullable final GpuBufferSlice right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.buffer() == right.buffer()
                && left.offset() == right.offset()
                && left.length() == right.length();
    }
}
