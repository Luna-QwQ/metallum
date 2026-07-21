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
        // iOS: 必须在任何 Spvc 类加载之前设置 Configuration.SPVC_LIBRARY_NAME，
        // 否则 LWJGL 会通过 dlsym(RTLD_DEFAULT) 拿到 MoltenVK 的精简版 SPIRV-Cross
        // 符号（无 MSL 后端），导致 spvc_context_create_compiler(SPVC_BACKEND_MSL)
        // 失败 -4 "Invalid backend"。详见 MetalNativeBridge.ensureSpvcLibraryConfigured。
        MetalNativeBridge.ensureSpvcLibraryConfigured();

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


        if (MetalNativeBridge.isIOS()) {
            // iOS: GameSurfaceView already overrides +layerClass to return
            // CAMetalLayer.class, so cocoaView.layer IS a CAMetalLayer. Use it
            // directly as the render target — this matches what Amethyst's own
            // Vulkan path does in pojavCreateContext (Natives/egl_bridge.m:
            // `return SurfaceViewController.surface.layer`). Creating a new
            // CAMetalLayer and attaching it as a sublayer does NOT work
            // reliably and results in a black screen with audio playing.
            metalLayer = MetalNativeBridge.metallum_ios_get_view_metal_layer(cocoaView, deviceHandle, scale);
            if (MetalNativeBridge.isNullHandle(metalLayer)) {
                throw new BackendCreationException("metallum_ios_get_view_metal_layer returned null", BackendCreationException.Reason.OTHER);
            }
            // No metallum_NSView_setMetalLayer call needed — the layer is
            // already view.layer and is attached to the view by the launcher.
        } else {
            metalLayer = MetalNativeBridge.metallum_create_metal_layer(deviceHandle, scale);
            if (MetalNativeBridge.isNullHandle(metalLayer)) {
                throw new BackendCreationException("Failed to create CAMetalLayer", BackendCreationException.Reason.OTHER);
            }

            MetalNativeBridge.metallum_NSView_setMetalLayer(cocoaView, metalLayer);
        }

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
            // Amethyst-iOS does not publish the UIView pointer as a system
            // property. Resolve it directly via the ObjC runtime instead:
            // metallum_ios_find_surface_view calls +[SurfaceViewController surface]
            // (with a key-window view-hierarchy fallback) to locate the host
            // launcher's GameSurfaceView. This is the supported path on
            // Amethyst/PojavLauncher_iOS.
            MemorySegment nativeView = MetalNativeBridge.metallum_ios_find_surface_view();
            if (!MetalNativeBridge.isNullHandle(nativeView)) {
                return nativeView;
            }
            throw new BackendCreationException(
                    "Could not locate the iOS surface view. Neither the "
                            + "'metallum.ios.view.pointer'/'pojav.view.pointer' system property "
                            + "nor the +[SurfaceViewController surface] class method returned a UIView. "
                            + "If you are using a launcher other than Amethyst/PojavLauncher, set "
                            + "'-Dmetallum.ios.view.pointer=<hex>' to the UIView address.",
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
