package com.leclowndu93150.foxy.compat;

import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;

public final class VoxyCompatImpl implements VoxyCompat {

    @Override
    public void shutdownRenderer(LevelRenderer levelRenderer) {
        ((IVoxyRenderSystemHolder) levelRenderer).voxy$shutdownRenderer();
    }

    @Override
    public void markAllChanged(Minecraft minecraft) {
        var extractor = minecraft.levelExtractor;
        if (extractor != null) {
            extractor.allChanged();
        }
    }
}
