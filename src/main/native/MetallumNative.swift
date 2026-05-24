import Foundation
import AppKit
import Metal
import QuartzCore
import simd

private let metallumMaxVertexBufferSlot = 30
private let metallumSharedResourceOptions: MTLResourceOptions = .storageModeShared

private struct DynamicPipelineKey: Hashable {
    let deviceAddress: UInt
    let vertexSource: String
    let fragmentSource: String
    let vertexEntry: String
    let fragmentEntry: String
    let colorFormat: UInt
    let depthFormat: UInt
    let stencilFormat: UInt
    let vertexAttributes: [UInt64]
    let vertexOffsets: [UInt64]
    let vertexAttributeBufferSlots: [UInt64]
    let vertexBindingBufferSlots: [UInt64]
    let vertexBindingStrides: [UInt64]
    let vertexBindingStepRates: [UInt64]
    let blendEnabled: Bool
    let blendSourceRgb: UInt64
    let blendDestRgb: UInt64
    let blendOpRgb: UInt64
    let blendSourceAlpha: UInt64
    let blendDestAlpha: UInt64
    let blendOpAlpha: UInt64
    let writeMask: UInt64
}

private struct DepthStencilKey: Hashable {
    let deviceAddress: UInt
    let compareOp: UInt64
    let writeDepth: Bool
}

private struct PipelineVariantKey: Hashable {
    let deviceAddress: UInt
    let colorFormat: MTLPixelFormat
    let depthFormat: MTLPixelFormat
}

private enum NativeState {
    static var debugLabelsEnabled = false
    static var dynamicPipelines: [DynamicPipelineKey: MTLRenderPipelineState] = [:]
    static var depthStencilStates: [DepthStencilKey: MTLDepthStencilState] = [:]
    static var clearPipelines: [PipelineVariantKey: MTLRenderPipelineState] = [:]
    static var presentPipelines: [PipelineVariantKey: MTLRenderPipelineState] = [:]
    static var presentNearestSamplers: [UInt: MTLSamplerState] = [:]
    static var presentLinearSamplers: [UInt: MTLSamplerState] = [:]
}

@inline(__always)
private func retainedPointer(_ object: AnyObject?) -> UnsafeMutableRawPointer? {
    guard let object else {
        return nil
    }
    return UnsafeMutableRawPointer(Unmanaged.passRetained(object).toOpaque())
}

@inline(__always)
private func unretainedPointer(_ object: AnyObject?) -> UnsafeMutableRawPointer? {
    guard let object else {
        return nil
    }
    return UnsafeMutableRawPointer(Unmanaged.passUnretained(object).toOpaque())
}

@inline(__always)
private func object<T: AnyObject>(_ pointer: UnsafeMutableRawPointer?) -> T? {
    guard let pointer else {
        return nil
    }
    return Unmanaged<T>.fromOpaque(pointer).takeUnretainedValue()
}

@inline(__always)
private func object<T: AnyObject>(_ pointer: UnsafeRawPointer?) -> T? {
    guard let pointer else {
        return nil
    }
    return Unmanaged<T>.fromOpaque(UnsafeMutableRawPointer(mutating: pointer)).takeUnretainedValue()
}

@inline(__always)
private func objectAddress(_ object: AnyObject) -> UInt {
    UInt(bitPattern: Unmanaged.passUnretained(object).toOpaque())
}

@inline(__always)
private func withMetalAutoreleasePool<T>(_ body: () -> T) -> T {
    autoreleasepool(invoking: body)
}

private func textureSliceCount(_ texture: MTLTexture) -> Int {
    switch texture.textureType {
    case .type2DArray:
        return max(texture.arrayLength, 1)
    case .typeCube:
        return 6
    case .typeCubeArray:
        return max(texture.arrayLength, 1) * 6
    default:
        return 1
    }
}

private func primitiveType(from code: Int) -> MTLPrimitiveType {
    switch code {
    case 0: return .triangle
    case 1: return .triangleStrip
    case 2: return .line
    case 3: return .lineStrip
    case 4: return .point
    default: return .triangle
    }
}

private func vertexFormat(from code: UInt64) -> MTLVertexFormat {
    switch code {
    case 1: return .float
    case 2: return .float2
    case 3: return .float3
    case 4: return .float4
    case 5: return .uchar4Normalized
    case 6: return .uchar4
    case 7: return .ushort2
    case 8: return .ushort2Normalized
    case 9: return .short2
    case 10: return .short2Normalized
    case 11: return .ushort4
    case 12: return .short4
    case 13: return .ushort4Normalized
    case 14: return .short4Normalized
    case 15: return .uint
    case 16: return .uint2
    case 17: return .uint3
    case 18: return .uint4
    case 19: return .int
    case 20: return .int2
    case 21: return .int3
    case 22: return .int4
    case 23: return .half
    case 24: return .half2
    case 25: return .half4
    case 26: return .char4Normalized
    case 27: return .char4
    case 28: return .uchar3Normalized
    case 29: return .char3Normalized
    case 30: return .uchar3
    case 31: return .char3
    case 32: return .ushort3
    case 33: return .short3
    case 34: return .ushort3Normalized
    case 35: return .short3Normalized
    case 36: return .half3
    case 37: return .uchar4Normalized_bgra
    case 38: return .uchar2
    default: return .invalid
    }
}

private func blendFactor(from code: UInt64) -> MTLBlendFactor {
    switch code {
    case 0: return .zero
    case 1: return .one
    case 2: return .sourceColor
    case 3: return .oneMinusSourceColor
    case 4: return .sourceAlpha
    case 5: return .oneMinusSourceAlpha
    case 6: return .destinationColor
    case 7: return .oneMinusDestinationColor
    case 8: return .destinationAlpha
    case 9: return .oneMinusDestinationAlpha
    case 10: return .sourceAlphaSaturated
    case 11: return .blendColor
    case 12: return .oneMinusBlendColor
    case 13: return .blendAlpha
    case 14: return .oneMinusBlendAlpha
    default: return .one
    }
}

private func blendOperation(from code: UInt64) -> MTLBlendOperation {
    switch code {
    case 0: return .add
    case 1: return .subtract
    case 2: return .reverseSubtract
    case 3: return .min
    case 4: return .max
    default: return .add
    }
}

private func compareFunction(from code: UInt64) -> MTLCompareFunction {
    switch code {
    case 1: return .always
    case 2: return .less
    case 3: return .lessEqual
    case 4: return .equal
    case 5: return .notEqual
    case 6: return .greaterEqual
    case 7: return .greater
    case 8: return .never
    default: return .always
    }
}

private func stencilPixelFormat(for depthFormat: MTLPixelFormat) -> MTLPixelFormat {
    switch depthFormat {
    case .depth24Unorm_stencil8, .depth32Float_stencil8:
        return depthFormat
    default:
        return .invalid
    }
}

private func samplerAddressMode(from code: UInt64) -> MTLSamplerAddressMode {
    switch code {
    case 2: return .repeat
    default: return .clampToEdge
    }
}

private func samplerMinMagFilter(from code: UInt64) -> MTLSamplerMinMagFilter {
    switch code {
    case 1: return .linear
    default: return .nearest
    }
}

private func samplerMipFilter(from code: UInt64) -> MTLSamplerMipFilter {
    switch code {
    case 1: return .nearest
    case 2: return .linear
    default: return .notMipmapped
    }
}

private func makeClearColor(red: Float, green: Float, blue: Float, alpha: Float) -> MTLClearColor {
    MTLClearColor(red: Double(red), green: Double(green), blue: Double(blue), alpha: Double(alpha))
}

private func stringFromOptionalCString(_ pointer: UnsafePointer<CChar>?) -> String? {
    guard let pointer else {
        return nil
    }
    let value = String(cString: pointer)
    return value.isEmpty ? nil : value
}

private func presentMslSource() -> String {
    """
    #include <metal_stdlib>
    using namespace metal;

    struct PresentVertexOut {
      float4 position [[position]];
      float2 uv;
    };

    vertex PresentVertexOut metallum_present_vs(uint vertexId [[vertex_id]]) {
      const float2 positions[3] = {
        float2(-1.0,  1.0),
        float2( 3.0,  1.0),
        float2(-1.0, -3.0)
      };

      // Y-flip version:
      // old equivalent was uvMin=(0,1), uvMax=(1,0)
      const float2 uvs[3] = {
        float2(0.0,  1.0),
        float2(2.0,  1.0),
        float2(0.0, -1.0)
      };

      PresentVertexOut out;
      out.position = float4(positions[vertexId], 0.0, 1.0);
      out.uv = uvs[vertexId];
      return out;
    }

    fragment float4 metallum_present_fs(
      PresentVertexOut in [[stage_in]],
      texture2d<float> tex [[texture(0)]],
      sampler smp [[sampler(0)]]
    ) {
      return tex.sample(smp, in.uv);
    }
    """
}

private struct MetallumClearUniforms {
    var z: Float
    var _padding0: SIMD3<Float>
    var color: SIMD4<Float>
}

private func clearMslSource() -> String {
    """
    #include <metal_stdlib>
    using namespace metal;

    struct ClearUniforms {
      float z;
      float3 _padding0;
      float4 color;
    };

    struct ClearVertexOut {
      float4 position [[position]];
      float4 color;
    };

    vertex ClearVertexOut metallum_clear_vs(
      uint vertexId [[vertex_id]],
      constant ClearUniforms& u [[buffer(1)]]
    ) {
      const float2 positions[3] = {
        float2(-1.0,  1.0),
        float2( 3.0,  1.0),
        float2(-1.0, -3.0)
      };

      ClearVertexOut out;
      out.position = float4(positions[vertexId], u.z, 1.0);
      out.color = u.color;
      return out;
    }

    fragment float4 metallum_clear_fs(ClearVertexOut in [[stage_in]]) {
      return in.color;
    }
    """
}

