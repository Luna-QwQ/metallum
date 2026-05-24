package com.metallum.client.metal.render.bridge;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

@Environment(EnvType.CLIENT)
public final class MetalNativeBridge {
    private static final String RESOURCE_PATH = "/natives/macos/libmetallum.dylib";
    private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT;
    private static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG;
    private static final ValueLayout.OfFloat FLOAT = ValueLayout.JAVA_FLOAT;
    private static final ValueLayout.OfDouble DOUBLE = ValueLayout.JAVA_DOUBLE;
    private static final Linker LINKER = Linker.nativeLinker();

    public static final MetalNativeBridge INSTANCE = loadNative();

    private final Arena libraryArena;
    private final MethodHandle createSystemDefaultDevice;
    private final MethodHandle copyDeviceName;
    private final MethodHandle NSWindowBackingScaleFactor;
    private final MethodHandle createMetalLayer;
    private final MethodHandle NSViewSetMetalLayer;
    private final MethodHandle NSViewClearLayer;
    private final MethodHandle setDebugLabelsEnabled;
    private final MethodHandle MTLDeviceMakeCommandQueue;
    private final MethodHandle MTLCommandQueueMakeCommandBuffer;
    private final MethodHandle MTLCommandBufferCommit;
    private final MethodHandle MTLCommandBufferIsCompleted;
    private final MethodHandle MTLCommandBufferWaitUntilCompleted;
    private final MethodHandle MTLCommandBufferPushDebugGroup;
    private final MethodHandle MTLCommandBufferPopDebugGroup;
    private final MethodHandle MTLCommandBufferMakeBlitCommandEncoder;
    private final MethodHandle MTLCommandEncoderEndEncoding;
    private final MethodHandle MTLBlitCommandEncoderCopyFromBufferToBuffer;
    private final MethodHandle MTLBlitCommandEncoderCopyFromBufferToTexture;
    private final MethodHandle MTLBlitCommandEncoderCopyFromTextureToTexture;
    private final MethodHandle MTLBlitCommandEncoderCopyFromTextureToBuffer;
    private final MethodHandle MTLDeviceMakeDepthStencilState;
    private final MethodHandle MTLCommandBufferMakeRenderCommandEncoder;
    private final MethodHandle MTLRenderCommandEncoderSetRenderPipelineState;
    private final MethodHandle MTLRenderCommandEncoderSetDepthStencilState;
    private final MethodHandle MTLRenderCommandEncoderSetDepthBias;
    private final MethodHandle MTLRenderCommandEncoderSetFrontFacingWinding;
    private final MethodHandle MTLRenderCommandEncoderSetCullMode;
    private final MethodHandle MTLRenderCommandEncoderSetTriangleFillMode;
    private final MethodHandle MTLRenderCommandEncoderSetVertexBuffer;
    private final MethodHandle MTLRenderCommandEncoderSetFragmentBuffer;
    private final MethodHandle MTLRenderCommandEncoderSetVertexTexture;
    private final MethodHandle MTLRenderCommandEncoderSetFragmentTexture;
    private final MethodHandle MTLRenderCommandEncoderSetVertexSamplerState;
    private final MethodHandle MTLRenderCommandEncoderSetFragmentSamplerState;
    private final MethodHandle MTLRenderCommandEncoderSetScissorRect;
    private final MethodHandle MTLRenderCommandEncoderDrawPrimitives;
    private final MethodHandle MTLRenderCommandEncoderDrawIndexedPrimitives;
    private final MethodHandle MTLRenderCommandEncoderDrawPrimitivesTriangleFan;
    private final MethodHandle MTLRenderCommandEncoderDrawIndexedPrimitivesTriangleFan;
    private final MethodHandle MTLCommandBufferClearColorDepthTexturesRegion;
    private final MethodHandle CAMetalLayerNextDrawable;
    private final MethodHandle MTLCommandBufferEncodePresentTextureToDrawable;
    private final MethodHandle MTLCommandBufferPresentDrawable;
    private final MethodHandle createBuffer;
    private final MethodHandle createTexture2d;
    private final MethodHandle createTextureView;
    private final MethodHandle createBufferTextureView;
    private final MethodHandle createSampler;
    private final MethodHandle createRenderPipeline;
    private final MethodHandle configureLayer;
    private final MethodHandle releaseObject;
    private final MethodHandle getBufferContents;
    private final MethodHandle createFence;
    private final MethodHandle MTLRenderCommandEncoderUpdateFence;
    private final MethodHandle MTLRenderCommandEncoderWaitForFence;
    private final MethodHandle MTLBlitCommandEncoderUpdateFence;
    private final MethodHandle MTLBlitCommandEncoderWaitForFence;

