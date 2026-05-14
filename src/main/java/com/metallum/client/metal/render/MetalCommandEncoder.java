package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
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
    public static final int MAX_SUBMITS_IN_FLIGHT = 2;
    private final MetalDevice device;
    private long currentSubmitIndex = 2L;
    private long completedSubmitIndex = 0L;
    private final MetalDestructionQueue destroyQueue = new MetalDestructionQueue(MAX_SUBMITS_IN_FLIGHT + 1);
    private final Map<MetalGpuTexture, PendingTextureClear> pendingTextureClears = new IdentityHashMap<>();
    private final Map<Long, MemorySegment> inFlightCommandBuffers = new java.util.HashMap<>();
    @Nullable
    private MetalRenderPass currentRenderPass;
    private MemorySegment commandBuffer = MemorySegment.NULL;
    private MemorySegment blitEncoder = MemorySegment.NULL;
    private MemorySegment renderEncoder = MemorySegment.NULL;
    private MemorySegment renderColorAttachment = MemorySegment.NULL;
    private MemorySegment renderDepthAttachment = MemorySegment.NULL;

    record ClearColor(float red, float green, float blue, float alpha) {
        static final ClearColor TRANSPARENT = new ClearColor(0.0F, 0.0F, 0.0F, 0.0F);

        static ClearColor copy(final Vector4fc color) {
            return new ClearColor(color.x(), color.y(), color.z(), color.w());
        }
    }

    MetalCommandEncoder(final MetalDevice device) {
        this.device = device;
    }

    MemorySegment commandBuffer() {
        if (!MetalProbe.isNullHandle(commandBuffer)) {
            return commandBuffer;
        }
        commandBuffer = device.commandQueue.makeCommandBuffer(
                device.useLabels() ? "Metallum frame " + currentSubmitIndex : null
        );
        return commandBuffer;
    }

    MemorySegment blitCommandEncoder() {
        this.endRenderEncoder();
        MemorySegment encoder = MetalNativeBridge.INSTANCE.MTLCommandBuffer_makeBlitCommandEncoder(this.commandBuffer());
        if (MetalProbe.isNullHandle(encoder)) {
            throw new IllegalStateException("Failed to create MTLBlitCommandEncoder");
        }
        this.blitEncoder = encoder;
        return encoder;
    }

    void endBlitEncoder() {
        if (MetalProbe.isNullHandle(this.blitEncoder)) {
            return;
        }
        MetalNativeBridge.INSTANCE.MTLCommandEncoder_endEncoding(this.blitEncoder);
        MetalNativeBridge.INSTANCE.metallum_release_object(this.blitEncoder);
        this.blitEncoder = MemorySegment.NULL;
    }

    @Override
    public void submit() {
        if (MetalProbe.isNullHandle(this.commandBuffer)) {
            return;
        }

        this.submitRenderPass();
        this.endBlitEncoder();
        this.endRenderEncoder();
        if (!this.awaitSubmitCompletion(this.currentSubmitIndex - MAX_SUBMITS_IN_FLIGHT, 5000L)) {
            throw new IllegalStateException("5s timeout reached when waiting for Metal submit completion");
        }

        MemorySegment submittedCommandBuffer = this.commandBuffer();
        MetalNativeBridge.INSTANCE.MTLCommandBuffer_commit(submittedCommandBuffer);
        this.inFlightCommandBuffers.put(this.currentSubmitIndex, submittedCommandBuffer);
        this.commandBuffer = MemorySegment.NULL;
        this.currentSubmitIndex++;
        this.destroyQueue.rotate();
    }

    MemorySegment renderCommandEncoder(
            final MetalGpuTextureView colorTextureView,
            @Nullable final MetalGpuTextureView depthTextureView,
            final int viewportWidth,
            final int viewportHeight,
            final Optional<ClearColor> clearColor,
            final OptionalDouble clearDepth
    ) {
        this.endBlitEncoder();
        MemorySegment colorAttachment = colorTextureView.nativeHandle();
        MemorySegment depthAttachment = depthTextureView == null ? MemorySegment.NULL : depthTextureView.nativeHandle();
        if (clearColor.isEmpty()
                && clearDepth.isEmpty()
                && !MetalProbe.isNullHandle(this.renderEncoder)
                && MetalPipelineSupport.sameHandle(this.renderColorAttachment, colorAttachment)
                && MetalPipelineSupport.sameHandle(this.renderDepthAttachment, depthAttachment)) {
            return this.renderEncoder;
        }

        this.endRenderEncoder();
        ClearColor colorClear = clearColor.orElse(ClearColor.TRANSPARENT);
        MemorySegment encoder = MetalNativeBridge.INSTANCE.MTLCommandBuffer_makeRenderCommandEncoder(
                this.commandBuffer(),
                colorAttachment,
                depthAttachment,
                viewportWidth,
                viewportHeight,
                clearColor.isPresent() ? 1 : 0,
                colorClear.red(),
                colorClear.green(),
                colorClear.blue(),
                colorClear.alpha(),
                clearDepth.isPresent() ? 1 : 0,
                clearDepth.orElse(1.0)
        );
        if (MetalProbe.isNullHandle(encoder)) {
            return null;
        }
        this.renderEncoder = encoder;
        this.renderColorAttachment = colorAttachment;
        this.renderDepthAttachment = depthAttachment;
        return encoder;
    }

    void endRenderEncoder() {
        if (MetalProbe.isNullHandle(this.renderEncoder)) {
            return;
        }
        MetalNativeBridge.INSTANCE.MTLCommandEncoder_endEncoding(this.renderEncoder);
        MetalNativeBridge.INSTANCE.metallum_release_object(this.renderEncoder);
        this.renderEncoder = MemorySegment.NULL;
        this.renderColorAttachment = MemorySegment.NULL;
        this.renderDepthAttachment = MemorySegment.NULL;
    }

    @Override
    public @NonNull RenderPassBackend createRenderPass(final RenderPassDescriptor descriptor) {
        List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> colorAttachments = descriptor.colorAttachments();
        if (colorAttachments.isEmpty() || colorAttachments.getFirst() == null) {
            throw new UnsupportedOperationException("Metal render passes currently require one color attachment");
        }

        RenderPassDescriptor.Attachment<Optional<Vector4fc>> colorAttachment = colorAttachments.getFirst();
        GpuTextureView colorTexture = colorAttachment.textureView();
        Optional<ClearColor> resolvedColorClear = this.resolveColorAttachmentClear(colorTexture, toClearColor(colorAttachment.clearValue()));

        RenderPassDescriptor.Attachment<OptionalDouble> depthAttachment = descriptor.depthAttachment();
        GpuTextureView depthTexture = depthAttachment == null ? null : depthAttachment.textureView();
        OptionalDouble resolvedDepthClear = depthAttachment == null
                ? OptionalDouble.empty()
                : this.resolveDepthAttachmentClear(depthTexture, depthAttachment.clearValue());
        RenderPass.RenderArea renderArea = descriptor.renderArea != null
                ? descriptor.renderArea
                : new RenderPass.RenderArea(0, 0, colorTexture.getWidth(0), colorTexture.getHeight(0));
        MetalRenderPass renderPass = new MetalRenderPass(
                this.device,
                this,
                descriptor.label(),
                colorTexture,
                depthTexture,
                renderArea,
                resolvedColorClear,
                resolvedDepthClear
        );
        this.currentRenderPass = renderPass;
        renderPass.pushDebugGroup(descriptor.label());
        return renderPass;
    }

    @Override
    public void submitRenderPass() {
        if (this.currentRenderPass != null) {
            try {
                this.currentRenderPass.end();
            } finally {
                this.currentRenderPass.popDebugGroup();
            }
        }
        this.currentRenderPass = null;
    }

    @Override
    public void clearColorTexture(final @NonNull GpuTexture colorTexture, final @NonNull Vector4fc clearColor) {
        MetalGpuTexture texture = castTexture(colorTexture);
        this.deferColorClear(texture, ClearColor.copy(clearColor));
    }

    @Override
    public void clearColorAndDepthTextures(final @NonNull GpuTexture colorTexture, final @NonNull Vector4fc clearColor, final @NonNull GpuTexture depthTexture, final double clearDepth) {
        MetalGpuTexture color = castTexture(colorTexture);
        MetalGpuTexture depth = castTexture(depthTexture);
        this.deferColorClear(color, ClearColor.copy(clearColor));
        this.deferDepthClear(depth, clearDepth);
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
        ClearColor resolvedClearColor = ClearColor.copy(clearColor);
        if (isFullTextureRegion(color, depth, regionX, regionY, regionWidth, regionHeight)) {
            this.deferColorClear(color, resolvedClearColor);
            this.deferDepthClear(depth, clearDepth);
            return;
        }
        this.submitRenderPass();
        this.endBlitEncoder();
        this.endRenderEncoder();
        MetalNativeBridge.INSTANCE.MTLCommandBuffer_clearColorDepthTexturesRegion(
                this.commandBuffer(),
                color.nativeHandle(),
                resolvedClearColor.red(),
                resolvedClearColor.green(),
                resolvedClearColor.blue(),
                resolvedClearColor.alpha(),
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
        MetalGpuTexture texture = castTexture(depthTexture);
        this.deferDepthClear(texture, clearDepth);
    }

    @Override
    public void writeToBuffer(final GpuBufferSlice destination, final ByteBuffer data) {
        MetalGpuBuffer buffer = castBuffer(destination.buffer());
        int length = data.remaining();

        try (MetalGpuBuffer stagingBuffer = this.createStagingBuffer(data)) {
            MemorySegment blit = this.blitCommandEncoder();
            MetalNativeBridge.INSTANCE.MTLBlitCommandEncoder_copyFromBufferToBuffer(
                    blit,
                    stagingBuffer.nativeHandle(),
                    0L,
                    buffer.nativeHandle(),
                    destination.offset(),
                    length
            );

            this.endBlitEncoder();
            this.queueForDestroy(stagingBuffer::close);
        }
    }

    @Override
    public void copyToBuffer(final GpuBufferSlice source, final GpuBufferSlice target) {
        MetalGpuBuffer sourceBuffer = castBuffer(source.buffer());
        MetalGpuBuffer targetBuffer = castBuffer(target.buffer());
        MemorySegment blit = this.blitCommandEncoder();
        MetalNativeBridge.INSTANCE.MTLBlitCommandEncoder_copyFromBufferToBuffer(
                blit,
                sourceBuffer.nativeHandle(),
                source.offset(),
                targetBuffer.nativeHandle(),
                target.offset(),
                source.length()
        );
        this.endBlitEncoder();
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
        this.writeToTexture((MetalGpuTexture) destination, sourceBytes, skipBytes, mipLevel, depthOrLayer, destX, destY, width, height, source.getWidth(), source.getHeight());
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
        this.writeToTexture((MetalGpuTexture) destination, source, 0L, mipLevel, depthOrLayer, destX, destY, width, height, width, height);
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
        this.flushPendingClear(destination);

        int pixelSize = destination.pixelSize();
        long bytesPerRow = (long) sourceWidth * pixelSize;
        long bytesPerImage = bytesPerRow * sourceHeight;

        try (MetalGpuBuffer stagingBuffer = this.createStagingBuffer(source)) {
            MemorySegment blit = this.blitCommandEncoder();
            MetalNativeBridge.INSTANCE.MTLBlitCommandEncoder_copyFromBufferToTexture(
                    blit,
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

            this.endBlitEncoder();
        }
    }

    @Override
    public void copyTextureToBuffer(final @NonNull GpuTexture source, final @NonNull GpuBuffer destination, final long offset, final @NonNull Runnable callback, final int mipLevel) {
        this.copyTextureToBuffer(source, destination, offset, callback, mipLevel, 0, 0, source.getWidth(mipLevel), source.getHeight(mipLevel));
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
        this.flushPendingClear(texture);
        MetalGpuBuffer buffer = castBuffer(destination);
        int bytesPerPixel = texture.pixelSize();
        int rowBytes = width * bytesPerPixel;
        int bytesPerImage = rowBytes * height;

        MemorySegment blit = this.blitCommandEncoder();
        MetalNativeBridge.INSTANCE.MTLBlitCommandEncoder_copyFromTextureToBuffer(
                blit,
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

        this.endBlitEncoder();
        this.queueForDestroy(callback);
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
        this.flushPendingClear(srcTexture);
        this.flushPendingClear(dstTexture);
        MemorySegment blit = this.blitCommandEncoder();
        MetalNativeBridge.INSTANCE.MTLBlitCommandEncoder_copyFromTextureToTexture(
                blit,
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
        this.endBlitEncoder();

    }

    @Override
    public @NonNull GpuFence createFence() {
        return new MetalFence(this, this.currentSubmitIndex);
    }

    void queueForDestroy(final Runnable destroyAction) {
        this.destroyQueue.add(destroyAction);
    }

    boolean awaitSubmitCompletion(final long submitIndex, final long timeoutMs) {
        if (this.completedSubmitIndex >= submitIndex) {
            return true;
        }
        if (submitIndex == this.currentSubmitIndex) {
            throw new IllegalStateException("Cannot wait on a fence for the current submit");
        }

        MemorySegment commandBuffer = this.inFlightCommandBuffers.get(submitIndex);
        if (MetalProbe.isNullHandle(commandBuffer)) {
            this.releaseCompletedCommandBuffers(submitIndex);
            return true;
        }

        int result = MetalNativeBridge.INSTANCE.MTLCommandBuffer_isCompleted(commandBuffer);
        if (result == 1 || MetalNativeBridge.INSTANCE.MTLCommandBuffer_waitUntilCompleted(commandBuffer, Math.max(timeoutMs, 0L)) == 0) {
            this.releaseCompletedCommandBuffers(submitIndex);
            return true;
        }
        return false;
    }

    void close() {
        this.submitRenderPass();
        this.endBlitEncoder();
        this.endRenderEncoder();
        for (MemorySegment commandBuffer : this.inFlightCommandBuffers.values()) {
            MetalNativeBridge.INSTANCE.metallum_release_object(commandBuffer);
        }
        this.inFlightCommandBuffers.clear();
        if (!MetalProbe.isNullHandle(this.commandBuffer)) {
            MetalNativeBridge.INSTANCE.metallum_release_object(this.commandBuffer);
            this.commandBuffer = MemorySegment.NULL;
        }
        this.destroyQueue.close();
    }

    void waitForSubmittedGpuWork() {
        if (!MetalProbe.isNullHandle(this.commandBuffer) || this.currentRenderPass != null || !MetalProbe.isNullHandle(this.blitEncoder)) {
            this.submit();
        } else {
            this.endRenderEncoder();
        }
        long latestSubmit = this.currentSubmitIndex - 1L;
        if (latestSubmit > this.completedSubmitIndex) {
            this.awaitSubmitCompletion(latestSubmit, Long.MAX_VALUE);
        }
    }

    private void releaseCompletedCommandBuffers(final long completedSubmitIndex) {
        this.completedSubmitIndex = Math.max(this.completedSubmitIndex, completedSubmitIndex);
        this.inFlightCommandBuffers.entrySet().removeIf(entry -> {
            if (entry.getKey() > this.completedSubmitIndex) {
                return false;
            }
            MetalNativeBridge.INSTANCE.metallum_release_object(entry.getValue());
            return true;
        });
    }

    @Override
    public void writeTimestamp(final @NonNull GpuQueryPool pool, final int index) {
        if (pool instanceof MetalGpuQueryPool metalPool && index >= 0 && index < pool.size()) {
            metalPool.setValue(index, this.device.getTimestampNow());
        }
    }

    static MetalGpuBuffer castBuffer(final GpuBuffer buffer) {
        return (MetalGpuBuffer) buffer;
    }

    static MetalGpuTexture castTexture(final GpuTexture texture) {
        return (MetalGpuTexture) texture;
    }

    void flushPendingTextureViewClear(final GpuTextureView textureView) {
        this.flushPendingClear(castTexture(textureView.texture()));
    }

    boolean deferRenderPassClear(
            final GpuTextureView colorTexture,
            final Optional<ClearColor> clearColor,
            @Nullable final GpuTextureView depthTexture,
            final OptionalDouble clearDepth
    ) {
        if (clearColor.isPresent() && !isFullTextureView(colorTexture)) {
            return false;
        }
        if (clearDepth.isPresent() && (depthTexture == null || !isFullTextureView(depthTexture))) {
            return false;
        }
        clearColor.ifPresent(color -> this.deferColorClear(castTexture(colorTexture.texture()), color));
        if (clearDepth.isPresent()) {
            this.deferDepthClear(castTexture(depthTexture.texture()), clearDepth.getAsDouble());
        }
        return true;
    }

    private void deferColorClear(final MetalGpuTexture texture, final ClearColor clearColor) {
        PendingTextureClear state = this.pendingTextureClears.computeIfAbsent(texture, ignored -> new PendingTextureClear());
        state.color = Optional.of(clearColor);
    }

    private void deferDepthClear(final MetalGpuTexture texture, final double clearDepth) {
        PendingTextureClear state = this.pendingTextureClears.computeIfAbsent(texture, ignored -> new PendingTextureClear());
        state.depth = OptionalDouble.of(clearDepth);
    }

    private void clearTexture(
            final MemorySegment texture,
            final int clearColorEnabled,
            final float clearColorRed,
            final float clearColorGreen,
            final float clearColorBlue,
            final float clearColorAlpha,
            final int clearDepthEnabled,
            final double clearDepth
    ) {
        this.endBlitEncoder();
        this.endRenderEncoder();
        MemorySegment encoder = MetalNativeBridge.INSTANCE.MTLCommandBuffer_makeRenderCommandEncoder(
                this.commandBuffer(),
                clearColorEnabled != 0 ? texture : null,
                clearDepthEnabled != 0 ? texture : null,
                1.0,
                1.0,
                clearColorEnabled,
                clearColorRed,
                clearColorGreen,
                clearColorBlue,
                clearColorAlpha,
                clearDepthEnabled,
                clearDepth
        );
        if (MetalProbe.isNullHandle(encoder)) {
            return;
        }
        MetalNativeBridge.INSTANCE.MTLCommandEncoder_endEncoding(encoder);
        MetalNativeBridge.INSTANCE.metallum_release_object(encoder);
    }

    private void flushPendingClear(final MetalGpuTexture texture) {
        PendingTextureClear pending = this.pendingTextureClears.remove(texture);
        if (pending == null) {
            return;
        }
        if (pending.color.isPresent()) {
            this.clearTexture(
                    texture.nativeHandle(),
                    1,
                    pending.color.get().red(),
                    pending.color.get().green(),
                    pending.color.get().blue(),
                    pending.color.get().alpha(),
                    0,
                    1.0
            );
        }
        if (pending.depth.isPresent()) {
            this.clearTexture(
                    texture.nativeHandle(),
                    0,
                    0.0F,
                    0.0F,
                    0.0F,
                    0.0F,
                    1,
                    pending.depth.getAsDouble()
            );
        }
    }

    private Optional<ClearColor> resolveColorAttachmentClear(
            final GpuTextureView textureView,
            final Optional<ClearColor> explicitClear
    ) {
        MetalGpuTexture texture = castTexture(textureView.texture());
        PendingTextureClear pending = this.pendingTextureClears.get(texture);
        if (pending == null || pending.color.isEmpty()) {
            return explicitClear;
        }
        if (!isFullTextureView(textureView)) {
            this.flushPendingClear(texture);
            return explicitClear;
        }
        Optional<ClearColor> clear = explicitClear.isPresent() ? explicitClear : pending.color;
        pending.color = Optional.empty();
        this.removePendingIfEmpty(texture, pending);
        return clear;
    }

    private OptionalDouble resolveDepthAttachmentClear(
            final GpuTextureView textureView,
            final OptionalDouble explicitClear
    ) {
        MetalGpuTexture texture = castTexture(textureView.texture());
        PendingTextureClear pending = this.pendingTextureClears.get(texture);
        if (pending == null || pending.depth.isEmpty()) {
            return explicitClear;
        }
        if (!isFullTextureView(textureView)) {
            this.flushPendingClear(texture);
            return explicitClear;
        }
        OptionalDouble clear = explicitClear.isPresent() ? explicitClear : pending.depth;
        pending.depth = OptionalDouble.empty();
        this.removePendingIfEmpty(texture, pending);
        return clear;
    }

    private void removePendingIfEmpty(final MetalGpuTexture texture, final PendingTextureClear pending) {
        if (pending.color.isEmpty() && pending.depth.isEmpty()) {
            this.pendingTextureClears.remove(texture);
        }
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

    private static Optional<ClearColor> toClearColor(final Optional<Vector4fc> clearColor) {
        return clearColor.map(ClearColor::copy);
    }

    private static final class PendingTextureClear {
        Optional<ClearColor> color = Optional.empty();
        OptionalDouble depth = OptionalDouble.empty();
    }

    private MetalGpuBuffer createStagingBuffer(final ByteBuffer source) {
        int length = source.remaining();
        MetalGpuBuffer stagingBuffer = new MetalGpuBuffer(
                this.device,
                GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_COPY_SRC,
                length
        );
        ByteBuffer staging = stagingBuffer.fullStorageView().order(ByteOrder.nativeOrder());
        staging.limit(length);
        staging.put(source);
        return stagingBuffer;
    }
}