private func encodeClearDraw(
    encoder: MTLRenderCommandEncoder,
    pipeline: MTLRenderPipelineState,
    textureWidth: Int,
    textureHeight: Int,
    clearColor: SIMD4<Float>,
    scissorRect: MTLScissorRect,
    depthState: MTLDepthStencilState? = nil,
    clearDepth: Double = 0.0
) {
    return withMetalAutoreleasePool {
    encoder.setViewport(MTLViewport(
        originX: 0.0,
        originY: 0.0,
        width: Double(textureWidth),
        height: Double(textureHeight),
        znear: 0.0,
        zfar: 1.0
    ))

    encoder.setScissorRect(scissorRect)
    encoder.setRenderPipelineState(pipeline)

    if let depthState {
        encoder.setDepthStencilState(depthState)
    }

    var uniforms = MetallumClearUniforms(
        z: depthState == nil ? 0.0 : Float(max(0.0, min(clearDepth, 1.0))),
        _padding0: SIMD3<Float>(0.0, 0.0, 0.0),
        color: clearColor
    )

    withUnsafeBytes(of: &uniforms) { bytes in
        encoder.setVertexBytes(bytes.baseAddress!, length: bytes.count, index: 1)
    }

    encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
    }
}

private func buildClearPipeline(
    device: MTLDevice,
    colorFormat: MTLPixelFormat,
    depthFormat: MTLPixelFormat = .invalid
) -> MTLRenderPipelineState? {
    return withMetalAutoreleasePool {
    do {
        let library = try device.makeLibrary(source: clearMslSource(), options: nil)

        guard
            let vertexFunction = library.makeFunction(name: "metallum_clear_vs"),
            let fragmentFunction = library.makeFunction(name: "metallum_clear_fs")
        else {
            NSLog("[metallum] Failed to create clear shader functions")
            return nil
        }

        let descriptor = MTLRenderPipelineDescriptor()
        descriptor.vertexFunction = vertexFunction
        descriptor.fragmentFunction = fragmentFunction
        descriptor.colorAttachments[0].pixelFormat = colorFormat
        descriptor.depthAttachmentPixelFormat = depthFormat
        descriptor.colorAttachments[0].isBlendingEnabled = false

        return try device.makeRenderPipelineState(descriptor: descriptor)
    } catch {
        NSLog("[metallum] Failed to create clear pipeline: %@", String(describing: error))
        return nil
    }
    }
}

private func buildPresentPipeline(
    device: MTLDevice,
    colorFormat: MTLPixelFormat
) -> MTLRenderPipelineState? {
    return withMetalAutoreleasePool {
    do {
        let library = try device.makeLibrary(source: presentMslSource(), options: nil)

        guard
            let vertexFunction = library.makeFunction(name: "metallum_present_vs"),
            let fragmentFunction = library.makeFunction(name: "metallum_present_fs")
        else {
            NSLog("[metallum] Failed to create present shader functions")
            return nil
        }

        let descriptor = MTLRenderPipelineDescriptor()
        descriptor.vertexFunction = vertexFunction
        descriptor.fragmentFunction = fragmentFunction
        descriptor.colorAttachments[0].pixelFormat = colorFormat
        descriptor.colorAttachments[0].isBlendingEnabled = false

        return try device.makeRenderPipelineState(descriptor: descriptor)
    } catch {
        NSLog("[metallum] Failed to create present render pipeline: %@", String(describing: error))
        return nil
    }
    }
}

private func ensurePresentLinearSampler(_ device: MTLDevice) -> MTLSamplerState? {
    return withMetalAutoreleasePool {
    let key = objectAddress(device)
    if let cached = NativeState.presentLinearSamplers[key] {
        return cached
    }
    let descriptor = MTLSamplerDescriptor()

    descriptor.minFilter = .linear
    descriptor.magFilter = .linear
    descriptor.mipFilter = .notMipmapped
    descriptor.sAddressMode = .clampToEdge
    descriptor.tAddressMode = .clampToEdge
    let sampler = device.makeSamplerState(descriptor: descriptor)
    if let sampler {
        NativeState.presentLinearSamplers[key] = sampler
    }
    return sampler
    }
}

private func ensurePresentNearestSampler(_ device: MTLDevice) -> MTLSamplerState? {
    return withMetalAutoreleasePool {
    let key = objectAddress(device)
    if let cached = NativeState.presentNearestSamplers[key] {
        return cached
    }
    let descriptor = MTLSamplerDescriptor()

    descriptor.minFilter = .nearest
    descriptor.magFilter = .nearest
    descriptor.mipFilter = .notMipmapped
    descriptor.sAddressMode = .clampToEdge
    descriptor.tAddressMode = .clampToEdge
    let sampler = device.makeSamplerState(descriptor: descriptor)
    if let sampler {
        NativeState.presentNearestSamplers[key] = sampler
    }
    return sampler
    }
}

private func ensurePresentPipeline(_ device: MTLDevice, _ colorFormat: MTLPixelFormat) -> MTLRenderPipelineState? {
    return withMetalAutoreleasePool {
    let key = PipelineVariantKey(deviceAddress: objectAddress(device), colorFormat: colorFormat, depthFormat: .invalid)
    if let cached = NativeState.presentPipelines[key] {
        return cached
    }
    let pipeline = buildPresentPipeline(device: device, colorFormat: colorFormat)
    if let pipeline {
        NativeState.presentPipelines[key] = pipeline
    }
    return pipeline
    }
}

private func ensureClearColorDepthPipeline(_ device: MTLDevice, _ colorFormat: MTLPixelFormat, _ depthFormat: MTLPixelFormat) -> MTLRenderPipelineState? {
    return withMetalAutoreleasePool {
    let key = PipelineVariantKey(deviceAddress: objectAddress(device), colorFormat: colorFormat, depthFormat: depthFormat)
    if let cached = NativeState.clearPipelines[key] {
        return cached
    }
    let pipeline = buildClearPipeline(device: device, colorFormat: colorFormat, depthFormat: depthFormat)
    if let pipeline {
        NativeState.clearPipelines[key] = pipeline
    }
    return pipeline
    }
}

private func ensureDepthStencilState(device: MTLDevice, compareOp: UInt64, writeDepth: Bool) -> MTLDepthStencilState? {
    return withMetalAutoreleasePool {
    let key = DepthStencilKey(deviceAddress: objectAddress(device), compareOp: compareOp, writeDepth: writeDepth)
    if let cached = NativeState.depthStencilStates[key] {
        return cached
    }
    let descriptor = MTLDepthStencilDescriptor()
    descriptor.depthCompareFunction = compareFunction(from: compareOp)
    descriptor.isDepthWriteEnabled = writeDepth
    let state = device.makeDepthStencilState(descriptor: descriptor)
    if let state {
        NativeState.depthStencilStates[key] = state
    }
    return state
    }
}

private func copiedArray(_ pointer: UnsafePointer<UInt64>?, count: UInt64) -> [UInt64] {
    return withMetalAutoreleasePool {
    guard let pointer, count > 0 else {
        return []
    }
    return Array(UnsafeBufferPointer(start: pointer, count: Int(count)))
    }
}

