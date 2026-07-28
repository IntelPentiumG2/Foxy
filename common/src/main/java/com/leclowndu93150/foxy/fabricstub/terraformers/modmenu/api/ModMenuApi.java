package com.leclowndu93150.foxy.fabricstub.terraformers.modmenu.api;

/**
 * Mod Menu's entrypoint interface, implemented by a Fabric mod that wants a settings button.
 *
 * <p>Only the one method the bridged mods override is declared. Mod Menu's interface has more —
 * badges, provided factories for other mods — and a class implementing those keeps its extra
 * methods regardless of whether this interface names them.
 */
public interface ModMenuApi {
    default ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> null;
    }
}
