package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.*;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

@Environment(EnvType.CLIENT)
final class MetalCommandEncoder implements CommandEncoderBackend {
    public static final int MAX_SUBMITS_IN_FLIGHT = 3;
    private final MetalDevice device;
    private long currentSubmitIndex = MAX_SUBMITS_IN_FLIGHT;
    private final InFlight[] inFlight = new InFlight[MAX_SUBMITS_IN_FLIGHT];
    private final MemorySegment[] submitSemaphores = new MemorySegment[MAX_SUBMITS_IN_FLIGHT];
    private final MetalDestructionQueue destroyQueue = new MetalDestructionQueue(MAX_SUBMITS_IN_FLIGHT);
    private final MetalTransientMemory transientMemory;
    private final Map<MetalGpuTexture, Vector4fc> pendingColorClears = new IdentityHashMap<>();
    private final Map<MetalGpuTexture, Double> pendingDepthClears = new IdentityHashMap<>();
    private final MemorySegment fence;
    @Nullable
    private MetalRenderPass currentRenderPass;
    @Nullable
    private MTLCommandBuffer commandBuffer;
    @Nullable
    private MTLCommandEncoder currentEncoder;
    private MemorySegment renderColorAttachment = MemorySegment.NULL;
    private MemorySegment renderDepthAttachment = MemorySegment.NULL;

    MetalCommandEncoder(final MetalDevice device) {
        this.device = device;
        this.transientMemory = new MetalTransientMemory(device, this);
        fence = MetalNativeBridge.metallum_create_fence(device.metalDeviceHandle());
        if (MetalNativeBridge.isNullHandle(fence)) {
            throw new IllegalStateException("Failed to allocate MTLFence");
        }
        for (int slot = 0; slot < MAX_SUBMITS_IN_FLIGHT; slot++) {
            submitSemaphores[slot] = MetalNativeBridge.metallum_create_semaphore();
            if (MetalNativeBridge.isNullHandle(submitSemaphores[slot])) {
                throw new IllegalStateException("Failed to allocate submit semaphore");
            }
        }
    }

    MTLCommandBuffer commandBuffer() {
        if (commandBuffer != null) {
            return commandBuffer;
        }
        return commandBuffer = device.commandQueue.makeCommandBuffer(
                device.useLabels() ? "Metallum frame " + currentSubmitIndex : null
        );
    }

    MTLBlitCommandEncoder blitCommandEncoder() {
        endEncoder();
        MTLBlitCommandEncoder encoder = commandBuffer().makeBlitCommandEncoder();
        encoder.waitForFence(fence);
        currentEncoder = encoder;
        return encoder;
    }

    void endEncoder() {
        if (currentEncoder != null) {
            if (currentEncoder instanceof MTLRenderCommandEncoder renderEncoder) {
                renderEncoder.updateFence(fence, MTLRenderStages.VertexAndFragment);
            } else if (currentEncoder instanceof MTLBlitCommandEncoder blitEncoder) {
                blitEncoder.updateFence(fence);
            }
            currentEncoder.endEncoding();
            currentEncoder = null;
        }
        renderColorAttachment = MemorySegment.NULL;
        renderDepthAttachment = MemorySegment.NULL;
    }

    @Override
    public TransientMemory transientMemory() {
        return transientMemory;
    }

    @Override
    public void submit() {
        if (commandBuffer == null) {
            return;
        }

        submitRenderPass();
        endEncoder();

        int slot = (int) (currentSubmitIndex % MAX_SUBMITS_IN_FLIGHT);
        MemorySegment completedSemaphore = submitSemaphores[slot];
        commandBuffer.commitWithSignal(completedSemaphore);

        InFlight toClose = inFlight[slot];
        inFlight[slot] = new InFlight(currentSubmitIndex, commandBuffer, completedSemaphore);
        commandBuffer = null;
        currentSubmitIndex++;

        if (!awaitSubmitCompletion(currentSubmitIndex - MAX_SUBMITS_IN_FLIGHT, 5000L)) {
            throw new IllegalStateException("5s timeout reached when waiting for Metal submit completion");
        }

        if (toClose != null) {
            toClose.buffer.close();
        }

        transientMemory.rotate();
        destroyQueue.rotate();
    }