private func ensureDynamicPipeline(
    device: MTLDevice,
    vertexSource: String,
    fragmentSource: String,
    vertexEntry: String,
    fragmentEntry: String,
    colorFormat: MTLPixelFormat,
    depthFormat: MTLPixelFormat,
    stencilFormat: MTLPixelFormat,
    vertexAttributeFormats: UnsafePointer<UInt64>?,
    vertexAttributeOffsets: UnsafePointer<UInt64>?,
    vertexAttributeBufferSlots: UnsafePointer<UInt64>?,
    vertexAttributeCount: UInt64,
    vertexBindingBufferSlots: UnsafePointer<UInt64>?,
    vertexBindingStrides: UnsafePointer<UInt64>?,
    vertexBindingStepRates: UnsafePointer<UInt64>?,
    vertexBindingCount: UInt64,
    blendEnabled: Bool,
    blendSourceRgb: UInt64,
    blendDestRgb: UInt64,
    blendOpRgb: UInt64,
    blendSourceAlpha: UInt64,
    blendDestAlpha: UInt64,
    blendOpAlpha: UInt64,
    writeMask: UInt64
) -> MTLRenderPipelineState? {
    return withMetalAutoreleasePool {
    let formats = copiedArray(vertexAttributeFormats, count: vertexAttributeCount)
    let offsets = copiedArray(vertexAttributeOffsets, count: vertexAttributeCount)
    let attributeBufferSlots = copiedArray(vertexAttributeBufferSlots, count: vertexAttributeCount)
    let bindingBufferSlots = copiedArray(vertexBindingBufferSlots, count: vertexBindingCount)
    let bindingStrides = copiedArray(vertexBindingStrides, count: vertexBindingCount)
    let bindingStepRates = copiedArray(vertexBindingStepRates, count: vertexBindingCount)
    guard formats.count == offsets.count,
          formats.count == attributeBufferSlots.count,
          bindingBufferSlots.count == bindingStrides.count,
          bindingBufferSlots.count == bindingStepRates.count
    else {
        return nil
    }

    let key = DynamicPipelineKey(
        deviceAddress: objectAddress(device),
        vertexSource: vertexSource,
        fragmentSource: fragmentSource,
        vertexEntry: vertexEntry,
        fragmentEntry: fragmentEntry,
        colorFormat: colorFormat.rawValue,
        depthFormat: depthFormat.rawValue,
        stencilFormat: stencilFormat.rawValue,
        vertexAttributes: formats,
        vertexOffsets: offsets,
        vertexAttributeBufferSlots: attributeBufferSlots,
        vertexBindingBufferSlots: bindingBufferSlots,
        vertexBindingStrides: bindingStrides,
        vertexBindingStepRates: bindingStepRates,
        blendEnabled: blendEnabled,
        blendSourceRgb: blendSourceRgb,
        blendDestRgb: blendDestRgb,
        blendOpRgb: blendOpRgb,
        blendSourceAlpha: blendSourceAlpha,
        blendDestAlpha: blendDestAlpha,
        blendOpAlpha: blendOpAlpha,
        writeMask: writeMask
    )

    if let cached = NativeState.dynamicPipelines[key] {
        return cached
    }

    do {
        let vertexLibrary = try device.makeLibrary(source: vertexSource, options: nil)
        let fragmentLibrary = try device.makeLibrary(source: fragmentSource, options: nil)
        guard
            let vertexFunction = vertexLibrary.makeFunction(name: vertexEntry),
            let fragmentFunction = fragmentLibrary.makeFunction(name: fragmentEntry)
        else {
            NSLog("[metallum] Failed to resolve MSL entry points v='%@' f='%@'", vertexEntry, fragmentEntry)
            return nil
        }

        let descriptor = MTLRenderPipelineDescriptor()

        descriptor.vertexFunction = vertexFunction
        descriptor.fragmentFunction = fragmentFunction
        descriptor.colorAttachments[0].pixelFormat = colorFormat
        descriptor.depthAttachmentPixelFormat = depthFormat
        descriptor.stencilAttachmentPixelFormat = stencilFormat
        descriptor.colorAttachments[0].writeMask = MTLColorWriteMask(rawValue: UInt(writeMask))

        if blendEnabled {
            descriptor.colorAttachments[0].isBlendingEnabled = true
            descriptor.colorAttachments[0].sourceRGBBlendFactor = blendFactor(from: blendSourceRgb)
            descriptor.colorAttachments[0].destinationRGBBlendFactor = blendFactor(from: blendDestRgb)
            descriptor.colorAttachments[0].rgbBlendOperation = blendOperation(from: blendOpRgb)
            descriptor.colorAttachments[0].sourceAlphaBlendFactor = blendFactor(from: blendSourceAlpha)
            descriptor.colorAttachments[0].destinationAlphaBlendFactor = blendFactor(from: blendDestAlpha)
            descriptor.colorAttachments[0].alphaBlendOperation = blendOperation(from: blendOpAlpha)
        } else {
            descriptor.colorAttachments[0].isBlendingEnabled = false
        }

        if vertexAttributeCount > 0 {
            let vertexDescriptor = MTLVertexDescriptor()
            for index in 0..<Int(vertexAttributeCount) {
                let format = vertexFormat(from: formats[index])
                if format == .invalid {
                    NSLog("[metallum] Unsupported vertex attribute format code: %llu", formats[index])
                    return nil
                }
                vertexDescriptor.attributes[index].format = format
                vertexDescriptor.attributes[index].offset = Int(offsets[index])
                vertexDescriptor.attributes[index].bufferIndex = Int(attributeBufferSlots[index])
            }
            for index in 0..<Int(vertexBindingCount) {
                let bufferSlot = Int(bindingBufferSlots[index])
                if bufferSlot < 0 || bufferSlot > metallumMaxVertexBufferSlot {
                    NSLog("[metallum] Unsupported vertex buffer slot: %d", bufferSlot)
                    return nil
                }
                vertexDescriptor.layouts[bufferSlot].stride = Int(bindingStrides[index])
                if bindingStepRates[index] > 0 {
                    vertexDescriptor.layouts[bufferSlot].stepFunction = .perInstance
                    vertexDescriptor.layouts[bufferSlot].stepRate = Int(bindingStepRates[index])
                } else {
                    vertexDescriptor.layouts[bufferSlot].stepFunction = .perVertex
                    vertexDescriptor.layouts[bufferSlot].stepRate = 1
                }
            }
            descriptor.vertexDescriptor = vertexDescriptor
        }

        let pipeline = try device.makeRenderPipelineState(descriptor: descriptor)
        NativeState.dynamicPipelines[key] = pipeline
        return pipeline
    } catch {
        NSLog("[metallum] Failed to create dynamic render pipeline: %@", String(describing: error))
        return nil
    }
    }
}

private func triangleFanOutputIndexCount(sourceCount: Int, buffer: MTLBuffer) -> Int? {
    let triangleCount = sourceCount - 2
    guard triangleCount <= Int.max / 3 else {
        return nil
    }

    let indexCount = triangleCount * 3
    let bufferIndexCapacity = UInt64(buffer.length / MemoryLayout<UInt32>.stride)
    guard indexCount <= UInt64(Int.max), indexCount <= bufferIndexCapacity else {
        return nil
    }
    return Int(indexCount)
}

private func writeSequentialTriangleFanIndices(_ indexBuffer: MTLBuffer, vertexCount: Int) -> Int? {
    return withMetalAutoreleasePool {
    guard vertexCount >= 3, vertexCount - 1 <= UInt64(UInt32.max), let generatedIndexCount = triangleFanOutputIndexCount(sourceCount: vertexCount, buffer: indexBuffer) else {
        return nil
    }
    let triangleCount = vertexCount - 2
    let indices = indexBuffer.contents().assumingMemoryBound(to: UInt32.self)
    var writeIndex = 0
    for triangle in 0..<triangleCount {
        indices[writeIndex] = 0
        indices[writeIndex + 1] = UInt32(triangle + 1)
        indices[writeIndex + 2] = UInt32(triangle + 2)
        writeIndex += 3
    }
    return generatedIndexCount
    }
}

private func readIndex(_ indexBuffer: MTLBuffer, byteOffset: Int, index: Int, indexType: Int) -> UInt32 {
    let base = indexBuffer.contents().advanced(by: Int(byteOffset))
    if indexType == 0 {
        return UInt32(base.assumingMemoryBound(to: UInt16.self)[Int(index)])
    }
    return base.assumingMemoryBound(to: UInt32.self)[Int(index)]
}

private func writeIndexedTriangleFanIndices(
    sourceIndexBuffer: MTLBuffer,
    destinationIndexBuffer: MTLBuffer,
    indexType: Int,
    indexOffsetBytes: Int,
    indexCount: Int
) -> Int? {
    return withMetalAutoreleasePool {
    guard indexCount >= 3, let generatedIndexCount = triangleFanOutputIndexCount(sourceCount: indexCount, buffer: destinationIndexBuffer) else {
        return nil
    }
    let triangleCount = indexCount - 2
    let center = readIndex(sourceIndexBuffer, byteOffset: indexOffsetBytes, index: 0, indexType: indexType)
    let indices = destinationIndexBuffer.contents().assumingMemoryBound(to: UInt32.self)
    var writeIndex = 0
    for triangle in 0..<triangleCount {
        indices[writeIndex] = center
        indices[writeIndex + 1] = readIndex(sourceIndexBuffer, byteOffset: indexOffsetBytes, index: triangle + 1, indexType: indexType)
        indices[writeIndex + 2] = readIndex(sourceIndexBuffer, byteOffset: indexOffsetBytes, index: triangle + 2, indexType: indexType)
        writeIndex += 3
    }
    return generatedIndexCount
    }
}

@_cdecl("metallum_create_system_default_device")
public func metallum_create_system_default_device() -> UnsafeMutableRawPointer? {
    return withMetalAutoreleasePool {
        retainedPointer(MTLCreateSystemDefaultDevice())
    }
}

@_cdecl("metallum_copy_device_name")
public func metallum_copy_device_name(
    _ devicePtr: UnsafeMutableRawPointer?,
    _ output: UnsafeMutablePointer<CChar>?,
    _ capacity: Int64
) -> Int32 {
    guard let device: MTLDevice = object(devicePtr), let output, capacity > 0 else {
        return 1
    }
    let maxLength = Int(capacity - 1)
    let bytes = Array(device.name.utf8.prefix(maxLength))
    for i in 0..<bytes.count {
        output[i] = CChar(bitPattern: bytes[i])
    }
    output[bytes.count] = 0
    return 0
}

@_cdecl("metallum_NSWindow_backingScaleFactor")
public func metallum_NSWindow_backingScaleFactor(_ windowPtr: UnsafeMutableRawPointer?) -> Double {
    guard let window: NSWindow = object(windowPtr) else {
        return 1.0
    }
    return Double(window.backingScaleFactor)
}

@_cdecl("metallum_create_metal_layer")
public func metallum_create_metal_layer(
    _ devicePtr: UnsafeMutableRawPointer?,
    _ contentsScale: Double
) -> UnsafeMutableRawPointer? {
    return withMetalAutoreleasePool {
    guard let device: MTLDevice = object(devicePtr) else {
        return nil
    }
    let layer = CAMetalLayer()
    layer.device = device
    layer.framebufferOnly = true
    layer.isOpaque = true
    layer.contentsScale = CGFloat(contentsScale > 0.0 ? contentsScale : 1.0)
    return retainedPointer(layer)
    }
}

