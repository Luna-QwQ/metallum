package com.metallum.client.metal.iris;

import com.metallum.Metallum;
import com.metallum.client.metal.render.MetalCrossShaderCompiler;
import com.metallum.client.metal.render.MetalCrossShaderCompiler.MslShader;
import com.metallum.mixin.accessor.MetallumGpuDeviceAccessor;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.loader.api.FabricLoader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
     * Determines whether the active GPU backend is a non-OpenGL backend
     * (Metal or Vulkan). On such backends there is no live OpenGL context, so
     * Iris's OpenGL-dependent initialization and rendering paths must be
     * diverted by MetalUniversal's Iris mixins.
     *
     * <p>This inspects the concrete backend held by the current
     * {@link com.mojang.blaze3d.systems.GpuDevice}. If the backend is a
     * {@link GlDevice}, OpenGL is available and Iris can run natively; otherwise
     * the Metal/Vulkan redirection path is required.
     *
     * @return {@code true} if the active backend is NOT OpenGL
     *         (i.e. Iris must be diverted)
     */
    public static boolean isNonGlBackend() {
        try {
            Object backend = ((MetallumGpuDeviceAccessor) RenderSystem.getDevice()).metallum$getBackend();
            return !(backend instanceof GlDevice);
        } catch (Throwable t) {
            // Device not ready yet, or accessor not applied — assume non-GL to
            // be safe so Iris's GL paths are diverted rather than crashing.
            return true;
        }
    }

    /**
     * Compiles a shaderpack GLSL source string to Metal Shading Language.
     *
     * <p>The GLSL is first preprocessed via {@link #ensureSpirvCompatible}
     * to make it Vulkan/SPIR-V-conformant (bump {@code #version} to 450,
     * wrap loose uniforms in a UBO, add {@code layout(location=N)} to
     * {@code in}/{@code out} variables), then compiled via MetalUniversal's
     * existing GLSL&rarr;SPIR-V&rarr;MSL pipeline.
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

    // ---- GLSL Vulkan/SPIR-V compatibility preprocessing ----
    //
    // Iris's TransformPatcher outputs desktop OpenGL GLSL (e.g. #version 330
    // core with loose `uniform vec3 foo;` declarations and in/out variables
    // without layout(location)). Mojang's GlslCompiler (in
    // com.mojang.blaze3d.vulkan.glsl) targets Vulkan/SPIR-V, which has two
    // strict requirements that desktop GLSL violates:
    //
    //   1. All non-opaque uniforms MUST be inside a uniform block (UBO).
    //      → error: "non-opaque uniforms outside a block"
    //   2. All in/out interface variables MUST have layout(location=N).
    //      → error: "location qualifier on output: not supported for this
    //        version or the enabled extensions" (needs #version 450 in
    //        Vulkan profile, not just 330)
    //
    // Iris's own LayoutTransformer would fix #2 when IrisLimits.VK_CONFORMANCE
    // is true, but that's a compile-time constant (false). Nothing in Iris
    // fixes #1 — Iris relies on desktop GL which allows loose uniforms.
    //
    // The following three-step pass makes the patched GLSL Vulkan-conformant:

    /** Matches {@code #version <number> [profile]} for version bumping. */
    private static final Pattern VERSION_LINE = Pattern.compile(
            "#version\\s+\\d+(?:\\s+\\w+)?", Pattern.MULTILINE
    );

    /**
     * Matches a loose (non-block, non-opaque) uniform declaration at global
     * scope. Captures:
     * <ul>
     *   <li>group 1 = type (vec3, mat4, float, user struct, ...)</li>
     *   <li>group 2 = variable name</li>
     *   <li>group 3 = optional array specifier (e.g. {@code [4]})</li>
     * </ul>
     *
     * <p>Excludes opaque types (sampler*, image*, atomicCounter, subpass*)
     * via negative lookahead — those stay as-is (Vulkan allows them outside
     * blocks). Also excludes uniform blocks ({@code uniform Name { ... };})
     * because they have {@code {} after the name, not {@code ;} or
     * {@code [...]}.
     */
    private static final Pattern LOOSE_UNIFORM = Pattern.compile(
            "^\\s*uniform\\s+(?!sampler|image|subpass|atomicCounter|isampler|usampler|iimage|uimage)" +
            "(\\w+)\\s+(\\w+)\\s*(\\[[^\\]]*\\])?\\s*(?:=[^;]+)?\\s*;",
            Pattern.MULTILINE
    );

    /**
     * Matches a top-level {@code in}/{@code out} declaration that lacks a
     * {@code layout(...)} qualifier, including optional interpolation
     * qualifiers (flat, smooth, noperspective, centroid, invariant, precise).
     * Captures:
     * <ul>
     *   <li>group 1 = preceding qualifiers (e.g. "flat " or "")</li>
     *   <li>group 2 = storage qualifier (in/out)</li>
     *   <li>group 3 = type</li>
     *   <li>group 4 = variable name(s)</li>
     * </ul>
     */
    private static final Pattern UNLOCATED_IN_OUT = Pattern.compile(
            "^\\s*((?:(?:flat|smooth|noperspective|centroid|invariant|precise)\\s+)*)(in|out)\\s+(\\w+)\\s+([^;]+);",
            Pattern.MULTILINE
    );

    /**
     * Transforms Iris's patched desktop-GLSL into Vulkan-conformant GLSL so
     * that Mojang's {@code GlslCompiler} can compile it to SPIR-V.
     *
     * <p>Three steps:
     * <ol>
     *   <li><b>Bump {@code #version} to 450.</b> The patcher bumps to 330,
     *       but glslang's Vulkan profile requires 450 for
     *       {@code layout(location)} on in/out without extensions.</li>
     *   <li><b>Wrap loose non-opaque uniforms in a UBO.</b> Collects all
     *       {@code uniform <type> <name>;} declarations (excluding
     *       samplers/images/already-in-blocks), removes them, and injects
     *       a single {@code layout(std140) uniform iris_LooseUniforms { ... };}
     *       block at the position of the first removed declaration.</li>
     *   <li><b>Add {@code layout(location=N)} to in/out.</b> Assigns
     *       sequential locations to in and out variables separately.</li>
     * </ol>
     *
     * @param glslSource GLSL already patched by Iris's TransformPatcher
     * @return Vulkan-conformant GLSL ready for SPIR-V compilation
     */
    static String ensureSpirvCompatible(final String glslSource) {
        if (glslSource == null || glslSource.isBlank()) {
            return glslSource;
        }
        String result = bumpVersionTo450(glslSource);
        result = wrapLooseUniformsInUbo(result);
        result = addLayoutLocationsToInOut(result);
        return result;
    }

    /**
     * Replaces the first {@code #version XXX [profile]} with
     * {@code #version 450}. If no version line exists, prepends one.
     */
    private static String bumpVersionTo450(final String glslSource) {
        Matcher m = VERSION_LINE.matcher(glslSource);
        if (m.find()) {
            return m.replaceFirst("#version 450");
        }
        return "#version 450\n" + glslSource;
    }

    /**
     * Collects all loose non-opaque uniform declarations, removes them from
     * the source, and injects a single UBO block at the position of the first
     * removed declaration (so any preceding struct/type definitions remain
     * visible).
     *
     * <p>Initializers ({@code = ...}) are stripped — UBO members cannot have
     * initializers. Array specifiers ({@code [N]}) are preserved.
     */
    private static String wrapLooseUniformsInUbo(final String glslSource) {
        Matcher matcher = LOOSE_UNIFORM.matcher(glslSource);
        List<String> members = new ArrayList<>();
        List<int[]> positions = new ArrayList<>(); // [start, end] of each match

        while (matcher.find()) {
            String type = matcher.group(1);
            String name = matcher.group(2);
            String arraySpec = matcher.group(3);
            members.add(type + " " + name + (arraySpec != null ? arraySpec : "") + ";");
            positions.add(new int[]{matcher.start(), matcher.end()});
        }

        if (members.isEmpty()) {
            return glslSource;
        }

        StringBuilder ubo = new StringBuilder();
        ubo.append("layout(std140) uniform iris_LooseUniforms {\n");
        for (String member : members) {
            ubo.append("    ").append(member).append("\n");
        }
        ubo.append("};\n");

        // Replace first uniform with the UBO block; remove the rest.
        StringBuilder result = new StringBuilder(glslSource.length() + ubo.length());
        int lastPos = 0;
        for (int i = 0; i < positions.size(); i++) {
            int[] pos = positions.get(i);
            result.append(glslSource, lastPos, pos[0]);
            if (i == 0) {
                result.append(ubo);
            }
            lastPos = pos[1];
        }
        result.append(glslSource, lastPos, glslSource.length());
        return result.toString();
    }

    /**
     * Pattern to find existing {@code layout(location=N)} on in/out declarations
     * so we can compute the starting offset for new locations. Captures group 1
     * = location number, group 2 = storage qualifier (in/out).
     */
    private static final Pattern EXISTING_LAYOUT_LOCATION = Pattern.compile(
            "layout\\s*\\(\\s*location\\s*=\\s*(\\d+)\\s*\\)[^;]*?\\b(in|out)\\b",
            Pattern.MULTILINE
    );

    /**
     * Detects array size in a variable name like {@code arr[8]} → {@code 8}.
     * Returns 1 if no array specifier is present.
     */
    private static int arraySize(final String nameSpec) {
        Matcher m = ARRAY_SIZE_PATTERN.matcher(nameSpec);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
                // unsized array or non-numeric — treat as 1
            }
        }
        return 1;
    }

    private static final Pattern ARRAY_SIZE_PATTERN = Pattern.compile("\\[(\\d+)\\]");

    /**
     * Adds {@code layout(location=N)} to every top-level {@code in}/{@code out}
     * declaration that doesn't already have a layout qualifier.
     *
     * <p>Three sub-issues must be handled correctly:
     * <ol>
     *   <li><b>Existing locations.</b> TransformPatcher injects its own
     *       interface variables with explicit locations (e.g.
     *       {@code layout(location=0) in vec3 iris_Position;}). New locations
     *       must start after the max existing location to avoid
     *       "overlapping use of location N" errors.</li>
     *   <li><b>Multi-name declarations.</b> {@code out vec2 a, b;} assigns
     *       both names to the same location if given a single
     *       {@code layout(location=N)}. Each name must be split into its own
     *       declaration with a unique location.</li>
     *   <li><b>Arrays.</b> {@code out vec4 arr[8];} consumes 8 consecutive
     *       locations in Vulkan GLSL. The counter must advance by the array
     *       size, not 1.</li>
     * </ol>
     *
     * <p>Handles optional interpolation qualifiers (flat, smooth, noperspective,
     * centroid, invariant, precise) that appear before the storage qualifier.
     */
    private static String addLayoutLocationsToInOut(final String glslSource) {
        // Step 1: find max existing location for in and out separately.
        int maxInLocation = -1;
        int maxOutLocation = -1;
        Matcher existingMatcher = EXISTING_LAYOUT_LOCATION.matcher(glslSource);
        while (existingMatcher.find()) {
            int loc = Integer.parseInt(existingMatcher.group(1));
            String storage = existingMatcher.group(2);
            if ("in".equals(storage)) {
                maxInLocation = Math.max(maxInLocation, loc);
            } else {
                maxOutLocation = Math.max(maxOutLocation, loc);
            }
        }
        int inLocation = maxInLocation + 1;
        int outLocation = maxOutLocation + 1;

        Matcher matcher = UNLOCATED_IN_OUT.matcher(glslSource);
        if (!matcher.find()) {
            return glslSource;
        }

        StringBuffer result = new StringBuffer(glslSource.length() + 128);
        matcher.reset();
        while (matcher.find()) {
            String fullMatch = matcher.group(0);
            // Skip if this declaration already has a layout qualifier.
            if (fullMatch.contains("layout")) {
                matcher.appendReplacement(result, Matcher.quoteReplacement(fullMatch));
                continue;
            }
            String qualifiers = matcher.group(1); // e.g. "flat " or ""
            String storage = matcher.group(2);     // "in" or "out"
            String type = matcher.group(3);
            String names = matcher.group(4).trim();

            // Step 2: split multi-name declarations into separate declarations,
            // each with its own location.
            String[] nameParts = names.split(",");
            StringBuilder replacement = new StringBuilder();
            for (int i = 0; i < nameParts.length; i++) {
                String name = nameParts[i].trim();
                int location = "in".equals(storage) ? inLocation : outLocation;
                // Step 3: arrays consume multiple consecutive locations.
                int size = arraySize(name);
                if ("in".equals(storage)) {
                    inLocation += size;
                } else {
                    outLocation += size;
                }
                replacement.append("layout(location = ").append(location).append(") ")
                        .append(qualifiers).append(storage).append(" ").append(type)
                        .append(" ").append(name).append(";");
                if (i < nameParts.length - 1) {
                    replacement.append("\n");
                }
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement.toString()));
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