    MTLRenderCommandEncoder renderCommandEncoder(
            final MetalGpuTextureView colorTextureView,
            @Nullable final MetalGpuTextureView depthTextureView,
            final int viewportWidth,
            final int viewportHeight,
            final boolean clearColorEnabled,
            final float clearColorRed,
            final float clearColorGreen,
            final float clearColorBlue,
            final float clearColorAlpha,
            final boolean clearDepthEnabled,
            final double clearDepthValue
    ) {
        MemorySegment colorAttachment = colorTextureView.nativeHandle();
        MemorySegment depthAttachment = depthTextureView == null ? MemorySegment.NULL : depthTextureView.nativeHandle();
        if (currentEncoder instanceof MTLRenderCommandEncoder enc
                && MetalPipelineSupport.sameHandle(renderColorAttachment, colorAttachment)
                && MetalPipelineSupport.sameHandle(renderDepthAttachment, depthAttachment)) {
            return enc;
        }

        endEncoder();
        MTLRenderCommandEncoder encoder = commandBuffer().makeRenderCommandEncoder(
                colorAttachment,
                depthAttachment,
                viewportWidth,
                viewportHeight,
                clearColorEnabled ? 1 : 0,
                clearColorRed,
                clearColorGreen,
                clearColorBlue,
                clearColorAlpha,
                clearDepthEnabled ? 1 : 0,
                clearDepthValue
        );
        encoder.waitForFence(fence, MTLRenderStages.VertexAndFragment);
        currentEncoder = encoder;
        renderColorAttachment = colorAttachment;
        renderDepthAttachment = depthAttachment;
        return encoder;
    }

    @Override
    public @NonNull RenderPassBackend createRenderPass(final RenderPassDescriptor descriptor) {
        RenderPassDescriptor.Attachment<Optional<Vector4fc>> colorAttachment = descriptor.colorAttachments().getFirst();
        GpuTextureView colorTexture = colorAttachment.textureView();
        Optional<Vector4fc> colorClear = colorAttachment.clearValue();
        MetalGpuTexture colorTex = castTexture(colorTexture.texture());
        Vector4fc pendingColor = pendingColorClears.get(colorTex);
        if (pendingColor != null && isFullTextureView(colorTexture) && colorClear.isEmpty()) {
            pendingColorClears.remove(colorTex);
            colorClear = Optional.of(pendingColor);
        } else if (pendingColor != null && colorClear.isEmpty()) {
            flushPendingClear(colorTex);
        } else {
            pendingColorClears.remove(colorTex);
        }

        RenderPassDescriptor.Attachment<OptionalDouble> depthAttachment = descriptor.depthAttachment();
        GpuTextureView depthTexture = depthAttachment == null ? null : depthAttachment.textureView();
        OptionalDouble depthClear = depthAttachment == null ? OptionalDouble.empty() : depthAttachment.clearValue();
        if (depthAttachment != null) {
            MetalGpuTexture metalDepth = castTexture(depthTexture.texture());
            Double pendingDepth = pendingDepthClears.get(metalDepth);
            if (pendingDepth != null && isFullTextureView(depthTexture) && depthClear.isEmpty()) {
                pendingDepthClears.remove(metalDepth);
                depthClear = OptionalDouble.of(pendingDepth);
            } else if (pendingDepth != null && depthClear.isEmpty()) {
                flushPendingClear(metalDepth);
            } else {
                pendingDepthClears.remove(metalDepth);
            }
        }

        castTexture(colorTexture.texture()).markContentsDirty();
        if (depthTexture != null) {
            castTexture(depthTexture.texture()).markContentsDirty();
        }

        assert descriptor.renderArea != null;
        RenderPass.RenderArea renderArea = descriptor.renderArea;
        MetalRenderPass renderPass = new MetalRenderPass(
                device,
                this,
                descriptor.label(),
                colorTexture,
                depthTexture,
                renderArea,
                colorClear.orElse(null),
                depthClear.isPresent(),
                depthClear.orElse(0.0)
        );
        currentRenderPass = renderPass;
        renderPass.pushDebugGroup(descriptor.label());
        return renderPass;
    }

    @Override
    public void submitRenderPass() {
        if (currentRenderPass != null) {
            try {
                currentRenderPass.end();
            } finally {
                currentRenderPass.popDebugGroup();
            }
        }
        currentRenderPass = null;
    }