@_cdecl("metallum_NSView_setMetalLayer")
public func metallum_NSView_setMetalLayer(
    _ viewPtr: UnsafeMutableRawPointer?,
    _ layerPtr: UnsafeMutableRawPointer?
) -> Int32 {
    guard let view: NSView = object(viewPtr), let layer: CAMetalLayer = object(layerPtr) else {
        return 1
    }
    view.wantsLayer = true
    view.layer = layer
    return 0
}

@_cdecl("metallum_NSView_clearLayer")
public func metallum_NSView_clearLayer(_ viewPtr: UnsafeMutableRawPointer?) {
    return withMetalAutoreleasePool {
    guard let view: NSView = object(viewPtr) else {
        return
    }
    view.layer = nil
    view.wantsLayer = false
    }
}

@_cdecl("metallum_set_debug_labels_enabled")
public func metallum_set_debug_labels_enabled(_ enabled: Int32) {
    NativeState.debugLabelsEnabled = enabled != 0
}

@_cdecl("metallum_MTLDevice_makeCommandQueue")
public func metallum_MTLDevice_makeCommandQueue(_ devicePtr: UnsafeMutableRawPointer?) -> UnsafeMutableRawPointer? {
    return withMetalAutoreleasePool {
    guard let device: MTLDevice = object(devicePtr) else {
        return nil
    }
    return retainedPointer(device.makeCommandQueue())
    }
}

@_cdecl("metallum_MTLCommandQueue_makeCommandBuffer")
public func metallum_MTLCommandQueue_makeCommandBuffer(
    _ commandQueuePtr: UnsafeMutableRawPointer?,
    _ labelPtr: UnsafePointer<CChar>?
) -> UnsafeMutableRawPointer? {
    return withMetalAutoreleasePool {
    guard let queue: MTLCommandQueue = object(commandQueuePtr), let commandBuffer = queue.makeCommandBuffer() else {
        return nil
    }
    if NativeState.debugLabelsEnabled {
        commandBuffer.label = stringFromOptionalCString(labelPtr)
    }
    return retainedPointer(commandBuffer)
    }
}

@_cdecl("metallum_MTLCommandBuffer_commit")
public func metallum_MTLCommandBuffer_commit(_ commandBufferPtr: UnsafeMutableRawPointer?) {
    return withMetalAutoreleasePool {
    guard let commandBuffer: MTLCommandBuffer = object(commandBufferPtr) else {
        return
    }
    commandBuffer.commit()
    }
}

@_cdecl("metallum_MTLCommandBuffer_isCompleted")
public func metallum_MTLCommandBuffer_isCompleted(_ commandBufferPtr: UnsafeMutableRawPointer?) -> Int32 {
    return withMetalAutoreleasePool {
    guard let commandBuffer: MTLCommandBuffer = object(commandBufferPtr) else {
        return 2
    }
    return commandBuffer.status == .completed || commandBuffer.status == .error ? 1 : 0
    }
}

@_cdecl("metallum_MTLCommandBuffer_waitUntilCompleted")
public func metallum_MTLCommandBuffer_waitUntilCompleted(_ commandBufferPtr: UnsafeMutableRawPointer?, _ timeoutMs: UInt64) -> Int32 {
    return withMetalAutoreleasePool {
    guard let commandBuffer: MTLCommandBuffer = object(commandBufferPtr) else {
        return 2
    }
    if commandBuffer.status == .completed || commandBuffer.status == .error {
        return 0
    }
    if timeoutMs == 0 {
        return 1
    }
    commandBuffer.waitUntilCompleted()
    return commandBuffer.status == .completed || commandBuffer.status == .error ? 0 : 1
    }
}

@_cdecl("metallum_MTLCommandBuffer_pushDebugGroup")
public func metallum_MTLCommandBuffer_pushDebugGroup(
    _ commandBufferPtr: UnsafeMutableRawPointer?,
    _ labelPtr: UnsafePointer<CChar>?
) {
    return withMetalAutoreleasePool {
    guard NativeState.debugLabelsEnabled, let commandBuffer: MTLCommandBuffer = object(commandBufferPtr) else {
        return
    }
    commandBuffer.pushDebugGroup(stringFromOptionalCString(labelPtr) ?? "")
    }
}

@_cdecl("metallum_MTLCommandBuffer_popDebugGroup")
public func metallum_MTLCommandBuffer_popDebugGroup(_ commandBufferPtr: UnsafeMutableRawPointer?) {
    return withMetalAutoreleasePool {
    guard NativeState.debugLabelsEnabled, let commandBuffer: MTLCommandBuffer = object(commandBufferPtr) else {
        return
    }
    commandBuffer.popDebugGroup()
    }
}

@_cdecl("metallum_MTLCommandBuffer_makeBlitCommandEncoder")
public func metallum_MTLCommandBuffer_makeBlitCommandEncoder(
    _ commandBufferPtr: UnsafeMutableRawPointer?
) -> UnsafeMutableRawPointer? {
    return withMetalAutoreleasePool {
    guard let commandBuffer: MTLCommandBuffer = object(commandBufferPtr), let encoder = commandBuffer.makeBlitCommandEncoder() else {
        return nil
    }
    return retainedPointer(encoder)
    }
}

@_cdecl("metallum_MTLCommandEncoder_endEncoding")
public func metallum_MTLCommandEncoder_endEncoding(_ encoderPtr: UnsafeMutableRawPointer?) {
    return withMetalAutoreleasePool {
    guard let encoder: MTLCommandEncoder = object(encoderPtr) else {
        return
    }
    encoder.endEncoding()
    }
}

@_cdecl("metallum_MTLBlitCommandEncoder_endEncoding")
public func metallum_MTLBlitCommandEncoder_endEncoding(_ blitEncoderPtr: UnsafeMutableRawPointer?) {
    return withMetalAutoreleasePool {
    guard let encoder: MTLBlitCommandEncoder = object(blitEncoderPtr) else {
        return
    }
    encoder.endEncoding()
    }
}

@_cdecl("metallum_MTLBlitCommandEncoder_copyFromBufferToBuffer")
public func metallum_MTLBlitCommandEncoder_copyFromBufferToBuffer(
    _ blitEncoderPtr: UnsafeMutableRawPointer?,
    _ sourceBufferPtr: UnsafeMutableRawPointer?,
    _ sourceOffset: UInt64,
    _ destinationBufferPtr: UnsafeMutableRawPointer?,
    _ destinationOffset: UInt64,
    _ length: UInt64
) {
    return withMetalAutoreleasePool {
    guard
        let blit: MTLBlitCommandEncoder = object(blitEncoderPtr),
        let sourceBuffer: MTLBuffer = object(sourceBufferPtr),
        let destinationBuffer: MTLBuffer = object(destinationBufferPtr),
        length > 0
    else {
        return
    }
    blit.copy(from: sourceBuffer, sourceOffset: Int(sourceOffset), to: destinationBuffer, destinationOffset: Int(destinationOffset), size: Int(length))
    }
}

@_cdecl("metallum_MTLBlitCommandEncoder_copyFromBufferToTexture")
public func metallum_MTLBlitCommandEncoder_copyFromBufferToTexture(
    _ blitEncoderPtr: UnsafeMutableRawPointer?,
    _ sourceBufferPtr: UnsafeMutableRawPointer?,
    _ sourceOffset: UInt64,
    _ texturePtr: UnsafeMutableRawPointer?,
    _ mipLevel: UInt64,
    _ slice: UInt64,
    _ x: UInt64,
    _ y: UInt64,
    _ width: UInt64,
    _ height: UInt64,
    _ bytesPerRow: UInt64,
    _ bytesPerImage: UInt64
) {
    return withMetalAutoreleasePool {
    guard
        let blit: MTLBlitCommandEncoder = object(blitEncoderPtr),
        let sourceBuffer: MTLBuffer = object(sourceBufferPtr),
        let texture: MTLTexture = object(texturePtr),
        width > 0,
        height > 0
    else {
        return
    }
    blit.copy(
        from: sourceBuffer,
        sourceOffset: Int(sourceOffset),
        sourceBytesPerRow: Int(bytesPerRow),
        sourceBytesPerImage: Int(bytesPerImage),
        sourceSize: MTLSize(width: Int(width), height: Int(height), depth: 1),
        to: texture,
        destinationSlice: Int(slice),
        destinationLevel: Int(mipLevel),
        destinationOrigin: MTLOrigin(x: Int(x), y: Int(y), z: 0)
    )
    }
}

@_cdecl("metallum_MTLBlitCommandEncoder_copyFromTextureToTexture")
public func metallum_MTLBlitCommandEncoder_copyFromTextureToTexture(
    _ blitEncoderPtr: UnsafeMutableRawPointer?,
    _ sourceTexturePtr: UnsafeMutableRawPointer?,
    _ destinationTexturePtr: UnsafeMutableRawPointer?,
    _ mipLevel: UInt64,
    _ sourceX: UInt64,
    _ sourceY: UInt64,
    _ destX: UInt64,
    _ destY: UInt64,
    _ width: UInt64,
    _ height: UInt64
) {
    return withMetalAutoreleasePool {
    guard
        let blit: MTLBlitCommandEncoder = object(blitEncoderPtr),
        let sourceTexture: MTLTexture = object(sourceTexturePtr),
        let destinationTexture: MTLTexture = object(destinationTexturePtr),
        width > 0,
        height > 0
    else {
        return
    }
    blit.copy(
        from: sourceTexture,
        sourceSlice: 0,
        sourceLevel: Int(mipLevel),
        sourceOrigin: MTLOrigin(x: Int(sourceX), y: Int(sourceY), z: 0),
        sourceSize: MTLSize(width: Int(width), height: Int(height), depth: 1),
        to: destinationTexture,
        destinationSlice: 0,
        destinationLevel: Int(mipLevel),
        destinationOrigin: MTLOrigin(x: Int(destX), y: Int(destY), z: 0)
    )
    }
}

