package com.metallum.client.metal.render;

import com.metallum.client.metal.optimization.MetalTerrainFaceCulling;
import com.metallum.client.metal.optimization.MetalTerrainVertexPacking;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLCommandQueue;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.textures.*;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Environment(EnvType.CLIENT)
final class MetalDevice implements GpuDeviceBackend {
    private final MetalCocoaBootstrap.BootstrapContext bootstrap;
    private final GpuDebugOptions debugOptions;
    private final MetalCommandEncoder commandEncoder;
    private final DeviceInfo deviceInfo;
    public final MTLCommandQueue commandQueue;
    private final MetalBufferPool bufferPool = new MetalBufferPool();
    private final Map<RenderPipeline, MetalCompiledRenderPipeline> compiledPipelines = new ConcurrentHashMap<>();
    private volatile ShaderSource activeShaderSource;

    MetalDevice(
            final ShaderSource defaultShaderSource,
            final MetalCocoaBootstrap.BootstrapContext bootstrap,
            final GpuDebugOptions debugOptions
    ) {
        this.activeShaderSource = defaultShaderSource;
        this.bootstrap = bootstrap;
        this.debugOptions = debugOptions;
        MetalNativeBridge.INSTANCE.metallum_set_debug_labels_enabled(this.useLabels());
        this.commandQueue = MTLCommandQueue.create(bootstrap.device());
        MetalTerrainVertexPacking.setEnabled(true); //todo config
        MetalTerrainFaceCulling.setEnabled(true);
        this.commandEncoder = new MetalCommandEncoder(this);
        this.deviceInfo = buildDeviceInfo(bootstrap);
    }

    @Override
    public GpuSurfaceBackend createSurface(final long windowHandle) {
        return new MetalSurface(windowHandle, this, this.bootstrap);
    }

    @Override
    public MetalCommandEncoder createCommandEncoder() {
        return this.commandEncoder;
    }

    @Override
    public GpuSampler createSampler(
            final AddressMode addressModeU,
            final AddressMode addressModeV,
            final FilterMode minFilter,
            final FilterMode magFilter,
            final int maxAnisotropy,
            final OptionalDouble maxLod
    ) {
        return new MetalGpuSampler(this, addressModeU, addressModeV, minFilter, magFilter, maxAnisotropy, maxLod);
    }

    @Override
    public GpuTexture createTexture(
            @Nullable final Supplier<String> label,
            @GpuTexture.Usage final int usage,
            final GpuFormat format,
            final int width,
            final int height,
            final int depthOrLayers,
            final int mipLevels
    ) {
        return this.createTexture(this.resolveDebugLabel(label), usage, format, width, height, depthOrLayers, mipLevels);
    }

    @Override
    public GpuTexture createTexture(
            @Nullable final String label,
            @GpuTexture.Usage final int usage,
            final GpuFormat format,
            final int width,
            final int height,
            final int depthOrLayers,
            final int mipLevels
    ) {
        return new MetalGpuTexture(this, usage, label == null ? "" : label, format, width, height, depthOrLayers, mipLevels);
    }

    @Override
    public GpuTextureView createTextureView(final GpuTexture texture) {
        return this.createTextureView(texture, 0, texture.getMipLevels());
    }

    @Override
    public GpuTextureView createTextureView(final GpuTexture texture, final int baseMipLevel, final int mipLevels) {
        return new MetalGpuTextureView(texture, baseMipLevel, mipLevels);
    }

    @Override
    public GpuBuffer createBuffer(@Nullable final Supplier<String> label, @GpuBuffer.Usage final int usage, final long size) {
        return new MetalGpuBuffer(this, usage, size);
    }

    @Override
    public GpuBuffer createBuffer(@Nullable final Supplier<String> label, @GpuBuffer.Usage final int usage, final ByteBuffer data) {
        MetalGpuBuffer buffer = (MetalGpuBuffer) this.createBuffer(label, usage | GpuBuffer.USAGE_COPY_DST, data.remaining());
        this.commandEncoder.writeToBuffer(buffer.slice(), data.duplicate());
        return buffer;
    }

