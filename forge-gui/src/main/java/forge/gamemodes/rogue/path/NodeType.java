package forge.gamemodes.rogue.path;

import forge.util.Localizer;

public enum NodeType {
    PLANEBOUND, SANCTUM, BAZAAR, EVENT, CHEST;

    /** A side node's name on the map. Null for a plane, which its planebound names. */
    public String sideName() {
        return switch (this) {
            case PLANEBOUND -> null;
            case SANCTUM -> Localizer.getInstance().getMessage("lblRogueSanctum");
            case BAZAAR -> Localizer.getInstance().getMessage("lblRogueBazaar");
            case EVENT -> Localizer.getInstance().getMessage("lblRogueEvent");
            case CHEST -> Localizer.getInstance().getMessage("lblRogueChest");
        };
    }

    /** What a side node offers, as its map tooltip says. Null for a plane. */
    public String sideTooltip() {
        return switch (this) {
            case PLANEBOUND -> null;
            case SANCTUM -> Localizer.getInstance().getMessage("lblRogueSanctumTooltip");
            case BAZAAR -> Localizer.getInstance().getMessage("lblRogueBazaarTooltip");
            case EVENT -> Localizer.getInstance().getMessage("lblRogueEventTooltip");
            case CHEST -> Localizer.getInstance().getMessage("lblRogueChestTooltip");
        };
    }
}
