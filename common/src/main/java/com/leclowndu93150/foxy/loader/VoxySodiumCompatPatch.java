package com.leclowndu93150.foxy.loader;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Adapts Voxy 0.2.16's Sodium mixins to Sodium 0.9.
 *
 * <p>Voxy 0.2.16 is the newest build for Minecraft 26.1.2 and targets Sodium 0.8.x. Two of its
 * Sodium mixins name method descriptors that 0.9 changed:
 *
 * <ul>
 *   <li>{@code RenderRegionManager.uploadResults} gained a {@code UniformBufferManager}
 *       parameter. The redirect only wraps {@code Math.toIntExact} and is semantically inert,
 *       so widening the descriptor is enough.
 *   <li>{@code RenderSection.setInfo} changed its return from {@code boolean} to an {@code int}
 *       bitmask. Voxy's handler never returns the value it received, so it cannot simply be
 *       re-typed — the annotation is dropped here and Foxy's own redirect, which preserves the
 *       bitmask, takes over. See {@code FoxyMixinRenderSectionManager}.
 * </ul>
 *
 * <p>Every rewrite is keyed to a descriptor that only Voxy 0.2.16 contains. A jar without them —
 * 0.2.18 on 26.2, or any future build — comes back byte-identical, so this stays inert rather
 * than corrupting a Voxy that no longer needs it.
 */
public final class VoxySodiumCompatPatch {

    private static final String REDIRECT = "Lorg/spongepowered/asm/mixin/injection/Redirect;";

    private static final String REGION_MANAGER_MIXIN =
            "me/cortex/voxy/client/mixin/sodium/MixinRenderRegionManager";
    private static final String SECTION_MANAGER_MIXIN =
            "me/cortex/voxy/client/mixin/sodium/MixinRenderSectionManager";

    private static final String UPDATE_ON_UPLOAD = "voxy$updateOnUpload";

    private static final String UPLOAD_RESULTS_SODIUM_0_8 =
            "uploadResults(Lnet/caffeinemc/mods/sodium/client/gl/device/CommandList;"
                    + "Lnet/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegion;"
                    + "Ljava/util/Collection;)V";

    private static final String UPLOAD_RESULTS_SODIUM_0_9 =
            "uploadResults(Lnet/caffeinemc/mods/sodium/client/gl/device/CommandList;"
                    + "Lnet/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegion;"
                    + "Ljava/util/Collection;"
                    + "Lnet/caffeinemc/mods/sodium/client/render/chunk/UniformBufferManager;)V";

    private VoxySodiumCompatPatch() {}

    /**
     * @return the rewritten class, or {@code clazz} itself when nothing matched
     */
    public static byte[] patch(byte[] clazz) {
        ClassReader reader = new ClassReader(clazz);
        String name = reader.getClassName();

        boolean isRegionManager = REGION_MANAGER_MIXIN.equals(name);
        boolean isSectionManager = SECTION_MANAGER_MIXIN.equals(name);
        if (!isRegionManager && !isSectionManager) {
            return clazz;
        }

        ClassWriter writer = new ClassWriter(0);
        Rewriter rewriter = new Rewriter(writer, isRegionManager, isSectionManager);
        reader.accept(rewriter, 0);
        return rewriter.changed ? writer.toByteArray() : clazz;
    }

    private static final class Rewriter extends ClassVisitor {
        private final boolean widenUploadResults;
        private final boolean dropUpdateOnUploadRedirect;
        boolean changed;

        Rewriter(ClassVisitor delegate, boolean widenUploadResults, boolean dropUpdateOnUploadRedirect) {
            super(Opcodes.ASM9, delegate);
            this.widenUploadResults = widenUploadResults;
            this.dropUpdateOnUploadRedirect = dropUpdateOnUploadRedirect;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
            boolean dropRedirect = dropUpdateOnUploadRedirect && UPDATE_ON_UPLOAD.equals(name);
            return new MethodRewriter(delegate, widenUploadResults, dropRedirect);
        }

        private final class MethodRewriter extends MethodVisitor {
            private final boolean widen;
            private final boolean dropRedirect;

            MethodRewriter(MethodVisitor delegate, boolean widen, boolean dropRedirect) {
                super(Opcodes.ASM9, delegate);
                this.widen = widen;
                this.dropRedirect = dropRedirect;
            }

            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (REDIRECT.equals(descriptor) && dropRedirect) {
                    changed = true;
                    return null;
                }
                AnnotationVisitor delegate = super.visitAnnotation(descriptor, visible);
                if (REDIRECT.equals(descriptor) && widen && delegate != null) {
                    return new DescriptorWidener(delegate);
                }
                return delegate;
            }
        }

        /** Replaces the 0.8 {@code uploadResults} descriptor wherever it appears as a value. */
        private final class DescriptorWidener extends AnnotationVisitor {
            DescriptorWidener(AnnotationVisitor delegate) {
                super(Opcodes.ASM9, delegate);
            }

            @Override
            public void visit(String name, Object value) {
                if (UPLOAD_RESULTS_SODIUM_0_8.equals(value)) {
                    changed = true;
                    super.visit(name, UPLOAD_RESULTS_SODIUM_0_9);
                } else {
                    super.visit(name, value);
                }
            }

            @Override
            public AnnotationVisitor visitArray(String name) {
                AnnotationVisitor delegate = super.visitArray(name);
                return delegate == null ? null : new DescriptorWidener(delegate);
            }

            @Override
            public AnnotationVisitor visitAnnotation(String name, String descriptor) {
                AnnotationVisitor delegate = super.visitAnnotation(name, descriptor);
                return delegate == null ? null : new DescriptorWidener(delegate);
            }
        }
    }
}
