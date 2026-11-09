package com.leclowndu93150.foxy.loader;

import net.neoforged.neoforgespi.transformation.ProcessorName;
import net.neoforged.neoforgespi.transformation.SimpleClassProcessor;
import net.neoforged.neoforgespi.transformation.SimpleTransformationContext;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * Gives Voxy's LoD texture bakery the block context NeoForge models need.
 *
 * <p>Voxy bakes one texture per block state by collecting the model's parts through vanilla's
 * {@code BlockStateModel.collectParts(RandomSource, List)}, which carries no level, position or
 * state. NeoForge models that derive their geometry from the world implement
 * {@code DynamicBlockStateModel}, whose deprecated no-context default fills those arguments in
 * with {@code BlockAndTintGetter.EMPTY}, {@code BlockPos.ZERO} and <em>air</em>. A model that
 * reads the state it was asked about — Dynamic Trees' branches read the trunk radius out of it —
 * sees air, emits nothing, and Voxy bakes a fully transparent block: whole trunks vanish from the
 * LoD while the leaves around them, which use ordinary JSON models, stay put.
 *
 * <p>So the call is redirected to {@link com.leclowndu93150.foxy.compat.DynamicModelBake}, which
 * calls the context-carrying overload with the state Voxy is baking. Vanilla models are unaffected:
 * NeoForge's default implementation of that overload forwards straight back to the no-context one.
 *
 * <p>The call is redirected wherever it sits in the class, not only in {@code bakeBlockModel}. A mod
 * that wraps it with a mixin moves the original call out of that method: Ecliptic Seasons swaps in
 * seasonal LoD models through MixinExtras' {@code @WrapOperation}, which leaves the wrapped call in
 * a synthetic {@code mixinextras$bridge$} method, and the mixin phase runs before the processor
 * group this belongs to. A method like that has no state argument to hand on, so the state is
 * published from the head of {@code bakeBlockModel} instead and withdrawn at its returns. The
 * seasonal model's own {@code collectParts} in the merged handler is caught too, which is right:
 * it stands in for the block's model and bakes into the same texture.
 */
public class FoxyDynamicModelPatch extends SimpleClassProcessor {
    private static final Logger LOGGER = LoggerFactory.getLogger("Foxy");

    private static final String BAKERY = "me.cortex.voxy.client.core.model.bakery.SoftwareModelTextureBakery";
    private static final String BAKE_METHOD = "bakeBlockModel";
    private static final String BAKE_DESC = "(Lnet/minecraft/world/level/block/state/BlockState;)V";

    private static final String MODEL = "net/minecraft/client/renderer/block/dispatch/BlockStateModel";
    private static final String COLLECT = "collectParts";
    private static final String COLLECT_DESC = "(Lnet/minecraft/util/RandomSource;Ljava/util/List;)V";

    private static final String BRIDGE = "com/leclowndu93150/foxy/compat/DynamicModelBake";
    private static final String BRIDGE_DESC = "(L" + MODEL + ";Lnet/minecraft/util/RandomSource;Ljava/util/List;)V";
    private static final String BEGIN = "beginBake";
    private static final String BEGIN_DESC = "(Lnet/minecraft/world/level/block/state/BlockState;)V";
    private static final String END = "endBake";
    private static final String END_DESC = "()V";

    @Override
    public ProcessorName name() {
        return new ProcessorName("foxy", "dynamic_model_bake");
    }

    @Override
    public Set<Target> targets() {
        return Set.of(new Target(BAKERY));
    }

    @Override
    public void transform(ClassNode node, SimpleTransformationContext context) {
        MethodNode bake = null;
        for (MethodNode method : node.methods) {
            if (BAKE_METHOD.equals(method.name) && BAKE_DESC.equals(method.desc)) {
                bake = method;
                break;
            }
        }
        if (bake == null) {
            LOGGER.warn("Foxy: {} has no {}{}; skipping the dynamic model patch.", BAKERY, BAKE_METHOD, BAKE_DESC);
            return;
        }

        // The bakery's state argument, published for the length of the bake.
        InsnList begin = new InsnList();
        begin.add(new VarInsnNode(Opcodes.ALOAD, 1));
        begin.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, BEGIN, BEGIN_DESC, false));
        bake.instructions.insert(begin);
        for (AbstractInsnNode insn : bake.instructions.toArray()) {
            if (insn.getOpcode() == Opcodes.RETURN) {
                bake.instructions.insertBefore(insn,
                        new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, END, END_DESC, false));
            }
        }

        int patched = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (!(insn instanceof MethodInsnNode call)
                        || call.getOpcode() != Opcodes.INVOKEINTERFACE
                        || !MODEL.equals(call.owner)
                        || !COLLECT.equals(call.name)
                        || !COLLECT_DESC.equals(call.desc)) {
                    continue;
                }
                method.instructions.set(call,
                        new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, COLLECT, BRIDGE_DESC, false));
                patched++;
            }
        }
        if (patched == 0) {
            LOGGER.warn("Foxy: {} no longer calls the no-context collectParts anywhere; leaving it alone. "
                    + "Blocks with world-dependent models may render as holes in the LoD.", BAKERY);
        }
    }
}
