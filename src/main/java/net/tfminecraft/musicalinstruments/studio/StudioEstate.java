package net.tfminecraft.musicalinstruments.studio;

import org.bukkit.plugin.Plugin;

/** The optional estate implementation is resolved only after all required API classes. */
final class StudioEstate {
    private StudioEstate() {}
    static void startIfAvailable(Plugin plugin, StudioService studio, StudioOwnership ownership) {
        try {
            for (String name : new String[] {"Estates", "EstateParticipant", "EstateDecision", "Outcome"})
                Class.forName("net.tfminecraft.tlibs.character.estate." + name);
            Class.forName("net.tfminecraft.musicalinstruments.studio.CharacterStudioEstate")
                    .getDeclaredMethod("start", Plugin.class, StudioService.class, StudioOwnership.class)
                    .invoke(null, plugin, studio, ownership);
        } catch (ReflectiveOperationException | LinkageError ex) {
            plugin.getLogger().warning("Studio estate integration unavailable; projects stay with their owners: " + ex);
        }
    }
}
