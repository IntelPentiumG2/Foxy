package com.leclowndu93150.foxy;

import com.leclowndu93150.foxy.loader.FoxyEntrypoints;
import com.leclowndu93150.foxy.loader.FoxyFabricApi;
import me.cortex.voxy.client.DebugEntries;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;

@Mod("foxy")
public class FoxyNeoForge {

    public FoxyNeoForge(IEventBus modBus) {
        FoxyFabricApi.init(modBus);
        modBus.addListener(this::onCommonSetup);
        modBus.addListener(this::onClientSetup);
        NeoForge.EVENT_BUS.addListener(this::onRegisterClientCommands);
    }

    /**
     * Fabric runs a mod's initializers from inside the {@code Minecraft} constructor, so they may
     * touch {@code Minecraft.getInstance()}. NeoForge constructs mods before that instance exists,
     * so the entrypoints wait for common setup — the first mod bus event of the loading phase that
     * runs once the game is up, and still ahead of {@code RegisterPayloadHandlersEvent}, which
     * needs the payload types the entrypoints register. Enqueued so they run on the main thread,
     * as they do on Fabric.
     */
    private void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(FoxyEntrypoints::invokeAll);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        DebugEntries.init();
    }

    private void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        if (VoxyCommon.isAvailable()) {
            FoxyCommands.register(event.getDispatcher());
        }
    }
}
