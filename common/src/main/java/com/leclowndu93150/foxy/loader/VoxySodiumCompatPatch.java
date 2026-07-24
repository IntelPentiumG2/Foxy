package com.leclowndu93150.foxy.loader;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Adapts Voxy 0.2.16's Sodium mixins to Sodium 0.9.
 *
 * <p>Voxy 0.2.16 is the newest build for Minecraft 26.1.2 and targets Sodium 0.8.x. Three of its
 * Sodium mixins name signatures that 0.9 changed:
 *
 * <ul>
 *   <li>{@code RenderRegionManager.uploadResults} gained a {@code UniformBufferManager}
 *       parameter. The redirect only wraps {@code Math.toIntExact} and is semantically inert,
 *       so widening the annotation's descriptor is enough.
 *   <li>{@code RenderSection.setInfo} changed its return from {@code boolean} to an {@code int}
 *       bitmask. Voxy's handler never returns the value it received, so it cannot simply be
 *       re-typed — the annotation is dropped here and Foxy's own redirect, which preserves the
 *       bitmask, takes over. See {@code FoxyMixinRenderSectionManager}.
 *   <li>{@code MixinDefaultChunkRenderer}'s two {@code @Inject} handlers into
 *       {@code DefaultChunkRenderer.render} capture Sodium 0.8's argument list, and sponge-mixin
 *       rejects handlers that capture a strict prefix of the target's arguments — with the
 *       config marked {@code required}, Voxy would not load at all. Both handler descriptors
 *       are widened to 0.9's list (adding {@code GpuBufferSlice} and {@code GlTexelBuffer}
 *       before {@code CallbackInfo}), local slots above the insertion point are shifted, and
 *       the 3-argument {@code ShaderChunkRenderer.begin} call inside {@code voxy$cancelThingie}
 *       is rewritten to 0.9's 5-argument form, fed from the two newly captured parameters.
 *       This cannot be done from a Foxy mixin: Mixin renames merged injector handlers
 *       ({@code handler$<uid>$voxy$cancelThingie}), so no selector can target the call site —
 *       but a bytecode rewrite before Mixin ever sees the class needs no selector.
 * </ul>
 *
 * <p>Every rewrite is keyed to a full signature that only Voxy 0.2.16 contains — the strip is
 * gated on {@code voxy$updateOnUpload}'s {@code (...)Z} return, and the handler widening on the
 * exact Sodium 0.8 parameter list. Voxy 0.2.18 keeps all of these method names but carries
 * Sodium-0.9-shaped signatures already (and, on 26.2, a different 0.9 variant again), so a jar
 * without the 0.8 signatures comes back byte-identical: the pass stays inert rather than
 * corrupting a Voxy that no longer needs it.
 */
public final class VoxySodiumCompatPatch {

    private static final String REDIRECT = "Lorg/spongepowered/asm/mixin/injection/Redirect;";

    private static final String REGION_MANAGER_MIXIN =
            "me/cortex/voxy/client/mixin/sodium/MixinRenderRegionManager";
    private static final String SECTION_MANAGER_MIXIN =
            "me/cortex/voxy/client/mixin/sodium/MixinRenderSectionManager";
    private static final String CHUNK_RENDERER_MIXIN =
            "me/cortex/voxy/client/mixin/sodium/MixinDefaultChunkRenderer";

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

    private static final String GPU_BUFFER_SLICE = "com/mojang/blaze3d/buffers/GpuBufferSlice";
    private static final String GL_TEXEL_BUFFER =
            "net/caffeinemc/mods/sodium/client/gl/buffer/GlTexelBuffer";

    /** The handler list widened to Sodium 0.9.1's: the two new types slot in before CallbackInfo. */
    private static final String RENDER_HANDLER_SODIUM_0_9 =
            RENDER_HANDLER_SODIUM_0_8.replace(
                    "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;",
                    "L" + GPU_BUFFER_SLICE + ";L" + GL_TEXEL_BUFFER
                            + ";Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;");

    private static final String SHADER_CHUNK_RENDERER =
            "net/caffeinemc/mods/sodium/client/render/chunk/ShaderChunkRenderer";

    private static final String BEGIN_SODIUM_0_8 =
            "(Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;"
                    + "Lnet/caffeinemc/mods/sodium/client/util/FogParameters;"
                    + "Lcom/mojang/blaze3d/textures/GpuSampler;)V";

    private static final String BEGIN_SODIUM_0_9 =
            BEGIN_SODIUM_0_8.substring(0, BEGIN_SODIUM_0_8.length() - 2)
                    + "L" + GPU_BUFFER_SLICE + ";L" + GL_TEXEL_BUFFER + ";)V";