@_cdecl("metallum_MTLBlitCommandEncoder_copyFromTextureToBuffer")
public func metallum_MTLBlitCommandEncoder_copyFromTextureToBuffer(
    _ blitEncoderPtr: UnsafeMutableRawPointer?,
    _ sourceTexturePtr: UnsafeMutableRawPointer?,
    _ destinationBufferPtr: UnsafeMutableRawPointer?,
    _ destinationOffset: UInt64,
    _ mipLevel: UInt64,
    _ slice: UInt64,
    _ x: UInt64,
    _ y: UInt64,
    _ width: UInt64,
    _ height: UInt64,
    _ bytesPerRow: UInt64,
    _ bytesPerImage: UInt64
) {
    return withMetalAutoreleasePool {
    guard
        let blit: MTLBlitCommandEncoder = object(blitEncoderPtr),
        let sourceTexture: MTLTexture = object(sourceTexturePtr),
        let destinationBuffer: MTLBuffer = object(destinationBufferPtr),
        width > 0,
        height > 0
    else {
        return
    }
    blit.copy(
        from: sourceTexture,
        sourceSlice: Int(slice),
        sourceLevel: Int(mipLevel),
        sourceOrigin: MTLOrigin(x: Int(x), y: Int(y), z: 0),
        sourceSize: MTLSize(width: Int(width), height: Int(height), depth: 1),
        to: destinationBuffer,
        destinationOffset: Int(destinationOffset),
        destinationBytesPerRow: Int(bytesPerRow),
        destinationBytesPerImage: Int(bytesPerImage)
    )
    }
}

@_cdecl("metallum_create_buffer")
public func metallum_create_buffer(
    _ devicePtr: UnsafeMutableRawPointer?,
    _ length: UInt64,
    _ options: UInt64
) -> UnsafeMutableRawPointer? {
    return withMetalAutoreleasePool {
    guard let device: MTLDevice = object(devicePtr) else {
        return nil
    }
    guard let buffer = device.makeBuffer(length: Int(length), options: MTLResourceOptions(rawValue: UInt(options))) else {
        return nil
    }
    return retainedPointer(buffer)
    }
}

@_cdecl("metallum_create_texture_2d")
public func metallum_create_texture_2d(
    _ devicePtr: UnsafeMutableRawPointer?,
    _ pixelFormat: UInt64,
    _ width: UInt64,
    _ height: UInt64,
    _ depthOrLayers: UInt64,
    _ mipLevels: UInt64,
    _ cubeCompatible: UInt64,
    _ usage: UInt64,
    _ storageMode: UInt64,
    _ labelPtr: UnsafePointer<CChar>?
) -> UnsafeMutableRawPointer? {
    return withMetalAutoreleasePool {
    guard let device: MTLDevice = object(devicePtr) else {
        return nil
    }

    let descriptor = MTLTextureDescriptor.texture2DDescriptor(
        pixelFormat: MTLPixelFormat(rawValue: UInt(pixelFormat)) ?? .invalid,
        width: Int(width),
        height: Int(height),
        mipmapped: mipLevels > 1
    )

    if cubeCompatible != 0 {
        if depthOrLayers > 6 {
            descriptor.textureType = MTLTextureType.typeCubeArray
            descriptor.arrayLength = Int(depthOrLayers)
        } else {
            descriptor.textureType = MTLTextureType.typeCube
            descriptor.arrayLength = 1
        }
    } else if depthOrLayers > 1 {
        descriptor.textureType = MTLTextureType.type2DArray
        descriptor.arrayLength = Int(depthOrLayers)
    }

    descriptor.mipmapLevelCount = max(Int(mipLevels), 1)
    descriptor.usage = MTLTextureUsage(rawValue: UInt(usage))
    descriptor.storageMode = MTLStorageMode(rawValue: UInt(storageMode)) ?? .shared
    descriptor.hazardTrackingMode = .untracked
    guard let texture = device.makeTexture(descriptor: descriptor) else {
        return nil
    }
    texture.label = stringFromOptionalCString(labelPtr)
    return retainedPointer(texture)
    }
}

@_cdecl("metallum_create_texture_view")
public func metallum_create_texture_view(_ texturePtr: UnsafeMutableRawPointer?, _ baseMipLevel: UInt64, _ mipLevelCount: UInt64) -> UnsafeMutableRawPointer? {
    return withMetalAutoreleasePool {
    guard let texture: MTLTexture = object(texturePtr), mipLevelCount > 0 else {
        return nil
    }

    let baseLevel = Int(baseMipLevel)
    let levelCount = Int(mipLevelCount)
    guard baseLevel < texture.mipmapLevelCount, baseLevel + levelCount <= texture.mipmapLevelCount else {
        return nil
    }

    let view = texture.__newTextureView(
        with: texture.pixelFormat,
        textureType: texture.textureType,
        levels: NSRange(location: baseLevel, length: levelCount),
        slices: NSRange(location: 0, length: textureSliceCount(texture))
    )

    return retainedPointer(view)
    }
}

@_cdecl("metallum_create_buffer_texture_view")
public func metallum_create_buffer_texture_view(
    _ bufferPtr: UnsafeMutableRawPointer?,
    _ pixelFormat: UInt64,
    _ offset: UInt64,
    _ width: UInt64,
    _ height: UInt64,
    _ bytesPerRow: UInt64
) -> UnsafeMutableRawPointer? {
    return withMetalAutoreleasePool {
    guard
        let buffer: MTLBuffer = object(bufferPtr),
        let metalPixelFormat = MTLPixelFormat(rawValue: UInt(pixelFormat)),
        metalPixelFormat != .invalid,
        width > 0,
        bytesPerRow > 0
    else {
        return nil
    }

    let nativeOffset = Int(offset)
    let nativeWidth = Int(width)
    let nativeBytesPerRow = Int(bytesPerRow)
    guard nativeOffset >= 0, nativeWidth > 0, nativeBytesPerRow > 0, nativeOffset <= buffer.length, nativeBytesPerRow <= buffer.length - nativeOffset else {
        return nil
    }

    let alignment = buffer.device.minimumLinearTextureAlignment(for: metalPixelFormat)
    guard alignment > 0, nativeOffset % alignment == 0 else {
        return nil
    }

    let alignedBytesPerRow = roundUp(nativeBytesPerRow, alignment: alignment)
    let descriptor = MTLTextureDescriptor.textureBufferDescriptor(
        with: metalPixelFormat,
        width: nativeWidth,
        resourceOptions: [],
        usage: MTLTextureUsage.shaderRead
    )
    descriptor.storageMode = buffer.storageMode
    descriptor.hazardTrackingMode = .untracked

    guard let textureView = buffer.makeTexture(descriptor: descriptor, offset: nativeOffset, bytesPerRow: alignedBytesPerRow) else {
        return nil
    }

    return retainedPointer(textureView)
    }
}

private func roundUp(_ value: Int, alignment: Int) -> Int {
    let remainder = value % alignment
    return remainder == 0 ? value : value + alignment - remainder
}

@_cdecl("metallum_create_sampler")
public func metallum_create_sampler(
    _ devicePtr: UnsafeMutableRawPointer?,
    _ addressModeU: UInt64,
    _ addressModeV: UInt64,
    _ minFilter: UInt64,
    _ magFilter: UInt64,
    _ mipFilter: UInt64,
    _ maxAnisotropy: Int32,
    _ lodMaxClamp: Double
) -> UnsafeMutableRawPointer? {
    return withMetalAutoreleasePool {
    guard let device: MTLDevice = object(devicePtr) else {
        return nil
    }
    let descriptor = MTLSamplerDescriptor()
    descriptor.minFilter = samplerMinMagFilter(from: minFilter)
    descriptor.magFilter = samplerMinMagFilter(from: magFilter)
    descriptor.mipFilter = samplerMipFilter(from: mipFilter)
    descriptor.sAddressMode = samplerAddressMode(from: addressModeU)
    descriptor.tAddressMode = samplerAddressMode(from: addressModeV)
    descriptor.maxAnisotropy = max(Int(maxAnisotropy), 1)
    descriptor.lodMinClamp = 0.0
    descriptor.lodMaxClamp = lodMaxClamp >= 0.0 && lodMaxClamp.isFinite ? Float(lodMaxClamp) : Float.greatestFiniteMagnitude
    return retainedPointer(device.makeSamplerState(descriptor: descriptor))
    }
}

@_cdecl("metallum_MTLDevice_makeDepthStencilState")
public func metallum_MTLDevice_makeDepthStencilState(
    _ devicePtr: UnsafeMutableRawPointer?,
    _ depthCompareOp: UInt64,
    _ writeDepth: Int32
) -> UnsafeMutableRawPointer? {
    return withMetalAutoreleasePool {
    guard let device: MTLDevice = object(devicePtr) else {
        return nil
    }
    return unretainedPointer(ensureDepthStencilState(device: device, compareOp: depthCompareOp, writeDepth: writeDepth != 0))
    }
}

