package com.leclowndu93150.foxy.compat;

import net.minecraft.client.Minecraft;

import java.util.ServiceLoader;

/**
 * The one place Foxy's shared code has to name something that differs between Minecraft
 * versions: 26.2 moved the receiver of {@code allChanged()} off {@code Minecraft.levelRenderer}.
 * Everything else Foxy touches is identical across both, now that 26.1 and 26.2 both build
 * against Voxy 0.2.18.
 */
public interface VoxyCompat {

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
