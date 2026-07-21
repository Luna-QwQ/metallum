# Metallum

Metallum 是一个基于 Apple Metal API 的 Minecraft 渲染后端模组（Fabric Mod），用于在 macOS 和 iOS 上替代 OpenGL/Vulkan 渲染路径，为 Apple Silicon 和 iOS 设备提供更高效的 GPU 渲染。

本项目仍处于实验性阶段（PoC），性能与稳定性可能因系统而异。

## 架构

| 层级 | 实现 |
|------|------|
| 入口点 | `com.metallum.Metallum`（PreLaunch + ModInitializer） |
| GPU 后端 | `MetalBackend` → `MetalDevice` → `MetalCommandEncoder` / `MetalRenderPass` |
| 着色器编译器 | `MetalCrossShaderCompiler`（GLSL/SPIR-V → MSL，基于 SPIRV-Cross） |
| 原生桥接 | `MetalNativeBridge`（Java Foreign Memory API ↔ Swift C 导出函数） |
| 原生实现 | `MetallumNative.swift`（Metal API 调用、CAMetalLayer 管理、MSL 内联着色器） |
| 模组注入 | Mixin 注入 Minecraft `PreferredGraphicsApi` 和 Sodium 渲染后端选择 |

## 兼容性

- **Sodium**：通过 Mixin 注入 `DrawBackend`、`DrawContext` 和 `SodiumPreferredGraphicsApi`，将 Metal 后端映射为 Sodium 的间接绘制路径
- **macOS**：Apple Silicon（M1 或更新），通过 Native Bridge 直接加载 `libmetallum.dylib`
- **iOS**：iOS 14.0 或更高版本，预编译 `libmetallum.dylib`（arm64）和 `libspvc.dylib`（带 MSL 后端）内置于 jar 中

## 构建

### 前置条件

- macOS（Apple Silicon）
- Xcode（含 iOS SDK，用于 iOS 目标）
- Java 25
- Swift 编译器（`swiftc`）

### 构建命令

```bash
# 完整构建（macOS 原生 + iOS 原生 + iOS libspvc）
./gradlew build

# 仅编译 macOS 原生 dylib
./gradlew buildMacNative

# 仅编译 iOS 原生 dylib（需要 Xcode + iOS SDK）
./gradlew buildIOSNative

# 仅编译 iOS libspvc（SPIRV-Cross MSL 后端，需要 Xcode + iOS SDK）
./gradlew buildIOSSpvc
```

构建产物：
- `src/main/resources/natives/macos/libmetallum.dylib` — macOS arm64, target 14.0
- `src/main/resources/natives/ios/libmetallum.dylib` — iOS arm64, target 14.0
- `src/main/resources/natives/ios/libspvc.dylib` — SPIRV-Cross C API（MSL 后端），iOS arm64

### CI/CD

GitHub Actions 工作流（`.github/workflows/build.yml`）在 `macos-15` 上构建，推送带 `v*` tag 时自动发布到 Modrinth 和 GitHub Releases。

## iOS 使用说明

1. 在设备上安装IOS启动器，推荐使用 TrollStore 进行永久签名和自动 JIT
2. 将 Metallum jar 放入 Minecraft 实例的 `mods/` 目录（通常位于 `~/Documents/minecraft/mods/`）

### 注意事项

- `libmetallum.dylib` 和 `libspvc.dylib` 由 Amethyst-iOS-MyRemastered 启动器在运行时加载，无需手动嵌入
- 必须使用 Fabric Loader
- 如遇渲染问题，先尝试禁用其他渲染相关模组

## macOS 使用说明

1. 下载最新 Metallum jar 并放入 `mods/` 目录
2. 启动 Minecraft，在视频设置中将图形后端选择为 "Prefer Metal"
3. 需要与其他 Fabric 模组配合使用时，确保 Sodium 版本兼容（`mc26.2-0.9.0-fabric`）

## 许可

MIT License — 详见 [LICENSE](LICENSE)
