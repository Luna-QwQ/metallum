package com.metallum.client.metal.render;

import com.metallum.client.metal.render.mtl.MTLBlitCommandEncoder;
import com.metallum.client.metal.render.mtl.MTLCommandBuffer;
import com.metallum.client.metal.render.mtl.MTLCommandEncoder;
import com.metallum.client.metal.render.mtl.MTLRenderCommandEncoder;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

@Environment(EnvType.CLIENT)
final class MetalCommandEncoder implements CommandEncoderBackend {
    public static final int MAX_SUBMITS_IN_FLIGHT = 3;
    private final MetalDevice device;
    private long currentSubmitIndex = MAX_SUBMITS_IN_FLIGHT;
    private long completedSubmitIndex = 0L;
    private final MetalDestructionQueue destroyQueue = new MetalDestructionQueue(MAX_SUBMITS_IN_FLIGHT + 1);
    private final Map<MetalGpuTexture, Vector4fc> pendingColorClears = new IdentityHashMap<>();
    private final Map<MetalGpuTexture, Double> pendingDepthClears = new IdentityHashMap<>();
    private final Map<Long, MTLCommandBuffer> inFlightCommandBuffers = new java.util.HashMap<>();
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
        currentEncoder = encoder;
        return encoder;
    }

    void endEncoder() {
        if (currentEncoder != null) {
            currentEncoder.endEncoding();
            currentEncoder = null;
        }
        renderColorAttachment = MemorySegment.NULL;
        renderDepthAttachment = MemorySegment.NULL;
    }

    @Override
    public void submit() {
        if (commandBuffer == null) {
            return;
        }

        submitRenderPass();
        endEncoder();

        commandBuffer.commit();
        inFlightCommandBuffers.put(currentSubmitIndex, commandBuffer);
        commandBuffer = null;
        currentSubmitIndex++;

        if (!awaitSubmitCompletion(currentSubmitIndex - MAX_SUBMITS_IN_FLIGHT, 5000L)) {
            throw new IllegalStateException("5s timeout reached when waiting for Metal submit completion");
        }

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
        currentEncoder = encoder;
        renderColorAttachment = colorAttachment;
        renderDepthAttachment = depthAttachment;
        return encoder;
    }

    @Override
    public @NonNull RenderPassBackend createRenderPass(final RenderPassDescriptor descriptor) {
        List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> colorAttachments = descriptor.colorAttachments();
        if (colorAttachments.isEmpty() || colorAttachments.getFirst() == null) {
            throw new UnsupportedOperationException("Metal render passes currently require one color attachment");
        }

        RenderPassDescriptor.Attachment<Optional<Vector4fc>> colorAttachment = colorAttachments.getFirst();
        GpuTextureView colorTexture = colorAttachment.textureView();
        Optional<Vector4fc> colorClear = colorAttachment.clearValue();
        Vector4fc pendingColor = pendingColorClears.remove(castTexture(colorTexture.texture()));
        if (pendingColor != null && isFullTextureView(colorTexture) && colorClear.isEmpty()) {
            colorClear = Optional.of(pendingColor);
        } else if (pendingColor != null) {
            flushPendingClear(castTexture(colorTexture.texture()));
        }

        RenderPassDescriptor.Attachment<OptionalDouble> depthAttachment = descriptor.depthAttachment();
        GpuTextureView depthTexture = depthAttachment == null ? null : depthAttachment.textureView();
        OptionalDouble depthClear = depthAttachment == null ? OptionalDouble.empty() : depthAttachment.clearValue();
        if (depthAttachment != null) {
            MetalGpuTexture metalDepth = castTexture(depthTexture.texture());
            Double pendingDepth = pendingDepthClears.remove(metalDepth);
            if (pendingDepth != null && isFullTextureView(depthTexture) && depthClear.isEmpty()) {
                depthClear = OptionalDouble.of(pendingDepth);
            } else if (pendingDepth != null) {
                flushPendingClear(metalDepth);
            }
        }

        RenderPass.RenderArea renderArea = descriptor.renderArea != null
                ? descriptor.renderArea
                : new RenderPass.RenderArea(0, 0, colorTexture.getWidth(0), colorTexture.getHeight(0));
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
                regionHeight
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

        try (MetalGpuBuffer stagingBuffer = createStagingBuffer(data)) {
            MTLBlitCommandEncoder blit = blitCommandEncoder();
            blit.copyFromBufferToBuffer(
                    stagingBuffer.nativeHandle(),
                    0L,
                    buffer.nativeHandle(),
                    destination.offset(),
                    length
            );

            endEncoder();
            queueForDestroy(stagingBuffer::close);
        }
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
            final NativeImage source,
            final int mipLevel,
            final int depthOrLayer,
            final int destX,
            final int destY,
            final int width,
            final int height,
            final int sourceX,
            final int sourceY
    ) {
        int stagingBufferSize = source.getWidth() * source.getHeight() * destination.getFormat().pixelSize();
        int texelSize = destination.getFormat().pixelSize();
        int skipTexels = sourceX + sourceY * source.getWidth();
        long skipBytes = (long) skipTexels * texelSize;

        ByteBuffer sourceBytes = MemoryUtil.memByteBuffer(source.getPointer(), stagingBufferSize);
        writeToTexture((MetalGpuTexture) destination, sourceBytes, skipBytes, mipLevel, depthOrLayer, destX, destY, width, height, source.getWidth(), source.getHeight());
    }

    @Override
    public void writeToTexture(
            final @NonNull GpuTexture destination,
            final @NonNull ByteBuffer source,
            final NativeImage.@NonNull Format format,
            final int mipLevel,
            final int depthOrLayer,
            final int destX,
            final int destY,
            final int width,
            final int height
    ) {
        writeToTexture((MetalGpuTexture) destination, source, 0L, mipLevel, depthOrLayer, destX, destY, width, height, width, height);
    }

    private void writeToTexture(
            final MetalGpuTexture destination,
            final ByteBuffer source,
            final long sourceOffset,
            final int mipLevel,
            final int depthOrLayer,
            final int destX,
            final int destY,
            final int width,
            final int height,
            final int sourceWidth,
            final int sourceHeight
    ) {
        flushPendingClear(destination);

        int pixelSize = destination.pixelSize();
        long bytesPerRow = (long) sourceWidth * pixelSize;
        long bytesPerImage = bytesPerRow * sourceHeight;

        try (MetalGpuBuffer stagingBuffer = createStagingBuffer(source)) {
            MTLBlitCommandEncoder blit = blitCommandEncoder();
            blit.copyFromBufferToTexture(
                    stagingBuffer.nativeHandle(),
                    sourceOffset,
                    destination.nativeHandle(),
                    mipLevel,
                    depthOrLayer,
                    destX,
                    destY,
                    width,
                    height,
                    bytesPerRow,
                    bytesPerImage
            );

            endEncoder();
        }
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
        flushPendingClear(dstTexture);
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
        if (completedSubmitIndex >= submitIndex) {
            return true;
        }
        if (submitIndex == currentSubmitIndex) {
            throw new IllegalStateException("Cannot wait on a fence for the current submit");
        }

        MTLCommandBuffer commandBuffer = inFlightCommandBuffers.get(submitIndex);
        if (commandBuffer == null) {
            releaseCompletedCommandBuffers(submitIndex);
            return true;
        }

        if (commandBuffer.isCompleted() || commandBuffer.waitUntilCompleted(timeoutMs)) {
            releaseCompletedCommandBuffers(submitIndex);
            return true;
        }
        return false;
    }

    void close() {
        submitRenderPass();
        endEncoder();
        for (MTLCommandBuffer commandBuffer : inFlightCommandBuffers.values()) {
            commandBuffer.close();
        }
        inFlightCommandBuffers.clear();
        if (commandBuffer != null) {
            commandBuffer.close();
            commandBuffer = null;
        }
        destroyQueue.close();
    }

    void waitForSubmittedGpuWork() {
        if (commandBuffer != null || currentRenderPass != null || currentEncoder != null) {
            submit();
        } else {
            endEncoder();
        }
        long latestSubmit = currentSubmitIndex - 1L;
        if (latestSubmit > completedSubmitIndex) {
            awaitSubmitCompletion(latestSubmit, Long.MAX_VALUE);
        }
    }

    private void releaseCompletedCommandBuffers(final long completedSubmitIndex) {
        this.completedSubmitIndex = Math.max(this.completedSubmitIndex, completedSubmitIndex);
        inFlightCommandBuffers.entrySet().removeIf(entry -> {
            if (entry.getKey() > this.completedSubmitIndex) {
                return false;
            }
            entry.getValue().close();
            return true;
        });
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

    void flushPendingClear(final MetalGpuTexture texture) {
        Vector4fc colorClear = pendingColorClears.remove(texture);
        Double depthClear = pendingDepthClears.remove(texture);
        if (colorClear == null && depthClear == null) {
            return;
        }

        endEncoder();
        currentEncoder = commandBuffer().makeRenderCommandEncoder(
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

    private MetalGpuBuffer createStagingBuffer(final ByteBuffer source) {
        int length = source.remaining();
        MetalGpuBuffer stagingBuffer = new MetalGpuBuffer(
                device,
                GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_COPY_SRC,
                length
        );
        ByteBuffer staging = stagingBuffer.fullStorageView().order(ByteOrder.nativeOrder());
        staging.limit(length);
        staging.put(source);
        return stagingBuffer;
    }
}
