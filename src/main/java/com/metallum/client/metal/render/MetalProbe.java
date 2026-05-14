package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;

@Environment(EnvType.CLIENT)
public final class MetalProbe {
    private MetalProbe() {
    }

    public static ProbeResult probe() {
        if (!MetalBackendConfig.isMacOs()) {
            return ProbeResult.unsupported("Metal probe is only available on macOS");
        }

        MemorySegment device = createSystemDefaultDevice();
        if (isNullHandle(device)) {
            return ProbeResult.unsupported("MTLCreateSystemDefaultDevice returned null");
        }

        String deviceName = readDeviceName(device);

        return ProbeResult.supported(deviceName);
    }

    static MemorySegment createSystemDefaultDevice() {
        return MetalNativeBridge.INSTANCE.metallum_create_system_default_device();
    }

    static String readDeviceName(final MemorySegment device) {
        String value = MetalNativeBridge.INSTANCE.metallum_copy_device_name(device);
        return value.isBlank() ? "<unknown Metal device>" : value;
    }

    public static boolean isNullHandle(@Nullable final MemorySegment pointer) {
        return pointer == null || pointer.address() == 0L;
    }

    @Environment(EnvType.CLIENT)
    public record ProbeResult(boolean supported, String deviceName, String message, @Nullable Throwable failure) {
        public static ProbeResult supported(final String deviceName) {
            return new ProbeResult(true, deviceName, "Metal probe succeeded on " + deviceName, null);
        }

        public static ProbeResult unsupported(final String message) {
            return new ProbeResult(false, "<none>", message, null);
        }

        public static ProbeResult failed(final Throwable failure) {
            String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            return new ProbeResult(false, "<none>", message, failure);
        }
    }
}