    private MetalNativeBridge(final Arena libraryArena, final SymbolLookup lookup) {
        this.libraryArena = libraryArena;
        this.createSystemDefaultDevice = downcall(lookup, "metallum_create_system_default_device", FunctionDescriptor.of(ValueLayout.ADDRESS));
        this.copyDeviceName = downcall(lookup, "metallum_copy_device_name", FunctionDescriptor.of(INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG));
        this.NSWindowBackingScaleFactor = downcall(lookup, "metallum_NSWindow_backingScaleFactor", FunctionDescriptor.of(DOUBLE, ValueLayout.ADDRESS));
        this.createMetalLayer = downcall(lookup, "metallum_create_metal_layer", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, DOUBLE));
        this.NSViewSetMetalLayer = downcall(lookup, "metallum_NSView_setMetalLayer", FunctionDescriptor.of(INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.NSViewClearLayer = downcall(lookup, "metallum_NSView_clearLayer", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        this.setDebugLabelsEnabled = downcall(lookup, "metallum_set_debug_labels_enabled", FunctionDescriptor.ofVoid(INT));
        this.MTLDeviceMakeCommandQueue = downcall(lookup, "metallum_MTLDevice_makeCommandQueue", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.MTLCommandQueueMakeCommandBuffer = downcall(lookup, "metallum_MTLCommandQueue_makeCommandBuffer", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.MTLCommandBufferCommit = downcall(lookup, "metallum_MTLCommandBuffer_commit", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        this.MTLCommandBufferIsCompleted = downcall(lookup, "metallum_MTLCommandBuffer_isCompleted", FunctionDescriptor.of(INT, ValueLayout.ADDRESS));
        this.MTLCommandBufferWaitUntilCompleted = downcall(lookup, "metallum_MTLCommandBuffer_waitUntilCompleted", FunctionDescriptor.of(INT, ValueLayout.ADDRESS, LONG));
        this.MTLCommandBufferPushDebugGroup = downcall(lookup, "metallum_MTLCommandBuffer_pushDebugGroup", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.MTLCommandBufferPopDebugGroup = downcall(lookup, "metallum_MTLCommandBuffer_popDebugGroup", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        this.MTLCommandBufferMakeBlitCommandEncoder = downcall(lookup, "metallum_MTLCommandBuffer_makeBlitCommandEncoder", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.MTLCommandEncoderEndEncoding = downcall(lookup, "metallum_MTLCommandEncoder_endEncoding", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        this.MTLBlitCommandEncoderCopyFromBufferToBuffer = downcall(
                lookup,
                "metallum_MTLBlitCommandEncoder_copyFromBufferToBuffer",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, ValueLayout.ADDRESS, LONG, LONG)
        );
        this.MTLBlitCommandEncoderCopyFromBufferToTexture = downcall(
                lookup,
                "metallum_MTLBlitCommandEncoder_copyFromBufferToTexture",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG, LONG, LONG, LONG, LONG)
        );
        this.MTLBlitCommandEncoderCopyFromTextureToTexture = downcall(
                lookup,
                "metallum_MTLBlitCommandEncoder_copyFromTextureToTexture",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG, LONG, LONG, LONG)
        );
        this.MTLBlitCommandEncoderCopyFromTextureToBuffer = downcall(
                lookup,
                "metallum_MTLBlitCommandEncoder_copyFromTextureToBuffer",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG, LONG, LONG, LONG, LONG, LONG)
        );
        this.MTLDeviceMakeDepthStencilState = downcall(lookup, "metallum_MTLDevice_makeDepthStencilState", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, INT));
        this.MTLCommandBufferMakeRenderCommandEncoder = downcall(
                lookup,
                "metallum_MTLCommandBuffer_makeRenderCommandEncoder",
                FunctionDescriptor.of(
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        DOUBLE,
                        DOUBLE,
                        INT,
                        FLOAT,
                        FLOAT,
                        FLOAT,
                        FLOAT,
                        INT,
                        DOUBLE
                )
        );
        this.MTLRenderCommandEncoderSetRenderPipelineState = downcall(lookup, "metallum_MTLRenderCommandEncoder_setRenderPipelineState", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.MTLRenderCommandEncoderSetDepthStencilState = downcall(lookup, "metallum_MTLRenderCommandEncoder_setDepthStencilState", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.MTLRenderCommandEncoderSetDepthBias = downcall(lookup, "metallum_MTLRenderCommandEncoder_setDepthBias", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, DOUBLE, DOUBLE, DOUBLE));
        this.MTLRenderCommandEncoderSetFrontFacingWinding = downcall(lookup, "metallum_MTLRenderCommandEncoder_setFrontFacingWinding", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, INT));
        this.MTLRenderCommandEncoderSetCullMode = downcall(lookup, "metallum_MTLRenderCommandEncoder_setCullMode", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, LONG));
        this.MTLRenderCommandEncoderSetTriangleFillMode = downcall(lookup, "metallum_MTLRenderCommandEncoder_setTriangleFillMode", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, INT));
        this.MTLRenderCommandEncoderSetVertexBuffer = downcall(lookup, "metallum_MTLRenderCommandEncoder_setVertexBuffer", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG));
        this.MTLRenderCommandEncoderSetFragmentBuffer = downcall(lookup, "metallum_MTLRenderCommandEncoder_setFragmentBuffer", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG));
        this.MTLRenderCommandEncoderSetVertexTexture = downcall(lookup, "metallum_MTLRenderCommandEncoder_setVertexTexture", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG));
        this.MTLRenderCommandEncoderSetFragmentTexture = downcall(lookup, "metallum_MTLRenderCommandEncoder_setFragmentTexture", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG));
        this.MTLRenderCommandEncoderSetVertexSamplerState = downcall(lookup, "metallum_MTLRenderCommandEncoder_setVertexSamplerState", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG));
        this.MTLRenderCommandEncoderSetFragmentSamplerState = downcall(lookup, "metallum_MTLRenderCommandEncoder_setFragmentSamplerState", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG));
        this.MTLRenderCommandEncoderSetScissorRect = downcall(lookup, "metallum_MTLRenderCommandEncoder_setScissorRect", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, LONG, LONG, LONG, LONG));
        this.MTLRenderCommandEncoderDrawPrimitives = downcall(lookup, "metallum_MTLRenderCommandEncoder_drawPrimitives", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, LONG, LONG, LONG, LONG));
        this.MTLRenderCommandEncoderDrawIndexedPrimitives = downcall(
                lookup,
                "metallum_MTLRenderCommandEncoder_drawIndexedPrimitives",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, LONG, LONG, LONG, ValueLayout.ADDRESS, LONG, LONG, LONG)
        );
        this.MTLRenderCommandEncoderDrawPrimitivesTriangleFan = downcall(
                lookup,
                "metallum_MTLRenderCommandEncoder_drawPrimitivesTriangleFan",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, LONG)
        );
        this.MTLRenderCommandEncoderDrawIndexedPrimitivesTriangleFan = downcall(
                lookup,
                "metallum_MTLRenderCommandEncoder_drawIndexedPrimitivesTriangleFan",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG, LONG)
        );
        this.MTLCommandBufferClearColorDepthTexturesRegion = downcall(
                lookup,
                "metallum_MTLCommandBuffer_clearColorDepthTexturesRegion",
                FunctionDescriptor.ofVoid(
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        FLOAT,
                        FLOAT,
                        FLOAT,
                        FLOAT,
                        ValueLayout.ADDRESS,
                        DOUBLE,
                        INT,
                        INT,
                        INT,
                        INT,
                        ValueLayout.ADDRESS
                )
        );
        this.CAMetalLayerNextDrawable = downcall(lookup, "metallum_CAMetalLayer_nextDrawable", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.MTLCommandBufferEncodePresentTextureToDrawable = downcall(
                lookup,
                "metallum_MTLCommandBuffer_encodePresentTextureToDrawable",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
        );
        this.MTLCommandBufferPresentDrawable = downcall(lookup, "metallum_MTLCommandBuffer_presentDrawable", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.createBuffer = downcall(lookup, "metallum_create_buffer", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG));
        this.createTexture2d = downcall(
                lookup,
                "metallum_create_texture_2d",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG, LONG, LONG, LONG, LONG, ValueLayout.ADDRESS)
        );
        this.createTextureView = downcall(lookup, "metallum_create_texture_view", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG));
        this.createBufferTextureView = downcall(
                lookup,
                "metallum_create_buffer_texture_view",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG, LONG)
        );
        this.createSampler = downcall(
                lookup,
                "metallum_create_sampler",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG, LONG, INT, DOUBLE)
        );
        this.createRenderPipeline = downcall(
                lookup,
                "metallum_create_render_pipeline",
                FunctionDescriptor.of(
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        LONG,
                        LONG,
                        LONG,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        LONG,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        LONG,
                        INT,
                        LONG,
                        LONG,
                        LONG,
                        LONG,
                        LONG,
                        LONG,
                        LONG
                )
        );
        this.configureLayer = downcall(lookup, "metallum_configure_layer", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, DOUBLE, DOUBLE, INT));
        this.releaseObject = downcall(lookup, "metallum_release_object", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        this.getBufferContents = downcall(lookup, "metallum_get_buffer_contents", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.createFence = downcall(lookup, "metallum_create_fence", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.MTLRenderCommandEncoderUpdateFence = downcall(lookup, "MTLRenderCommandEncoder_updateFence", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG));
        this.MTLRenderCommandEncoderWaitForFence = downcall(lookup, "MTLRenderCommandEncoder_waitForFence", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG));
        this.MTLBlitCommandEncoderUpdateFence = downcall(lookup, "MTLBlitCommandEncoder_updateFence", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.MTLBlitCommandEncoderWaitForFence = downcall(lookup, "MTLBlitCommandEncoder_waitForFence", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    }

    private static MetalNativeBridge loadNative() {
        try {
            Path tempLib = Files.createTempFile("metallum-native-", ".dylib");
            tempLib.toFile().deleteOnExit();
            try (InputStream stream = MetalNativeBridge.class.getResourceAsStream(RESOURCE_PATH)) {
                if (stream == null) {
                    throw new IllegalStateException("Missing native library resource: " + RESOURCE_PATH);
                }
                Files.copy(stream, tempLib, StandardCopyOption.REPLACE_EXISTING);
            }

            Arena arena = Arena.ofShared();
            SymbolLookup lookup = SymbolLookup.libraryLookup(tempLib, arena);
            return new MetalNativeBridge(arena, lookup);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load Metal native bridge", e);
        }
    }

    private static MethodHandle downcall(final SymbolLookup lookup, final String symbol, final FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(lookup.findOrThrow(symbol), descriptor);
    }

    public MemorySegment metallum_create_system_default_device() {
        try {
            return (MemorySegment) this.createSystemDefaultDevice.invokeExact();
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_create_system_default_device", throwable);
        }
    }

    public String metallum_copy_device_name(final MemorySegment device) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(256L);
            int result = (int) this.copyDeviceName.invokeExact(segment(device), buffer, 256L);
            return result == 0 ? buffer.getString(0L) : "";
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_copy_device_name", throwable);
        }
    }

    public double metallum_NSWindow_backingScaleFactor(final MemorySegment window) {
        try {
            return (double) this.NSWindowBackingScaleFactor.invokeExact(segment(window));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_NSWindow_backingScaleFactor", throwable);
        }
    }

    public MemorySegment metallum_create_metal_layer(final MemorySegment device, final double contentsScale) {
        try {
            return (MemorySegment) this.createMetalLayer.invokeExact(segment(device), contentsScale);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_create_metal_layer", throwable);
        }
    }

    public void metallum_NSView_setMetalLayer(final MemorySegment view, final MemorySegment layer) {
        try {
            int result = (int) this.NSViewSetMetalLayer.invokeExact(segment(view), segment(layer));
            if (result != 0) {
                throw new IllegalStateException("metallum_NSView_setMetalLayer returned " + result);
            }
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_NSView_setMetalLayer", throwable);
        }
    }

    public void metallum_NSView_clearLayer(final MemorySegment view) {
        try {
            this.NSViewClearLayer.invokeExact(segment(view));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_NSView_clearLayer", throwable);
        }
    }

    public void metallum_set_debug_labels_enabled(final boolean enabled) {
        try {
            this.setDebugLabelsEnabled.invokeExact(enabled ? 1 : 0);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_set_debug_labels_enabled", throwable);
        }
    }

    public MemorySegment MTLDevice_makeCommandQueue(final MemorySegment device) {
        try {
            return (MemorySegment) this.MTLDeviceMakeCommandQueue.invokeExact(segment(device));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLDevice_makeCommandQueue", throwable);
        }
    }

    public MemorySegment MTLCommandQueue_makeCommandBuffer(final MemorySegment commandQueue, final String label) {
        try (Arena arena = Arena.ofConfined()) {
            return (MemorySegment) this.MTLCommandQueueMakeCommandBuffer.invokeExact(segment(commandQueue), toCString(arena, label));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLCommandQueue_makeCommandBuffer", throwable);
        }
    }

    public void MTLCommandBuffer_commit(final MemorySegment commandBuffer) {
        try {
            this.MTLCommandBufferCommit.invokeExact(segment(commandBuffer));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLCommandBuffer_commit", throwable);
        }
    }

    public int MTLCommandBuffer_isCompleted(final MemorySegment commandBuffer) {
        try {
            return (int) this.MTLCommandBufferIsCompleted.invokeExact(segment(commandBuffer));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLCommandBuffer_isCompleted", throwable);
        }
    }

    public int MTLCommandBuffer_waitUntilCompleted(final MemorySegment commandBuffer, final long timeoutMs) {
        try {
            return (int) this.MTLCommandBufferWaitUntilCompleted.invokeExact(segment(commandBuffer), timeoutMs);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLCommandBuffer_waitUntilCompleted", throwable);
        }
    }

    public void MTLCommandBuffer_pushDebugGroup(final MemorySegment commandBuffer, final String label) {
        try (Arena arena = Arena.ofConfined()) {
            this.MTLCommandBufferPushDebugGroup.invokeExact(segment(commandBuffer), toCString(arena, label));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLCommandBuffer_pushDebugGroup", throwable);
        }
    }

    public void MTLCommandBuffer_popDebugGroup(final MemorySegment commandBuffer) {
        try {
            this.MTLCommandBufferPopDebugGroup.invokeExact(segment(commandBuffer));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLCommandBuffer_popDebugGroup", throwable);
        }
    }

    public MemorySegment MTLCommandBuffer_makeBlitCommandEncoder(final MemorySegment commandBuffer) {
        try {
            return (MemorySegment) this.MTLCommandBufferMakeBlitCommandEncoder.invokeExact(segment(commandBuffer));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLCommandBuffer_makeBlitCommandEncoder", throwable);
        }
    }

    public void MTLCommandEncoder_endEncoding(final MemorySegment encoder) {
        try {
            this.MTLCommandEncoderEndEncoding.invokeExact(segment(encoder));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLCommandEncoder_endEncoding", throwable);
        }
    }

    public void MTLBlitCommandEncoder_copyFromBufferToBuffer(
            final MemorySegment blitEncoder,
            final MemorySegment sourceBuffer,
            final long sourceOffset,
            final MemorySegment destinationBuffer,
            final long destinationOffset,
            final long length
    ) {
        try {
            this.MTLBlitCommandEncoderCopyFromBufferToBuffer.invokeExact(
                    segment(blitEncoder),
                    segment(sourceBuffer),
                    sourceOffset,
                    segment(destinationBuffer),
                    destinationOffset,
                    length
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLBlitCommandEncoder_copyFromBufferToBuffer", throwable);
        }
    }

    public void MTLBlitCommandEncoder_copyFromBufferToTexture(
            final MemorySegment blitEncoder,
            final MemorySegment sourceBuffer,
            final long sourceOffset,
            final MemorySegment texture,
            final long mipLevel,
            final long slice,
            final long x,
            final long y,
            final long width,
            final long height,
            final long bytesPerRow,
            final long bytesPerImage
    ) {
        try {
            this.MTLBlitCommandEncoderCopyFromBufferToTexture.invokeExact(
                    segment(blitEncoder),
                    segment(sourceBuffer),
                    sourceOffset,
                    segment(texture),
                    mipLevel,
                    slice,
                    x,
                    y,
                    width,
                    height,
                    bytesPerRow,
                    bytesPerImage
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLBlitCommandEncoder_copyFromBufferToTexture", throwable);
        }
    }

    public void MTLBlitCommandEncoder_copyFromTextureToTexture(
            final MemorySegment blitEncoder,
            final MemorySegment sourceTexture,
            final MemorySegment destinationTexture,
            final long mipLevel,
            final long sourceX,
            final long sourceY,
            final long destX,
            final long destY,
            final long width,
            final long height
    ) {
        try {
            this.MTLBlitCommandEncoderCopyFromTextureToTexture.invokeExact(
                    segment(blitEncoder),
                    segment(sourceTexture),
                    segment(destinationTexture),
                    mipLevel,
                    sourceX,
                    sourceY,
                    destX,
                    destY,
                    width,
                    height
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLBlitCommandEncoder_copyFromTextureToTexture", throwable);
        }
    }

    public void MTLBlitCommandEncoder_copyFromTextureToBuffer(
            final MemorySegment blitEncoder,
            final MemorySegment sourceTexture,
            final MemorySegment destinationBuffer,
            final long destinationOffset,
            final long mipLevel,
            final long slice,
            final long x,
            final long y,
            final long width,
            final long height,
            final long bytesPerRow,
            final long bytesPerImage
    ) {
        try {
            this.MTLBlitCommandEncoderCopyFromTextureToBuffer.invokeExact(
                    segment(blitEncoder),
                    segment(sourceTexture),
                    segment(destinationBuffer),
                    destinationOffset,
                    mipLevel,
                    slice,
                    x,
                    y,
                    width,
                    height,
                    bytesPerRow,
                    bytesPerImage
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLBlitCommandEncoder_copyFromTextureToBuffer", throwable);
        }
    }

    public MemorySegment metallum_create_buffer(final MemorySegment device, final long length, final long options) {
        try {
            return (MemorySegment) this.createBuffer.invokeExact(segment(device), length, options);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_create_buffer", throwable);
        }
    }

    public MemorySegment metallum_create_texture_2d(
            final MemorySegment device,
            final long pixelFormat,
            final long width,
            final long height,
            final long depthOrLayers,
            final long mipLevels,
            final long cubeCompatible,
            final long usage,
            final long storageMode,
            final String label
    ) {
        try (Arena arena = Arena.ofConfined()) {
            return (MemorySegment) this.createTexture2d.invokeExact(
                    segment(device),
                    pixelFormat,
                    width,
                    height,
                    depthOrLayers,
                    mipLevels,
                    cubeCompatible,
                    usage,
                    storageMode,
                    toCString(arena, label)
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_create_texture_2d", throwable);
        }
    }

    public MemorySegment metallum_create_texture_view(final MemorySegment texture, final long baseMipLevel, final long mipLevelCount) {
        try {
            return (MemorySegment) this.createTextureView.invokeExact(segment(texture), baseMipLevel, mipLevelCount);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_create_texture_view", throwable);
        }
    }

    public MemorySegment metallum_create_buffer_texture_view(
            final MemorySegment buffer,
            final long pixelFormat,
            final long offset,
            final long width,
            final long height,
            final long bytesPerRow
    ) {
        try {
            return (MemorySegment) this.createBufferTextureView.invokeExact(segment(buffer), pixelFormat, offset, width, height, bytesPerRow);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_create_buffer_texture_view", throwable);
        }
    }

    public MemorySegment metallum_create_sampler(
            final MemorySegment device,
            final long addressModeU,
            final long addressModeV,
            final long minFilter,
            final long magFilter,
            final long mipFilter,
            final int maxAnisotropy,
            final double lodMaxClamp
    ) {
        try {
            return (MemorySegment) this.createSampler.invokeExact(
                    segment(device),
                    addressModeU,
                    addressModeV,
                    minFilter,
                    magFilter,
                    mipFilter,
                    maxAnisotropy,
                    lodMaxClamp
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_create_sampler", throwable);
        }
    }

    public MemorySegment MTLDevice_makeDepthStencilState(final MemorySegment device, final long depthCompareOp, final int writeDepth) {
        try {
            return (MemorySegment) this.MTLDeviceMakeDepthStencilState.invokeExact(segment(device), depthCompareOp, writeDepth);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLDevice_makeDepthStencilState", throwable);
        }
    }

    public MemorySegment MTLCommandBuffer_makeRenderCommandEncoder(
            final MemorySegment commandBuffer,
            final MemorySegment colorTexture,
            final MemorySegment depthTexture,
            final double viewportWidth,
            final double viewportHeight,
            final int clearColorEnabled,
            final float clearColorRed,
            final float clearColorGreen,
            final float clearColorBlue,
            final float clearColorAlpha,
            final int clearDepthEnabled,
            final double clearDepth
    ) {
        try {
            return (MemorySegment) this.MTLCommandBufferMakeRenderCommandEncoder.invokeExact(
                    segment(commandBuffer),
                    segment(colorTexture),
                    segment(depthTexture),
                    viewportWidth,
                    viewportHeight,
                    clearColorEnabled,
                    clearColorRed,
                    clearColorGreen,
                    clearColorBlue,
                    clearColorAlpha,
                    clearDepthEnabled,
                    clearDepth
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLCommandBuffer_makeRenderCommandEncoder", throwable);
        }
    }

    public void MTLRenderCommandEncoder_setRenderPipelineState(final MemorySegment encoder, final MemorySegment pipeline) {
        try {
            this.MTLRenderCommandEncoderSetRenderPipelineState.invokeExact(segment(encoder), segment(pipeline));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_setRenderPipelineState", throwable);
        }
    }

    public void MTLRenderCommandEncoder_setDepthStencilState(final MemorySegment encoder, final MemorySegment depthStencilState) {
        try {
            this.MTLRenderCommandEncoderSetDepthStencilState.invokeExact(segment(encoder), segment(depthStencilState));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_setDepthStencilState", throwable);
        }
    }

    public void MTLRenderCommandEncoder_setDepthBias(final MemorySegment encoder, final double depthBias, final double slopeScale, final double clamp) {
        try {
            this.MTLRenderCommandEncoderSetDepthBias.invokeExact(segment(encoder), depthBias, slopeScale, clamp);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_setDepthBias", throwable);
        }
    }

    public void MTLRenderCommandEncoder_setFrontFacingWinding(final MemorySegment encoder, final int clockwise) {
        try {
            this.MTLRenderCommandEncoderSetFrontFacingWinding.invokeExact(segment(encoder), clockwise);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_setFrontFacingWinding", throwable);
        }
    }

    public void MTLRenderCommandEncoder_setCullMode(final MemorySegment encoder, final long cullMode) {
        try {
            this.MTLRenderCommandEncoderSetCullMode.invokeExact(segment(encoder), cullMode);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_setCullMode", throwable);
        }
    }

    public void MTLRenderCommandEncoder_setTriangleFillMode(final MemorySegment encoder, final int lines) {
        try {
            this.MTLRenderCommandEncoderSetTriangleFillMode.invokeExact(segment(encoder), lines);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_setTriangleFillMode", throwable);
        }
    }

    public void MTLRenderCommandEncoder_setVertexBuffer(final MemorySegment encoder, final MemorySegment buffer, final long offset, final long index) {
        try {
            this.MTLRenderCommandEncoderSetVertexBuffer.invokeExact(segment(encoder), segment(buffer), offset, index);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_setVertexBuffer", throwable);
        }
    }

    public void MTLRenderCommandEncoder_setFragmentBuffer(final MemorySegment encoder, final MemorySegment buffer, final long offset, final long index) {
        try {
            this.MTLRenderCommandEncoderSetFragmentBuffer.invokeExact(segment(encoder), segment(buffer), offset, index);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_setFragmentBuffer", throwable);
        }
    }

    public void MTLRenderCommandEncoder_setVertexTexture(final MemorySegment encoder, final MemorySegment texture, final long index) {
        try {
            this.MTLRenderCommandEncoderSetVertexTexture.invokeExact(segment(encoder), segment(texture), index);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_setVertexTexture", throwable);
        }
    }

    public void MTLRenderCommandEncoder_setFragmentTexture(final MemorySegment encoder, final MemorySegment texture, final long index) {
        try {
            this.MTLRenderCommandEncoderSetFragmentTexture.invokeExact(segment(encoder), segment(texture), index);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_setFragmentTexture", throwable);
        }
    }

    public void MTLRenderCommandEncoder_setVertexSamplerState(final MemorySegment encoder, final MemorySegment sampler, final long index) {
        try {
            this.MTLRenderCommandEncoderSetVertexSamplerState.invokeExact(segment(encoder), segment(sampler), index);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_setVertexSamplerState", throwable);
        }
    }

    public void MTLRenderCommandEncoder_setFragmentSamplerState(final MemorySegment encoder, final MemorySegment sampler, final long index) {
        try {
            this.MTLRenderCommandEncoderSetFragmentSamplerState.invokeExact(segment(encoder), segment(sampler), index);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_setFragmentSamplerState", throwable);
        }
    }

    public void MTLRenderCommandEncoder_setScissorRect(final MemorySegment encoder, final long x, final long y, final long width, final long height) {
        try {
            this.MTLRenderCommandEncoderSetScissorRect.invokeExact(segment(encoder), x, y, width, height);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_setScissorRect", throwable);
        }
    }

    public void MTLRenderCommandEncoder_drawPrimitives(
            final MemorySegment encoder,
            final long primitiveType,
            final long firstVertex,
            final long vertexCount,
            final long instanceCount
    ) {
        try {
            this.MTLRenderCommandEncoderDrawPrimitives.invokeExact(segment(encoder), primitiveType, firstVertex, vertexCount, instanceCount);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_drawPrimitives", throwable);
        }
    }

    public void MTLRenderCommandEncoder_drawIndexedPrimitives(
            final MemorySegment encoder,
            final long primitiveType,
            final long indexCount,
            final long indexType,
            final MemorySegment indexBuffer,
            final long indexBufferOffset,
            final long instanceCount,
            final long baseVertex
    ) {
        try {
            this.MTLRenderCommandEncoderDrawIndexedPrimitives.invokeExact(
                    segment(encoder),
                    primitiveType,
                    indexCount,
                    indexType,
                    segment(indexBuffer),
                    indexBufferOffset,
                    instanceCount,
                    baseVertex
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_drawIndexedPrimitives", throwable);
        }
    }

    public void MTLRenderCommandEncoder_drawPrimitivesTriangleFan(
            final MemorySegment encoder,
            final MemorySegment fanIndexBuffer,
            final long firstVertex,
            final long vertexCount,
            final long instanceCount
    ) {
        try {
            this.MTLRenderCommandEncoderDrawPrimitivesTriangleFan.invokeExact(
                    segment(encoder),
                    segment(fanIndexBuffer),
                    firstVertex,
                    vertexCount,
                    instanceCount
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_drawPrimitivesTriangleFan", throwable);
        }
    }

    public void MTLRenderCommandEncoder_drawIndexedPrimitivesTriangleFan(
            final MemorySegment encoder,
            final MemorySegment indexBuffer,
            final MemorySegment fanIndexBuffer,
            final long indexType,
            final long indexBufferOffset,
            final long indexCount,
            final long baseVertex,
            final long instanceCount
    ) {
        try {
            this.MTLRenderCommandEncoderDrawIndexedPrimitivesTriangleFan.invokeExact(
                    segment(encoder),
                    segment(indexBuffer),
                    segment(fanIndexBuffer),
                    indexType,
                    indexBufferOffset,
                    indexCount,
                    baseVertex,
                    instanceCount
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_drawIndexedPrimitivesTriangleFan", throwable);
        }
    }

    public void MTLCommandBuffer_clearColorDepthTexturesRegion(
            final MemorySegment commandBuffer,
            final MemorySegment colorTexture,
            final float clearColorRed,
            final float clearColorGreen,
            final float clearColorBlue,
            final float clearColorAlpha,
            final MemorySegment depthTexture,
            final double clearDepth,
            final int x,
            final int y,
            final int width,
            final int height,
            final MemorySegment globalFence
    ) {
        try {
            this.MTLCommandBufferClearColorDepthTexturesRegion.invokeExact(
                    segment(commandBuffer),
                    segment(colorTexture),
                    clearColorRed,
                    clearColorGreen,
                    clearColorBlue,
                    clearColorAlpha,
                    segment(depthTexture),
                    clearDepth,
                    x,
                    y,
                    width,
                    height,
                    segment(globalFence)
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLCommandBuffer_clearColorDepthTexturesRegion", throwable);
        }
    }

    public MemorySegment metallum_create_render_pipeline(
            final MemorySegment device,
            final String vertexMsl,
            final String fragmentMsl,
            final String vertexEntryPoint,
            final String fragmentEntryPoint,
            final long colorFormat,
            final long depthFormat,
            final long stencilFormat,
            final long[] vertexAttributeFormats,
            final long[] vertexAttributeOffsets,
            final long[] vertexAttributeBufferSlots,
            final long vertexAttributeCount,
            final long[] vertexBindingBufferSlots,
            final long[] vertexBindingStrides,
            final long[] vertexBindingStepRates,
            final long vertexBindingCount,
            final int blendEnabled,
            final long blendSourceRgb,
            final long blendDestRgb,
            final long blendOpRgb,
            final long blendSourceAlpha,
            final long blendDestAlpha,
            final long blendOpAlpha,
            final long writeMask
    ) {
        try (Arena arena = Arena.ofConfined()) {
            return (MemorySegment) this.createRenderPipeline.invokeExact(
                    segment(device),
                    toCString(arena, vertexMsl),
                    toCString(arena, fragmentMsl),
                    toCString(arena, vertexEntryPoint),
                    toCString(arena, fragmentEntryPoint),
                    colorFormat,
                    depthFormat,
                    stencilFormat,
                    toLongArray(arena, vertexAttributeFormats),
                    toLongArray(arena, vertexAttributeOffsets),
                    toLongArray(arena, vertexAttributeBufferSlots),
                    vertexAttributeCount,
                    toLongArray(arena, vertexBindingBufferSlots),
                    toLongArray(arena, vertexBindingStrides),
                    toLongArray(arena, vertexBindingStepRates),
                    vertexBindingCount,
                    blendEnabled,
                    blendSourceRgb,
                    blendDestRgb,
                    blendOpRgb,
                    blendSourceAlpha,
                    blendDestAlpha,
                    blendOpAlpha,
                    writeMask
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_create_render_pipeline", throwable);
        }
    }

    public void metallum_configure_layer(final MemorySegment layer, final double width, final double height, final int immediatePresentMode) {
        try {
            this.configureLayer.invokeExact(segment(layer), width, height, immediatePresentMode);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_configure_layer", throwable);
        }
    }

    public MemorySegment CAMetalLayer_nextDrawable(final MemorySegment layer) {
        try {
            return (MemorySegment) this.CAMetalLayerNextDrawable.invokeExact(segment(layer));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_CAMetalLayer_nextDrawable", throwable);
        }
    }

    public void MTLCommandBuffer_encodePresentTextureToDrawable(final MemorySegment commandBuffer, final MemorySegment drawable, final MemorySegment sourceTexture, final MemorySegment globalFence) {
        try {
            this.MTLCommandBufferEncodePresentTextureToDrawable.invokeExact(segment(commandBuffer), segment(drawable), segment(sourceTexture), segment(globalFence));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLCommandBuffer_encodePresentTextureToDrawable", throwable);
        }
    }

    public void MTLCommandBuffer_presentDrawable(final MemorySegment commandBuffer, final MemorySegment drawable) {
        try {
            this.MTLCommandBufferPresentDrawable.invokeExact(segment(commandBuffer), segment(drawable));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLCommandBuffer_presentDrawable", throwable);
        }
    }

    public void metallum_release_object(final MemorySegment object) {
        try {
            this.releaseObject.invokeExact(segment(object));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_release_object", throwable);
        }
    }

    public MemorySegment metallum_create_fence(final MemorySegment device) {
        try {
            return (MemorySegment) this.createFence.invokeExact(segment(device));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_create_fence", throwable);
        }
    }

    public void MTLRenderCommandEncoder_updateFence(final MemorySegment encoder, final MemorySegment fence, final long stages) {
        try {
            this.MTLRenderCommandEncoderUpdateFence.invokeExact(segment(encoder), segment(fence), stages);
        } catch (Throwable throwable) {
            throw bridgeFailure("MTLRenderCommandEncoder_updateFence", throwable);
        }
    }

    public void MTLRenderCommandEncoder_waitForFence(final MemorySegment encoder, final MemorySegment fence, final long stages) {
        try {
            this.MTLRenderCommandEncoderWaitForFence.invokeExact(segment(encoder), segment(fence), stages);
        } catch (Throwable throwable) {
            throw bridgeFailure("MTLRenderCommandEncoder_waitForFence", throwable);
        }
    }

    public void MTLBlitCommandEncoder_updateFence(final MemorySegment encoder, final MemorySegment fence) {
        try {
            this.MTLBlitCommandEncoderUpdateFence.invokeExact(segment(encoder), segment(fence));
        } catch (Throwable throwable) {
            throw bridgeFailure("MTLBlitCommandEncoder_updateFence", throwable);
        }
    }

    public void MTLBlitCommandEncoder_waitForFence(final MemorySegment encoder, final MemorySegment fence) {
        try {
            this.MTLBlitCommandEncoderWaitForFence.invokeExact(segment(encoder), segment(fence));
        } catch (Throwable throwable) {
            throw bridgeFailure("MTLBlitCommandEncoder_waitForFence", throwable);
        }
    }

    public MemorySegment metallum_get_buffer_contents(final MemorySegment buffer) {
        try {
            return (MemorySegment) this.getBufferContents.invokeExact(segment(buffer));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_get_buffer_contents", throwable);
        }
    }

    public ByteBuffer nativeByteBufferView(final MemorySegment pointer, final long byteSize) {
        if (pointer == null || pointer.address() == 0L) {
            throw new IllegalArgumentException("Cannot create a ByteBuffer view for a null native pointer");
        }
        if (byteSize < 0L) {
            throw new IllegalArgumentException("Byte size must be non-negative");
        }
        return MemorySegment.ofAddress(pointer.address()).reinterpret(byteSize).asByteBuffer();
    }

    private static MemorySegment segment(final MemorySegment pointer) {
        return pointer == null || pointer.address() == 0L ? MemorySegment.NULL : pointer;
    }

    private static MemorySegment toCString(final Arena arena, final String value) {
        return value == null ? MemorySegment.NULL : arena.allocateFrom(value);
    }

    private static MemorySegment toLongArray(final Arena arena, final long[] values) {
        if (values == null || values.length == 0) {
            return MemorySegment.NULL;
        }
        MemorySegment segment = arena.allocate(LONG, values.length);
        for (int i = 0; i < values.length; i++) {
            segment.setAtIndex(LONG, i, values[i]);
        }
        return segment;
    }

    public static boolean isNullHandle(@Nullable final MemorySegment pointer) {
        return pointer == null || pointer.address() == 0L;
    }

    public static boolean isMacOs() {
        String osName = System.getProperty("os.name", "");
        return osName.toLowerCase(Locale.ROOT).contains("mac");
    }

    private static RuntimeException bridgeFailure(final String symbol, final Throwable throwable) {
        return new IllegalStateException("Native bridge call failed: " + symbol, throwable);
    }
}
