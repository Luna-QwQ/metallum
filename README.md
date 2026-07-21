## Metallum
Metallum is an experimental rendering backend for Minecraft on macOS and iOS that uses Apple's Metal API instead of OpenGL/Vulkan. It provides a more native rendering path and aims to improve performance and efficiency on Apple Silicon and iOS devices.

On macOS, the Metal rendering backend is loaded directly via the native bridge. On iOS, the precompiled iOS dylib (`libmetallum.dylib`, arm64, target iOS 14.0+) is included in the jar and intended to be consumed by [Amethyst-iOS](https://github.com/AngelAuraMC/Amethyst-iOS) .

This project is still experimental. Performance, stability, and compatibility may vary depending on your system and installed mods. If you encounter bugs, please report them on GitHub.

Compatible with Sodium.

vibecoded as hell

## Requirements

### macOS
- macOS
- Apple Silicon (M1 or newer)

### iOS
- iOS 14.0 or later
- An iPhone, iPad, or iPod touch capable of running [Amethyst-iOS](https://github.com/AngelAuraMC/Amethyst-iOS)
- Amethyst-iOS installed via TrollStore, AltStore, SideStore, or a jailbreak

## iOS Installation

Metallum is packaged as a Fabric mod and is loaded automatically on Amethyst-iOS when installed as a mod. The iOS native library (`libmetallum.dylib`, arm64) is bundled inside the jar and is loaded by the launcher at runtime via the embedded native bridge.

### Steps

1. **Install Amethyst-iOS** on your device following the [official instructions](https://github.com/AngelAuraMC/Amethyst-iOS). The recommended approach is to use TrollStore for permanent signing and automatic JIT.

2. **Embed the iOS dylib into the Amethyst app bundle.** This is required — iOS forbids loading unsigned dylibs from writable directories, so the dylib must live inside the app bundle's `Frameworks/` directory and be signed with the app's signing identity. The easiest way is to rebuild the Amethyst IPA with the dylib included:
   - Extract `natives/ios/libmetallum.dylib` from the Metallum jar (any unzip tool will do).
   - Place it at `Amethyst.app/Frameworks/libmetallum.dylib` inside the IPA.
   - Re-sign the app. With TrollStore, simply re-import the modified IPA; TrollStore will re-sign it automatically. With AltStore/SideStore, rebuild and re-sign via your signing workflow.

3. **Download the latest Metallum jar** from the [Releases](https://github.com/Luna-QwQ/metallum/releases) page.

4. **Place the jar** in the `mods/` folder of your Minecraft instance on Amethyst-iOS (typically located at `~/Documents/minecraft/mods/` or accessible via the Amethyst file manager).

5. **Launch Minecraft** through Amethyst-iOS. Metallum will automatically detect the Metal backend and use it instead of the default OpenGL/Vulkan renderer.

### Notes

- The iOS dylib is built with target `arm64-apple-ios14.0` and is **unsigned** in the jar. It must be re-signed as part of the Amethyst app bundle — iOS will refuse to `dlopen` it from any writable directory due to code-signing restrictions.
- If you see a crash like `Cannot open library: .../tmp/metallum-native-*.dylib`, it means the dylib was not embedded in the app bundle's `Frameworks/` directory and the launcher tried to extract it to a writable tmp directory (which iOS rejects). Follow step 2 above to embed the dylib.
- On developer devices with relaxed signing (e.g. jailbroken with `ldid` ad-hoc signing), the jar-extraction fallback may work without embedding — but this is not supported on stock iOS.
- If you encounter crashes or rendering issues, try disabling other rendering-related mods first.
- Metallum requires Fabric Loader; make sure your Amethyst instance is using Fabric.

