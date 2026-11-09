package com.leclowndu93150.foxy.compat;

import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.client.model.DynamicBlockStateModel;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Collects a block model's parts for Voxy's texture bakery, with world context attached.
 *
 * <p>Called from Voxy's bakery in place of the no-context {@code collectParts}; see
 * {@link com.leclowndu93150.foxy.loader.FoxyDynamicModelPatch} for why.
 */
public final class DynamicModelBake {
    private static final Logger LOGGER = LoggerFactory.getLogger("Foxy");

    /** One complaint per block, not one per bake; the bakery runs off render threads. */
    private static final Set<Block> WARNED = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** States already accounted for in the log, same reasoning. */
    private static final Set<BlockState> REPORTED = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** Slack for quads that sit on the block boundary itself. */
    private static final float EPSILON = 1.0E-4F;

    /** How Dynamic Trees names a branch's thickness, and where a twig becomes a limb. */
    private static final String RADIUS = "radius";
    private static final int THICK_RADIUS = 5;

    /**
     * The state the bakery is working on, published by {@link #beginBake} for the length of one
     * {@code bakeBlockModel} call. The redirected {@code collectParts} may sit in a method that no
     * longer has the state as an argument — a mixin that wraps the call moves it into one of its
     * own — so it is looked up here rather than passed along.
     */
    private static final ThreadLocal<BlockState> BAKING = new ThreadLocal<>();

    private DynamicModelBake() {}

    /** Called at the head of Voxy's {@code bakeBlockModel}; see {@code FoxyDynamicModelPatch}. */
    public static void beginBake(BlockState state) {
        BAKING.set(state);
    }

    /** Called before each return of {@code bakeBlockModel}. */
    public static void endBake() {
        BAKING.remove();
    }

    /** The replacement for every no-context {@code collectParts} call in Voxy's bakery. */
    @SuppressWarnings("deprecation") // Outside a bake there is no state to offer, so the original call stands.
    public static void collectParts(BlockStateModel model, RandomSource random,
                                    List<BlockStateModelPart> parts) {
        BlockState state = BAKING.get();
        if (state == null) {
            model.collectParts(random, parts);
            return;
        }
        collectParts(model, random, parts, state);
    }

    @SuppressWarnings("deprecation") // The fallback is the call this patch exists to replace.
    private static void collectParts(BlockStateModel model, RandomSource random,
                                     List<BlockStateModelPart> parts, BlockState state) {
        try {
            model.collectParts(new LoneBlock(state), BlockPos.ZERO, state, random, parts);
            addConnections(model, random, parts, state);
            int clamped = clampToBlock(parts);
            report(model, state, parts, clamped);
        } catch (Throwable t) {
            // A model that cannot cope with a stub level still has to bake into something, and a
            // model built for the no-context call is exactly what the unpatched bakery would have
            // asked for. Anything the failed attempt left behind goes first.
            if (WARNED.add(state.getBlock())) {
                LOGGER.warn("Foxy: {} failed to collect parts with block context; "
                        + "baking it without.", state.getBlock(), t);
            }
            parts.clear();
            model.collectParts(random, parts);
        }
    }

    /**
     * Adds the geometry a model only grows towards a neighbour, on top of the closed shape.
     *
     * <p>The lone-block pass gives a model's self-contained form, which for a Dynamic Trees branch
     * is a cube the width of the branch, floating in the middle of its block. Stack those and they
     * do not touch: a trunk becomes a column of separate cubes with air between them, because what
     * joins them in the world is the sleeve each branch grows towards its neighbour. Asking again
     * with the block stacked on itself produces those joining pieces, and merging the two gives a
     * voxel that is both solid and continuous with the ones above and below.
     *
     * <p>Sideways is offered only to the thick ones. Every join a block can make gets drawn, and a
     * Dynamic Trees twig asked about all six grows all six at once — a cross, which is what a
     * canopy full of radius-1 branches turns into. A limb is another matter: there are few of them
     * and their reach is what reads as the shape of a tree from far away.
     *
     * <p>Parts already collected are not added twice — models hand out the same part instance for
     * the same geometry, and a model that ignores its surroundings answers both passes identically.
     */
    private static void addConnections(BlockStateModel model, RandomSource random,
                                       List<BlockStateModelPart> parts, BlockState state) {
        if (!(model instanceof DynamicBlockStateModel)) {
            return; // Nothing else looks at its neighbours, so there is nothing else to add.
        }
        List<BlockStateModelPart> connected = new ArrayList<>();
        BlockAndTintGetter neighbours = isThick(state) ? new SolidNeighbours(state) : new Column(state);
        model.collectParts(neighbours, BlockPos.ZERO, state, random, connected);
        for (BlockStateModelPart part : connected) {
            if (!parts.contains(part)) {
                parts.add(part);
            }
        }
    }

