package com.metallum.client.metal.render;

import com.sun.jna.Pointer;
import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
final class MetalNativeBridge {
	private static final String RESOURCE_PATH = "/natives/macos/libmetallum.dylib";
	private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT;
	private static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG;
	private static final ValueLayout.OfFloat FLOAT = ValueLayout.JAVA_FLOAT;
	private static final ValueLayout.OfDouble DOUBLE = ValueLayout.JAVA_DOUBLE;
	private static final Linker LINKER = Linker.nativeLinker();

	static final MetalNativeBridge INSTANCE = loadNative();

	private final Arena libraryArena;
	private final MethodHandle createSystemDefaultDevice;
	private final MethodHandle setDebugLabelsEnabled;
	private final MethodHandle createCommandQueue;
	private final MethodHandle MTLCommandQueueMakeCommandBuffer;
	private final MethodHandle MTLCommandBufferCommit;
	private final MethodHandle MTLCommandBufferIsCompleted;
	private final MethodHandle MTLCommandBufferWaitUntilCompleted;
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

	private MetalNativeBridge(final Arena libraryArena, final SymbolLookup lookup) {
		this.libraryArena = libraryArena;
		this.createSystemDefaultDevice = downcall(lookup, "metallum_create_system_default_device", FunctionDescriptor.of(ValueLayout.ADDRESS));
		this.setDebugLabelsEnabled = downcall(lookup, "metallum_set_debug_labels_enabled", FunctionDescriptor.ofVoid(INT));
		this.createCommandQueue = downcall(lookup, "metallum_create_command_queue", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
		this.MTLCommandQueueMakeCommandBuffer = downcall(lookup, "metallum_MTLCommandQueue_makeCommandBuffer", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
		this.MTLCommandBufferCommit = downcall(lookup, "metallum_MTLCommandBuffer_commit", FunctionDescriptor.ofVoid( ValueLayout.ADDRESS));
		this.MTLCommandBufferIsCompleted = downcall(lookup, "metallum_MTLCommandBuffer_isCompleted", FunctionDescriptor.of(INT, ValueLayout.ADDRESS));
		this.MTLCommandBufferWaitUntilCompleted = downcall(lookup, "metallum_MTLCommandBuffer_waitUntilCompleted", FunctionDescriptor.of(INT, ValueLayout.ADDRESS, LONG));
		this.MTLCommandBufferMakeBlitCommandEncoder = downcall(lookup, "metallum_MTLCommandBuffer_makeBlitCommandEncoder", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
		this.MTLCommandEncoderEndEncoding = downcall(lookup, "metallum_MTLCommandEncoder_endEncoding", FunctionDescriptor.ofVoid( ValueLayout.ADDRESS));
		this.MTLBlitCommandEncoderCopyFromBufferToBuffer = downcall(
			lookup,
			"metallum_MTLBlitCommandEncoder_copyFromBufferToBuffer",
			FunctionDescriptor.ofVoid( ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, ValueLayout.ADDRESS, LONG, LONG)
		);
		this.MTLBlitCommandEncoderCopyFromBufferToTexture = downcall(
			lookup,
			"metallum_MTLBlitCommandEncoder_copyFromBufferToTexture",
			FunctionDescriptor.ofVoid( ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG, LONG, LONG, LONG, LONG)
		);
		this.MTLBlitCommandEncoderCopyFromTextureToTexture = downcall(
			lookup,
			"metallum_MTLBlitCommandEncoder_copyFromTextureToTexture",
			FunctionDescriptor.ofVoid( ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG, LONG, LONG, LONG)
		);
		this.MTLBlitCommandEncoderCopyFromTextureToBuffer = downcall(
			lookup,
			"metallum_MTLBlitCommandEncoder_copyFromTextureToBuffer",
			FunctionDescriptor.ofVoid( ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG, LONG, LONG, LONG, LONG, LONG)
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
		this.MTLRenderCommandEncoderSetRenderPipelineState = downcall(lookup, "metallum_MTLRenderCommandEncoder_setRenderPipelineState", FunctionDescriptor.ofVoid( ValueLayout.ADDRESS, ValueLayout.ADDRESS));
		this.MTLRenderCommandEncoderSetDepthStencilState = downcall(lookup, "metallum_MTLRenderCommandEncoder_setDepthStencilState", FunctionDescriptor.ofVoid( ValueLayout.ADDRESS, ValueLayout.ADDRESS));
		this.MTLRenderCommandEncoderSetDepthBias = downcall(lookup, "metallum_MTLRenderCommandEncoder_setDepthBias", FunctionDescriptor.ofVoid( ValueLayout.ADDRESS, DOUBLE, DOUBLE, DOUBLE));
		this.MTLRenderCommandEncoderSetFrontFacingWinding = downcall(lookup, "metallum_MTLRenderCommandEncoder_setFrontFacingWinding", FunctionDescriptor.ofVoid( ValueLayout.ADDRESS, INT));
		this.MTLRenderCommandEncoderSetCullMode = downcall(lookup, "metallum_MTLRenderCommandEncoder_setCullMode", FunctionDescriptor.ofVoid( ValueLayout.ADDRESS, LONG));
		this.MTLRenderCommandEncoderSetTriangleFillMode = downcall(lookup, "metallum_MTLRenderCommandEncoder_setTriangleFillMode", FunctionDescriptor.ofVoid( ValueLayout.ADDRESS, INT));
		this.MTLRenderCommandEncoderSetVertexBuffer = downcall(lookup, "metallum_MTLRenderCommandEncoder_setVertexBuffer", FunctionDescriptor.ofVoid( ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG));
		this.MTLRenderCommandEncoderSetFragmentBuffer = downcall(lookup, "metallum_MTLRenderCommandEncoder_setFragmentBuffer", FunctionDescriptor.ofVoid( ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG));
		this.MTLRenderCommandEncoderSetVertexTexture = downcall(lookup, "metallum_MTLRenderCommandEncoder_setVertexTexture", FunctionDescriptor.ofVoid( ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG));
		this.MTLRenderCommandEncoderSetFragmentTexture = downcall(lookup, "metallum_MTLRenderCommandEncoder_setFragmentTexture", FunctionDescriptor.ofVoid( ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG));
		this.MTLRenderCommandEncoderSetVertexSamplerState = downcall(lookup, "metallum_MTLRenderCommandEncoder_setVertexSamplerState", FunctionDescriptor.ofVoid( ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG));
		this.MTLRenderCommandEncoderSetFragmentSamplerState = downcall(lookup, "metallum_MTLRenderCommandEncoder_setFragmentSamplerState", FunctionDescriptor.ofVoid( ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG));
		this.MTLRenderCommandEncoderSetScissorRect = downcall(lookup, "metallum_MTLRenderCommandEncoder_setScissorRect", FunctionDescriptor.ofVoid( ValueLayout.ADDRESS, LONG, LONG, LONG, LONG));
		this.MTLRenderCommandEncoderDrawPrimitives = downcall(lookup, "metallum_MTLRenderCommandEncoder_drawPrimitives", FunctionDescriptor.ofVoid( ValueLayout.ADDRESS, LONG, LONG, LONG, LONG));
		this.MTLRenderCommandEncoderDrawIndexedPrimitives = downcall(
			lookup,
			"metallum_MTLRenderCommandEncoder_drawIndexedPrimitives",
			FunctionDescriptor.ofVoid( ValueLayout.ADDRESS, LONG, LONG, LONG, ValueLayout.ADDRESS, LONG, LONG, LONG)
		);
		this.MTLRenderCommandEncoderDrawPrimitivesTriangleFan = downcall(
			lookup,
			"metallum_MTLRenderCommandEncoder_drawPrimitivesTriangleFan",
			FunctionDescriptor.ofVoid( ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, LONG)
		);
		this.MTLRenderCommandEncoderDrawIndexedPrimitivesTriangleFan = downcall(
			lookup,
			"metallum_MTLRenderCommandEncoder_drawIndexedPrimitivesTriangleFan",
			FunctionDescriptor.ofVoid( ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG, LONG)
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
				INT
			)
		);
		this.CAMetalLayerNextDrawable = downcall(lookup, "metallum_CAMetalLayer_nextDrawable", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
		this.MTLCommandBufferEncodePresentTextureToDrawable = downcall(
			lookup,
			"metallum_MTLCommandBuffer_encodePresentTextureToDrawable",
			FunctionDescriptor.ofVoid( ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
		);
		this.MTLCommandBufferPresentDrawable = downcall(lookup, "metallum_MTLCommandBuffer_presentDrawable", FunctionDescriptor.ofVoid( ValueLayout.ADDRESS, ValueLayout.ADDRESS));
		this.createBuffer = downcall(lookup, "metallum_create_buffer", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, ValueLayout.ADDRESS));
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
		this.configureLayer = downcall(lookup, "metallum_configure_layer", FunctionDescriptor.ofVoid( ValueLayout.ADDRESS, DOUBLE, DOUBLE, INT));
		this.releaseObject = downcall(lookup, "metallum_release_object", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
		this.getBufferContents = downcall(lookup, "metallum_get_buffer_contents", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
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

	Pointer metallum_create_system_default_device() {
		try {
			return toPointer((MemorySegment)this.createSystemDefaultDevice.invokeExact());
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_create_system_default_device", throwable);
		}
	}

	void metallum_set_debug_labels_enabled(final boolean enabled) {
		try {
			this.setDebugLabelsEnabled.invokeExact(enabled ? 1 : 0);
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_set_debug_labels_enabled", throwable);
		}
	}

	Pointer metallum_create_command_queue(final Pointer device) {
		try {
			return toPointer((MemorySegment)this.createCommandQueue.invokeExact(toSegment(device)));
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_create_command_queue", throwable);
		}
	}

	Pointer MTLCommandQueue_makeCommandBuffer(final Pointer commandQueue, final String label) {
		try (Arena arena = Arena.ofConfined()) {
			return toPointer((MemorySegment)this.MTLCommandQueueMakeCommandBuffer.invokeExact(toSegment(commandQueue), toCString(arena, label)));
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_MTLCommandQueue_makeCommandBuffer", throwable);
		}
	}

	void MTLCommandBuffer_commit(final Pointer commandBuffer) {
		try {
			this.MTLCommandBufferCommit.invokeExact(toSegment(commandBuffer));
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_MTLCommandBuffer_commit", throwable);
		}
	}

	int MTLCommandBuffer_isCompleted(final Pointer commandBuffer) {
		try {
			return (int)this.MTLCommandBufferIsCompleted.invokeExact(toSegment(commandBuffer));
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_MTLCommandBuffer_isCompleted", throwable);
		}
	}

	int MTLCommandBuffer_waitUntilCompleted(final Pointer commandBuffer, final long timeoutMs) {
		try {
			return (int)this.MTLCommandBufferWaitUntilCompleted.invokeExact(toSegment(commandBuffer), timeoutMs);
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_MTLCommandBuffer_waitUntilCompleted", throwable);
		}
	}

	Pointer MTLCommandBuffer_makeBlitCommandEncoder(final Pointer commandBuffer, final String label) {
		try (Arena arena = Arena.ofConfined()) {
			return toPointer((MemorySegment)this.MTLCommandBufferMakeBlitCommandEncoder.invokeExact(toSegment(commandBuffer), toCString(arena, label)));
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_MTLCommandBuffer_makeBlitCommandEncoder", throwable);
		}
	}

	void MTLCommandEncoder_endEncoding(final Pointer encoder) {
		try {
			this.MTLCommandEncoderEndEncoding.invokeExact(toSegment(encoder));
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_MTLCommandEncoder_endEncoding", throwable);
		}
	}

	void MTLBlitCommandEncoder_copyFromBufferToBuffer(
		final Pointer blitEncoder,
		final Pointer sourceBuffer,
		final long sourceOffset,
		final Pointer destinationBuffer,
		final long destinationOffset,
		final long length
	) {
		try {
			this.MTLBlitCommandEncoderCopyFromBufferToBuffer.invokeExact(
				toSegment(blitEncoder),
				toSegment(sourceBuffer),
				sourceOffset,
				toSegment(destinationBuffer),
				destinationOffset,
				length
			);
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_MTLBlitCommandEncoder_copyFromBufferToBuffer", throwable);
		}
	}

	void MTLBlitCommandEncoder_copyFromBufferToTexture(
		final Pointer blitEncoder,
		final Pointer sourceBuffer,
		final long sourceOffset,
		final Pointer texture,
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
				toSegment(blitEncoder),
				toSegment(sourceBuffer),
				sourceOffset,
				toSegment(texture),
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

	void MTLBlitCommandEncoder_copyFromTextureToTexture(
		final Pointer blitEncoder,
		final Pointer sourceTexture,
		final Pointer destinationTexture,
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
				toSegment(blitEncoder),
				toSegment(sourceTexture),
				toSegment(destinationTexture),
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

	void MTLBlitCommandEncoder_copyFromTextureToBuffer(
		final Pointer blitEncoder,
		final Pointer sourceTexture,
		final Pointer destinationBuffer,
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
				toSegment(blitEncoder),
				toSegment(sourceTexture),
				toSegment(destinationBuffer),
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

	Pointer metallum_create_buffer(final Pointer device, final long length, final long options, final String label) {
		try (Arena arena = Arena.ofConfined()) {
			return toPointer((MemorySegment)this.createBuffer.invokeExact(toSegment(device), length, options, toCString(arena, label)));
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_create_buffer", throwable);
		}
	}

	Pointer metallum_create_texture_2d(
		final Pointer device,
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
			return toPointer((MemorySegment)this.createTexture2d.invokeExact(
				toSegment(device),
				pixelFormat,
				width,
				height,
				depthOrLayers,
				mipLevels,
				cubeCompatible,
				usage,
				storageMode,
				toCString(arena, label)
			));
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_create_texture_2d", throwable);
		}
	}

	Pointer metallum_create_texture_view(final Pointer texture, final long baseMipLevel, final long mipLevelCount) {
		try {
			return toPointer((MemorySegment)this.createTextureView.invokeExact(toSegment(texture), baseMipLevel, mipLevelCount));
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_create_texture_view", throwable);
		}
	}

	Pointer metallum_create_buffer_texture_view(
		final Pointer buffer,
		final long pixelFormat,
		final long offset,
		final long width,
		final long height,
		final long bytesPerRow
	) {
		try {
			return toPointer((MemorySegment)this.createBufferTextureView.invokeExact(toSegment(buffer), pixelFormat, offset, width, height, bytesPerRow));
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_create_buffer_texture_view", throwable);
		}
	}

	Pointer metallum_create_sampler(
		final Pointer device,
		final long addressModeU,
		final long addressModeV,
		final long minFilter,
		final long magFilter,
		final long mipFilter,
		final int maxAnisotropy,
		final double lodMaxClamp
	) {
		try {
			return toPointer((MemorySegment)this.createSampler.invokeExact(
				toSegment(device),
				addressModeU,
				addressModeV,
				minFilter,
				magFilter,
				mipFilter,
				maxAnisotropy,
				lodMaxClamp
			));
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_create_sampler", throwable);
		}
	}

	Pointer MTLDevice_makeDepthStencilState(final Pointer device, final long depthCompareOp, final int writeDepth) {
		try {
			return toPointer((MemorySegment)this.MTLDeviceMakeDepthStencilState.invokeExact(toSegment(device), depthCompareOp, writeDepth));
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_MTLDevice_makeDepthStencilState", throwable);
		}
	}

	Pointer MTLCommandBuffer_makeRenderCommandEncoder(
		final Pointer commandBuffer,
		final Pointer colorTexture,
		final Pointer depthTexture,
		final double viewportWidth,
		final double viewportHeight,
		final int clearColorEnabled,
		final float clearColorRed,
		final float clearColorGreen,
		final float clearColorBlue,
		final float clearColorAlpha,
		final int clearDepthEnabled,
		final double clearDepth,
		final String label
	) {
		try (Arena arena = Arena.ofConfined()) {
			return toPointer((MemorySegment)this.MTLCommandBufferMakeRenderCommandEncoder.invokeExact(
				toSegment(commandBuffer),
				toSegment(colorTexture),
				toSegment(depthTexture),
				toCString(arena, label),
				viewportWidth,
				viewportHeight,
				clearColorEnabled,
				clearColorRed,
				clearColorGreen,
				clearColorBlue,
				clearColorAlpha,
				clearDepthEnabled,
				clearDepth
			));
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_MTLCommandBuffer_makeRenderCommandEncoder", throwable);
		}
	}

	void MTLRenderCommandEncoder_setRenderPipelineState(final Pointer encoder, final Pointer pipeline) {
		try {
			this.MTLRenderCommandEncoderSetRenderPipelineState.invokeExact(toSegment(encoder), toSegment(pipeline));
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_MTLRenderCommandEncoder_setRenderPipelineState", throwable);
		}
	}

	void MTLRenderCommandEncoder_setDepthStencilState(final Pointer encoder, final Pointer depthStencilState) {
		try {
			this.MTLRenderCommandEncoderSetDepthStencilState.invokeExact(toSegment(encoder), toSegment(depthStencilState));
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_MTLRenderCommandEncoder_setDepthStencilState", throwable);
		}
	}

	void MTLRenderCommandEncoder_setDepthBias(final Pointer encoder, final double depthBias, final double slopeScale, final double clamp) {
		try {
			this.MTLRenderCommandEncoderSetDepthBias.invokeExact(toSegment(encoder), depthBias, slopeScale, clamp);
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_MTLRenderCommandEncoder_setDepthBias", throwable);
		}
	}

	void MTLRenderCommandEncoder_setFrontFacingWinding(final Pointer encoder, final int clockwise) {
		try {
			this.MTLRenderCommandEncoderSetFrontFacingWinding.invokeExact(toSegment(encoder), clockwise);
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_MTLRenderCommandEncoder_setFrontFacingWinding", throwable);
		}
	}

	void MTLRenderCommandEncoder_setCullMode(final Pointer encoder, final long cullMode) {
		try {
			this.MTLRenderCommandEncoderSetCullMode.invokeExact(toSegment(encoder), cullMode);
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_MTLRenderCommandEncoder_setCullMode", throwable);
		}
	}

	void MTLRenderCommandEncoder_setTriangleFillMode(final Pointer encoder, final int lines) {
		try {
			this.MTLRenderCommandEncoderSetTriangleFillMode.invokeExact(toSegment(encoder), lines);
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_MTLRenderCommandEncoder_setTriangleFillMode", throwable);
		}
	}

	void MTLRenderCommandEncoder_setVertexBuffer(final Pointer encoder, final Pointer buffer, final long offset, final long index) {
		try {
			this.MTLRenderCommandEncoderSetVertexBuffer.invokeExact(toSegment(encoder), toSegment(buffer), offset, index);
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_MTLRenderCommandEncoder_setVertexBuffer", throwable);
		}
	}

	void MTLRenderCommandEncoder_setFragmentBuffer(final Pointer encoder, final Pointer buffer, final long offset, final long index) {
		try {
			this.MTLRenderCommandEncoderSetFragmentBuffer.invokeExact(toSegment(encoder), toSegment(buffer), offset, index);
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_MTLRenderCommandEncoder_setFragmentBuffer", throwable);
		}
	}

	void MTLRenderCommandEncoder_setVertexTexture(final Pointer encoder, final Pointer texture, final long index) {
		try {
			this.MTLRenderCommandEncoderSetVertexTexture.invokeExact(toSegment(encoder), toSegment(texture), index);
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_MTLRenderCommandEncoder_setVertexTexture", throwable);
		}
	}

	void MTLRenderCommandEncoder_setFragmentTexture(final Pointer encoder, final Pointer texture, final long index) {
		try {
			this.MTLRenderCommandEncoderSetFragmentTexture.invokeExact(toSegment(encoder), toSegment(texture), index);
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_MTLRenderCommandEncoder_setFragmentTexture", throwable);
		}
	}

	void MTLRenderCommandEncoder_setVertexSamplerState(final Pointer encoder, final Pointer sampler, final long index) {
		try {
			this.MTLRenderCommandEncoderSetVertexSamplerState.invokeExact(toSegment(encoder), toSegment(sampler), index);
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_MTLRenderCommandEncoder_setVertexSamplerState", throwable);
		}
	}

	void MTLRenderCommandEncoder_setFragmentSamplerState(final Pointer encoder, final Pointer sampler, final long index) {
		try {
			this.MTLRenderCommandEncoderSetFragmentSamplerState.invokeExact(toSegment(encoder), toSegment(sampler), index);
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_MTLRenderCommandEncoder_setFragmentSamplerState", throwable);
		}
	}

	void MTLRenderCommandEncoder_setScissorRect(final Pointer encoder, final long x, final long y, final long width, final long height) {
		try {
			this.MTLRenderCommandEncoderSetScissorRect.invokeExact(toSegment(encoder), x, y, width, height);
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_MTLRenderCommandEncoder_setScissorRect", throwable);
		}
	}

	void MTLRenderCommandEncoder_drawPrimitives(
		final Pointer encoder,
		final long primitiveType,
		final long firstVertex,
		final long vertexCount,
		final long instanceCount
	) {
		try {
			this.MTLRenderCommandEncoderDrawPrimitives.invokeExact(toSegment(encoder), primitiveType, firstVertex, vertexCount, instanceCount);
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_MTLRenderCommandEncoder_drawPrimitives", throwable);
		}
	}

	void MTLRenderCommandEncoder_drawIndexedPrimitives(
		final Pointer encoder,
		final long primitiveType,
		final long indexCount,
		final long indexType,
		final Pointer indexBuffer,
		final long indexBufferOffset,
		final long instanceCount,
		final long baseVertex
	) {
		try {
			this.MTLRenderCommandEncoderDrawIndexedPrimitives.invokeExact(
				toSegment(encoder),
				primitiveType,
				indexCount,
				indexType,
				toSegment(indexBuffer),
				indexBufferOffset,
				instanceCount,
				baseVertex
			);
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_MTLRenderCommandEncoder_drawIndexedPrimitives", throwable);
		}
	}

	void MTLRenderCommandEncoder_drawPrimitivesTriangleFan(
		final Pointer encoder,
		final Pointer fanIndexBuffer,
		final long firstVertex,
		final long vertexCount,
		final long instanceCount
	) {
		try {
			this.MTLRenderCommandEncoderDrawPrimitivesTriangleFan.invokeExact(
				toSegment(encoder),
				toSegment(fanIndexBuffer),
				firstVertex,
				vertexCount,
				instanceCount
			);
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_MTLRenderCommandEncoder_drawPrimitivesTriangleFan", throwable);
		}
	}

	void MTLRenderCommandEncoder_drawIndexedPrimitivesTriangleFan(
		final Pointer encoder,
		final Pointer indexBuffer,
		final Pointer fanIndexBuffer,
		final long indexType,
		final long indexBufferOffset,
		final long indexCount,
		final long baseVertex,
		final long instanceCount
	) {
		try {
			this.MTLRenderCommandEncoderDrawIndexedPrimitivesTriangleFan.invokeExact(
				toSegment(encoder),
				toSegment(indexBuffer),
				toSegment(fanIndexBuffer),
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

	void MTLCommandBuffer_clearColorDepthTexturesRegion(
		final Pointer commandBuffer,
		final Pointer colorTexture,
		final float clearColorRed,
		final float clearColorGreen,
		final float clearColorBlue,
		final float clearColorAlpha,
		final Pointer depthTexture,
		final double clearDepth,
		final int x,
		final int y,
		final int width,
		final int height
	) {
		try {
			this.MTLCommandBufferClearColorDepthTexturesRegion.invokeExact(
				toSegment(commandBuffer),
				toSegment(colorTexture),
				clearColorRed,
				clearColorGreen,
				clearColorBlue,
				clearColorAlpha,
				toSegment(depthTexture),
				clearDepth,
				x,
				y,
				width,
				height
			);
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_MTLCommandBuffer_clearColorDepthTexturesRegion", throwable);
		}
	}

	Pointer metallum_create_render_pipeline(
		final Pointer device,
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
			return toPointer((MemorySegment)this.createRenderPipeline.invokeExact(
				toSegment(device),
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
			));
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_create_render_pipeline", throwable);
		}
	}

	void metallum_configure_layer(final Pointer layer, final double width, final double height, final int immediatePresentMode) {
		try {
			this.configureLayer.invokeExact(toSegment(layer), width, height, immediatePresentMode);
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_configure_layer", throwable);
		}
	}

	Pointer CAMetalLayer_nextDrawable(final Pointer layer) {
		try {
			return toPointer((MemorySegment)this.CAMetalLayerNextDrawable.invokeExact(toSegment(layer)));
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_CAMetalLayer_nextDrawable", throwable);
		}
	}

	void MTLCommandBuffer_encodePresentTextureToDrawable(final Pointer commandBuffer, final Pointer drawable, final Pointer sourceTexture) {
		try {
			this.MTLCommandBufferEncodePresentTextureToDrawable.invokeExact(toSegment(commandBuffer), toSegment(drawable), toSegment(sourceTexture));
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_MTLCommandBuffer_encodePresentTextureToDrawable", throwable);
		}
	}

	void MTLCommandBuffer_presentDrawable(final Pointer commandBuffer, final Pointer drawable) {
		try {
			this.MTLCommandBufferPresentDrawable.invokeExact(toSegment(commandBuffer), toSegment(drawable));
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_MTLCommandBuffer_presentDrawable", throwable);
		}
	}

	void metallum_release_object(final Pointer object) {
		try {
			this.releaseObject.invokeExact(toSegment(object));
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_release_object", throwable);
		}
	}

	Pointer metallum_get_buffer_contents(final Pointer buffer) {
		try {
			return toPointer((MemorySegment)this.getBufferContents.invokeExact(toSegment(buffer)));
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_get_buffer_contents", throwable);
		}
	}

	ByteBuffer nativeByteBufferView(final Pointer pointer, final long byteSize) {
		if (pointer == null || Pointer.nativeValue(pointer) == 0L) {
			throw new IllegalArgumentException("Cannot create a ByteBuffer view for a null native pointer");
		}
		if (byteSize < 0L) {
			throw new IllegalArgumentException("Byte size must be non-negative");
		}
		return MemorySegment.ofAddress(Pointer.nativeValue(pointer)).reinterpret(byteSize).asByteBuffer();
	}

	private static MemorySegment renderPassSegment(final Pointer renderPass) {
		return toSegment(renderPass);
	}

	private static MemorySegment toSegment(final Pointer pointer) {
		if (pointer == null) {
			return MemorySegment.NULL;
		}
		long address = Pointer.nativeValue(pointer);
		return address == 0L ? MemorySegment.NULL : MemorySegment.ofAddress(address);
	}

	private static Pointer toPointer(final MemorySegment segment) {
		long address = segment.address();
		return address == 0L ? null : new Pointer(address);
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

	private static RuntimeException bridgeFailure(final String symbol, final Throwable throwable) {
		return new IllegalStateException("Native bridge call failed: " + symbol, throwable);
	}
}
