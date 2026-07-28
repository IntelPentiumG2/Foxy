package com.leclowndu93150.foxy.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Optional;

/**
 * Replaces Dynamic Trees' trunk shells with the trunk they belong to, as Voxy ingests a section.
 *
 * <p>A Dynamic Trees trunk wider than one block is a single branch block surrounded by
 * {@code dynamictrees:trunk_shell}. The shell is {@code RenderShape.INVISIBLE} — the branch draws
 * the whole trunk's surface into the shells' space — and its state carries only which way the
 * trunk's core lies, no tree family. Voxy stores the right state for every voxel, but it bakes a
 * texture per state with no world around it, so a shell can never be drawn: the state that says
 * "jungle" and the state that says "birch" are the same state. Every thick trunk ends up one block
 * wide in the LoD.
 *
 * <p>The world is still there at ingest, so that is where the shells can be resolved. Each one
 * names the direction of its core, which is always horizontal, so following it — through further
 * shells, for trunks thicker than three blocks — reaches the branch state that should be drawn in
 * its place. Sections whose palette holds no shell, which is nearly all of them, are handed back
 * untouched.
 *
 * <p>The one case left alone is a shell whose core lies in the neighbouring chunk section, which
 * this cannot see: a sliver of trunk on a section boundary stays as it is today.
 */
public final class TrunkShellIngest {
    private static final Identifier TRUNK_SHELL = Identifier.fromNamespaceAndPath("dynamictrees", "trunk_shell");

    /** Dynamic Trees' {@code CoordUtils.Surround}, which names the eight horizontal neighbours. */
    private static final String CORE_DIR = "coredir";

    /** How wide the trunk's own block is, in texels, either side of its middle. */
    private static final String RADIUS = "radius";
    private static final int CORE_HALF_WIDTH = 8;
    private static final int HALF_BLOCK = 8;

    /** A trunk is at most three blocks across, so one hop is enough; two is slack. */
    private static final int MAX_HOPS = 2;

    private static final int SIZE = 16;

    private static volatile boolean resolved;
    private static Block shellBlock;

    private TrunkShellIngest() {}

    public static PalettedContainer<BlockState> substitute(PalettedContainer<BlockState> states) {
        Block shell = shellBlock();
        if (shell == null || !containsShell(states, shell)) {
            return states;
        }

        PalettedContainer<BlockState> substituted = states.copy();
        for (int y = 0; y < SIZE; y++) {
            for (int z = 0; z < SIZE; z++) {
                for (int x = 0; x < SIZE; x++) {
                    // Read the core out of the original: a shell chain has to resolve against the
                    // states it was built from, not against ones already replaced.
                    if (states.get(x, y, z).getBlock() != shell) {
                        continue;
                    }
                    Core core = resolveCore(states, shell, x, y, z);
                    if (core != null && fillsMostOfBlock(core)) {
                        substituted.set(x, y, z, waterlogLike(core.state(), states.get(x, y, z)));
                    }
                }
            }
        }
        return substituted;
    }

    /** The trunk a shell belongs to, and how far the shell stands from it. */
    private record Core(BlockState state, int offsetX, int offsetZ) {}

    @Nullable
    private static Core resolveCore(PalettedContainer<BlockState> states, Block shell, int x, int y, int z) {
        BlockState state = states.get(x, y, z);
        int walkedX = 0;
        int walkedZ = 0;
        for (int hop = 0; hop <= MAX_HOPS; hop++) {
            BlockPos offset = coreOffset(state);
            if (offset == null) {
                return null;
            }
            x += offset.getX();
            z += offset.getZ();
            walkedX += offset.getX();
            walkedZ += offset.getZ();
            if (x < 0 || x >= SIZE || z < 0 || z >= SIZE) {
                return null; // The core is in the next section along; leave this one alone.
            }
            state = states.get(x, y, z);
            if (state.getBlock() != shell) {
                return state.isAir() ? null : new Core(state, -walkedX, -walkedZ);
            }
        }
        return null;
    }

    /**
     * Whether the trunk fills enough of the shell's block to be drawn as one.
     *
     * <p>The LoD has one appearance per block, so a shell either becomes trunk or stays out of the
     * way — there is no half. A trunk of radius r reaches r texels from the middle of its core, so
     * a block one step away holds {@code r - 8} texels of it: everything for the widest trunks, a
     * single texel for the narrowest. Claiming the whole block for that one texel is what made a
     * trunk barely wider than a block render three blocks thick, so the block has to be at least
     * half trunk to count.
     */
    private static boolean fillsMostOfBlock(Core core) {
        int radius = radiusOf(core.state());
        if (radius <= 0) {
            return false;
        }
        return covers(core.offsetX(), radius) && covers(core.offsetZ(), radius);
    }

    private static boolean covers(int offset, int radius) {
        if (offset == 0) {
            return true; // The core's own column, filled all the way across.
        }
        int reach = radius - CORE_HALF_WIDTH * (2 * Math.abs(offset) - 1);
        return reach >= HALF_BLOCK;
    }

    private static int radiusOf(BlockState state) {
        Property<?> property = state.getBlock().getStateDefinition().getProperty(RADIUS);
        if (!(property instanceof IntegerProperty radius)) {
            return 0;
        }
        return state.getValue(radius);
    }

    /** Keeps a waterlogged shell wet, when whatever replaces it can be. */
    private static BlockState waterlogLike(BlockState core, BlockState shell) {
        if (!shell.hasProperty(BlockStateProperties.WATERLOGGED)
                || !core.hasProperty(BlockStateProperties.WATERLOGGED)
                || !shell.getValue(BlockStateProperties.WATERLOGGED)) {
            return core;
        }
        return core.setValue(BlockStateProperties.WATERLOGGED, true);
    }

    @Nullable
    private static BlockPos coreOffset(BlockState state) {
        Property<?> property = state.getBlock().getStateDefinition().getProperty(CORE_DIR);
        if (property == null) {
            return null;
        }
        return offsetOf(state.getValue(property).toString().toLowerCase(Locale.ROOT));
    }

    /**
     * Turns a {@code Surround} name — {@code n}, {@code ne}, {@code e} and so on — into the step
     * towards the core. Read off the name rather than the enum so this stays free of a compile-time
     * dependency on Dynamic Trees.
     */
    @Nullable
    private static BlockPos offsetOf(String name) {
        BlockPos offset = BlockPos.ZERO;
        for (int i = 0; i < name.length(); i++) {
            offset = switch (name.charAt(i)) {
                case 'n' -> offset.relative(Direction.NORTH);
                case 's' -> offset.relative(Direction.SOUTH);
                case 'e' -> offset.relative(Direction.EAST);
                case 'w' -> offset.relative(Direction.WEST);
                default -> null;
            };
            if (offset == null) {
                return null;
            }
        }
        return offset.equals(BlockPos.ZERO) ? null : offset;
    }

    /** Cheap enough to run on every section: the palette holds the section's distinct states. */
    private static boolean containsShell(PalettedContainer<BlockState> states, Block shell) {
        boolean[] found = new boolean[1];
        states.getAll(state -> found[0] |= state.getBlock() == shell);
        return found[0];
    }

    @Nullable
    private static Block shellBlock() {
        if (!resolved) {
            Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(TRUNK_SHELL);
            shellBlock = block.orElse(null);
            resolved = true;
        }
        return shellBlock;
    }
}
