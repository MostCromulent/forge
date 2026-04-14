package forge.screens.deckeditor.controllers;

import forge.gamemodes.net.draft.EventParticipant;

import java.awt.Color;
import java.util.List;

/**
 * Utility for logging network draft events to the Editor Log tab.
 * All entries driven by protocol events — no new network messages needed.
 */
public final class NetworkDraftLog {
    // Colors for draft log styling
    private static final Color COLOR_BANNER = new Color(100, 150, 200);     // muted blue
    private static final Color COLOR_SEPARATOR = new Color(130, 130, 130);  // gray
    private static final Color COLOR_MY_PICK = new Color(50, 200, 50);      // green
    private static final Color COLOR_OTHER_PICK = new Color(180, 180, 180); // light gray

    private NetworkDraftLog() { } // utility class

    /** Log the draft start banner with pod information. */
    public static void logDraftStart(List<EventParticipant> participants, int totalPacks,
            String productName, int mySeatIndex) {
        log("======================================", COLOR_BANNER);
        log("  Draft started -- " + participants.size() + " players", COLOR_BANNER);
        log("  " + totalPacks + " packs of " + productName, COLOR_BANNER);

        StringBuilder humans = new StringBuilder("  Players: You");
        StringBuilder ais = new StringBuilder("  AI seats:");
        boolean hasAI = false;
        for (EventParticipant p : participants) {
            if (p.isHuman() && p.getSeatIndex() != mySeatIndex) {
                humans.append(", ").append(p.getName());
            } else if (p.isAI()) {
                ais.append(" ").append(p.getName()).append(",");
                hasAI = true;
            }
        }
        log(humans.toString(), COLOR_BANNER);
        if (hasAI) {
            // Trim trailing comma
            log(ais.substring(0, ais.length() - 1), COLOR_BANNER);
        }
        log("======================================", COLOR_BANNER);
    }

    /** Log a pack round header. */
    public static void logPackHeader(int packNumber, boolean passingRight) {
        String direction = passingRight ? "passing right" : "passing left";
        log("-- Pack " + packNumber + " -- " + direction + " ------", COLOR_SEPARATOR);
    }

    /** Log another player's pick (no card name revealed). */
    public static void logOtherPick(String playerName, int pickNumber) {
        log(playerName + " picked (card " + pickNumber + ")", COLOR_OTHER_PICK);
    }

    /** Log your own pick (card name shown). */
    public static void logMyPick(String cardName, int pickNumber) {
        log("You picked: " + cardName + " (card " + pickNumber + ")", COLOR_MY_PICK);
    }

    /** Log pack round completion. */
    public static void logPackComplete(int packNumber) {
        log("-- Pack " + packNumber + " complete ---------------", COLOR_SEPARATOR);
    }

    /** Log draft completion. */
    public static void logDraftComplete(int totalCards) {
        log("======================================", COLOR_BANNER);
        log("  Draft complete -- " + totalCards + " cards", COLOR_BANNER);
        log("  Building deck...", COLOR_BANNER);
        log("======================================", COLOR_BANNER);
    }

    private static void log(String message, Color color) {
        CEditorLog.SINGLETON_INSTANCE.addLogEntry(message, color);
    }
}
