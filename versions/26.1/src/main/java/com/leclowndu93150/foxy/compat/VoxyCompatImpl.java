package com.leclowndu93150.foxy.compat;

import net.minecraft.client.Minecraft;

public final class VoxyCompatImpl implements VoxyCompat {

    @Override
    public void markAllChanged(Minecraft minecraft) {
        var renderer = minecraft.levelRenderer;
        if (renderer != null) {
            renderer.allChanged();
        }
    }
}
