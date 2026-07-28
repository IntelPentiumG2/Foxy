package com.leclowndu93150.foxy.loader;

import net.neoforged.neoforgespi.transformation.ProcessorName;
import net.neoforged.neoforgespi.transformation.SimpleClassProcessor;
import net.neoforged.neoforgespi.transformation.SimpleTransformationContext;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * Runs a section's block states past {@link com.leclowndu93150.foxy.compat.TrunkShellIngest} on
 * their way into Voxy's voxelizer.
 *
 * <p>Patching the conversion itself rather than one of its callers covers every route into the LoD
 * — the client's own ingest, {@code rawIngest}, and whatever a world-gen mod drives — with one
 * substitution, and the container Foxy hands back is a copy, so nothing touches the live section.
 */
public class FoxyIngestPatch extends SimpleClassProcessor {
    private static final Logger LOGGER = LoggerFactory.getLogger("Foxy");

    private static final String FACTORY = "me.cortex.voxy.common.voxelization.WorldConversionFactory";
    private static final String CONVERT = "convert";
    private static final String CONVERT_DESC =
            "(Lme/cortex/voxy/common/voxelization/VoxelizedSection;"
                    + "Lme/cortex/voxy/common/world/other/Mapper;"
                    + "Lnet/minecraft/world/level/chunk/PalettedContainer;"
                    + "Lnet/minecraft/world/level/chunk/PalettedContainerRO;"
                    + "Lme/cortex/voxy/common/voxelization/ILightingSupplier;ZJ)"
                    + "Lme/cortex/voxy/common/voxelization/VoxelizedSection;";

    /** The block state container, third of the static method's arguments. */
    private static final int CONTAINER_SLOT = 2;

    private static final String BRIDGE = "com/leclowndu93150/foxy/compat/TrunkShellIngest";
    private static final String SUBSTITUTE = "substitute";
    private static final String SUBSTITUTE_DESC =
            "(Lnet/minecraft/world/level/chunk/PalettedContainer;)Lnet/minecraft/world/level/chunk/PalettedContainer;";

    @Override
    public ProcessorName name() {
        return new ProcessorName("foxy", "trunk_shell_ingest");
    }

    @Override
    public Set<Target> targets() {
        return Set.of(new Target(FACTORY));
    }

    @Override
    public void transform(ClassNode node, SimpleTransformationContext context) {
        for (MethodNode method : node.methods) {
            if (!CONVERT.equals(method.name) || !CONVERT_DESC.equals(method.desc)) {
                continue;
            }
            InsnList substitution = new InsnList();
            substitution.add(new VarInsnNode(Opcodes.ALOAD, CONTAINER_SLOT));
            substitution.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, BRIDGE, SUBSTITUTE, SUBSTITUTE_DESC, false));
            substitution.add(new VarInsnNode(Opcodes.ASTORE, CONTAINER_SLOT));
            method.instructions.insert(substitution);
            return;
        }
        LOGGER.warn("Foxy: {} has no {}{}; thick Dynamic Trees trunks will stay one block wide in the LoD.",
                FACTORY, CONVERT, CONVERT_DESC);
    }
}