    void presentTextureToDrawable(final MemorySegment drawable, final GpuTextureView textureView) {
        MetalGpuTexture source = castTexture(textureView.texture());
        flushPendingClear(source);
        submitRenderPass();
        endEncoder();
        MTLCommandBuffer commandBuffer = commandBuffer();
        commandBuffer.encodePresentTextureToDrawable(drawable, source.nativeHandle(), fence);
    }

    @Override
    public void clearColorTexture(final @NonNull GpuTexture colorTexture, final @NonNull Vector4fc clearColor) {
        pendingColorClears.put(castTexture(colorTexture), new Vector4f(clearColor));
    }

    @Override
    public void clearColorAndDepthTextures(final @NonNull GpuTexture colorTexture, final @NonNull Vector4fc clearColor, final @NonNull GpuTexture depthTexture, final double clearDepth) {
        MetalGpuTexture color = castTexture(colorTexture);
        MetalGpuTexture depth = castTexture(depthTexture);
        pendingColorClears.put(color, new Vector4f(clearColor));
        pendingDepthClears.put(depth, clearDepth);
    }

    @Override
    public void clearColorAndDepthTextures(
            final @NonNull GpuTexture colorTexture,
            final @NonNull Vector4fc clearColor,
            final @NonNull GpuTexture depthTexture,
            final double clearDepth,
            final int regionX,
            final int regionY,
            final int regionWidth,
            final int regionHeight
    ) {
        MetalGpuTexture color = castTexture(colorTexture);
        MetalGpuTexture depth = castTexture(depthTexture);
        Vector4fc clearColorCopy = new Vector4f(clearColor);
        if (isFullTextureRegion(color, depth, regionX, regionY, regionWidth, regionHeight)) {
            pendingColorClears.put(color, clearColorCopy);
            pendingDepthClears.put(depth, clearDepth);
            return;
        }
        color.markContentsDirty();
        depth.markContentsDirty();
        submitRenderPass();
        endEncoder();
        commandBuffer().clearColorDepthTexturesRegion(
                color.nativeHandle(),
                clearColorCopy.x(),
                clearColorCopy.y(),
                clearColorCopy.z(),
                clearColorCopy.w(),
                depth.nativeHandle(),
                clearDepth,
                regionX,
                regionY,
                regionWidth,
                regionHeight,
                fence
        );
    }

    @Override
    public void clearDepthTexture(final @NonNull GpuTexture depthTexture, final double clearDepth) {
        pendingDepthClears.put(castTexture(depthTexture), clearDepth);
    }

    @Override
    public void writeToBuffer(final GpuBufferSlice destination, final ByteBuffer data) {
        MetalGpuBuffer buffer = castBuffer(destination.buffer());
        int length = data.remaining();

        GpuBufferSlice staging = transientMemory.uploadStaging(data, 4L, GpuBuffer.USAGE_COPY_SRC);
        MetalGpuBuffer stagingBuffer = castBuffer(staging.buffer());

        MTLBlitCommandEncoder blit = blitCommandEncoder();
        blit.copyFromBufferToBuffer(
                stagingBuffer.nativeHandle(),
                staging.offset(),
                buffer.nativeHandle(),
                destination.offset(),
                length
        );
        endEncoder();
    }

    @Override
    public void copyToBuffer(final GpuBufferSlice source, final GpuBufferSlice target) {
        MetalGpuBuffer sourceBuffer = castBuffer(source.buffer());
        MetalGpuBuffer targetBuffer = castBuffer(target.buffer());
        MTLBlitCommandEncoder blit = blitCommandEncoder();
        blit.copyFromBufferToBuffer(
                sourceBuffer.nativeHandle(),
                source.offset(),
                targetBuffer.nativeHandle(),
                target.offset(),
                source.length()
        );
        endEncoder();
    }

