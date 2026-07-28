package com.leclowndu93150.foxy.fabricstub.terraformers.modmenu.api;

import net.minecraft.client.gui.screens.Screen;

/**
 * Mod Menu's screen factory, which a mod hands over to have its settings opened from the mod list.
 *
 * <p>The mods Foxy bridges implement this as a lambda, so the erasure of {@link #create} has to
 * stay {@code (Screen)Screen} for their invokedynamic call sites to link against it.
 */
@FunctionalInterface
public interface ConfigScreenFactory<S extends Screen> {
    S create(Screen parent);
}