@_cdecl("metallum_MTLCommandBuffer_makeRenderCommandEncoder")
public func metallum_MTLCommandBuffer_makeRenderCommandEncoder(
    _ commandBufferPtr: UnsafeMutableRawPointer?,
    _ colorTexturePtr: UnsafeMutableRawPointer?,
    _ depthTexturePtr: UnsafeMutableRawPointer?,
    _ viewportWidth: Double,
    _ viewportHeight: Double,
    _ clearColorEnabled: Int32,
    _ clearColorRed: Float,
    _ clearColorGreen: Float,
    _ clearColorBlue: Float,
    _ clearColorAlpha: Float,
    _ clearDepthEnabled: Int32,
    _ clearDepth: Double
) -> UnsafeMutableRawPointer? {
    return withMetalAutoreleasePool {
    guard let commandBuffer: MTLCommandBuffer = object(commandBufferPtr) else {
        return nil
    }
    let colorTexture: MTLTexture? = object(colorTexturePtr)
    let depthTexture: MTLTexture? = object(depthTexturePtr)
    guard colorTexture != nil || depthTexture != nil else {
        return nil
    }
    let depthFormat = depthTexture?.pixelFormat ?? .invalid
    let stencilFormat = stencilPixelFormat(for: depthFormat)

    let renderPass = MTLRenderPassDescriptor()
    if let colorTexture {
        renderPass.colorAttachments[0].texture = colorTexture
        if clearColorEnabled != 0 {
            renderPass.colorAttachments[0].loadAction = .clear
            renderPass.colorAttachments[0].clearColor = makeClearColor(red: clearColorRed, green: clearColorGreen, blue: clearColorBlue, alpha: clearColorAlpha)
        } else {
            renderPass.colorAttachments[0].loadAction = .load
        }
        renderPass.colorAttachments[0].storeAction = .store
    }

    if let depthTexture {
        renderPass.depthAttachment.texture = depthTexture
        renderPass.depthAttachment.loadAction = clearDepthEnabled != 0 ? .clear : .load
        renderPass.depthAttachment.clearDepth = clearDepth
        renderPass.depthAttachment.storeAction = .store
        if stencilFormat != .invalid {
            renderPass.stencilAttachment.texture = depthTexture
            renderPass.stencilAttachment.loadAction = .dontCare
            renderPass.stencilAttachment.storeAction = .dontCare
        }
    }

    guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: renderPass) else {
        return nil
    }
    encoder.setViewport(MTLViewport(originX: 0.0, originY: 0.0, width: viewportWidth, height: viewportHeight, znear: 0.0, zfar: 1.0))
    return retainedPointer(encoder)
    }
}

@_cdecl("metallum_MTLRenderCommandEncoder_setRenderPipelineState")
public func metallum_MTLRenderCommandEncoder_setRenderPipelineState(_ encoderPtr: UnsafeMutableRawPointer?, _ pipelinePtr: UnsafeMutableRawPointer?) {
    return withMetalAutoreleasePool {
    guard let encoder: MTLRenderCommandEncoder = object(encoderPtr), let pipeline: MTLRenderPipelineState = object(pipelinePtr) else {
        return
    }
    encoder.setRenderPipelineState(pipeline)
    }
}

@_cdecl("metallum_MTLRenderCommandEncoder_setDepthStencilState")
public func metallum_MTLRenderCommandEncoder_setDepthStencilState(_ encoderPtr: UnsafeMutableRawPointer?, _ depthStencilStatePtr: UnsafeMutableRawPointer?) {
    return withMetalAutoreleasePool {
    guard let encoder: MTLRenderCommandEncoder = object(encoderPtr) else {
        return
    }
    let state: MTLDepthStencilState? = object(depthStencilStatePtr)
    encoder.setDepthStencilState(state)
    }
}

@_cdecl("metallum_MTLRenderCommandEncoder_setDepthBias")
public func metallum_MTLRenderCommandEncoder_setDepthBias(
    _ encoderPtr: UnsafeMutableRawPointer?,
    _ depthBias: Double,
    _ slopeScale: Double,
    _ clamp: Double
) {
    return withMetalAutoreleasePool {
    guard let encoder: MTLRenderCommandEncoder = object(encoderPtr) else {
        return
    }
    encoder.setDepthBias(Float(depthBias), slopeScale: Float(slopeScale), clamp: Float(clamp))
    }
}

@_cdecl("metallum_MTLRenderCommandEncoder_setFrontFacingWinding")
public func metallum_MTLRenderCommandEncoder_setFrontFacingWinding(_ encoderPtr: UnsafeMutableRawPointer?, _ clockwise: Int32) {
    return withMetalAutoreleasePool {
    guard let encoder: MTLRenderCommandEncoder = object(encoderPtr) else {
        return
    }
    encoder.setFrontFacing(clockwise != 0 ? .clockwise : .counterClockwise)
    }
}

@_cdecl("metallum_MTLRenderCommandEncoder_setCullMode")
public func metallum_MTLRenderCommandEncoder_setCullMode(_ encoderPtr: UnsafeMutableRawPointer?, _ cullMode: UInt64) {
    return withMetalAutoreleasePool {
    guard let encoder: MTLRenderCommandEncoder = object(encoderPtr) else {
        return
    }
    switch cullMode {
    case 1:
        encoder.setCullMode(.front)
    case 2:
        encoder.setCullMode(.back)
    default:
        encoder.setCullMode(.none)
    }
    }
}

@_cdecl("metallum_MTLRenderCommandEncoder_setTriangleFillMode")
public func metallum_MTLRenderCommandEncoder_setTriangleFillMode(_ encoderPtr: UnsafeMutableRawPointer?, _ lines: Int32) {
    return withMetalAutoreleasePool {
    guard let encoder: MTLRenderCommandEncoder = object(encoderPtr) else {
        return
    }
    encoder.setTriangleFillMode(lines != 0 ? .lines : .fill)
    }
}

@_cdecl("metallum_MTLRenderCommandEncoder_setVertexBuffer")
public func metallum_MTLRenderCommandEncoder_setVertexBuffer(_ encoderPtr: UnsafeMutableRawPointer?, _ bufferPtr: UnsafeMutableRawPointer?, _ offset: UInt64, _ index: UInt64) {
    return withMetalAutoreleasePool {
    guard let encoder: MTLRenderCommandEncoder = object(encoderPtr) else {
        return
    }
    if index > UInt64(metallumMaxVertexBufferSlot) {
        return
    }
    let buffer: MTLBuffer? = object(bufferPtr)
    encoder.setVertexBuffer(buffer, offset: Int(offset), index: Int(index))
    }
}

@_cdecl("metallum_MTLRenderCommandEncoder_setFragmentBuffer")
public func metallum_MTLRenderCommandEncoder_setFragmentBuffer(_ encoderPtr: UnsafeMutableRawPointer?, _ bufferPtr: UnsafeMutableRawPointer?, _ offset: UInt64, _ index: UInt64) {
    return withMetalAutoreleasePool {
    guard let encoder: MTLRenderCommandEncoder = object(encoderPtr) else {
        return
    }
    let buffer: MTLBuffer? = object(bufferPtr)
    encoder.setFragmentBuffer(buffer, offset: Int(offset), index: Int(index))
    }
}

@_cdecl("metallum_MTLRenderCommandEncoder_setVertexTexture")
public func metallum_MTLRenderCommandEncoder_setVertexTexture(_ encoderPtr: UnsafeMutableRawPointer?, _ texturePtr: UnsafeMutableRawPointer?, _ index: UInt64) {
    return withMetalAutoreleasePool {
    guard let encoder: MTLRenderCommandEncoder = object(encoderPtr) else {
        return
    }
    let texture: MTLTexture? = object(texturePtr)
    encoder.setVertexTexture(texture, index: Int(index))
    }
}

@_cdecl("metallum_MTLRenderCommandEncoder_setFragmentTexture")
public func metallum_MTLRenderCommandEncoder_setFragmentTexture(_ encoderPtr: UnsafeMutableRawPointer?, _ texturePtr: UnsafeMutableRawPointer?, _ index: UInt64) {
    return withMetalAutoreleasePool {
    guard let encoder: MTLRenderCommandEncoder = object(encoderPtr) else {
        return
    }
    let texture: MTLTexture? = object(texturePtr)
    encoder.setFragmentTexture(texture, index: Int(index))
    }
}

@_cdecl("metallum_MTLRenderCommandEncoder_setVertexSamplerState")
public func metallum_MTLRenderCommandEncoder_setVertexSamplerState(_ encoderPtr: UnsafeMutableRawPointer?, _ samplerPtr: UnsafeMutableRawPointer?, _ index: UInt64) {
    return withMetalAutoreleasePool {
    guard let encoder: MTLRenderCommandEncoder = object(encoderPtr) else {
        return
    }
    let sampler: MTLSamplerState? = object(samplerPtr)
    encoder.setVertexSamplerState(sampler, index: Int(index))
    }
}

@_cdecl("metallum_MTLRenderCommandEncoder_setFragmentSamplerState")
public func metallum_MTLRenderCommandEncoder_setFragmentSamplerState(_ encoderPtr: UnsafeMutableRawPointer?, _ samplerPtr: UnsafeMutableRawPointer?, _ index: UInt64) {
    return withMetalAutoreleasePool {
    guard let encoder: MTLRenderCommandEncoder = object(encoderPtr) else {
        return
    }
    let sampler: MTLSamplerState? = object(samplerPtr)
    encoder.setFragmentSamplerState(sampler, index: Int(index))
    }
}

