package com.metallum.client.metal.iris;

import com.metallum.Metallum;
import com.metallum.client.metal.render.MetalCrossShaderCompiler;
import com.metallum.client.metal.render.MetalCrossShaderCompiler.MslShader;
import com.mojang.blaze3d.shaders.ShaderType;
import net.fabricmc.loader.api.FabricLoader;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bridge between Iris shader packs and MetalUniversal's GLSL&rarr;MSL
 * compilation pipeline.
 *
 * <p>MetalUniversal already converts Minecraft's own vanilla GLSL shaders to
 * Metal Shading Language (MSL) via a two-step pipeline:
 * <ol>
 *   <li>GLSL &rarr; SPIR-V using Mojang's {@code GlslCompiler}
 *       ({@code com.mojang.blaze3d.vulkan.glsl})</li>
 *   <li>SPIR-V &rarr; MSL using SPIRV-Cross (LWJGL {@code spvc} bindings)</li>
 * </ol>
 *
 * <p>This bridge exposes that same conversion capability for Iris's shaderpack
 * GLSL, so that when Iris is installed alongside MetalUniversal, shader pack
 * programs (gbuffer, composite, shadow, etc.) can be compiled to Metal instead
 * of requiring an OpenGL backend.
 *
 * <p><b>Reference:</b> The Iris 26.2 source (available on the {@code iris}
 * branch of this repository) was studied to understand how Iris structures its
 * shaders. Key findings that shaped this bridge:
 * <ul>
 *   <li>Iris's {@code TransformPatcher} produces patched GLSL source strings
 *       per shader stage (vertex, fragment, geometry, etc.).</li>
 *   <li>Iris's {@code GlShader} / {@code ShaderCreator} compile these strings
 *       via raw OpenGL ({@code glCreateShader}/{@code glCompileShader}).</li>
 *   <li>Iris has a {@code VK_CONFORMANCE} flag in {@code IrisLimits} that, when
 *       enabled, adds explicit {@code layout(location=N)} qualifiers via
 *       {@code LayoutTransformer} — exactly what SPIR-V requires.</li>
 * </ul>
 *
 * <p>This bridge is deliberately decoupled from Iris at compile time: it does
 * not reference any {@code net.irisshaders.iris.*} classes, so Iris does not
 * need to be present on the build classpath. Iris is detected at runtime via
 * {@link FabricLoader}. Shader stages are identified by string name (e.g.
 * {@code "VERTEX"}, {@code "FRAGMENT"}) to avoid a hard type dependency on
 * Iris's {@code ShaderType} enum.
 *
 * <p>The bridge replicates the layout-location assignment (simplified) so that
 * Iris's desktop GLSL becomes SPIR-V-compatible without needing to modify
 * Iris's compile-time-constant {@code VK_CONFORMANCE} flag.
 */
public final class MetalIrisBridge {
    private static final String IRIS_MOD_ID = "iris";

    private static volatile boolean initialized;
    private static volatile boolean irisPresent;

    /** Cache of compiled MSL shaders keyed by (name + source hash). */
    private static final Map<String, MslShader> shaderCache = new LinkedHashMap<>();
    private static final int CACHE_LIMIT = 256;

    private MetalIrisBridge() {
    }

