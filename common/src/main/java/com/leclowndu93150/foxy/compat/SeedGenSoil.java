package com.leclowndu93150.foxy.compat;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;

/**
 * The ground Voxy SeedGen's painted features stand on, as the biome would have it.
 *
 * <p>SeedGen grows mod features into a stand-in world whose ground is stone all the way up, because
 * the blocks it paints are the only ones it keeps: the terrain itself is drawn elsewhere. A feature
 * that looks at the ground before placing anything therefore always sees stone. Dynamic Trees does
 * exactly that — every species names the soils it may root in, and stone is none of them — so every
 * tree spot in the LoD came back {@code FAIL_SOIL} and no tree was ever drawn.
 *
 * <p>SeedGen already decides each biome's surface to colour its own terrain, from the biome's surface
 * rules or, for the biomes it knows by heart, from a fixed table. The top few blocks of a column are
 * answered the same way, so a feature stands on the grass, snow or sand the LoD shows. Below that,
 * and wherever SeedGen has no answer either, the ground stays stone.
 *
 * <p>SeedGen is reached by reflection so Foxy builds without it. If its shape changes, this logs
 * once and leaves the stand-in as it was.
 */
public final class SeedGenSoil {
    private static final Logger LOGGER = LoggerFactory.getLogger("Foxy");

    /** How many blocks below the top are answered from the surface rules: grass and the dirt under it. */
    public static final int DEPTH = 4;

    private static volatile boolean resolved;
    private static Field sampler;
    private static MethodHandle biome;
    private static MethodHandle blockAt;
    private static MethodHandle newPalette;
    private static MethodHandle surfaceFor;
    private static MethodHandle ruleOf;
    private static MethodHandle surfaceAt;
    private static MethodHandle fillerAt;

    /** SeedGen's own per-biome surface table; it caches per instance without locking, so one per worker. */
    private static final ThreadLocal<Object> PALETTES = new ThreadLocal<>();

    private SeedGenSoil() {}

    /**
     * The surface blocks of one column, top first, or {@code null} when the biome's rules give none.
     * An entry is {@code null} where the rules have no answer at that depth.
     */
    @Nullable
    public static BlockState[] column(Object terrain, int x, int z, int ground, int seaLevel) {
        if (!resolve(terrain)) {
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            // Picked the way SeedGen's own terrain picks it (OverworldColumns.coverFor): the biome is read
            // at the surface or the sea, whichever is higher, and its palette entry either carries the
            // biome's surface rules or, for the biomes SeedGen knows by heart, fixed top and filler blocks.
            Holder<Biome> holder = (Holder<Biome>) biome.invoke(sampler.get(terrain), x, Math.max(ground, seaLevel), z);
            if (holder == null) {
                return null;
            }
            Object palette = PALETTES.get();
            if (palette == null) {
                palette = newPalette.invoke();
                PALETTES.set(palette);
            }
            Object surface = surfaceFor.invoke(palette, holder);
            if (surface == null) {
                return null;
            }
            Object rule = ruleOf.invoke(surface);
            BlockState[] blocks = new BlockState[DEPTH];
            if (rule == null) {
                blocks[0] = (BlockState) surfaceAt.invoke(surface, ground);
                for (int depth = 1; depth < DEPTH; depth++) {
                    blocks[depth] = (BlockState) fillerAt.invoke(surface, ground);
                }
                return blocks;
            }
            // The rules ask how deep below the surface a block is, and how high water stands over it.
            int water = ground + 1 < seaLevel ? seaLevel : Integer.MIN_VALUE;
            for (int depth = 0; depth < DEPTH; depth++) {
                blocks[depth] = (BlockState) blockAt.invoke(rule, holder, x, ground - depth, z, depth + 1, 256, water);
            }
            return blocks;
        } catch (Throwable t) {
            disable(t);
            return null;
        }
    }

    private static boolean resolve(Object terrain) {
        if (resolved) {
            return biome != null;
        }
        synchronized (SeedGenSoil.class) {
            if (!resolved) {
                try {
                    MethodHandles.Lookup lookup = MethodHandles.lookup();
                    Field field = terrain.getClass().getDeclaredField("sampler");
                    field.setAccessible(true);
                    ClassLoader loader = terrain.getClass().getClassLoader();
                    Class<?> rule = Class.forName("mobius.voxyseedgen.terrain.BiomeSurfaces$Rule", false, loader);
                    Class<?> palette = Class.forName("mobius.voxyseedgen.terrain.SurfaceBlocks", false, loader);
                    Class<?> surface = Class.forName("mobius.voxyseedgen.terrain.SurfaceBlocks$Surface", false, loader);
                    sampler = field;
                    biome = lookup.findVirtual(field.getType(), "biome",
                            MethodType.methodType(Holder.class, int.class, int.class, int.class));
                    newPalette = lookup.findConstructor(palette, MethodType.methodType(void.class));
                    surfaceFor = lookup.findVirtual(palette, "surfaceFor", MethodType.methodType(surface, Holder.class));
                    ruleOf = lookup.findVirtual(surface, "rule", MethodType.methodType(rule));
                    surfaceAt = lookup.findVirtual(surface, "surfaceAt", MethodType.methodType(BlockState.class, int.class));
                    fillerAt = lookup.findVirtual(surface, "fillerAt", MethodType.methodType(BlockState.class, int.class));
                    blockAt = lookup.findVirtual(rule, "blockAt", MethodType.methodType(BlockState.class,
                            Holder.class, int.class, int.class, int.class, int.class, int.class, int.class));
                } catch (ReflectiveOperationException | RuntimeException e) {
                    biome = null;
                    LOGGER.warn("Foxy: Voxy SeedGen's terrain no longer looks as expected; "
                            + "painted features keep standing on stone.", e);
                }
                resolved = true;
            }
        }
        return biome != null;
    }

    private static void disable(Throwable t) {
        synchronized (SeedGenSoil.class) {
            if (biome != null) {
                biome = null;
                LOGGER.warn("Foxy: could not read a biome's surface for Voxy SeedGen; "
                        + "painted features keep standing on stone.", t);
            }
        }
    }
}