@_cdecl("metallum_MTLRenderCommandEncoder_setScissorRect")
public func metallum_MTLRenderCommandEncoder_setScissorRect(
    _ encoderPtr: UnsafeMutableRawPointer?,
    _ x: UInt64,
    _ y: UInt64,
    _ width: UInt64,
    _ height: UInt64
) {
    return withMetalAutoreleasePool {
    guard let encoder: MTLRenderCommandEncoder = object(encoderPtr) else {
        return
    }
    encoder.setScissorRect(MTLScissorRect(x: Int(x), y: Int(y), width: Int(width), height: Int(height)))
    }
}

@_cdecl("metallum_MTLRenderCommandEncoder_drawPrimitives")
public func metallum_MTLRenderCommandEncoder_drawPrimitives(
    _ encoderPtr: UnsafeMutableRawPointer?,
    _ primitiveTypeCode: Int,
    _ firstVertex: Int,
    _ vertexCount: Int,
    _ instanceCount: Int
) {
    return withMetalAutoreleasePool {
    guard let encoder: MTLRenderCommandEncoder = object(encoderPtr) else {
        return
    }

    encoder.drawPrimitives(
        type: primitiveType(from: primitiveTypeCode),
        vertexStart: firstVertex,
        vertexCount: vertexCount,
        instanceCount: instanceCount
    )
    }
}

@_cdecl("metallum_MTLRenderCommandEncoder_drawIndexedPrimitives")
public func metallum_MTLRenderCommandEncoder_drawIndexedPrimitives(
    _ encoderPtr: UnsafeMutableRawPointer?,
    _ primitiveTypeCode: Int,
    _ indexCount: Int,
    _ indexType: Int,
    _ indexBufferPtr: UnsafeMutableRawPointer?,
    _ indexBufferOffset: Int,
    _ instanceCount: Int,
    _ baseVertex: Int
) {
    return withMetalAutoreleasePool {
    guard let encoder: MTLRenderCommandEncoder = object(encoderPtr), let indexBuffer: MTLBuffer = object(indexBufferPtr) else {
        return
    }
    encoder.drawIndexedPrimitives(
        type: primitiveType(from: primitiveTypeCode),
        indexCount: indexCount,
        indexType: indexType == 0 ? .uint16 : .uint32,
        indexBuffer: indexBuffer,
        indexBufferOffset: indexBufferOffset,
        instanceCount: instanceCount,
        baseVertex: baseVertex,
        baseInstance: 0
    )
    }
}

@_cdecl("metallum_MTLRenderCommandEncoder_drawPrimitivesTriangleFan")
public func metallum_MTLRenderCommandEncoder_drawPrimitivesTriangleFan(
    _ encoderPtr: UnsafeMutableRawPointer?,
    _ fanIndexBufferPtr: UnsafeMutableRawPointer?,
    _ firstVertex: Int,
    _ vertexCount: Int,
    _ instanceCount: Int
) {
    return withMetalAutoreleasePool {
    guard let encoder: MTLRenderCommandEncoder = object(encoderPtr), let fanIndexBuffer: MTLBuffer = object(fanIndexBufferPtr) else {
        return
    }
    guard let generatedIndexCount = writeSequentialTriangleFanIndices(fanIndexBuffer, vertexCount: vertexCount) else {
        return
    }
    encoder.drawIndexedPrimitives(
        type: .triangle,
        indexCount: generatedIndexCount,
        indexType: .uint32,
        indexBuffer: fanIndexBuffer,
        indexBufferOffset: 0,
        instanceCount: instanceCount,
        baseVertex: firstVertex,
        baseInstance: 0
    )
    }
}

@_cdecl("metallum_MTLRenderCommandEncoder_drawIndexedPrimitivesTriangleFan")
public func metallum_MTLRenderCommandEncoder_drawIndexedPrimitivesTriangleFan(
    _ encoderPtr: UnsafeMutableRawPointer?,
    _ indexBufferPtr: UnsafeMutableRawPointer?,
    _ fanIndexBufferPtr: UnsafeMutableRawPointer?,
    _ indexType: Int,
    _ indexOffsetBytes: Int,
    _ indexCount: Int,
    _ baseVertex: Int,
    _ instanceCount: Int
) {
    return withMetalAutoreleasePool {
    guard
        let encoder: MTLRenderCommandEncoder = object(encoderPtr),
        let indexBuffer: MTLBuffer = object(indexBufferPtr),
        let fanIndexBuffer: MTLBuffer = object(fanIndexBufferPtr)
    else {
        return
    }

    guard let generatedIndexCount = writeIndexedTriangleFanIndices(
        sourceIndexBuffer: indexBuffer,
        destinationIndexBuffer: fanIndexBuffer,
        indexType: indexType,
        indexOffsetBytes: indexOffsetBytes,
        indexCount: indexCount
    ) else {
        return
    }
    encoder.drawIndexedPrimitives(
        type: .triangle,
        indexCount: generatedIndexCount,
        indexType: .uint32,
        indexBuffer: fanIndexBuffer,
        indexBufferOffset: 0,
        instanceCount: instanceCount,
        baseVertex: baseVertex,
        baseInstance: 0
    )
    }
}

@_cdecl("metallum_MTLCommandBuffer_clearColorDepthTexturesRegion")
public func metallum_MTLCommandBuffer_clearColorDepthTexturesRegion(
    _ commandBufferPtr: UnsafeMutableRawPointer?,
    _ colorTexturePtr: UnsafeMutableRawPointer?,
    _ clearColorRed: Float,
    _ clearColorGreen: Float,
    _ clearColorBlue: Float,
    _ clearColorAlpha: Float,
    _ depthTexturePtr: UnsafeMutableRawPointer?,
    _ clearDepth: Double,
    _ x: Int32,
    _ y: Int32,
    _ width: Int32,
    _ height: Int32,
    _ globalFencePtr: UnsafeMutableRawPointer?
) {
    return withMetalAutoreleasePool {
    guard
        let commandBuffer: MTLCommandBuffer = object(commandBufferPtr),
        let colorTexture: MTLTexture = object(colorTexturePtr),
        let depthTexture: MTLTexture = object(depthTexturePtr),
        width > 0,
        height > 0
    else {
        return
    }

    let textureWidth = min(colorTexture.width, depthTexture.width)
    let textureHeight = min(colorTexture.height, depthTexture.height)
    let clampedX = max(Int(x), 0)
    let clampedY = max(Int(y), 0)
    let clampedMaxX = min(Int(x) + Int(width), textureWidth)
    let clampedMaxY = min(Int(y) + Int(height), textureHeight)
    if clampedX >= clampedMaxX || clampedY >= clampedMaxY {
        return
    }
    let scissorRect = MTLScissorRect(x: clampedX, y: clampedY, width: clampedMaxX - clampedX, height: clampedMaxY - clampedY)
    let fullRegion = clampedX == 0 && clampedY == 0 && clampedMaxX == textureWidth && clampedMaxY == textureHeight

    let renderPass = MTLRenderPassDescriptor()
    renderPass.colorAttachments[0].texture = colorTexture
    renderPass.colorAttachments[0].loadAction = fullRegion ? .clear : .load
    renderPass.colorAttachments[0].clearColor = makeClearColor(red: clearColorRed, green: clearColorGreen, blue: clearColorBlue, alpha: clearColorAlpha)
    renderPass.colorAttachments[0].storeAction = .store

    renderPass.depthAttachment.texture = depthTexture
    renderPass.depthAttachment.loadAction = fullRegion ? .clear : .load
    renderPass.depthAttachment.clearDepth = clearDepth
    renderPass.depthAttachment.storeAction = .store

    let depthFormat = depthTexture.pixelFormat
    if depthFormat == .depth24Unorm_stencil8 || depthFormat == .depth32Float_stencil8 {
        renderPass.stencilAttachment.texture = depthTexture
        renderPass.stencilAttachment.loadAction = .dontCare
        renderPass.stencilAttachment.storeAction = .dontCare
    }

    guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: renderPass) else {
        return
    }

    if let globalFence: MTLFence = object(globalFencePtr) {
        encoder.waitForFence(globalFence, before: .fragment)
    }

    if !fullRegion {
        guard
            let pipeline = ensureClearColorDepthPipeline(commandBuffer.device, colorTexture.pixelFormat, depthTexture.pixelFormat),
            let depthState = ensureDepthStencilState(device: commandBuffer.device, compareOp: 1, writeDepth: true)
        else {
            encoder.endEncoding()
            return
        }
        encodeClearDraw(
            encoder: encoder,
            pipeline: pipeline,
            textureWidth: textureWidth,
            textureHeight: textureHeight,
            clearColor: SIMD4<Float>(clearColorRed, clearColorGreen, clearColorBlue, clearColorAlpha),
            scissorRect: scissorRect,
            depthState: depthState,
            clearDepth: clearDepth
        )
    }

    if let globalFence: MTLFence = object(globalFencePtr) {
        encoder.updateFence(globalFence, after: .fragment)
    }

    encoder.endEncoding()
    }
}

