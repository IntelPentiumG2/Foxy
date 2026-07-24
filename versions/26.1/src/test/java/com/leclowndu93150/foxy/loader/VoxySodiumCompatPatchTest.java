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
}
