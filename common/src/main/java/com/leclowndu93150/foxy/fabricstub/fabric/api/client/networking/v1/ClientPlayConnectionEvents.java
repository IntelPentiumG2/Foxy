package com.leclowndu93150.foxy.fabricstub.fabric.api.client.networking.v1;

import com.leclowndu93150.foxy.fabricstub.fabric.api.event.Event;
import com.leclowndu93150.foxy.fabricstub.fabric.api.networking.v1.PacketSender;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;

public final class ClientPlayConnectionEvents {
    public static final Event<Join> JOIN = new Event<>();
    public static final Event<Disconnect> DISCONNECT = new Event<>();

    private ClientPlayConnectionEvents() {}

    @FunctionalInterface
    public interface Join {
        void onPlayReady(ClientPacketListener handler, PacketSender sender, Minecraft client);
    }

    @FunctionalInterface
    public interface Disconnect {
        void onPlayDisconnect(ClientPacketListener handler, Minecraft client);
    }
}
