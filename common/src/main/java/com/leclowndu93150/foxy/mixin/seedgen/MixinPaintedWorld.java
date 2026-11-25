package com.leclowndu93150.foxy.mixin.seedgen;

import com.leclowndu93150.foxy.compat.SeedGenClosing;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Ends a SeedGen tile early when the world closes; see {@link SeedGenClosing}. */
@Mixin(targets = "mobius.voxyseedgen.terrain.PaintedChunks$World", remap = false)
public abstract class MixinPaintedWorld {
    @Inject(method = "at", at = @At("HEAD"))
    private void foxy$stopWhenLeaving(CallbackInfoReturnable<?> cir) {
        SeedGenClosing.abortIfLeaving(this);
    }
}