    /**
     * In the 0.8 handler shape, {@code this} is slot 0 and the eight arguments (all size-1)
     * occupy slots 1-8, putting {@code CallbackInfo} at slot 9 — which is where the two new
     * parameters go, shifting it and everything above it up by two.
     */
    private static final int FIRST_INSERTED_SLOT = 9;

    private VoxySodiumCompatPatch() {}

    /**
     * @return the rewritten class, or {@code clazz} itself when nothing matched
     */
    public static byte[] patch(byte[] clazz) {
        ClassReader reader = new ClassReader(clazz);
        String name = reader.getClassName();

        if (CHUNK_RENDERER_MIXIN.equals(name)) {
            return patchChunkRendererMixin(clazz, reader);
        }

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

    /**
     * Widens {@code voxy$cancelThingie} and {@code voxy$injectRender} from Sodium 0.8's captured
     * argument list to 0.9's, and feeds the widened {@code begin} call from the two new
     * parameters. Tree-based, unlike the annotation-only rewrites above, because it edits
     * instructions and local slots, not just annotation strings.
     *
     * <p>Frames are read expanded so each {@code FrameNode} carries the full locals list; the
     * two new parameter types are inserted at the same position they occupy in the descriptor,
     * keeping every frame consistent with the shifted slots. {@code ClassWriter(0)} then writes
     * them back (recompressing expanded frames) without ever computing frames itself —
     * {@code COMPUTE_FRAMES} would call {@code getCommonSuperClass}, which loads classes, and at
     * FML discovery time the classes involved are not loadable.
     */
    private static byte[] patchChunkRendererMixin(byte[] clazz, ClassReader reader) {
        ClassNode node = new ClassNode();
        reader.accept(node, ClassReader.EXPAND_FRAMES);

        boolean changed = false;
        for (MethodNode method : node.methods) {
            if (RENDER_HANDLER_SODIUM_0_8.equals(method.desc)
                    && (method.name.equals("voxy$cancelThingie")
                            || method.name.equals("voxy$injectRender"))) {
                widenRenderHandler(method);
                changed = true;
            }
        }
        if (!changed) {
            return clazz;
        }

        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void widenRenderHandler(MethodNode method) {
        method.desc = RENDER_HANDLER_SODIUM_0_9;

        // Shift every use of a local at or above the insertion point up by two. Done before the
        // new loads are inserted, so they cannot themselves be shifted.
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof VarInsnNode var && var.var >= FIRST_INSERTED_SLOT) {
                var.var += 2;
            } else if (insn instanceof IincInsnNode inc && inc.var >= FIRST_INSERTED_SLOT) {
                inc.var += 2;
            }
        }

        // Frames arrive expanded (F_NEW): each locals list is absolute, so the two new types are
        // inserted at the same index they hold in the descriptor. Frames describing fewer locals
        // than the insertion point never mention a shifted slot and need no change. All locals
        // below the insertion point are size-1 in the gated descriptor, so list index equals
        // slot index there.
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof FrameNode frame
                    && frame.local != null && frame.local.size() > FIRST_INSERTED_SLOT) {
                frame.local.add(FIRST_INSERTED_SLOT, GL_TEXEL_BUFFER);
                frame.local.add(FIRST_INSERTED_SLOT, GPU_BUFFER_SLICE);
            }
        }

        // The debug table mirrors the slot shift; entries for the new parameters are not added
        // (they are debug-only, and mixin captures nothing here — LocalCapture.NO_CAPTURE).
        if (method.localVariables != null) {
            for (LocalVariableNode local : method.localVariables) {
                if (local.index >= FIRST_INSERTED_SLOT) {
                    local.index += 2;
                }
            }
        }

        // The 0.8 begin call becomes 0.9's, fed from the two newly captured parameters, which
        // sit exactly in the inserted slots.
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESPECIAL
                    && SHADER_CHUNK_RENDERER.equals(call.owner)
                    && call.name.equals("begin")
                    && BEGIN_SODIUM_0_8.equals(call.desc)) {
                method.instructions.insertBefore(call,
                        new VarInsnNode(Opcodes.ALOAD, FIRST_INSERTED_SLOT));
                method.instructions.insertBefore(call,
                        new VarInsnNode(Opcodes.ALOAD, FIRST_INSERTED_SLOT + 1));
                call.desc = BEGIN_SODIUM_0_9;
            }
        }

        method.maxLocals += 2;
        method.maxStack += 2;
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
            // Gated on the 0.8 (...)Z return, not just the name: Voxy 0.2.18 keeps
            // voxy$updateOnUpload but its handler already returns (...)I for Sodium 0.9, and its
            // @Redirect is already correct. Stripping on name alone would delete that working hook.
            boolean dropRedirect = dropUpdateOnUploadRedirect
                    && UPDATE_ON_UPLOAD.equals(name)
                    && descriptor.endsWith(")Z");
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
