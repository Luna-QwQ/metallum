package com.metallum;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Metallum implements ModInitializer, PreLaunchEntrypoint {
    public static final String MOD_ID = "metallum";

    // This logger is used to write text to the console and the log file.
    // It is considered best practice to use your mod id as the logger's name.
    // That way, it's clear which mod wrote info, warnings, and errors.
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onPreLaunch() {
        // PreLaunch 是 Fabric Loader 提供的最早入口点，在游戏启动之前调用，
        // 早于任何 Minecraft 类（包括 VulkanBackend、GlBackend、MetalBackend）被加载。
        // 必须在这里设置 Configuration.SPVC_LIBRARY_NAME，因为 LWJGL 的 Spvc.SPVC 是
        // static final 字段，类初始化时一次性读取配置并缓存，之后修改无效。
        // 如果等到 onInitialize 或 MetalBackend.createDevice，Spvc 类可能已被
        // VulkanBackend 的类加载触发初始化，配置就来不及了。
        // 非 iOS 环境下此方法立即返回（isIOS() 检查）。
        MetalNativeBridge.ensureSpvcLibraryConfigured();
    }

    @Override
    public void onInitialize() {
    }
}