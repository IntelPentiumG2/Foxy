package com.leclowndu93150.foxy.mixin;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Voxy SeedGen builds mod structures in a stand-in world on its own threads, but hands the real
 * level to anything that asks for one. A mod that then reads the world through it — Guard
 * Villagers picks a guard's variant from the biome it spawns in — sends the chunk request to the
 * server thread and waits. That generates real chunks for a LoD, and once the server has stopped
 * nothing answers: the worker never returns, Voxy never sees its world released, and leaving the
 * world hangs. Answering "not loaded" keeps the read off the server; callers that can do without
 * a chunk fall back to the generator, and the ones that cannot fail where SeedGen catches it.
 */
@Mixin(ServerChunkCache.class)
public class MixinServerChunkCache {
    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;", at = @At("HEAD"), cancellable = true)
    private void foxy$keepSeedGenOffTheServer(int x, int z, ChunkStatus status, boolean create, CallbackInfoReturnable<ChunkAccess> cir) {
        if (Thread.currentThread().getName().startsWith("VoxySeedGen worker")) {
            cir.setReturnValue(null);
        }
    }
}
