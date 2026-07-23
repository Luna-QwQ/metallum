package com.metallum.client.metal.iris;

import com.mojang.blaze3d.textures.GpuTextureView;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats;
import net.irisshaders.iris.compat.dh.DHCompat;
import net.irisshaders.iris.features.FeatureFlags;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.helpers.Tri;
import net.irisshaders.iris.mixin.LevelRendererAccessor;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.shaderpack.properties.CloudSetting;
import net.irisshaders.iris.shaderpack.properties.ParticleRenderingSettings;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import net.minecraft.client.Camera;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * A Metal-native implementation of Iris's {@link WorldRenderingPipeline}
 * interface, used when a shaderpack is loaded on a non-OpenGL backend.
 *
 * <p>Instead of constructing Iris's {@code IrisRenderingPipeline} (which
 * creates hundreds of OpenGL resources — framebuffers, textures, shader
 * programs — via native GL calls that crash on Metal), this pipeline:
 * <ul>
 *   <li>Compiles all shaderpack programs from GLSL to Metal Shading Language
 *       via {@link MetalIrisBridge#compileIrisProgram} during construction,
 *       validating the GLSL→SPIR-V→MSL pipeline.</li>
 *   <li>Implements all {@code WorldRenderingPipeline} methods with the same
 *       safe defaults as {@code VanillaRenderingPipeline}.</li>
 *   <li>Makes {@link #beginLevelRendering()} a true no-op (no GL calls),
 *       unlike {@code VanillaRenderingPipeline} which calls
 *       {@code GL.getCapabilities()} and {@code glUseProgram(0)}.</li>
 * </ul>
 *
 * <p>The compiled MSL shaders are not yet used for rendering — this is a
 * validation step. Actual Metal render pass integration is future work.
 *
 * <p>The pipeline is selected by {@code MixinIris}'s {@code createPipeline}
 * redirect, which returns {@code new MetalIrisRenderingPipeline(programs)}
 * instead of {@code new IrisRenderingPipeline(programs)} when
 * {@link MetalIrisBridge#isNonGlBackend()} returns {@code true} and a
 * shaderpack is loaded.
 */
public class MetalIrisRenderingPipeline implements WorldRenderingPipeline {
    private static final Logger LOGGER = LoggerFactory.getLogger("MetalUniversal");

    private final ProgramSet programSet;
    private final FrameUpdateNotifier frameUpdateNotifier = new FrameUpdateNotifier();

    public MetalIrisRenderingPipeline(ProgramSet programSet) {
        this.programSet = programSet;

        WorldRenderingSettings.INSTANCE.setDisableDirectionalShading(false);
        WorldRenderingSettings.INSTANCE.setUseSeparateAo(false);
        WorldRenderingSettings.INSTANCE.setSeparateEntityDraws(false);
        WorldRenderingSettings.INSTANCE.setAmbientOcclusionLevel(1.0f);
        WorldRenderingSettings.INSTANCE.setVertexFormat(ChunkMeshFormats.COMPACT);
        WorldRenderingSettings.INSTANCE.setVoxelizeLightBlocks(false);
        WorldRenderingSettings.INSTANCE.setBreaksAnisotropy(false);
        WorldRenderingSettings.INSTANCE.setBlockTypeIds(Object2ObjectMaps.emptyMap());

        compileShadersToMsl();
    }

    /**
     * Compiles all shaderpack programs from GLSL to Metal Shading Language
     * via {@link MetalIrisBridge}, validating the GLSL→SPIR-V→MSL pipeline.
     *
     * <p>This iterates over all gbuffer and composite programs in the
     * {@link ProgramSet}, extracts their vertex/fragment GLSL source, and
     * compiles each pair to MSL. Results are logged but not yet used for
     * rendering — this is a validation step for the compilation pipeline.
     *
     * <p>Compilation failures are logged as warnings but do not prevent the
     * pipeline from being created — the game continues with vanilla rendering
     * for any programs that fail to compile.
     */
    private void compileShadersToMsl() {
        int total = 0;
        int success = 0;
        int skipped = 0;
        int failed = 0;

        for (ProgramId id : ProgramId.values()) {
            Optional<ProgramSource> sourceOpt = programSet.get(id);
            if (sourceOpt.isEmpty()) {
                skipped++;
                continue;
            }
            ProgramSource source = sourceOpt.get();
            if (!source.isValid()) {
                skipped++;
                continue;
            }

            total++;
            String name = source.getName();
            Optional<String> vsOpt = source.getVertexSource();
            Optional<String> fsOpt = source.getFragmentSource();

            if (vsOpt.isEmpty() || fsOpt.isEmpty()) {
                skipped++;
                continue;
            }

            try {
                MetalIrisBridge.ShaderPair pair = MetalIrisBridge.compileIrisProgram(
                    name, vsOpt.get(), fsOpt.get());
                if (pair.vertex() != null && pair.fragment() != null) {
                    success++;
                    LOGGER.info("[MetalUniversal] Compiled '{}' → MSL (vsh: {} chars, fsh: {} chars)",
                        name, pair.vertex().source().length(), pair.fragment().source().length());
                }
            } catch (Exception e) {
                failed++;
                LOGGER.warn("[MetalUniversal] Failed to compile '{}' to MSL: {}", name, e.getMessage());
            }
        }

        for (ProgramArrayId arrayId : ProgramArrayId.values()) {
            ProgramSource[] sources = programSet.getComposite(arrayId);
            if (sources == null) continue;
            for (int i = 0; i < sources.length; i++) {
                ProgramSource source = sources[i];
                if (source == null || !source.isValid()) {
                    continue;
                }
                total++;
                String name = source.getName();
                Optional<String> vsOpt = source.getVertexSource();
                Optional<String> fsOpt = source.getFragmentSource();
                if (vsOpt.isEmpty() || fsOpt.isEmpty()) {
                    skipped++;
                    continue;
                }
                try {
                    MetalIrisBridge.ShaderPair pair = MetalIrisBridge.compileIrisProgram(
                        name, vsOpt.get(), fsOpt.get());
                    if (pair.vertex() != null && pair.fragment() != null) {
                        success++;
                        LOGGER.info("[MetalUniversal] Compiled '{}' → MSL (vsh: {} chars, fsh: {} chars)",
                            name, pair.vertex().source().length(), pair.fragment().source().length());
                    }
                } catch (Exception e) {
                    failed++;
                    LOGGER.warn("[MetalUniversal] Failed to compile '{}' to MSL: {}", name, e.getMessage());
                }
            }
        }

        LOGGER.info("[MetalUniversal] MetalIrisRenderingPipeline ready. MSL compilation: {} compiled, {} failed, {} skipped (no source), {} total.",
            success, failed, skipped, total);
    }

    @Override
    public void beginLevelRendering() {
        // No-op: safe on Metal (no GL calls)
    }

    @Override
    public void renderShadows(LevelRendererAccessor worldRenderer, Camera camera, CameraRenderState renderState) {
        // stub
    }

    @Override
    public void addDebugText(DebugScreenDisplayer messages) {
        // stub
    }

    @Override
    public OptionalInt getForcedShadowRenderDistanceChunksForDisplay() {
        return OptionalInt.empty();
    }

    @Override
    public Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> getTextureMap() {
        return Object2ObjectMaps.emptyMap();
    }

    @Override
    public WorldRenderingPhase getPhase() {
        return WorldRenderingPhase.NONE;
    }

    @Override
    public void setPhase(WorldRenderingPhase phase) {
    }

    @Override
    public void setOverridePhase(WorldRenderingPhase phase) {
    }

    @Override
    public int getCurrentNormalTexture() {
        return 0;
    }

    @Override
    public int getCurrentSpecularTexture() {
        return 0;
    }

    @Override
    public void onSetAlbedoTex(GpuTextureView id) {
    }

    @Override
    public void beginHand() {
    }

    @Override
    public void beginTranslucents() {
    }

    @Override
    public void finalizeLevelRendering() {
    }

    @Override
    public void finalizeGameRendering() {
    }

    @Override
    public void destroy() {
        LOGGER.info("[MetalUniversal] MetalIrisRenderingPipeline destroyed.");
    }

    @Override
    public FrameUpdateNotifier getFrameUpdateNotifier() {
        return this.frameUpdateNotifier;
    }

    @Override
    public boolean shouldDisableVanillaEntityShadows() {
        return false;
    }

    @Override
    public boolean shouldDisableDirectionalShading() {
        return false;
    }

    @Override
    public boolean shouldDisableFrustumCulling() {
        return false;
    }

    @Override
    public boolean shouldDisableOcclusionCulling() {
        return false;
    }

    @Override
    public CloudSetting getCloudSetting() {
        return CloudSetting.DEFAULT;
    }

    @Override
    public boolean shouldRenderUnderwaterOverlay() {
        return true;
    }

    @Override
    public boolean shouldRenderVignette() {
        return true;
    }

    @Override
    public boolean shouldRenderSun() {
        return true;
    }

    @Override
    public boolean shouldRenderWeather() {
        return true;
    }

    @Override
    public boolean shouldRenderWeatherParticles() {
        return true;
    }

    @Override
    public boolean shouldRenderMoon() {
        return true;
    }

    @Override
    public boolean shouldRenderStars() {
        return true;
    }

    @Override
    public boolean shouldRenderSkyDisc() {
        return true;
    }

    @Override
    public boolean shouldWriteRainAndSnowToDepthBuffer() {
        return false;
    }

    @Override
    public ParticleRenderingSettings getParticleRenderingSettings() {
        return ParticleRenderingSettings.MIXED;
    }

    @Override
    public boolean allowConcurrentCompute() {
        return false;
    }

    @Override
    public boolean hasFeature(FeatureFlags flags) {
        return false;
    }

    @Override
    public float getSunPathRotation() {
        return 0;
    }

    @Override
    public DHCompat getDHCompat() {
        return null;
    }

    @Override
    public void setIsMainBound(boolean mainBound) {
    }

    @Override
    public void onBeginClear() {
    }

    @Override
    public boolean supportsEndFlash() {
        return false;
    }

    @Override
    public int getAlbedoTex() {
        return 0;
    }
}
