package com.leclowndu93150.foxy.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;

import java.util.ServiceLoader;

/**
 * The two places Foxy's shared code has to name something that differs between Minecraft
 * versions. Voxy renamed its render-system holder interface between 0.2.16 and 0.2.18, and
 * Minecraft 26.2 moved the receiver of {@code allChanged()} off {@code Minecraft.levelRenderer}.
 * Everything else Foxy touches is identical across both.
 */
public interface VoxyCompat {

    /** Casts to Voxy's render-system holder interface, whatever it is called this version. */
    void shutdownRenderer(LevelRenderer levelRenderer);

    /** Forces a full renderer rebuild, on whichever object owns that call this version. */
    void markAllChanged(Minecraft minecraft);

    static VoxyCompat get() {
        return Holder.INSTANCE;
    }

    final class Holder {
        private static final VoxyCompat INSTANCE =
                ServiceLoader.load(VoxyCompat.class, VoxyCompat.class.getClassLoader())
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException(
                                "Foxy: no VoxyCompat implementation found; the version module "
                                        + "did not ship its META-INF/services entry"));

        private Holder() {}
    }
}
