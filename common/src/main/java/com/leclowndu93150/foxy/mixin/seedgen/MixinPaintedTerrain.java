package com.leclowndu93150.foxy.mixin.seedgen;

import com.leclowndu93150.foxy.compat.SeedGenSoil;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lays the biome's surface over the stone of Voxy SeedGen's painting ground; see {@link SeedGenSoil}.
 * One terrain serves one painted chunk on one thread, so the column cache needs no locking.
 */
@Mixin(targets = "mobius.voxyseedgen.terrain.PaintedChunks$Terrain", remap = false)
public abstract class MixinPaintedTerrain {
    @Shadow @Final private int seaLevel;
    @Shadow @Final private boolean floating;

    @Shadow
    private int groundAt(int x, int z) {
        throw new AssertionError();
    }

    @Unique
    private static final BlockState[] foxy$NO_SOIL = new BlockState[0];

    @Unique
    private final Long2ObjectOpenHashMap<BlockState[]> foxy$soil = new Long2ObjectOpenHashMap<>();

    @Inject(method = "at(III)Lnet/minecraft/world/level/block/state/BlockState;", at = @At("RETURN"), cancellable = true)
    private void foxy$surfaceSoil(int x, int y, int z, CallbackInfoReturnable<BlockState> cir) {
        if (this.floating) {
            return; // The End's islands have no surface rules worth reading.
        }
        int ground = this.groundAt(x, z);
        int depth = ground - y;
        if (depth < 0 || depth >= SeedGenSoil.DEPTH) {
            return;
        }
        BlockState[] column = this.foxy$soil.get(BlockPos.asLong(x, 0, z));
        if (column == null) {
            column = SeedGenSoil.column(this, x, z, ground, this.seaLevel);
            if (column == null) {
                column = foxy$NO_SOIL;
            }
            this.foxy$soil.put(BlockPos.asLong(x, 0, z), column);
        }
        if (depth < column.length && column[depth] != null) {
            cir.setReturnValue(column[depth]);
        }
    }
}