@_cdecl("metallum_create_render_pipeline")
public func metallum_create_render_pipeline(
    _ devicePtr: UnsafeMutableRawPointer?,
    _ vertexMsl: UnsafePointer<CChar>?,
    _ fragmentMsl: UnsafePointer<CChar>?,
    _ vertexEntryPoint: UnsafePointer<CChar>?,
    _ fragmentEntryPoint: UnsafePointer<CChar>?,
    _ colorFormat: UInt64,
    _ depthFormat: UInt64,
    _ stencilFormat: UInt64,
    _ vertexAttributeFormats: UnsafePointer<UInt64>?,
    _ vertexAttributeOffsets: UnsafePointer<UInt64>?,
    _ vertexAttributeBufferSlots: UnsafePointer<UInt64>?,
    _ vertexAttributeCount: UInt64,
    _ vertexBindingBufferSlots: UnsafePointer<UInt64>?,
    _ vertexBindingStrides: UnsafePointer<UInt64>?,
    _ vertexBindingStepRates: UnsafePointer<UInt64>?,
    _ vertexBindingCount: UInt64,
    _ blendEnabled: Int32,
    _ blendSourceRgb: UInt64,
    _ blendDestRgb: UInt64,
    _ blendOpRgb: UInt64,
    _ blendSourceAlpha: UInt64,
    _ blendDestAlpha: UInt64,
    _ blendOpAlpha: UInt64,
    _ writeMask: UInt64
) -> UnsafeMutableRawPointer? {
    return withMetalAutoreleasePool {
    guard
        let device: MTLDevice = object(devicePtr),
        let vertexMsl,
        let fragmentMsl,
        let vertexEntryPoint,
        let fragmentEntryPoint
    else {
        return nil
    }

    let pipeline = ensureDynamicPipeline(
        device: device,
        vertexSource: String(cString: vertexMsl),
        fragmentSource: String(cString: fragmentMsl),
        vertexEntry: String(cString: vertexEntryPoint),
        fragmentEntry: String(cString: fragmentEntryPoint),
        colorFormat: MTLPixelFormat(rawValue: UInt(colorFormat)) ?? .invalid,
        depthFormat: MTLPixelFormat(rawValue: UInt(depthFormat)) ?? .invalid,
        stencilFormat: MTLPixelFormat(rawValue: UInt(stencilFormat)) ?? .invalid,
        vertexAttributeFormats: vertexAttributeFormats,
        vertexAttributeOffsets: vertexAttributeOffsets,
        vertexAttributeBufferSlots: vertexAttributeBufferSlots,
        vertexAttributeCount: vertexAttributeCount,
        vertexBindingBufferSlots: vertexBindingBufferSlots,
        vertexBindingStrides: vertexBindingStrides,
        vertexBindingStepRates: vertexBindingStepRates,
        vertexBindingCount: vertexBindingCount,
        blendEnabled: blendEnabled != 0,
        blendSourceRgb: blendSourceRgb,
        blendDestRgb: blendDestRgb,
        blendOpRgb: blendOpRgb,
        blendSourceAlpha: blendSourceAlpha,
        blendDestAlpha: blendDestAlpha,
        blendOpAlpha: blendOpAlpha,
        writeMask: writeMask
    )
    return unretainedPointer(pipeline)
    }
}

@_cdecl("metallum_configure_layer")
public func metallum_configure_layer(_ layerPtr: UnsafeMutableRawPointer?, _ width: Double, _ height: Double, _ immediatePresentMode: Int32) {
    return withMetalAutoreleasePool {
    guard let layer: CAMetalLayer = object(layerPtr), width > 0.0, height > 0.0 else {
        return
    }
    layer.pixelFormat = .bgra8Unorm
    layer.drawableSize = CGSize(width: width, height: height)
    layer.allowsNextDrawableTimeout = false
    layer.presentsWithTransaction = false
    layer.displaySyncEnabled = immediatePresentMode == 0
    }
}

@_cdecl("metallum_CAMetalLayer_nextDrawable")
public func metallum_CAMetalLayer_nextDrawable(_ layerPtr: UnsafeMutableRawPointer?) -> UnsafeMutableRawPointer? {
    return withMetalAutoreleasePool {
    guard let layer: CAMetalLayer = object(layerPtr), let drawable = layer.nextDrawable() else {
        return nil
    }
    return retainedPointer(drawable)
    }
}

@_cdecl("metallum_MTLCommandBuffer_encodePresentTextureToDrawable")
public func metallum_MTLCommandBuffer_encodePresentTextureToDrawable(
    _ commandBufferPtr: UnsafeMutableRawPointer?,
    _ drawablePtr: UnsafeMutableRawPointer?,
    _ sourceTexturePtr: UnsafeMutableRawPointer?,
    _ globalFencePtr: UnsafeMutableRawPointer?
) {
    return withMetalAutoreleasePool {
        guard
            let commandBuffer: MTLCommandBuffer = object(commandBufferPtr),
            let drawable: CAMetalDrawable = object(drawablePtr),
            let sourceTexture: MTLTexture = object(sourceTexturePtr)
        else {
            return
        }

        guard let pipeline = ensurePresentPipeline(sourceTexture.device, drawable.texture.pixelFormat) else {
            return
        }

        let renderPass = MTLRenderPassDescriptor()
        renderPass.colorAttachments[0].texture = drawable.texture
        renderPass.colorAttachments[0].loadAction = .dontCare
        renderPass.colorAttachments[0].storeAction = .store

        guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: renderPass) else {
            return
        }

        if let globalFence: MTLFence = object(globalFencePtr) {
            encoder.waitForFence(globalFence, before: .fragment)
        }

        encoder.setViewport(MTLViewport(
            originX: 0.0,
            originY: 0.0,
            width: Double(drawable.texture.width),
            height: Double(drawable.texture.height),
            znear: 0.0,
            zfar: 1.0
        ))

        encoder.setRenderPipelineState(pipeline)
        encoder.setFragmentTexture(sourceTexture, index: 0)

        let requiresScaling = sourceTexture.width != drawable.texture.width ||
                              sourceTexture.height != drawable.texture.height

        let sampler = requiresScaling
            ? ensurePresentLinearSampler(sourceTexture.device)
            : ensurePresentNearestSampler(sourceTexture.device)

        if let sampler {
            encoder.setFragmentSamplerState(sampler, index: 0)
        }

        encoder.drawPrimitives(
            type: .triangle,
            vertexStart: 0,
            vertexCount: 3
        )

        encoder.endEncoding()
    }
}

@_cdecl("metallum_create_fence")
public func metallum_create_fence(_ devicePtr: UnsafeMutableRawPointer?) -> UnsafeMutableRawPointer? {
    return withMetalAutoreleasePool {
        guard let device: MTLDevice = object(devicePtr) else { return nil }
        return retainedPointer(device.makeFence())
    }
}

@_cdecl("MTLRenderCommandEncoder_updateFence")
public func MTLRenderCommandEncoder_updateFence(
    _ encoderPtr: UnsafeMutableRawPointer?,
    _ fencePtr: UnsafeMutableRawPointer?,
    _ stages: UInt64
) {
    withMetalAutoreleasePool {
        guard let encoder: MTLRenderCommandEncoder = object(encoderPtr),
              let fence: MTLFence = object(fencePtr)
        else { return }
        encoder.updateFence(fence, after: MTLRenderStages(rawValue: UInt(stages)))
    }
}

@_cdecl("MTLRenderCommandEncoder_waitForFence")
public func MTLRenderCommandEncoder_waitForFence(
    _ encoderPtr: UnsafeMutableRawPointer?,
    _ fencePtr: UnsafeMutableRawPointer?,
    _ stages: UInt64
) {
    withMetalAutoreleasePool {
        guard let encoder: MTLRenderCommandEncoder = object(encoderPtr),
              let fence: MTLFence = object(fencePtr)
        else { return }
        encoder.waitForFence(fence, before: MTLRenderStages(rawValue: UInt(stages)))
    }
}

@_cdecl("MTLBlitCommandEncoder_updateFence")
public func MTLBlitCommandEncoder_updateFence(
    _ encoderPtr: UnsafeMutableRawPointer?,
    _ fencePtr: UnsafeMutableRawPointer?
) {
    withMetalAutoreleasePool {
        guard let encoder: MTLBlitCommandEncoder = object(encoderPtr),
              let fence: MTLFence = object(fencePtr)
        else { return }
        encoder.updateFence(fence)
    }
}

@_cdecl("MTLBlitCommandEncoder_waitForFence")
public func MTLBlitCommandEncoder_waitForFence(
    _ encoderPtr: UnsafeMutableRawPointer?,
    _ fencePtr: UnsafeMutableRawPointer?
) {
    withMetalAutoreleasePool {
        guard let encoder: MTLBlitCommandEncoder = object(encoderPtr),
              let fence: MTLFence = object(fencePtr)
        else { return }
        encoder.waitForFence(fence)
    }
}

@_cdecl("metallum_MTLCommandBuffer_presentDrawable")
public func metallum_MTLCommandBuffer_presentDrawable(_ commandBufferPtr: UnsafeMutableRawPointer?, _ drawablePtr: UnsafeMutableRawPointer?) {
    return withMetalAutoreleasePool {
    guard let commandBuffer: MTLCommandBuffer = object(commandBufferPtr), let drawable: CAMetalDrawable = object(drawablePtr) else {
        return
    }
    commandBuffer.present(drawable)
    }
}

@_cdecl("metallum_release_object")
public func metallum_release_object(_ obj: UnsafeMutableRawPointer?) {
    return withMetalAutoreleasePool {
    guard let obj else {
        return
    }
    Unmanaged<AnyObject>.fromOpaque(obj).release()
    }
}

@_cdecl("metallum_get_buffer_contents")
public func metallum_get_buffer_contents(_ bufferPtr: UnsafeMutableRawPointer?) -> UnsafeMutableRawPointer? {
    return withMetalAutoreleasePool {
    guard let buffer: MTLBuffer = object(bufferPtr) else {
        return nil
    }
    return buffer.contents()
    }
}
