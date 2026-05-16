package com.metallum.client.metal.render;

import com.metallum.Metallum;
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

@Environment(EnvType.CLIENT)
public class MetalBackend implements GpuBackend {
    @Override
    public String getName() {
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
    )
            throws BackendCreationException {
        MetalProbe.ProbeResult probe = MetalProbe.probe();
        if (!probe.supported()) {
            throw new BackendCreationException("Metal probe failed: " + probe.message(), BackendCreationException.Reason.OTHER);
        }

        MetalCocoaBootstrap.BootstrapContext bootstrap;
        try {
            bootstrap = MetalCocoaBootstrap.bootstrap(window);
        } catch (Throwable throwable) {
            throw new BackendCreationException("Metal Cocoa bootstrap failed: " + throwable.getMessage(), BackendCreationException.Reason.OTHER);
        }

        Metallum.LOGGER.info(
                "Metal bootstrap prepared device {} ({}) with NSWindow {}, NSView {}, CAMetalLayer {}, scale {}",
                bootstrap.deviceName(),
                bootstrap.deviceHandleHex(),
                bootstrap.cocoaWindowHandleHex(),
                bootstrap.cocoaViewHandleHex(),
                bootstrap.metalLayerHandleHex(),
                bootstrap.backingScaleFactor()
        );

        try {
            return new GpuDevice(new MetalDevice(defaultShaderSource, bootstrap, debugOptions), criticalShaderLoader);
        } catch (Throwable throwable) {
            throw new BackendCreationException("Metal device initialization failed: " + throwable.getMessage(), BackendCreationException.Reason.OTHER);
        }
    }
}
