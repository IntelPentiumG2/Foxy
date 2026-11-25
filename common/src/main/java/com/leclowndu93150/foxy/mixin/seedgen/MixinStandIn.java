package com.leclowndu93150.foxy.mixin.seedgen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Method;
import java.util.Set;

/**
 * Tells features in Voxy SeedGen's stand-in world that every chunk is there.
 *
 * <p>The stand-in answers block reads anywhere, but not {@code getChunkSource()}, which it hands back
 * as {@code null}. {@code LevelReader}'s chunk-presence checks are default methods built on it, so a
 * feature that asks whether a chunk is loaded before reading it throws instead. Dynamic Trees asks on
 * almost every step of growing a tree, and lost every one SeedGen tried to paint. Any position the
 * stand-in is asked about is one it can answer, so the answer is yes.
 */
@Mixin(targets = "mobius.voxyseedgen.terrain.TreeGrower$StandIn", remap = false)
public abstract class MixinStandIn {
    @Unique
    private static final Set<String> FOXY$PRESENCE = Set.of("hasChunk", "hasChunkAt", "hasChunksAt", "isLoaded", "isAreaLoaded");

    @Inject(method = "invoke", at = @At("HEAD"), cancellable = true)
    private void foxy$everyChunkIsThere(Object proxy, Method method, Object[] args, CallbackInfoReturnable<Object> cir) {
        if (method.getReturnType() == boolean.class && FOXY$PRESENCE.contains(method.getName())) {
            cir.setReturnValue(Boolean.TRUE);
        }
    }
}