    @Override
    public List<String> getLastDebugMessages() {
        return List.of();
    }

    @Override
    public boolean isDebuggingEnabled() {
        return this.debugOptions.logLevel() > 0 || this.debugOptions.useLabels() || this.debugOptions.useValidationLayers();
    }

    boolean useLabels() {
        return this.debugOptions.useLabels();
    }

    @Override
    public CompiledRenderPipeline precompilePipeline(final RenderPipeline pipeline, @Nullable final ShaderSource shaderSource) {
        ShaderSource effectiveSource = shaderSource == null ? this.activeShaderSource : shaderSource;
        if (shaderSource != null) {
            this.activeShaderSource = shaderSource;
        }
        MetalCompiledRenderPipeline compiled = MetalCrossShaderCompiler.compile(pipeline, effectiveSource);
        this.compiledPipelines.put(pipeline, compiled);
        return compiled;
    }

    @Override
    public void clearPipelineCache() {
        this.compiledPipelines.clear();
    }

    @Override
    public void close() {
        this.waitForSubmittedGpuWork();
        this.commandEncoder.close();
        this.bufferPool.close();
        try {
            MetalNativeBridge.INSTANCE.metallum_NSView_clearLayer(this.bootstrap.cocoaView());
        } catch (Throwable ignored) {
        }
        this.commandQueue.close();
        MetalNativeBridge.INSTANCE.metallum_release_object(this.bootstrap.device());
        MetalTerrainFaceCulling.setEnabled(false);
        MetalTerrainVertexPacking.setEnabled(false);
    }

    @Override
    public GpuQueryPool createTimestampQueryPool(final int size) {
        return new MetalGpuQueryPool(size);
    }

    @Override
    public long getTimestampNow() {
        return System.nanoTime();
    }

    @Override
    public DeviceInfo getDeviceInfo() {
        return this.deviceInfo;
    }

    MemorySegment metalDeviceHandle() {
        return this.bootstrap.device();
    }

    void waitForSubmittedGpuWork() {
        this.commandEncoder.waitForSubmittedGpuWork();
    }

    void queueResourceRelease(final MemorySegment handle) {
        this.commandEncoder.queueForDestroy(() -> MetalNativeBridge.INSTANCE.metallum_release_object(handle));
    }

    @Nullable
    MemorySegment acquireReusableBuffer(final long size, final long resourceOptions) {
        return this.bufferPool.acquire(size, resourceOptions);
    }

    long allocationSize(final long requestedSize) {
        return this.bufferPool.allocationSize(requestedSize);
    }

    void queueBufferRecycle(final MemorySegment handle, final long size, final long resourceOptions) {
        this.commandEncoder.queueForDestroy(() -> this.bufferPool.recycle(handle, size, resourceOptions));
    }

    MetalCompiledRenderPipeline getOrCompilePipeline(final RenderPipeline pipeline) {
        MetalCompiledRenderPipeline cached = this.compiledPipelines.get(pipeline);
        if (cached != null) {
            return cached;
        }

        MetalCompiledRenderPipeline compiled = MetalCrossShaderCompiler.compile(pipeline, this.activeShaderSource);
        this.compiledPipelines.put(pipeline, compiled);
        return compiled;
    }

    private static DeviceInfo buildDeviceInfo(final MetalCocoaBootstrap.BootstrapContext bootstrap) {
        DeviceType type = DeviceType.INTEGRATED;
        Set<String> underlyingExtensions = Set.of("CAMetalLayer", "MTLDevice", "Runtime MSL");
        String osVersion = System.getProperty("os.version", "").trim();
        String driverDescription = "macOS " + osVersion;
        return new DeviceInfo(
                bootstrap.deviceName(),
                "Apple",
                driverDescription,
                true,
                "Metal",
                1.0F,
                new DeviceLimits(16, 256, 16384, 1L << 30, 1),
                new DeviceFeatures(false, false, false, false, true),
                underlyingExtensions,
                new HintsAndWorkarounds(false, false),
                type
        );
    }

    @Nullable
    private String resolveDebugLabel(@Nullable final Supplier<String> label) {
        return this.useLabels() && label != null ? label.get() : null;
    }
}
