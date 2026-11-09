package com.leclowndu93150.foxy.mixin;

import com.leclowndu93150.foxy.fabricstub.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.function.Supplier;

/**
 * Fabric's client commands are typed against its own source, and NeoForge's against the vanilla
 * one. Making the vanilla source a Fabric one lets a Fabric mod's command tree be registered on
 * NeoForge's client dispatcher as it is.
 */
@Mixin(CommandSourceStack.class)
public abstract class MixinCommandSourceStack implements FabricClientCommandSource {
    @Shadow
    public abstract void sendSuccess(Supplier<Component> message, boolean broadcast);

    @Shadow
    public abstract void sendFailure(Component message);

    @Override
    public void sendFeedback(Component message) {
        sendSuccess(() -> message, false);
    }

    @Override
    public void sendError(Component message) {
        sendFailure(message);
    }
}
