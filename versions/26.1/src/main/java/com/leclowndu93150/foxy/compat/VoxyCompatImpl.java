package com.leclowndu93150.foxy.compat;

import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;

public final class VoxyCompatImpl implements VoxyCompat {

    @Override
    public void shutdownRenderer(LevelRenderer levelRenderer) {
        ((IGetVoxyRenderSystem) levelRenderer).voxy$shutdownRenderer();
    }

    @Override
    public void markAllChanged(Minecraft minecraft) {
        var renderer = minecraft.levelRenderer;
        if (renderer != null) {
            renderer.allChanged();
        }
    }
}
