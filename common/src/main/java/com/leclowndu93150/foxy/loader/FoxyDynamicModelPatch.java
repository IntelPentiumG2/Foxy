package com.leclowndu93150.foxy.loader;

import net.neoforged.neoforgespi.transformation.ProcessorName;
import net.neoforged.neoforgespi.transformation.SimpleClassProcessor;
import net.neoforged.neoforgespi.transformation.SimpleTransformationContext;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
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
    private static final String BRIDGE_DESC =
            "(L" + MODEL + ";Lnet/minecraft/util/RandomSource;Ljava/util/List;"
                    + "Lnet/minecraft/world/level/block/state/BlockState;)V";

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
        for (MethodNode method : node.methods) {
            if (!BAKE_METHOD.equals(method.name) || !BAKE_DESC.equals(method.desc)) {
                continue;
            }
            int patched = 0;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (!(insn instanceof MethodInsnNode call)
                        || call.getOpcode() != Opcodes.INVOKEINTERFACE
                        || !MODEL.equals(call.owner)
                        || !COLLECT.equals(call.name)
                        || !COLLECT_DESC.equals(call.desc)) {
                    continue;
                }
                // The bakery's own state argument, appended to the call so the bridge can pass it on.
                method.instructions.insertBefore(call, new VarInsnNode(Opcodes.ALOAD, 1));
                method.instructions.set(call,
                        new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, COLLECT, BRIDGE_DESC, false));
                patched++;
            }
            if (patched == 0) {
                LOGGER.warn("Foxy: {}.{} no longer calls the no-context collectParts; leaving it alone. "
                        + "Blocks with world-dependent models may render as holes in the LoD.",
                        BAKERY, BAKE_METHOD);
            }
            return;
        }
        LOGGER.warn("Foxy: {} has no {}{}; skipping the dynamic model patch.", BAKERY, BAKE_METHOD, BAKE_DESC);
    }
}