    @Override
    public void writeToTexture(
            final @NonNull GpuTexture destination,
            final @NonNull ByteBuffer source,
            final int mipLevel,
            final int depthOrLayer,
            final int destX,
            final int destY,
            final int width,
            final int height
    ) {
        MetalGpuTexture metalDst = (MetalGpuTexture) destination;
        flushPendingClearForWrite(metalDst);

        int pixelSize = metalDst.pixelSize();
        int rowBytes = width * pixelSize;
        int bytesPerImage = rowBytes * height;
        GpuBufferSlice slice = transientMemory.uploadStaging(source.limit(bytesPerImage), pixelSize, GpuBuffer.USAGE_COPY_SRC);

        MTLBlitCommandEncoder blit = blitCommandEncoder();
        blit.copyFromBufferToTexture(
                castBuffer(slice.buffer()).nativeHandle(),
                slice.offset(),
                metalDst.nativeHandle(),
                mipLevel,
                depthOrLayer,
                destX,
                destY,
                width,
                height,
                rowBytes,
                bytesPerImage
        );
        endEncoder();
    }

    @Override
    public void copyBufferToTexture(
            final @NonNull GpuBufferSlice source,
            final int sourceX,
            final int sourceY,
            final int sourceWidth,
            final int sourceHeight,
            final @NonNull GpuTexture destination,
            final int destinationX,
            final int destinationY,
            final int copyWidth,
            final int copyHeight,
            final int mipLevel,
            final int arrayLayer
    ) {
        MetalGpuTexture metalDst = (MetalGpuTexture) destination;
        flushPendingClearForWrite(metalDst);

        int texelSize = destination.getFormat().blockSize();
        long skipBytes = (sourceX + (long) sourceY * sourceWidth) * texelSize;
        long rowBytes = (long) sourceWidth * texelSize;

        MTLBlitCommandEncoder blit = blitCommandEncoder();
        blit.copyFromBufferToTexture(
                castBuffer(source.buffer()).nativeHandle(),
                source.offset() + skipBytes,
                metalDst.nativeHandle(),
                mipLevel,
                arrayLayer,
                destinationX,
                destinationY,
                copyWidth,
                copyHeight,
                rowBytes,
                rowBytes * sourceHeight
        );
        endEncoder();
    }

    @Override
    public void copyTextureToBuffer(final @NonNull GpuTexture source, final @NonNull GpuBuffer destination, final long offset, final @NonNull Runnable callback, final int mipLevel) {
        copyTextureToBuffer(source, destination, offset, callback, mipLevel, 0, 0, source.getWidth(mipLevel), source.getHeight(mipLevel));
    }

    @Override
    public void copyTextureToBuffer(
            final @NonNull GpuTexture source,
            final @NonNull GpuBuffer destination,
            final long offset,
            final @NonNull Runnable callback,
            final int mipLevel,
            final int x,
            final int y,
            final int width,
            final int height
    ) {
        MetalGpuTexture texture = castTexture(source);
        flushPendingClear(texture);
        MetalGpuBuffer buffer = castBuffer(destination);
        int bytesPerPixel = texture.pixelSize();
        int rowBytes = width * bytesPerPixel;
        int bytesPerImage = rowBytes * height;

        MTLBlitCommandEncoder blit = blitCommandEncoder();
        blit.copyFromTextureToBuffer(
                texture.nativeHandle(),
                buffer.nativeHandle(),
                offset,
                mipLevel,
                0,
                x,
                y,
                width,
                height,
                rowBytes,
                bytesPerImage
        );

        endEncoder();
        queueForDestroy(callback);
    }

    @Override
    public void copyTextureToTexture(
            final @NonNull GpuTexture source,
            final @NonNull GpuTexture destination,
            final int mipLevel,
            final int destX,
            final int destY,
            final int sourceX,
            final int sourceY,
            final int width,
            final int height
    ) {
        MetalGpuTexture srcTexture = castTexture(source);
        MetalGpuTexture dstTexture = castTexture(destination);
        flushPendingClear(srcTexture);
        flushPendingClearForWrite(dstTexture);
        MTLBlitCommandEncoder blit = blitCommandEncoder();
        blit.copyFromTextureToTexture(
                srcTexture.nativeHandle(),
                dstTexture.nativeHandle(),
                mipLevel,
                sourceX,
                sourceY,
                destX,
                destY,
                width,
                height
        );
        endEncoder();
    }

    @Override
    public @NonNull GpuFence createFence() {
        return new MetalFence(this, currentSubmitIndex);
    }

    void queueForDestroy(final Runnable destroyAction) {
        destroyQueue.add(destroyAction);
    }

