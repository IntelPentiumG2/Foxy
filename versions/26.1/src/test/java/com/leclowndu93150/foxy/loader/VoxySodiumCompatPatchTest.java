package com.leclowndu93150.foxy.loader;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.ClassReader;

import java.io.InputStream;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoxySodiumCompatPatchTest {

    private static final String REDIRECT = "Lorg/spongepowered/asm/mixin/injection/Redirect;";

    private static byte[] readClass(String internalName) throws Exception {
        try (InputStream in = VoxySodiumCompatPatchTest.class.getClassLoader()
                .getResourceAsStream(internalName + ".class")) {
            assertNotNull(in, "not on the test classpath: " + internalName);
            return in.readAllBytes();
        }
    }

    private static ClassNode parse(byte[] clazz) {
        ClassNode node = new ClassNode();
        new ClassReader(clazz).accept(node, 0);
        return node;
    }

    /**
     * Reads a class straight out of the Voxy 0.2.18 jar by file path, bypassing the classloader
     * entirely. 0.2.16 (already on the test classpath via testImplementation) and 0.2.18 both
     * contain a class of this same name, so a classloader lookup here would be ambiguous about
     * which jar answers it; the Gradle build resolves 0.2.18 into its own non-consumable
     * configuration and hands us the jar's file path via this system property instead. See
     * build.gradle.kts's "voxy0218Fixture" configuration.
     */
    private static byte[] readClassFromVoxy0218(String internalName) throws Exception {
        String jarPath = System.getProperty("foxy.voxy0218Jar");
        assertNotNull(jarPath, "system property foxy.voxy0218Jar not set;"
                + " check the voxy0218Fixture wiring in build.gradle.kts");
        try (ZipFile jar = new ZipFile(jarPath)) {
            ZipEntry entry = jar.getEntry(internalName + ".class");
            assertNotNull(entry, "not found in the 0.2.18 jar: " + internalName);
            try (InputStream in = jar.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }
    }

    private static MethodNode findMethod(ClassNode node, String methodName) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(methodName)) return method;
        }
        return null;
    }

    /** Finds the @Redirect annotation on the named method, or null if it has none. */
    private static AnnotationNode redirectOn(ClassNode node, String methodName) {
        for (var method : node.methods) {
            if (!method.name.equals(methodName)) continue;
            List<AnnotationNode> annotations = method.visibleAnnotations;
            if (annotations == null) continue;
            for (AnnotationNode annotation : annotations) {
                if (REDIRECT.equals(annotation.desc)) return annotation;
            }
        }
        return null;
    }

    /** The first @Redirect anywhere in the class, whatever its handler is named. */
    private static AnnotationNode anyRedirect(ClassNode node) {
        for (var method : node.methods) {
            List<AnnotationNode> annotations = method.visibleAnnotations;
            if (annotations == null) continue;
            for (AnnotationNode annotation : annotations) {
                if (REDIRECT.equals(annotation.desc)) return annotation;
            }
        }
        return null;
    }

    /** Reads the single string in the annotation's "method" array value. */
    private static String methodValue(AnnotationNode annotation) {
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if ("method".equals(annotation.values.get(i))) {
                return (String) ((List<?>) annotation.values.get(i + 1)).getFirst();
            }
        }
        return null;
    }

    @Test
    void appendsUniformBufferManagerToUploadResultsRedirect() throws Exception {
        byte[] original = readClass("me/cortex/voxy/client/mixin/sodium/MixinRenderRegionManager");

        AnnotationNode before = anyRedirect(parse(original));
        assertNotNull(before, "precondition: the shipped jar should carry a @Redirect");
        assertTrue(methodValue(before).endsWith("Ljava/util/Collection;)V"),
                "precondition: the shipped jar should carry the Sodium 0.8 descriptor");

        AnnotationNode after = anyRedirect(parse(VoxySodiumCompatPatch.patch(original)));
        assertNotNull(after, "the annotation must survive; only its descriptor changes");

        assertEquals(
                "uploadResults(Lnet/caffeinemc/mods/sodium/client/gl/device/CommandList;"
                        + "Lnet/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegion;"
                        + "Ljava/util/Collection;"
                        + "Lnet/caffeinemc/mods/sodium/client/render/chunk/UniformBufferManager;)V",
                methodValue(after));
    }

    @Test
    void stripsTheRedirectFromUpdateOnUpload() throws Exception {
        byte[] original = readClass("me/cortex/voxy/client/mixin/sodium/MixinRenderSectionManager");
        assertNotNull(redirectOn(parse(original), "voxy$updateOnUpload"),
                "precondition: the shipped jar should carry the @Redirect");

        byte[] patched = VoxySodiumCompatPatch.patch(original);

        assertNull(redirectOn(parse(patched), "voxy$updateOnUpload"),
                "the @Redirect must be gone so Foxy's replacement is the only one");
    }

    @Test
    void leavesUnrelatedClassesByteIdentical() throws Exception {
        byte[] original = readClass("me/cortex/voxy/commonImpl/VoxyCommon");
        assertSame(original, VoxySodiumCompatPatch.patch(original),
                "a class with none of the patterns must come back untouched");
    }

    /**
     * Pins the inertness guarantee for the case that actually matters: a class whose NAME still
     * matches ({@code MixinRenderSectionManager}, still carrying {@code voxy$updateOnUpload})
     * but whose Sodium-0.9 signature does not. {@code leavesUnrelatedClassesByteIdentical} only
     * exercises the trivial early return in {@code patch()} (wrong class name entirely); this is
     * the one that would have caught the strip path deleting an already-correct 0.2.18 redirect.
     */
    @Test
    void leavesVoxy0218SectionManagerByteIdentical() throws Exception {
        byte[] original = readClassFromVoxy0218("me/cortex/voxy/client/mixin/sodium/MixinRenderSectionManager");

        MethodNode handler = findMethod(parse(original), "voxy$updateOnUpload");
        assertNotNull(handler, "precondition: 0.2.18 should still carry voxy$updateOnUpload");
        assertTrue(handler.desc.endsWith(")I"),
                "precondition: 0.2.18's handler should already return the Sodium 0.9 bitmask, not 0.8's boolean");

        assertSame(original, VoxySodiumCompatPatch.patch(original),
                "0.2.18's redirect already targets Sodium 0.9; the strip must not touch it");
    }

    // ---------------------------------------------------------------------------------------
    // MixinDefaultChunkRenderer: handler widening + begin() call-site rewrite
    // ---------------------------------------------------------------------------------------

    private static final String CHUNK_RENDERER_MIXIN =
            "me/cortex/voxy/client/mixin/sodium/MixinDefaultChunkRenderer";

    /** Sodium 0.8's render(...) argument list, as captured by Voxy 0.2.16's @Inject handlers. */
    private static final String RENDER_HANDLER_SODIUM_0_8 =
            "(Lnet/caffeinemc/mods/sodium/client/render/chunk/ChunkRenderMatrices;"
                    + "Lnet/caffeinemc/mods/sodium/client/gl/device/CommandList;"
                    + "Lnet/caffeinemc/mods/sodium/client/render/chunk/lists/ChunkRenderListIterable;"
                    + "Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;"
                    + "Lnet/caffeinemc/mods/sodium/client/render/viewport/CameraTransform;"
                    + "Lnet/caffeinemc/mods/sodium/client/util/FogParameters;"
                    + "ZLcom/mojang/blaze3d/textures/GpuSampler;"
                    + "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V";

    /** The same list widened to Sodium 0.9.1-for-26.1.2's, two new params before CallbackInfo. */
    private static final String RENDER_HANDLER_SODIUM_0_9 =
            "(Lnet/caffeinemc/mods/sodium/client/render/chunk/ChunkRenderMatrices;"
                    + "Lnet/caffeinemc/mods/sodium/client/gl/device/CommandList;"
                    + "Lnet/caffeinemc/mods/sodium/client/render/chunk/lists/ChunkRenderListIterable;"
                    + "Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;"
                    + "Lnet/caffeinemc/mods/sodium/client/render/viewport/CameraTransform;"
                    + "Lnet/caffeinemc/mods/sodium/client/util/FogParameters;"
                    + "ZLcom/mojang/blaze3d/textures/GpuSampler;"
                    + "Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"
                    + "Lnet/caffeinemc/mods/sodium/client/gl/buffer/GlTexelBuffer;"
                    + "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V";

    private static final String BEGIN_SODIUM_0_8 =
            "(Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;"
                    + "Lnet/caffeinemc/mods/sodium/client/util/FogParameters;"
                    + "Lcom/mojang/blaze3d/textures/GpuSampler;)V";

    private static final String BEGIN_SODIUM_0_9 =
            "(Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;"
                    + "Lnet/caffeinemc/mods/sodium/client/util/FogParameters;"
                    + "Lcom/mojang/blaze3d/textures/GpuSampler;"
                    + "Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"
                    + "Lnet/caffeinemc/mods/sodium/client/gl/buffer/GlTexelBuffer;)V";

    private static java.util.List<org.objectweb.asm.tree.MethodInsnNode> beginCalls(
            MethodNode method) {
        var calls = new java.util.ArrayList<org.objectweb.asm.tree.MethodInsnNode>();
        for (org.objectweb.asm.tree.AbstractInsnNode insn : method.instructions) {
            if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call
                    && "begin".equals(call.name)) {
                calls.add(call);
            }
        }
        return calls;
    }

    @Test
    void widensDefaultChunkRendererRenderHandlers() throws Exception {
        byte[] original = readClass(CHUNK_RENDERER_MIXIN);

        for (String handler : List.of("voxy$cancelThingie", "voxy$injectRender")) {
            assertEquals(RENDER_HANDLER_SODIUM_0_8, findMethod(parse(original), handler).desc,
                    "precondition: 0.2.16's " + handler + " should capture Sodium 0.8's args");
        }

        ClassNode patched = parse(VoxySodiumCompatPatch.patch(original));
        for (String handler : List.of("voxy$cancelThingie", "voxy$injectRender")) {
            assertEquals(RENDER_HANDLER_SODIUM_0_9, findMethod(patched, handler).desc,
                    handler + " must capture Sodium 0.9's full argument list, or sponge-mixin"
                            + " rejects the whole required config (prefix capture is not allowed)");
        }
    }

    @Test
    void rewritesBeginCallToFiveArgsFedFromTheNewParameters() throws Exception {
        byte[] patched = VoxySodiumCompatPatch.patch(readClass(CHUNK_RENDERER_MIXIN));
        MethodNode handler = findMethod(parse(patched), "voxy$cancelThingie");

        var calls = beginCalls(handler);
        assertEquals(1, calls.size(), "exactly one begin() call expected");
        var call = calls.getFirst();
        assertEquals(org.objectweb.asm.Opcodes.INVOKESPECIAL, call.getOpcode());
        assertEquals(BEGIN_SODIUM_0_9, call.desc,
                "the 3-arg Sodium 0.8 form no longer exists in 0.9.1");

        // The two new values must come from the widened handler's own parameter slots
        // (9 = GpuBufferSlice, 10 = GlTexelBuffer), loaded directly before the call.
        var texels = call.getPrevious();
        var uniforms = texels.getPrevious();
        assertTrue(texels instanceof org.objectweb.asm.tree.VarInsnNode v && v.var == 10,
                "GlTexelBuffer must be loaded from slot 10 immediately before begin()");
        assertTrue(uniforms instanceof org.objectweb.asm.tree.VarInsnNode v && v.var == 9,
                "GpuBufferSlice must be loaded from slot 9 before the GlTexelBuffer load");
    }

    /**
     * The strongest check this suite has: the JVM's own bytecode verifier. A botched local-slot
     * shift or stack-map frame does not fail an ASM round-trip or an Analyzer pass (which
     * recomputes frames rather than checking the ones in the file) — it fails at class-load
     * time, in game. So this test loads the patched class for real. The classloader chain is:
     * test classpath (Voxy 0.2.16, sponge-mixin) -> the Sodium fabric jar (superclass
     * ShaderChunkRenderer) -> a defining loader holding the patched bytes.
     *
     * <p>The negative control then corrupts the patched bytes the way a wrong slot shift would
     * (the CallbackInfo load reverted from slot 11 to slot 9, which now holds a GpuBufferSlice)
     * and asserts the JVM rejects them — proof that verification actually ran, and that this
     * test cannot silently pass by skipping it.
     */
    @Test
    void patchedDefaultChunkRendererSurvivesTheJvmVerifier() throws Exception {
        byte[] patched = VoxySodiumCompatPatch.patch(readClass(CHUNK_RENDERER_MIXIN));

        String sodiumJar = System.getProperty("foxy.sodiumFabricJar");
        assertNotNull(sodiumJar, "system property foxy.sodiumFabricJar not set;"
                + " check the sodiumFabricCompileOnly wiring in build.gradle.kts");
        ClassLoader sodium = new java.net.URLClassLoader(
                new java.net.URL[] {java.nio.file.Path.of(sodiumJar).toUri().toURL()},
                getClass().getClassLoader());
        String binaryName = CHUNK_RENDERER_MIXIN.replace('/', '.');

        // Must link and verify cleanly.
        Class.forName(binaryName, true, new DefiningLoader(sodium, binaryName, patched));

        // Negative control: revert the shifted CallbackInfo load back to slot 9.
        ClassNode corrupt = parse(patched);
        MethodNode handler = findMethod(corrupt, "voxy$cancelThingie");
        boolean reverted = false;
        for (org.objectweb.asm.tree.AbstractInsnNode insn : handler.instructions) {
            if (insn instanceof org.objectweb.asm.tree.VarInsnNode v && v.var == 11) {
                v.var = 9;
                reverted = true;
            }
        }
        assertTrue(reverted, "control setup: expected a slot-11 load to corrupt");
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        corrupt.accept(writer);
        byte[] corruptBytes = writer.toByteArray();

        org.junit.jupiter.api.Assertions.assertThrows(LinkageError.class,
                () -> Class.forName(binaryName, true,
                        new DefiningLoader(sodium, binaryName, corruptBytes)),
                "the JVM verifier must reject a wrong slot; if it does not, this test is not"
                        + " actually verifying anything");
    }

    @Test
    void leavesVoxy0218DefaultChunkRendererByteIdentical() throws Exception {
        byte[] original = readClassFromVoxy0218(CHUNK_RENDERER_MIXIN);

        MethodNode handler = findMethod(parse(original), "voxy$cancelThingie");
        assertNotNull(handler, "precondition: 0.2.18 should still carry voxy$cancelThingie");
        assertTrue(!RENDER_HANDLER_SODIUM_0_8.equals(handler.desc),
                "precondition: 0.2.18's handler should not carry the Sodium 0.8 argument list");

        assertSame(original, VoxySodiumCompatPatch.patch(original),
                "0.2.18's handlers already match its Sodium; the widen must not touch them");
    }

    /** Defines exactly one class from the given bytes; everything else comes from the parent. */
    private static final class DefiningLoader extends ClassLoader {
        private final String binaryName;
        private final byte[] bytes;

        DefiningLoader(ClassLoader parent, String binaryName, byte[] bytes) {
            super(parent);
            this.binaryName = binaryName;
            this.bytes = bytes;
        }

        /**
         * Child-first for the one class under test: the ORIGINAL class is also on the test
         * classpath (inside the Voxy jar), so parent-first delegation would define that copy
         * instead of the patched bytes — and fail anyway, since the app classloader cannot see
         * the Sodium superclass.
         */
        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (binaryName.equals(name)) {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) {
                        loaded = defineClass(name, bytes, 0, bytes.length);
                    }
                    if (resolve) {
                        resolveClass(loaded);
                    }
                    return loaded;
                }
            }
            return super.loadClass(name, resolve);
        }
    }
}
