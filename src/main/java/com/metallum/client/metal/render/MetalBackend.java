package com.metallum.client.metal.render;

import com.metallum.Metallum;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.mojang.blaze3d.GLFWErrorCapture;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.BackendCreationException;
import com.mojang.blaze3d.systems.GpuBackend;
import com.mojang.blaze3d.systems.GpuDevice;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.NonNull;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeCocoa;

import java.lang.foreign.MemorySegment;

@Environment(EnvType.CLIENT)
public class MetalBackend implements GpuBackend {
    @Override
    public @NonNull String getName() {
        return "Metal";
    }

    @Override
    public void setWindowHints() {
        GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API);
    }

    @Override
    public void handleWindowCreationErrors(final GLFWErrorCapture.Error error) throws BackendCreationException {
        throw new BackendCreationException(error.toString(), BackendCreationException.Reason.GLFW_ERROR);
    }

    @Override
    public @NonNull GpuDevice createDevice(
            final long window, final @NonNull ShaderSource defaultShaderSource, final @NonNull GpuDebugOptions debugOptions, final @NonNull Runnable criticalShaderLoader
    ) throws BackendCreationException {
        MemorySegment deviceHandle;
        MemorySegment cocoaWindow;
        MemorySegment cocoaView;
        MemorySegment metalLayer;
        String deviceName;
        deviceHandle = MetalNativeBridge.metallum_create_system_default_device();
        if (MetalNativeBridge.isNullHandle(deviceHandle)) {
            throw new BackendCreationException("MTLCreateSystemDefaultDevice returned null", BackendCreationException.Reason.OTHER);
        }

        deviceName = MetalNativeBridge.metallum_copy_device_name(deviceHandle);
        if (deviceName.isBlank()) deviceName = "<unknown Metal device>";

        double scale;
        if (MetalNativeBridge.isIOS()) {
            // iOS: GLFW does not expose Cocoa window handles. The host launcher
            // (e.g. PojavLauncher) owns the UIWindow/UIView and publishes the
            // view pointer (and optionally the backing scale) via system
            // properties so we can attach a CAMetalLayer to it.
            cocoaWindow = MemorySegment.NULL;
            cocoaView = readIOSSurfacePointer();
            scale = readIOSScreenScale();
        } else {
            cocoaWindow = MemorySegment.ofAddress(GLFWNativeCocoa.glfwGetCocoaWindow(window));
            if (MetalNativeBridge.isNullHandle(cocoaWindow)) {
                throw new BackendCreationException("glfwGetCocoaWindow returned null", BackendCreationException.Reason.GLFW_ERROR);
            }

            cocoaView = MemorySegment.ofAddress(GLFWNativeCocoa.glfwGetCocoaView(window));
            if (MetalNativeBridge.isNullHandle(cocoaView)) {
                throw new BackendCreationException("glfwGetCocoaView returned null", BackendCreationException.Reason.GLFW_ERROR);
            }

            scale = MetalNativeBridge.metallum_NSWindow_backingScaleFactor(cocoaWindow);
        }
        if (scale <= 0.0) scale = 1.0;


        metalLayer = MetalNativeBridge.metallum_create_metal_layer(deviceHandle, scale);
        if (MetalNativeBridge.isNullHandle(metalLayer)) {
            throw new BackendCreationException("Failed to create CAMetalLayer", BackendCreationException.Reason.OTHER);
        }

        MetalNativeBridge.metallum_NSView_setMetalLayer(cocoaView, metalLayer);

        Metallum.LOGGER.info("Metal device: {}", deviceName);

        try {
            return new GpuDevice(new MetalDevice(defaultShaderSource, debugOptions, deviceHandle, metalLayer, deviceName, cocoaView), criticalShaderLoader);
        } catch (Throwable throwable) {
            throw new BackendCreationException("Metal device initialization failed: " + throwable.getMessage(), BackendCreationException.Reason.OTHER);
        }
    }

    /**
     * Reads the host-provided {@code UIView} pointer on iOS. The host launcher
     * (PojavLauncher) owns the {@code UIView} that backs the game surface and
     * exposes its address via a system property so the mod can attach a
     * {@code CAMetalLayer} to it.
     *
     * <p>Recognised properties (in order of preference):
     * <ul>
     *   <li>{@code metallum.ios.view.pointer} – hex address of the UIView</li>
     *   <li>{@code pojav.view.pointer} – legacy PojavLauncher property</li>
     * </ul>
     */
    private static MemorySegment readIOSSurfacePointer() throws BackendCreationException {
        String raw = System.getProperty("metallum.ios.view.pointer");
        if (raw == null || raw.isBlank()) {
            raw = System.getProperty("pojav.view.pointer");
        }
        if (raw == null || raw.isBlank()) {
            throw new BackendCreationException(
                    "On iOS the host launcher must expose the UIView pointer via the "
                            + "'metallum.ios.view.pointer' (or 'pojav.view.pointer') system property",
                    BackendCreationException.Reason.OTHER
            );
        }
        raw = raw.trim();
        String hex = raw.startsWith("0x") || raw.startsWith("0X") ? raw.substring(2) : raw;
        long address;
        try {
            address = Long.parseUnsignedLong(hex, 16);
        } catch (NumberFormatException e) {
            throw new BackendCreationException(
                    "Invalid UIView pointer '" + raw + "': expected a hex address",
                    BackendCreationException.Reason.OTHER
            );
        }
        MemorySegment view = MemorySegment.ofAddress(address);
        if (MetalNativeBridge.isNullHandle(view)) {
            throw new BackendCreationException(
                    "Host-provided UIView pointer is null",
                    BackendCreationException.Reason.OTHER
            );
        }
        return view;
    }

    /**
     * Reads the backing scale factor on iOS. Defaults to {@code 2.0} (typical
     * Retina scale) if the host does not publish one.
     */
    private static double readIOSScreenScale() {
        String raw = System.getProperty("metallum.ios.screen.scale");
        if (raw == null || raw.isBlank()) {
            return 2.0;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return 2.0;
        }
    }
}