    boolean awaitSubmitCompletion(final long submitIndex, final long timeoutMs) {
        if (submitIndex == currentSubmitIndex) {
            throw new IllegalStateException("Cannot wait on a fence for the current submit");
        }
        for (InFlight f : inFlight) {
            if (f != null && f.index == submitIndex) {
                return MetalNativeBridge.metallum_semaphore_wait(f.completedSemaphore, Math.max(timeoutMs, 0L)) == 0;
            }
        }
        return true;
    }

    void close() {
        submitRenderPass();
        endEncoder();
        for (int slot = 0; slot < inFlight.length; slot++) {
            InFlight f = inFlight[slot];
            if (f != null) {
                f.buffer.close();
                inFlight[slot] = null;
            }
        }
        for (int slot = 0; slot < submitSemaphores.length; slot++) {
            if (!MetalNativeBridge.isNullHandle(submitSemaphores[slot])) {
                MetalNativeBridge.metallum_release_object(submitSemaphores[slot]);
                submitSemaphores[slot] = MemorySegment.NULL;
            }
        }
        if (commandBuffer != null) {
            commandBuffer.close();
            commandBuffer = null;
        }
        transientMemory.close();
        device.queueResourceRelease(fence);
        destroyQueue.close();
    }

    void waitForSubmittedGpuWork() {
        if (commandBuffer != null || currentRenderPass != null || currentEncoder != null) {
            submit();
        } else {
            endEncoder();
        }
        long latestSubmit = currentSubmitIndex - 1L;
        if (latestSubmit >= MAX_SUBMITS_IN_FLIGHT) {
            awaitSubmitCompletion(latestSubmit, Long.MAX_VALUE);
        }
    }

    @Override
    public void writeTimestamp(final @NonNull GpuQueryPool pool, final int index) {
        if (pool instanceof MetalGpuQueryPool metalPool && index >= 0 && index < pool.size()) {
            metalPool.setValue(index, device.getTimestampNow());
        }
    }

    static MetalGpuBuffer castBuffer(final GpuBuffer buffer) {
        return (MetalGpuBuffer) buffer;
    }

    static MetalGpuTexture castTexture(final GpuTexture texture) {
        return (MetalGpuTexture) texture;
    }

    private void flushPendingClearForWrite(final MetalGpuTexture texture) {
        flushPendingClear(texture);
        texture.markContentsDirty();
    }

    void flushPendingClear(final MetalGpuTexture texture) {
        Vector4fc colorClear = pendingColorClears.remove(texture);
        Double depthClear = pendingDepthClears.remove(texture);
        if (colorClear == null && depthClear == null) {
            return;
        }

        if (texture.clearIsRedundant(colorClear, depthClear)) {
            return;
        }

        endEncoder();
        MTLRenderCommandEncoder encoder = commandBuffer().makeRenderCommandEncoder(
                colorClear != null ? texture.nativeHandle() : null,
                depthClear != null ? texture.nativeHandle() : null,
                1.0, 1.0,
                colorClear != null ? 1 : 0,
                colorClear != null ? colorClear.x() : 0.0F,
                colorClear != null ? colorClear.y() : 0.0F,
                colorClear != null ? colorClear.z() : 0.0F,
                colorClear != null ? colorClear.w() : 0.0F,
                depthClear != null ? 1 : 0,
                depthClear != null ? depthClear : 1.0
        );
        encoder.waitForFence(fence, MTLRenderStages.VertexAndFragment);
        currentEncoder = encoder;
        texture.recordMaterializedClear(colorClear, depthClear);
    }

    private static boolean isFullTextureView(final GpuTextureView textureView) {
        return textureView.baseMipLevel() == 0
                && textureView.mipLevels() >= textureView.texture().getMipLevels()
                && textureView.texture().getDepthOrLayers() == 1;
    }

    private static boolean isFullTextureRegion(
            final MetalGpuTexture color,
            final MetalGpuTexture depth,
            final int x,
            final int y,
            final int width,
            final int height
    ) {
        return x == 0
                && y == 0
                && width == color.getWidth(0)
                && height == color.getHeight(0)
                && width == depth.getWidth(0)
                && height == depth.getHeight(0);
    }

    private record InFlight(long index, MTLCommandBuffer buffer, MemorySegment completedSemaphore) {
    }
}
