package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.lwjgl.glfw.GLFWNativeCocoa;

import java.lang.foreign.MemorySegment;

@Environment(EnvType.CLIENT)
public final class MetalCocoaBootstrap {
    private MetalCocoaBootstrap() {
    }

    public static BootstrapContext bootstrap(final long windowHandle) {
        if (!MetalBackendConfig.isMacOs()) {
            throw new IllegalStateException("Metal bootstrap is only available on macOS");
        }
        MemorySegment device = MetalProbe.createSystemDefaultDevice();
        if (MetalProbe.isNullHandle(device)) {
            throw new IllegalStateException("MTLCreateSystemDefaultDevice returned null during Cocoa bootstrap");
        }

        MemorySegment cocoaWindow = MemorySegment.ofAddress(GLFWNativeCocoa.glfwGetCocoaWindow(windowHandle));
        if (MetalProbe.isNullHandle(cocoaWindow)) {
            throw new IllegalStateException("glfwGetCocoaWindow returned null");
        }

        MemorySegment cocoaView = MemorySegment.ofAddress(GLFWNativeCocoa.glfwGetCocoaView(windowHandle));
        if (MetalProbe.isNullHandle(cocoaView)) {
            throw new IllegalStateException("glfwGetCocoaView returned null");
        }

        double scale = readBackingScaleFactor(cocoaWindow);
        MemorySegment metalLayer = MetalNativeBridge.INSTANCE.metallum_create_metal_layer(device, scale);
        if (MetalProbe.isNullHandle(metalLayer)) {
            throw new IllegalStateException("Failed to create CAMetalLayer");
        }

        MetalNativeBridge.INSTANCE.metallum_NSView_setMetalLayer(cocoaView, metalLayer);

        return new BootstrapContext(device, MetalProbe.readDeviceName(device), cocoaWindow, cocoaView, metalLayer, scale);
    }

    private static double readBackingScaleFactor(final MemorySegment cocoaWindow) {
        double value = MetalNativeBridge.INSTANCE.metallum_NSWindow_backingScaleFactor(cocoaWindow);
        return value > 0.0 ? value : 1.0;
    }

    @Environment(EnvType.CLIENT)
    public record BootstrapContext(
            MemorySegment device,
            String deviceName,
            MemorySegment cocoaWindow,
            MemorySegment cocoaView,
            MemorySegment metalLayer,
            double backingScaleFactor
    ) {
        public String deviceHandleHex() {
            return toHex(this.device);
        }

        public String cocoaWindowHandleHex() {
            return toHex(this.cocoaWindow);
        }

        public String cocoaViewHandleHex() {
            return toHex(this.cocoaView);
        }

        public String metalLayerHandleHex() {
            return toHex(this.metalLayer);
        }

        private static String toHex(final MemorySegment handle) {
            return "0x" + Long.toUnsignedString(handle.address(), 16);
        }
    }
}
