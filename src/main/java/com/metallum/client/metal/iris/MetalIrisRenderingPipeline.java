package com.metallum.client.metal.iris;

import com.mojang.blaze3d.textures.GpuTextureView;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats;
import net.irisshaders.iris.compat.dh.DHCompat;
import net.irisshaders.iris.features.FeatureFlags;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.state.ShaderAttributeInputs;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.helpers.Tri;
import net.irisshaders.iris.mixin.LevelRendererAccessor;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.loading.ProgramGroup;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.shaderpack.properties.CloudSetting;
import net.irisshaders.iris.shaderpack.properties.ParticleRenderingSettings;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import net.minecraft.client.Camera;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
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
 *       storing the results in {@link #getCompiledShaders()} for later use
 *       by the Metal render pass layer.</li>
 *   <li>Implements all {@code WorldRenderingPipeline} methods with the same
 *       safe defaults as {@code VanillaRenderingPipeline}.</li>
 *   <li>Makes {@link #beginLevelRendering()} a true no-op (no GL calls),
 *       unlike {@code VanillaRenderingPipeline} which calls
 *       {@code GL.getCapabilities()} and {@code glUseProgram(0)}.</li>
 * </ul>
 *
 * <p><b>Status:</b> MSL compilation is fully working (26/26 programs compile
 * successfully). The compiled MSL is stored but not yet used for rendering —
 * the {@code WorldRenderingPipeline} methods are still no-op stubs. The next
 * phase (M4) will create native {@code MTLRenderPipelineState} objects from
 * the stored MSL and implement actual Metal render passes.
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

    /**
     * Stores all successfully compiled MSL shader pairs, keyed by program
     * name (e.g. {@code "gbuffers_terrain"}, {@code "composite1"},
     * {@code "final"}). Populated during construction by
     * {@link #compileShadersToMsl()}.
     *
     * <p>This is the foundation for M4 (Metal render pass integration):
     * future rendering code will retrieve compiled MSL from this map and
     * use {@code MetalDevice.getOrCompileFunction(msl, entryPoint)} to
     * create native {@code MTLFunction} handles, then build
     * {@code MTLRenderPipelineState} objects for actual rendering.
     */
    private final Map<String, MetalIrisBridge.ShaderPair> compiledShaders = new LinkedHashMap<>();

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
     * Safe defaults used when calling Iris's {@link TransformPatcher} for the
     * MSL validation pipeline. Real rendering would need per-program values
     * (alpha test, attribute inputs) — these defaults are sufficient to get
     * the AST-level GLSL transformation done (which is what makes SPIR-V
     * compilation possible at all).
     */
    private static final AlphaTest DEFAULT_ALPHA_TEST = AlphaTest.ALWAYS;
    private static final ShaderAttributeInputs DEFAULT_ATTRIBUTE_INPUTS =
        new ShaderAttributeInputs(true, true, true, true, true);
    private static final Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> EMPTY_TEXTURE_MAP =
        Object2ObjectMaps.emptyMap();

    /**
     * Compiles all shaderpack programs from GLSL to Metal Shading Language
     * via {@link MetalIrisBridge}, validating the GLSL→SPIR-V→MSL pipeline.
     *
     * <p>Each program's raw GLSL (already processed by Iris's stage-1 text
     * preprocessor: {@code IncludeProcessor} + {@code JcppProcessor}) is first
     * passed through the appropriate {@link TransformPatcher} stage-2 AST
     * transformer before being handed to {@code MetalIrisBridge}. The stage-2
     * patcher performs the bulk of the GLSL modernisation that SPIR-V
     * compilation requires:
     * <ul>
     *   <li>{@code attribute}/{@code varying} &rarr; {@code in}/{@code out}</li>
     *   <li>{@code gl_Vertex} &rarr; {@code iris_Position},
     *       {@code gl_MultiTexCoord0} &rarr; {@code iris_UV0}, etc.</li>
     *   <li>{@code gl_FragColor} &rarr; {@code iris_FragData[0]}</li>
     *   <li>UBO injection for {@code iris_ModelViewMat},
     *       {@code iris_ProjMat}, ...</li>
     *   <li>legacy function renames, builtin uniform replacement</li>
     *   <li>{@code #version} bump to at least 330 with {@code core} profile</li>
     * </ul>
     *
     * <p>Which {@code patch*} method is used depends on the program's group:
     * <ul>
     *   <li>{@link ProgramGroup#Gbuffers} and {@link ProgramGroup#Shadow}
     *       &rarr; {@link TransformPatcher#patchVanilla}</li>
     *   <li>{@link ProgramGroup#Dh} with {@link ProgramId#DhTerrain}
     *       &rarr; {@link TransformPatcher#patchDHTerrain}</li>
     *   <li>Other {@link ProgramGroup#Dh} programs
     *       &rarr; {@link TransformPatcher#patchDHGeneric}</li>
     *   <li>{@link ProgramGroup#Final} and all {@link ProgramArrayId}s
     *       &rarr; {@link TransformPatcher#patchComposite} (with the
     *       appropriate {@link TextureStage})</li>
     * </ul>
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

            PatchPlan plan = planForProgramId(id);
            if (plan == null) {
                skipped++;
                continue;
            }

            total++;
            int result = patchAndCompile(source, plan);
            if (result == 1) success++;
            else if (result == 0) skipped++;
            else failed++;
        }

        for (ProgramArrayId arrayId : ProgramArrayId.values()) {
            ProgramSource[] sources = programSet.getComposite(arrayId);
            if (sources == null) continue;
            for (ProgramSource source : sources) {
                if (source == null || !source.isValid()) {
                    continue;
                }
                PatchPlan plan = planForProgramArrayId(arrayId);
                if (plan == null) {
                    continue;
                }
                total++;
                int result = patchAndCompile(source, plan);
                if (result == 1) success++;
                else if (result == 0) skipped++;
                else failed++;
            }
        }

        LOGGER.info("[MetalUniversal] MetalIrisRenderingPipeline ready. MSL compilation: {} compiled, {} failed, {} skipped (no source), {} total.",
            success, failed, skipped, total);
    }

    /**
     * Determines which {@link TransformPatcher} method to call for a given
     * {@link ProgramId}, based on its {@link ProgramGroup}.
     *
     * @return the {@link PatchPlan} describing how to patch this program,
     *         or {@code null} if the program group is not handled
     *         (e.g. {@code Setup}, {@code Begin}, ... — those are
     *         {@link ProgramArrayId}s, not {@link ProgramId}s).
     */
    private static PatchPlan planForProgramId(ProgramId id) {
        ProgramGroup group = id.getGroup();
        switch (group) {
            case Shadow, Gbuffers -> {
                return new PatchPlan(PatchMethod.VANILLA, null);
            }
            case Dh -> {
                if (id == ProgramId.DhTerrain) {
                    return new PatchPlan(PatchMethod.DH_TERRAIN, null);
                }
                return new PatchPlan(PatchMethod.DH_GENERIC, null);
            }
            case Final -> {
                return new PatchPlan(PatchMethod.COMPOSITE, TextureStage.COMPOSITE_AND_FINAL);
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * Determines which {@link TransformPatcher} method to call for a given
     * {@link ProgramArrayId}, mapping the array's group to the corresponding
     * {@link TextureStage} for {@link TransformPatcher#patchComposite}.
     */
    private static PatchPlan planForProgramArrayId(ProgramArrayId id) {
        // All ProgramArrayId programs (Setup/Begin/ShadowComposite/Prepare/
        // Deferred/Composite) are patched with patchComposite, but each one
        // passes a different TextureStage so that custom-texture lookups in
        // TextureTransformer resolve against the right stage's texture map.
        TextureStage stage = switch (id.getGroup()) {
            case Setup -> TextureStage.SETUP;
            case Begin -> TextureStage.BEGIN;
            case ShadowComposite -> TextureStage.SHADOWCOMP;
            case Prepare -> TextureStage.PREPARE;
            case Deferred -> TextureStage.DEFERRED;
            case Composite -> TextureStage.COMPOSITE_AND_FINAL;
            default -> null;
        };
        if (stage == null) {
            return null;
        }
        return new PatchPlan(PatchMethod.COMPOSITE, stage);
    }

    /**
     * Runs Iris's {@link TransformPatcher} on the program's GLSL sources,
     * then compiles the patched GLSL to MSL via {@link MetalIrisBridge}.
     *
     * <p>Iris's patchers return a {@link Map} keyed by {@link PatchShaderType}
     * containing the transformed source for each stage (the patcher may
     * inject/remove stages or split one into several). Only VERTEX and
     * FRAGMENT are consumed here — geometry/tessellation are unsupported by
     * {@link MetalIrisBridge} in this beta.
     *
     * @return {@code 1} on success, {@code 0} if the program was skipped
     *         (no patched vertex or fragment source), {@code -1} on failure
     */
    private int patchAndCompile(ProgramSource source, PatchPlan plan) {
        String name = source.getName();
        String vertex = source.getVertexSource().orElse(null);
        String geometry = source.getGeometrySource().orElse(null);
        String tessControl = source.getTessControlSource().orElse(null);
        String tessEval = source.getTessEvalSource().orElse(null);
        String fragment = source.getFragmentSource().orElse(null);

        if (vertex == null || fragment == null) {
            return 0;
        }

        Map<PatchShaderType, String> patched;
        try {
            patched = switch (plan.method) {
                case VANILLA -> TransformPatcher.patchVanilla(
                    name, vertex, geometry, tessControl, tessEval, fragment,
                    DEFAULT_ALPHA_TEST,
                    /* isLines */ false,
                    /* isClouds */ false,
                    /* hasChunkOffset */ true,
                    DEFAULT_ATTRIBUTE_INPUTS,
                    EMPTY_TEXTURE_MAP);
                case COMPOSITE -> TransformPatcher.patchComposite(
                    name, vertex, geometry, fragment,
                    plan.stage, EMPTY_TEXTURE_MAP);
                case DH_TERRAIN -> TransformPatcher.patchDHTerrain(
                    name, vertex, tessControl, tessEval, geometry, fragment,
                    EMPTY_TEXTURE_MAP);
                case DH_GENERIC -> TransformPatcher.patchDHGeneric(
                    name, vertex, tessControl, tessEval, geometry, fragment,
                    EMPTY_TEXTURE_MAP);
            };
        } catch (Exception e) {
            logFailure(name, "TransformPatcher.patch* failed: " + e.getMessage(), e);
            return -1;
        }

        if (patched == null) {
            return 0;
        }

        String patchedVertex = patched.get(PatchShaderType.VERTEX);
        String patchedFragment = patched.get(PatchShaderType.FRAGMENT);
        if (patchedVertex == null || patchedFragment == null) {
            LOGGER.warn("[MetalUniversal] '{}' patcher produced null vertex or fragment (vertex={}, fragment={})",
                name, patchedVertex != null, patchedFragment != null);
            return 0;
        }

        try {
            MetalIrisBridge.ShaderPair pair = MetalIrisBridge.compileIrisProgram(
                name, patchedVertex, patchedFragment);
            if (pair.vertex() != null && pair.fragment() != null) {
                compiledShaders.put(name, pair);
                LOGGER.info("[MetalUniversal] Compiled '{}' → MSL (vsh: {} chars, fsh: {} chars)",
                    name, pair.vertex().source().length(), pair.fragment().source().length());
                return 1;
            }
            return -1;
        } catch (Exception e) {
            logFailure(name, e.getMessage(), e);
            return -1;
        }
    }

    private static void logFailure(String name, String message, Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        LOGGER.warn("[MetalUniversal] Failed to compile '{}' to MSL: {} | root: {}",
            name, message, root.toString());
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("[MetalUniversal] Compilation failure stack for '{}'", name, root);
        }
    }

    /** Which {@link TransformPatcher} method to invoke. */
    private enum PatchMethod {
        VANILLA,
        COMPOSITE,
        DH_TERRAIN,
        DH_GENERIC
    }

    /** Carrier for the patching strategy selected for a single program. */
    private record PatchPlan(PatchMethod method, TextureStage stage) {
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
        compiledShaders.clear();
        LOGGER.info("[MetalUniversal] MetalIrisRenderingPipeline destroyed (released {} compiled MSL shader pairs).",
            compiledShaders.size());
    }

    /**
     * Returns an unmodifiable view of all successfully compiled MSL shader
     * pairs, keyed by program name. Used by the M4 rendering layer to
     * retrieve compiled MSL source for native pipeline creation.
     *
     * @return unmodifiable map of program name → compiled MSL shader pair
     */
    public Map<String, MetalIrisBridge.ShaderPair> getCompiledShaders() {
        return Collections.unmodifiableMap(compiledShaders);
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