    /**
     * Whether a block is heavy enough for its sideways joins to be worth drawing.
     *
     * <p>Read off a {@code radius} property, which is how Dynamic Trees carries the thickness of a
     * branch, and a threshold the width of a twig cluster. A block without one keeps to the column.
     */
    private static boolean isThick(BlockState state) {
        Property<?> property = state.getBlock().getStateDefinition().getProperty(RADIUS);
        return property instanceof IntegerProperty radius && state.getValue(radius) >= THICK_RADIUS;
    }

    /**
     * Pulls geometry that sits outside the block back onto its own faces, and reports how many
     * quads that touched.
     *
     * <p>A model may draw into the blocks around it, expecting them to draw its own volume back:
     * Dynamic Trees' thick trunks are wider than one block, so each block draws the trunk's outer
     * shell one block over and none of the geometry inside itself. Voxy bakes a block alone, from
     * six orthographic views of that block's cube, so all of that lands outside the frame and a
     * 2x2 trunk bakes into nothing but its top and bottom rings — invisible from any angle a player
     * looks from. Clamping each vertex into the cube lays those shell quads flat against the face
     * they were drawn beyond, which is where the surface belongs when the whole trunk collapses
     * into one voxel. Quads already inside the cube — every ordinary model — are left alone.
     */
    private static int clampToBlock(List<BlockStateModelPart> parts) {
        int clamped = 0;
        for (int i = 0; i < parts.size(); i++) {
            BlockStateModelPart part = parts.get(i);
            int outside = countOutside(part);
            if (outside != 0) {
                parts.set(i, new ClampedPart(part));
                clamped += outside;
            }
        }
        return clamped;
    }

    private static int countOutside(BlockStateModelPart part) {
        int outside = 0;
        for (Direction direction : DIRECTIONS) {
            for (BakedQuad quad : part.getQuads(direction)) {
                if (isOutside(quad)) {
                    outside++;
                }
            }
        }
        return outside;
    }

    /**
     * Whether the whole quad sits beyond one of the block's six faces, which is what the bakery
     * cannot see. A quad that merely pokes out — sugar cane, a moss carpet climbing its wall — has
     * geometry inside the cube that rasterises correctly, and is left as the model built it.
     */
    private static boolean isOutside(BakedQuad quad) {
        for (Axis axis : Axis.values()) {
            boolean below = true;
            boolean above = true;
            for (int vertex = 0; vertex < BakedQuad.VERTEX_COUNT; vertex++) {
                float coordinate = (float) axis.choose(
                        quad.position(vertex).x(), quad.position(vertex).y(), quad.position(vertex).z());
                below &= coordinate < -EPSILON;
                above &= coordinate > 1 + EPSILON;
            }
            if (below || above) {
                return true;
            }
        }
        return false;
    }

    /**
     * Notes what the patch actually bought, once per block state. Only models implementing
     * NeoForge's {@code DynamicBlockStateModel} were being handed air before, so those are the ones
     * worth counting: a state that shows up here with zero quads is still a hole in the LoD, and one
     * that never shows up at all was never reaching the bakery to begin with.
     */
    private static void report(BlockStateModel model, BlockState state, List<BlockStateModelPart> parts, int clamped) {
        if (!LOGGER.isDebugEnabled() || !(model instanceof DynamicBlockStateModel) || !REPORTED.add(state)) {
            return;
        }
        int quads = 0;
        for (BlockStateModelPart part : parts) {
            for (Direction direction : DIRECTIONS) {
                quads += part.getQuads(direction).size();
            }
        }
        LOGGER.debug("Foxy: baked {} with block context ({} parts, {} quads, {} clamped).",
                state, parts.size(), quads, clamped);
    }

    /** A part whose geometry is confined to the block it belongs to. */
    private record ClampedPart(BlockStateModelPart delegate) implements BlockStateModelPart {
        @Override
        public List<BakedQuad> getQuads(Direction direction) {
            List<BakedQuad> quads = delegate.getQuads(direction);
            List<BakedQuad> clamped = new ArrayList<>(quads.size());
            for (BakedQuad quad : quads) {
                clamped.add(isOutside(quad) ? clamp(quad) : quad);
            }
            return clamped;
        }

        private static BakedQuad clamp(BakedQuad quad) {
            return new BakedQuad(
                    clamp(quad.position0()), clamp(quad.position1()),
                    clamp(quad.position2()), clamp(quad.position3()),
                    quad.packedUV0(), quad.packedUV1(), quad.packedUV2(), quad.packedUV3(),
                    quad.direction(), quad.materialInfo());
        }

        private static Vector3fc clamp(Vector3fc position) {
            return new Vector3f(
                    Mth.clamp(position.x(), 0, 1),
                    Mth.clamp(position.y(), 0, 1),
                    Mth.clamp(position.z(), 0, 1));
        }

