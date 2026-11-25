package com.leclowndu93150.foxy.mixin;

import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.WeightedVariants;
import net.minecraft.util.random.WeightedList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets the LoD bake pick a weighted model's likeliest variant; see {@code DynamicModelBake}. */
@Mixin(WeightedVariants.class)
public interface WeightedVariantsAccessor {
    @Accessor("list")
    WeightedList<BlockStateModel> foxy$variants();
}
