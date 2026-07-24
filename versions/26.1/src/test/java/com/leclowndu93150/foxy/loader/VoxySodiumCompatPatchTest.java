package com.leclowndu93150.foxy.loader;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.ClassReader;

import java.io.InputStream;
import java.util.List;

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
}