        @Override
        public boolean useAmbientOcclusion() {
            return delegate.useAmbientOcclusion();
        }

        @Override
        public Material.Baked particleMaterial() {
            return delegate.particleMaterial();
        }

        @Override
        public int materialFlags() {
            return delegate.materialFlags();
        }
    }

    /** The bakery's own quad buckets: one per face plus the unculled bucket. */
    private static final Direction[] DIRECTIONS = {
            Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, null
    };

    /**
     * The block ringed by itself, with air above and below, for the limbs wide enough that their
     * reach is worth drawing.
     *
     * <p>Sideways the block answers with itself, so a model too wide for its own block closes the
     * seams it would otherwise leave to its neighbours: a Dynamic Trees trunk of more than a
     * block's width draws its outer shell into the blocks around it, and only a neighbour it
     * recognises makes it drop the faces that meet them. Above and below it answers air, because a
     * model that believes the trunk carries on drops its end caps too, and those caps are the only
     * geometry the bakery's views from above and below have to rasterise.
     */
    private record SolidNeighbours(BlockState state) implements BlockAndTintGetter {
        @Override
        public BlockState getBlockState(BlockPos pos) {
            return pos.getY() == 0 ? state : Blocks.AIR.defaultBlockState();
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        @Nullable
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
            return -1;
        }

        @Override
        public int getBrightness(LightLayer type, BlockPos pos) {
            return 0;
        }

        @Override
        public LevelLightEngine getLightEngine() {
            return LevelLightEngine.EMPTY;
        }

        @Override
        public CardinalLighting cardinalLighting() {
            return CardinalLighting.DEFAULT;
        }

        @Override
        public int getMinY() {
            return -64;
        }

        @Override
        public int getHeight() {
            return 384;
        }
    }

    /**
     * The same block above and below, air to the sides, which draws the joins that stack.
     *
     * <p>Offering the block every neighbour draws every join it has, and a Dynamic Trees branch
     * answers with all six sleeves at once — a cross, which is what a branch looks like nowhere.
     * The join worth having is the vertical one: a trunk is a column, and a column that does not
     * reach the top of its block leaves a gap in every trunk in view. Sideways runs of branch fall
     * back to their own cubes, which reads as beading rather than as a field of crosses.
     */
    private record Column(BlockState state) implements BlockAndTintGetter {
        @Override
        public BlockState getBlockState(BlockPos pos) {
            return pos.getX() == 0 && pos.getZ() == 0 ? state : Blocks.AIR.defaultBlockState();
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        @Nullable
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
            return -1;
        }

        @Override
        public int getBrightness(LightLayer type, BlockPos pos) {
            return 0;
        }

        @Override
        public LevelLightEngine getLightEngine() {
            return LevelLightEngine.EMPTY;
        }

        @Override
        public CardinalLighting cardinalLighting() {
            return CardinalLighting.DEFAULT;
        }

        @Override
        public int getMinY() {
            return -64;
        }

        @Override
        public int getHeight() {
            return 384;
        }
    }

    /**
     * A level holding the block being baked and nothing else: air in every other direction.
     *
     * <p>Voxy keeps one baked texture per block state and connection state is not part of the
     * state, so no answer here is right for every copy of the block in the world. Air is the answer
     * that keeps a model's geometry <em>closed</em>. Reporting the block as its own neighbour
     * instead sounds better — a fully connected shape fills more of the voxel — but connection
     * geometry is open-ended by construction: Dynamic Trees' sleeves drop the face towards the core
     * and cap the far end only at radius 1, because in the world the neighbouring block covers
     * both. Voxy has no neighbour to bake, and rasterises that from six orthographic views into
     * outlines. Air keeps the core, which is a closed cube of the branch's radius.
     */
    private record LoneBlock(BlockState state) implements BlockAndTintGetter {
        @Override
        public BlockState getBlockState(BlockPos pos) {
            return BlockPos.ZERO.equals(pos) ? state : Blocks.AIR.defaultBlockState();
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        @Nullable
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
            // Voxy resolves biome tint separately, per biome, after the bake.
            return -1;
        }

        @Override
        public int getBrightness(LightLayer type, BlockPos pos) {
            return 0;
        }

        @Override
        public LevelLightEngine getLightEngine() {
            return LevelLightEngine.EMPTY;
        }

        @Override
        public CardinalLighting cardinalLighting() {
            return CardinalLighting.DEFAULT;
        }

        // An overworld-sized range, so the block and its six neighbours all count as in-world for
        // models that check before they look.
        @Override
        public int getMinY() {
            return -64;
        }

        @Override
        public int getHeight() {
            return 384;
        }
    }
}
