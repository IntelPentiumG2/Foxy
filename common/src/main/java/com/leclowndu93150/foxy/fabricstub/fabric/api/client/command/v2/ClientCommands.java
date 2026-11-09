package com.leclowndu93150.foxy.fabricstub.fabric.api.client.command.v2;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;

/**
 * The builders Fabric mods make their client command trees with. The trees are registered on
 * NeoForge's client dispatcher, whose source is made a {@link FabricClientCommandSource} by
 * {@code MixinCommandSourceStack}.
 */
public final class ClientCommands {
    private ClientCommands() {}

    public static LiteralArgumentBuilder<FabricClientCommandSource> literal(String name) {
        return LiteralArgumentBuilder.literal(name);
    }

    public static <T> RequiredArgumentBuilder<FabricClientCommandSource, T> argument(String name, ArgumentType<T> type) {
        return RequiredArgumentBuilder.argument(name, type);
    }
}
