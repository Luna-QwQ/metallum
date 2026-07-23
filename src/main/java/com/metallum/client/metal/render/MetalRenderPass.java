package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.*;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
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
import org.lwjgl.vulkan.VkDrawIndexedIndirectCommand;
import org.lwjgl.vulkan.VkDrawIndirectCommand;

import java.lang.foreign.MemorySegment;
import java.nio.IntBuffer;
import java.util.Collection;
import java.util.HashMap;
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
    private Vector4fc clearColor;
    private boolean clearDepthEnabled;
    private final double clearDepthValue;
    private final ScissorState scissorState = new ScissorState();
    private final GpuBufferSlice[] vertexBuffers = new GpuBufferSlice[MAX_VERTEX_BUFFERS];
    private final HashMap<String, GpuBufferSlice> uniforms = new HashMap<>();
    private final HashMap<String, TextureViewAndSampler> samplers = new HashMap<>();
    private long dirtyDescriptorMask;
    @Nullable
    private MetalCompiledRenderPipeline compiledPipeline;
    /**
     * When non-null, substitutes the Iris gbuffers pipeline for vanilla's
     * during {@link #bindDrawState} (M5d-1). Set by {@link #setPipeline} when
     * {@link MetalIrisRenderer#isPipelineSwapEnabled()} is true and a cached
     * Iris gbuffers pipeline exists for the active phase. While non-null, the
     * native pipeline state, depth stencil, cull/fill mode, topology and vertex
     * buffer layout all come from the Iris pipeline, and the vanilla named-
     * resource descriptor loop is skipped. Uniform/sampler binding for the Iris
     * MSL is added in M5d-2/M5d-3.
     */
    @Nullable
    private MetalIrisPipeline irisPipeline;
    @Nullable
    private GpuBuffer indexBuffer;
    private MTLIndexType indexType = MTLIndexType.UInt16;
    private int pushedDebugGroups = 0;
    private boolean scissorDirty = true;
    private boolean vertexBuffersDirty = true;
    private boolean pipelineDirty = true;

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
        MetalCompiledRenderPipeline compiled = device.getOrCompilePipeline(pipeline);
        if (this.compiledPipeline != compiled) {
            this.compiledPipeline = compiled;
            vertexBuffersDirty = true;
            pipelineDirty = true;
        }
        // M5d-1: when the Iris pipeline swap is enabled and a gbuffers phase is
        // active, resolve the cached Iris gbuffers pipeline. If found,
        // bindDrawState substitutes its native pipeline state (and vertex
        // buffer layout) for vanilla's. Uniform/sampler binding for the Iris
        // MSL is added in M5d-2/M5d-3; until then the swap is gated off by
        // MetalIrisRenderer.pipelineSwapEnabled (default false), so vanilla
        // rendering is unaffected.
        MetalIrisPipeline resolved = MetalIrisRenderer.isPipelineSwapEnabled()
                ? MetalIrisRenderer.getActiveIrisPipeline()
                : null;
        if (this.irisPipeline != resolved) {
            this.irisPipeline = resolved;
            vertexBuffersDirty = true;
            pipelineDirty = true;
        }
    }

    @Override
    public void bindTexture(final @NonNull String name, @Nullable final GpuTextureView textureView, @Nullable final GpuSampler sampler) {
        if (textureView != null && sampler != null) {
            samplers.put(name, new TextureViewAndSampler(textureView, sampler));
            commandEncoder.flushPendingClear((MetalGpuTexture) textureView.texture());
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
        setIndexBuffer(indexBuffer, MTLIndexType.from(indexType));
    }

    private void setIndexBuffer(@Nullable final GpuBuffer indexBuffer, final MTLIndexType indexType) {
        if (this.indexBuffer != indexBuffer || this.indexType != indexType) {
            this.indexBuffer = indexBuffer;
            this.indexType = indexType;
        }
    }

    @Override
    public void drawIndexed(final int indexCount, final int instanceCount, final int firstIndex, final int vertexOffset, final int firstInstance) {
        MetalGpuBuffer nativeIndexBuffer = (MetalGpuBuffer) indexBuffer;
        MTLRenderCommandEncoder enc = renderEncoder();

        bindDrawState(enc);
        drawIndexedNative(enc, nativeIndexBuffer, firstIndex, indexCount, vertexOffset, instanceCount, indexType, firstInstance);
    }

    @Override
    public void multiDrawIndexed(@NonNull IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
        MetalGpuBuffer nativeIndexBuffer = (MetalGpuBuffer) indexBuffer;
        MTLRenderCommandEncoder enc = renderEncoder();
        bindDrawState(enc);

        for (int i = 0; i < drawCount; i++) {
            int firstIndex = drawParameters.get(i * 3);
            int indexCount = drawParameters.get(i * 3 + 1);
            int baseVertex = drawParameters.get(i * 3 + 2);
            if (indexCount > 0) {
                drawIndexedNative(enc, nativeIndexBuffer, firstIndex, indexCount, baseVertex, instanceCount, indexType, firstInstance);
            }
        }
    }

    @Override
    public void multiDrawIndexed(@NonNull PointerBuffer firstIndexOffsets, @NonNull IntBuffer indexCounts, @NonNull IntBuffer vertexOffsets, int drawCount) {
        MTLPrimitiveType primitiveType = primitiveTopology();
        if (primitiveType == MTLPrimitiveType.TriangleFan) {
            throw new UnsupportedOperationException("Metal backend does not support triangle fan multiDrawIndexed");
        }

        MetalGpuBuffer nativeIndexBuffer = (MetalGpuBuffer) indexBuffer;
        MTLRenderCommandEncoder enc = renderEncoder();
        bindDrawState(enc);

        MetalNativeBridge.MTLRenderCommandEncoder_multiDrawIndexed(
                enc.handle(),
                primitiveType.value,
                indexType.value,
                nativeIndexBuffer.nativeHandle(),
                MemorySegment.ofAddress(org.lwjgl.system.MemoryUtil.memAddress(firstIndexOffsets)),
                MemorySegment.ofAddress(org.lwjgl.system.MemoryUtil.memAddress(indexCounts)),
                MemorySegment.ofAddress(org.lwjgl.system.MemoryUtil.memAddress(vertexOffsets)),
                drawCount,
                1L,
                0L
        );
    }

    @Override
    public void drawIndexedIndirect(final @NonNull GpuBufferSlice commands, final int drawCount) {
        MTLPrimitiveType primitiveType = primitiveTopology();
        if (primitiveType == MTLPrimitiveType.TriangleFan) {
            throw new UnsupportedOperationException("Metal backend does not support triangle fan indirect draws");
        }

        MetalGpuBuffer nativeIndexBuffer = (MetalGpuBuffer) indexBuffer;
        MTLRenderCommandEncoder enc = renderEncoder();
        bindDrawState(enc);

        enc.drawIndexedPrimitivesIndirect(
                primitiveType,
                indexType,
                nativeIndexBuffer.nativeHandle(),
                ((MetalGpuBuffer) commands.buffer()).nativeHandle(),
                commands.offset(),
                drawCount,
                VkDrawIndexedIndirectCommand.SIZEOF
        );
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
            MTLIndexType drawIndexType = MTLIndexType.from(draw.indexType() == null ? fallbackIndexType : draw.indexType());
            GpuBuffer currentIndexBuffer = draw.indexBuffer() == null ? defaultIndexBuffer : draw.indexBuffer();

            setIndexBuffer(currentIndexBuffer, drawIndexType);
            setVertexBuffer(draw.slot(), draw.vertexBuffer().slice());

            if (draw.uniformUploaderConsumer() != null) {
                draw.uniformUploaderConsumer().accept(uniformArgument, this::setUniform);
            }

            if (scissorDirty || vertexBuffersDirty || dirtyDescriptorMask != 0L || pipelineDirty) {
                bindDrawState(enc);
            }
            MetalGpuBuffer nativeIndexBuffer = (MetalGpuBuffer) indexBuffer;
            drawIndexedNative(enc, nativeIndexBuffer, draw.firstIndex(), draw.indexCount(), draw.baseVertex(), 1, drawIndexType, 0);
        }
    }

    @Override
    public void draw(final int vertexCount, final int instanceCount, final int firstVertex, final int firstInstance) {
        MTLPrimitiveType primitiveType = primitiveTopology();
        MTLRenderCommandEncoder enc = renderEncoder();

        bindDrawState(enc);

        if (primitiveType == MTLPrimitiveType.TriangleFan) {
            drawTriangleFan(enc, firstVertex, vertexCount, instanceCount, firstInstance);
        } else {
            enc.drawPrimitives(primitiveType, firstVertex, vertexCount, Math.max(1, instanceCount), firstInstance);
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
        MTLPrimitiveType primitiveType = primitiveTopology();
        if (primitiveType == MTLPrimitiveType.TriangleFan) {
            throw new UnsupportedOperationException("Metal backend does not support triangle fan indirect draws");
        }

        MTLRenderCommandEncoder enc = renderEncoder();
        bindDrawState(enc);

        enc.drawPrimitivesIndirect(
                primitiveType,
                ((MetalGpuBuffer) commands.buffer()).nativeHandle(),
                commands.offset(),
                drawCount,
                VkDrawIndirectCommand.SIZEOF
        );
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

    void materializePendingClear() {
        if (clearColor != null || clearDepthEnabled) {
            renderEncoder();
        }
    }

    private MTLRenderCommandEncoder renderEncoder() {
        MetalGpuTextureView colorTextureView = (MetalGpuTextureView) colorTexture;
        MetalGpuTextureView depthTextureView = depthTexture == null ? null : (MetalGpuTextureView) depthTexture;
        boolean clearColorNow = clearColor != null;
        boolean clearDepthNow = clearDepthEnabled;
        MTLRenderCommandEncoder encoder = commandEncoder.renderCommandEncoder(
                colorTextureView,
                depthTextureView,
                colorTexture.getWidth(0),
                colorTexture.getHeight(0),
                clearColorNow,
                clearColorNow ? clearColor.x() : 0.0F,
                clearColorNow ? clearColor.y() : 0.0F,
                clearColorNow ? clearColor.z() : 0.0F,
                clearColorNow ? clearColor.w() : 0.0F,
                clearDepthNow,
                clearDepthValue
        );
        clearColor = null;
        clearDepthEnabled = false;
        return encoder;
    }

    GpuBufferSlice.MappedView allocateTransient(final long size, final long alignment, @GpuBuffer.Usage final int usage) {
        return commandEncoder.transientMemory().allocateGpuMapped(size, alignment, usage);
    }

    private void pushVertexBuffers(final MTLRenderCommandEncoder enc) {
        // M5d-1: vertex buffer layout comes from the Iris pipeline when the
        // override is active (its firstAvailableVertexBufferSlot is 0 and its
        // vertexBufferCount matches the gbuffers vertex descriptor).
        int firstSlot;
        int count;
        if (irisPipeline != null) {
            firstSlot = irisPipeline.firstAvailableVertexBufferSlot();
            count = irisPipeline.vertexBufferCount();
        } else {
            firstSlot = compiledPipeline.firstAvailableVertexBufferSlot();
            count = compiledPipeline.vertexBufferCount();
        }
        for (int slot = 0; slot < count; slot++) {
            GpuBufferSlice vertexBuffer = vertexBuffers[slot];
            if (vertexBuffer == null) {
                continue;
            }
            if (VALIDATION && vertexBuffer.buffer().isClosed()) {
                throw new IllegalStateException("Vertex buffer at slot " + slot + " has been closed");
            }

            MetalGpuBuffer nativeVertexBuffer = (MetalGpuBuffer) vertexBuffer.buffer();
            int metalSlot = firstSlot + slot;
            enc.setBuffer(nativeVertexBuffer.nativeHandle(), vertexBuffer.offset(), metalSlot, MetalCompiledRenderPipeline.STAGE_VERTEX);
        }
    }

    private void drawTriangleFan(MTLRenderCommandEncoder encoder, final int firstVertex, final int vertexCount, final int instanceCount, final int baseInstance) {
        int triangleCount = vertexCount - 2;
        int indexCount = triangleCount * 3;
        MTLIndexType fanIndexType = vertexCount - 1 <= 0xFFFF ? MTLIndexType.UInt16 : MTLIndexType.UInt32;

        try (GpuBufferSlice.MappedView mapped = commandEncoder.transientMemory().allocateGpuMapped((long) indexCount * fanIndexType.bytes, fanIndexType.bytes, GpuBuffer.USAGE_INDEX)) {
            if (fanIndexType == MTLIndexType.UInt16) {
                java.nio.ShortBuffer indices = mapped.data().asShortBuffer();
                for (int i = 0; i < triangleCount; i++) {
                    indices.put((short) 0);
                    indices.put((short) (i + 1));
                    indices.put((short) (i + 2));
                }
            } else {
                java.nio.IntBuffer indices = mapped.data().asIntBuffer();
                for (int i = 0; i < triangleCount; i++) {
                    indices.put(0);
                    indices.put(i + 1);
                    indices.put(i + 2);
                }
            }
            GpuBufferSlice slice = mapped.slice();
            encoder.drawIndexedPrimitives(MTLPrimitiveType.Triangle, indexCount, fanIndexType, ((MetalGpuBuffer) slice.buffer()).nativeHandle(), slice.offset(), Math.max(1, instanceCount), firstVertex, baseInstance);
        }
    }

    private void drawIndexedNative(
            final MTLRenderCommandEncoder enc,
            final MetalGpuBuffer nativeIndexBuffer,
            final int firstIndex,
            final int indexCount,
            final int baseVertex,
            final int instanceCount,
            final MTLIndexType indexType,
            final int baseInstance
    ) {
        MTLPrimitiveType primitiveType = primitiveTopology();

        long indexOffsetBytes = (long) firstIndex * indexType.bytes;
        if (primitiveType == MTLPrimitiveType.TriangleFan) {
            long fanSize = Math.multiplyExact(Math.multiplyExact((long) indexCount - 2L, 3L), Integer.BYTES);
            try (GpuBufferSlice.MappedView mapped = commandEncoder.transientMemory().allocateGpuMapped(fanSize, Integer.BYTES, GpuBuffer.USAGE_INDEX)) {
                GpuBufferSlice slice = mapped.slice();
                enc.drawIndexedPrimitivesTriangleFan(
                        nativeIndexBuffer.nativeHandle(),
                        ((MetalGpuBuffer) slice.buffer()).nativeHandle(),
                        slice.offset(),
                        indexType.value,
                        indexOffsetBytes,
                        indexCount,
                        baseVertex,
                        instanceCount,
                        baseInstance
                );
            }
        } else {
            enc.drawIndexedPrimitives(primitiveType, indexCount, indexType, nativeIndexBuffer.nativeHandle(), indexOffsetBytes, instanceCount, baseVertex, baseInstance);
        }
    }

    private void bindDrawState(final MTLRenderCommandEncoder enc) {
        if (compiledPipeline == null) {
            throw new IllegalStateException("Pipeline is missing");
        }

        if (pipelineDirty) {
            boolean useDepth = depthAttachmentFormat().value != MTLPixelFormat.Invalid.value;
            // M5d-1: when an Iris gbuffers pipeline override is active, bind its
            // native pipeline state / depth stencil / draw state instead of
            // vanilla's. The Iris MSL uses a different (SPIR-V-cross) binding
            // convention than vanilla's named-resource model, so the vanilla
            // descriptor loop below is skipped while the override is active;
            // Iris UBO/sampler binding is added in M5d-2/M5d-3.
            MemorySegment pipelineHandle;
            MemorySegment depthState;
            float depthBiasConstant;
            float depthBiasScaleFactor;
            MTLCullMode cullMode;
            MTLTriangleFillMode fillMode;
            if (irisPipeline != null) {
                pipelineHandle = irisPipeline.getNativePipeline(useDepth);
                depthState = irisPipeline.depthStencilState();
                depthBiasConstant = irisPipeline.depthBiasConstant();
                depthBiasScaleFactor = irisPipeline.depthBiasScaleFactor();
                cullMode = irisPipeline.cullMode();
                fillMode = irisPipeline.fillMode();
            } else {
                pipelineHandle = compiledPipeline.getNativePipeline(useDepth);
                depthState = compiledPipeline.getDepthStencilState();
                depthBiasConstant = compiledPipeline.depthBiasConstant();
                depthBiasScaleFactor = compiledPipeline.depthBiasScaleFactor();
                cullMode = compiledPipeline.cullMode();
                fillMode = compiledPipeline.fillMode();
            }
            if (MetalNativeBridge.isNullHandle(pipelineHandle)) {
                throw new IllegalStateException("Native pipeline is unavailable");
            }
            enc.setRenderPipelineState(pipelineHandle);
            pipelineDirty = false;

            if (useDepth) {
                if (MetalNativeBridge.isNullHandle(depthState)) {
                    throw new IllegalStateException("Native depth state is unavailable");
                }
                enc.setDepthStencilState(depthState);
                enc.setDepthBias(depthBiasConstant, depthBiasScaleFactor, 0.0f);
            }

            enc.setFrontFacingWinding(MTLWinding.Clockwise);
            enc.setCullMode(cullMode);
            enc.setTriangleFillMode(fillMode);

            if (irisPipeline == null) {
                dirtyDescriptorMask |= compiledPipeline.allResourceMask();
            }
        }

        if (scissorDirty) {
            pushEffectiveScissor(enc);
            scissorDirty = false;
        }

        if (vertexBuffersDirty) {
            pushVertexBuffers(enc);
            vertexBuffersDirty = false;
        }

        if (irisPipeline == null && dirtyDescriptorMask != 0) {
            for (MetalCompiledRenderPipeline.ResourceBinding binding : compiledPipeline.resources()) {
                if ((dirtyDescriptorMask & (1L << binding.bindingIndex())) != 0L) {
                    pushDescriptor(enc, binding);
                }
            }
        } else if (irisPipeline != null) {
            // M5d-2: bind reflected Iris UBO slots (textures/samplers are M5d-3).
            // The swap stays gated off by MetalIrisRenderer.pipelineSwapEnabled
            // (default false) until M5d-3, so this branch is infrastructure that
            // does not execute in M5d-2.
            pushIrisUniformBindings(enc, irisPipeline);
        }

        dirtyDescriptorMask = 0L;
    }

    private MTLPrimitiveType primitiveTopology() {
        if (compiledPipeline == null) {
            throw new IllegalStateException("Pipeline is missing");
        }
        return irisPipeline != null ? irisPipeline.topology() : compiledPipeline.topology();
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
                dirtyDescriptorMask |= 1L << binding.bindingIndex();
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

        MetalGpuBuffer uniformBuffer = (MetalGpuBuffer) uniformSlice.buffer();
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

        MetalGpuBuffer texelBuffer = (MetalGpuBuffer) texelSlice.buffer();
        long pixelFormat = MTLPixelFormat.from(texelFormat).value;
        int pixelSize = texelFormat.blockSize();
        long texelByteLength = texelSlice.length();
        if (texelByteLength <= 0L || texelByteLength % pixelSize != 0L) {
            throw new IllegalStateException("Texel buffer " + binding.name() + " length " + texelByteLength + " is not a valid " + texelFormat + " range");
        }
        long texelCount = texelByteLength / pixelSize;
        MemorySegment texelTexture = MetalNativeBridge.metallum_create_buffer_texture_view(
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
        commandEncoder.queueForDestroy(() -> MetalNativeBridge.metallum_release_object(texelTexture));
    }

    /**
     * Binds reflected Iris MSL UBO slots (M5d-2). For each reflected
     * {@link MetalIrisPipeline.IrisResourceBinding} of kind
     * {@link MetalIrisPipeline.IrisResourceKind#UNIFORM_BUFFER}, binds the
     * matching {@link #uniforms} entry if one was provided via
     * {@link #setUniform}; otherwise binds the device's zeroed scratch uniform
     * buffer so the MSL's {@code [[buffer(N)]]} argument is never left unbound.
     *
     * <p>Texture and sampler bindings are reflected but intentionally skipped
     * here — they are bound in M5d-3. The pipeline swap remains gated off
     * ({@code MetalIrisRenderer.pipelineSwapEnabled == false}) until M5d-3, so
     * this path does not execute in M5d-2; it is infrastructure for the M5d-3
     * enable.
     *
     * <p>Dirtiness tracking is not yet implemented — all reflected UBOs are
     * re-bound on every {@link #bindDrawState} call while the Iris override is
     * active. This is correct (idempotent) and can be optimized later.
     */
    private void pushIrisUniformBindings(final MTLRenderCommandEncoder enc, final MetalIrisPipeline iris) {
        final MetalGpuBuffer scratch = device.getOrEnsureIrisScratchUniformBuffer();
        for (MetalIrisPipeline.IrisResourceBinding binding : iris.bindings()) {
            if (binding.kind() != MetalIrisPipeline.IrisResourceKind.UNIFORM_BUFFER) {
                continue;
            }
            final GpuBufferSlice slice = uniforms.get(binding.name());
            if (slice != null && !slice.buffer().isClosed()) {
                enc.setBuffer(
                        ((MetalGpuBuffer) slice.buffer()).nativeHandle(),
                        slice.offset(),
                        binding.bindingIndex(),
                        binding.stageMask());
            } else {
                enc.setBuffer(
                        scratch.nativeHandle(),
                        0L,
                        binding.bindingIndex(),
                        binding.stageMask());
            }
        }
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
