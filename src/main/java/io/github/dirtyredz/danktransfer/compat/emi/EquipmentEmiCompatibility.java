package io.github.dirtyredz.danktransfer.compat.emi;

import net.neoforged.fml.ModList;

/** Exact optional handler allowlist; unrelated handlers named "Handler" must remain untouched. */
final class EquipmentEmiCompatibility {
    private EquipmentEmiCompatibility() {}

    static boolean accepts(String handlerClass) {
        ModList mods = ModList.get();
        return mods != null && mods.isLoaded("evileye_client") && named(handlerClass);
    }

    static boolean named(String handlerClass) {
        return "io.github.dirtyredz.evileyeclient.integration.emi.EquipmentEmiPlugin$Handler".equals(handlerClass);
    }
}
