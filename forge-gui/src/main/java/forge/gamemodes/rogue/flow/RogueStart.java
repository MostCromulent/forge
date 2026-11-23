package forge.gamemodes.rogue.flow;

import forge.gamemodes.rogue.RogueConfig;
import forge.gamemodes.rogue.RogueDeck;
import forge.gamemodes.rogue.RogueMetaProgress;
import forge.gamemodes.rogue.effect.CustomRunModification;
import forge.gamemodes.rogue.effect.DescensionLevel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** What the player chose on the start page. A generated commander is played instead of the deck, which may then be null. */
public record RogueStart(RogueDeck deck, boolean custom, Set<CustomRunModification> modifications, int descension) {

    public static List<RogueDeck> roster(boolean experimental) {
        return roster(RogueConfig.loadRogueDecks(), experimental);
    }

    /** decks are all Rogue decks, as RogueConfig loads them. Reading them is slow, so a caller that asks often keeps them. */
    public static List<RogueDeck> roster(List<RogueDeck> decks, boolean experimental) {
        List<RogueDeck> availableDecks = new ArrayList<>(decks);
        availableDecks.removeIf(deck -> deck.isExperimental() != experimental);

        // Sort commanders: unlocked first (alphabetically), then locked (alphabetically)
        availableDecks.sort(Comparator
            .comparing((RogueDeck d) -> !d.isUnlocked())  // false (unlocked) before true (locked)
            .thenComparing(RogueDeck::getName));
        return availableDecks;
    }

    public boolean canBegin(RogueMetaProgress progress) {
        if (custom && !progress.isCustomRunsUnlocked()) {
            return false;
        }
        if (descension > 0 && !progress.isDescensionModeUnlocked()) {
            return false;
        }
        List<CustomRunModification> runModifications = runModifications();
        if (custom && runModifications.isEmpty()) {
            return false;
        }
        for (CustomRunModification modification : runModifications) {
            if (runModifications.stream().anyMatch(modification::conflictsWith)) {
                return false;
            }
        }
        if (!isGeneratedCommander() && (deck == null || !deck.isUnlocked())) {
            return false;
        }
        // The level after the last one counts as unlocked once the last one is won
        return descension >= 0 && descension <= DescensionLevel.getMaxLevel()
            && (custom || descension <= progress.getMaxDescensionUnlocked(deck.getCommanderCardName()));
    }

    boolean isGeneratedCommander() {
        return custom && modifications.stream().anyMatch(CustomRunModification::isGeneratedCommander);
    }

    /** In the order they apply. */
    List<CustomRunModification> runModifications() {
        List<CustomRunModification> runModifications = new ArrayList<>();
        if (!custom) {
            return runModifications;
        }
        boolean generatedCommander = isGeneratedCommander();
        for (CustomRunModification modification : CustomRunModification.values()) {
            if (modifications.contains(modification)
                && (!generatedCommander || !modification.isIncludedWithGeneratedCommander())) {
                runModifications.add(modification);
            }
        }
        return runModifications;
    }
}
