package com.leclowndu93150.foxy.loader;

import com.leclowndu93150.foxy.fabricstub.terraformers.modmenu.api.ConfigScreenFactory;
import com.leclowndu93150.foxy.fabricstub.terraformers.modmenu.api.ModMenuApi;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforgespi.language.IModInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Puts the settings screens of the bridged mods back in reach.
 *
 * <p>A Fabric mod offers its settings through a Mod Menu entrypoint: a class returning a factory
 * that builds the screen. NeoForge asks the same question a different way — an
 * {@link IConfigScreenFactory} registered on the mod container, which is what puts the Config
 * button next to a mod in the mod list. The two are the same shape, so each entrypoint is
 * instantiated and its factory wrapped.
 *
 * <p>Voxy and Voxy Extra also declare Sodium's config entrypoint, which Foxy already carries over,
 * so their screens are reachable from Sodium's video settings as well; this adds the mod-list
 * route. Voxy World Gen declares only the Mod Menu one, and had no way in at all.
 */
public final class FoxyConfigScreens {
    private static final Logger LOGGER = LoggerFactory.getLogger("Foxy");

    private FoxyConfigScreens() {}

    public static void registerAll() {
        for (IModInfo mod : ModList.get().getMods()) {
            Object property = mod.getModProperties().get(FoxyFabricModReader.MODMENU_PROPERTY);
            if (!(property instanceof String classNames) || classNames.isBlank()) {
                continue;
            }
            ModList.get().getModContainerById(mod.getModId())
                    .ifPresent(container -> register(container, classNames));
        }
    }

    private static void register(ModContainer container, String classNames) {
        for (String className : classNames.split(",")) {
            ConfigScreenFactory<?> factory = screenFactory(container.getModId(), className.trim());
            if (factory == null) {
                continue;
            }
            container.registerExtensionPoint(IConfigScreenFactory.class,
                    (owner, modListScreen) -> open(factory, modListScreen));
            LOGGER.debug("Registered the config screen of {} from {}", container.getModId(), className);
            return; // The mod list has room for one button; the first entrypoint that answers wins.
        }
    }

    private static ConfigScreenFactory<?> screenFactory(String modId, String className) {
        try {
            Object instance = Class.forName(className).getDeclaredConstructor().newInstance();
            if (!(instance instanceof ModMenuApi api)) {
                return null;
            }
            return api.getModConfigScreenFactory();
        } catch (ClassNotFoundException e) {
            LOGGER.debug("Skipping absent Mod Menu entrypoint {} of {}", className, modId);
            return null;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            LOGGER.warn("Failed to read the config screen of {} from {}", modId, className, e);
            return null;
        }
    }

    /**
     * Builds the screen when the button is pressed. A factory that cannot build one hands back
     * null, which NeoForge reads as "no screen" and leaves the player on the mod list.
     */
    private static Screen open(ConfigScreenFactory<?> factory, Screen modListScreen) {
        try {
            return factory.create(modListScreen);
        } catch (LinkageError | RuntimeException e) {
            LOGGER.error("Failed to open a bridged config screen", e);
            return null;
        }
    }
}