    /**
     * Initializes the bridge. Safe to call multiple times. Detects whether Iris
     * is installed and logs the bridge status.
     */
    public static synchronized void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;
        irisPresent = FabricLoader.getInstance().isModLoaded(IRIS_MOD_ID);
        if (irisPresent) {
            Metallum.LOGGER.info(
                    "[MetalUniversal] Iris detected. Metal GLSL shader bridge is active — "
                            + "Iris shaderpack programs will be compiled to Metal via GLSL→SPIR-V→MSL."
            );
        } else {
            Metallum.LOGGER.debug(
                    "[MetalUniversal] Iris not detected; Metal GLSL shader bridge is standby."
            );
        }
    }

    /**
     * @return {@code true} if Iris was detected at initialization time.
     */
    public static boolean isIrisPresent() {
        return irisPresent;
    }

    /**
     * Compiles a shaderpack GLSL source string to Metal Shading Language.
     *
     * <p>The GLSL is first preprocessed to ensure SPIR-V compatibility (explicit
     * {@code layout(location=N)} on {@code in}/{@code out} variables), then
     * compiled via MetalUniversal's existing GLSL&rarr;SPIR-V&rarr;MSL pipeline.
     *
     * <p>Shader stage names match Iris's {@code ShaderType} enum names (case-
     * insensitive): {@code VERTEX}, {@code FRAGMENT}, {@code GEOMETRY},
     * {@code COMPUTE}, {@code TESSELATION_CONTROL}, {@code TESSELATION_EVAL}.
     * Only {@code VERTEX} and {@code FRAGMENT} are supported in this beta.
     *
     * @param name            debug name for the shader (used in error messages)
     * @param glslSource      the patched GLSL source from Iris's TransformPatcher
     * @param shaderStageName the shader stage name (e.g. "VERTEX", "FRAGMENT")
     * @return the compiled MSL shader source plus reflection metadata
     * @throws ShaderCompilationFailedException if compilation fails
     * @throws UnsupportedOperationException     if the shader stage is unsupported
     */
    public static MslShader compileIrisShader(
            final String name,
            final String glslSource,
            final String shaderStageName
    ) {
        if (!initialized) {
            initialize();
        }
        final ShaderType mojangType = mapShaderType(shaderStageName);
        final String cacheKey = name + ":" + shaderStageName + ":" + glslSource.hashCode();

        synchronized (shaderCache) {
            MslShader cached = shaderCache.get(cacheKey);
            if (cached != null) {
                return cached;
            }
        }

        final String spirvCompatibleGlsl = ensureSpirvCompatible(glslSource);

        try {
            MslShader result = MetalCrossShaderCompiler.compileGlslToMsl(name, spirvCompatibleGlsl, mojangType);
            Metallum.LOGGER.debug(
                    "[MetalUniversal] Compiled Iris shader '{}' ({}) to MSL ({} chars)",
                    name, shaderStageName, result.source().length()
            );
            synchronized (shaderCache) {
                if (shaderCache.size() >= CACHE_LIMIT) {
                    shaderCache.clear();
                }
                shaderCache.put(cacheKey, result);
            }
            return result;
        } catch (Exception e) {
            throw new ShaderCompilationFailedException(name, shaderStageName, e);
        }
    }

    /**
     * Compiles a vertex+fragment GLSL shader pair to MSL. This is the common
     * case for Iris gbuffer/composite/shadow programs.
     *
     * @param name           debug name for the program
     * @param vertexSource   patched vertex GLSL source
     * @param fragmentSource patched fragment GLSL source
     * @return a pair of compiled MSL shaders (vertex, fragment)
     * @throws ShaderCompilationFailedException if either compilation fails
     */
    public static ShaderPair compileIrisProgram(
            final String name,
            final String vertexSource,
            final String fragmentSource
    ) {
        MslShader vertex = compileIrisShader(name + ".vsh", vertexSource, "VERTEX");
        MslShader fragment = compileIrisShader(name + ".fsh", fragmentSource, "FRAGMENT");
        return new ShaderPair(vertex, fragment);
    }

    /**
     * Maps a shader stage name (matching Iris's {@code ShaderType} enum names)
     * to Mojang's {@link ShaderType}. Only VERTEX and FRAGMENT are supported in
     * this beta; other stages will throw.
     */
    private static ShaderType mapShaderType(final String shaderStageName) {
        return switch (shaderStageName.toUpperCase(Locale.ROOT)) {
            case "VERTEX" -> ShaderType.VERTEX;
            case "FRAGMENT" -> ShaderType.FRAGMENT;
            default -> throw new UnsupportedOperationException(
                    "Metal shader compilation for shader stage '" + shaderStageName
                            + "' is not yet supported in this beta. Only VERTEX and FRAGMENT are supported."
            );
        };
    }

    // ---- GLSL SPIR-V compatibility preprocessing ----

    /**
     * Pattern matching a top-level {@code in}/{@code out} declaration that
     * lacks a {@code layout(...)} qualifier. Captures group 1 = storage
     * qualifier (in/out), group 2 = type, group 3 = variable name(s).
     *
     * <p>This is a simplified regex-based equivalent of Iris's
     * {@code LayoutTransformer}, which uses a full ANTLR AST to do the same
     * job. The regex approach handles the common cases (single declarations,
     * simple types) and is sufficient for most shader packs.
     */
    private static final Pattern UNLOCATED_IN_OUT = Pattern.compile(
            "^\\s*(?!layout\\s*\\()(in|out)\\s+(\\w+)\\s+([^;]+);",
            Pattern.MULTILINE
    );

    /**
     * Ensures the GLSL source is SPIR-V-compatible by adding explicit
     * {@code layout(location=N)} qualifiers to {@code in}/{@code out} variables
     * that lack them. SPIR-V (and thus the GlslCompiler) requires explicit
     * locations on interface variables; desktop GLSL does not.
     *
     * <p>This replicates what Iris's {@code LayoutTransformer.transformGrouped}
     * does when {@code IrisLimits.VK_CONFORMANCE} is {@code true}. Since that
     * flag is a compile-time constant ({@code false}) baked into Iris's jar,
     * we cannot enable it at runtime; instead we perform the transformation
     * here before handing the GLSL to the SPIR-V compiler.
     *
     * @param glslSource the GLSL source (already patched by Iris's TransformPatcher)
     * @return SPIR-V-compatible GLSL with explicit layout locations
     */
    static String ensureSpirvCompatible(final String glslSource) {
        if (glslSource == null || glslSource.isBlank()) {
            return glslSource;
        }
        Matcher matcher = UNLOCATED_IN_OUT.matcher(glslSource);
        if (!matcher.find()) {
            return glslSource;
        }

        int inLocation = 0;
        int outLocation = 0;
        StringBuffer result = new StringBuffer(glslSource.length() + 128);
        matcher.reset();
        while (matcher.find()) {
            String qualifier = matcher.group(1);
            String type = matcher.group(2);
            String names = matcher.group(3).trim();
            int location = "in".equals(qualifier) ? inLocation++ : outLocation++;
            String replacement = "layout(location = " + location + ") " + qualifier + " " + type + " " + names + ";";
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /** A compiled vertex+fragment MSL shader pair. */
    public record ShaderPair(MslShader vertex, MslShader fragment) {
    }

    /** Thrown when Iris GLSL compilation to MSL fails. */
    public static final class ShaderCompilationFailedException extends RuntimeException {
        ShaderCompilationFailedException(final String name, final String shaderStageName, final Throwable cause) {
            super("Failed to compile Iris shader '" + name + "' (" + shaderStageName + ") to Metal MSL", cause);
        }
    }
}
