package com.metallum.client.metal.render.mtl;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.lang.foreign.MemorySegment;

@Environment(EnvType.CLIENT)
public final class MTLRenderCommandEncoder extends MTLCommandEncoder {

    MTLRenderCommandEncoder(final MemorySegment handle) {
        super(handle);
    }

    public void setRenderPipelineState(final MemorySegment pipeline) {
        MetalNativeBridge.INSTANCE.MTLRenderCommandEncoder_setRenderPipelineState(handle(), pipeline);
    }

    public void setDepthStencilState(final MemorySegment depthStencilState) {
        MetalNativeBridge.INSTANCE.MTLRenderCommandEncoder_setDepthStencilState(handle(), depthStencilState);
    }

    public void setDepthBias(final double depthBias, final double slopeScale, final double clamp) {
        MetalNativeBridge.INSTANCE.MTLRenderCommandEncoder_setDepthBias(handle(), depthBias, slopeScale, clamp);
    }

    public void setFrontFacingWinding(final MTLWinding winding) {
        MetalNativeBridge.INSTANCE.MTLRenderCommandEncoder_setFrontFacingWinding(handle(), winding.value);
    }

    public void setCullMode(final MTLCullMode cullMode) {
        MetalNativeBridge.INSTANCE.MTLRenderCommandEncoder_setCullMode(handle(), cullMode.value);
    }

    public void setTriangleFillMode(final MTLTriangleFillMode fillMode) {
        MetalNativeBridge.INSTANCE.MTLRenderCommandEncoder_setTriangleFillMode(handle(), fillMode.value);
    }

    public void setVertexBuffer(final MemorySegment buffer, final long offset, final long index) {
        MetalNativeBridge.INSTANCE.MTLRenderCommandEncoder_setVertexBuffer(handle(), buffer, offset, index);
    }

    public void setFragmentBuffer(final MemorySegment buffer, final long offset, final long index) {
        MetalNativeBridge.INSTANCE.MTLRenderCommandEncoder_setFragmentBuffer(handle(), buffer, offset, index);
    }

    public void setVertexTexture(final MemorySegment texture, final long index) {
        MetalNativeBridge.INSTANCE.MTLRenderCommandEncoder_setVertexTexture(handle(), texture, index);
    }

    public void setFragmentTexture(final MemorySegment texture, final long index) {
        MetalNativeBridge.INSTANCE.MTLRenderCommandEncoder_setFragmentTexture(handle(), texture, index);
    }

    public void setVertexSamplerState(final MemorySegment sampler, final long index) {
        MetalNativeBridge.INSTANCE.MTLRenderCommandEncoder_setVertexSamplerState(handle(), sampler, index);
    }

    public void setFragmentSamplerState(final MemorySegment sampler, final long index) {
        MetalNativeBridge.INSTANCE.MTLRenderCommandEncoder_setFragmentSamplerState(handle(), sampler, index);
    }

    public void setScissorRect(final long x, final long y, final long width, final long height) {
        MetalNativeBridge.INSTANCE.MTLRenderCommandEncoder_setScissorRect(handle(), x, y, width, height);
    }

    public void drawPrimitives(final long primitiveType, final int firstVertex, final int vertexCount, final int instanceCount) {
        MetalNativeBridge.INSTANCE.MTLRenderCommandEncoder_drawPrimitives(handle(), primitiveType, firstVertex, vertexCount, instanceCount);
    }

    public void drawIndexedPrimitives(final long primitiveType, final int indexCount, final long indexType, final MemorySegment indexBuffer, final long offset, final int instanceCount, final int baseVertex) {
        MetalNativeBridge.INSTANCE.MTLRenderCommandEncoder_drawIndexedPrimitives(handle(), primitiveType, indexCount, indexType, indexBuffer, offset, instanceCount, baseVertex);
    }

    public void drawPrimitivesTriangleFan(final MemorySegment fanIndexBuffer, final int firstVertex, final int vertexCount, final int instanceCount) {
        MetalNativeBridge.INSTANCE.MTLRenderCommandEncoder_drawPrimitivesTriangleFan(handle(), fanIndexBuffer, firstVertex, vertexCount, instanceCount);
    }

    public void drawIndexedPrimitivesTriangleFan(final MemorySegment indexBuffer, final MemorySegment fanIndexBuffer, final long indexType, final long offset, final int indexCount, final int baseVertex, final int instanceCount) {
        MetalNativeBridge.INSTANCE.MTLRenderCommandEncoder_drawIndexedPrimitivesTriangleFan(handle(), indexBuffer, fanIndexBuffer, indexType, offset, indexCount, baseVertex, instanceCount);
    }

    public void updateFence(final MemorySegment fence, final MTLRenderStages stages) {
        MetalNativeBridge.INSTANCE.MTLRenderCommandEncoder_updateFence(handle(), fence, stages.value);
    }

    public void waitForFence(final MemorySegment fence, final MTLRenderStages stages) {
        MetalNativeBridge.INSTANCE.MTLRenderCommandEncoder_waitForFence(handle(), fence, stages.value);
    }
}
