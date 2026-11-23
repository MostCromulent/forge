package forge.web.protocol;

import forge.web.protocol.MessageJson.Command;
import forge.web.protocol.MessageJson.Message;
import forge.web.protocol.MessageJson.Nullable;
import forge.web.protocol.ToBrowser.DeckDetails;

import java.util.List;

/**
 * Rogue Commander's messages in both directions. Each page carries the number of the snapshot it was taken from,
 * and each command names the snapshot it was made from, so a command made from a page the player no longer sees is refused.
 */
public final class RogueProtocol {
    private RogueProtocol() {
    }

    /** The pages shown with no run open. run is the unfinished run, if there is one. sparks is null until Descension is unlocked. */
    @Message("rogueHome")
    public record RogueHome(long snapshot, @Nullable RogueRunCard run, boolean started, int runs, int won, boolean aether, boolean codex,
            boolean history, int echoes, @Nullable Integer sparks) {
    }

    public record RogueRunCard(String commander, int life, int maxLife, int gold, int cards) {
    }

    /** The open run's map. deck is the run's deck, read only. */
    @Message("rogueMap")
    public record RogueMap(long snapshot, int selected, int rerolls, DeckDetails deck) {
    }

    @Message("rogueTraits")
    public record RogueTraits(long snapshot, List<String> traits) {
    }

    @Command("rogueSelect")
    public record RogueSelect(long snapshot, int node) {
    }

    @Command("rogueEnter")
    public record RogueEnter(long snapshot, int node) {
    }

    @Command("rogueReroll")
    public record RogueReroll(long snapshot, int node) {
    }

    @Command("rogueAbandon")
    public record RogueAbandon(long snapshot) {
    }

    /** deck is a Rogue deck's name, or empty when a custom run generates its commander. modifications are CustomRunModification names. */
    @Command("rogueBegin")
    public record RogueBegin(long snapshot, String deck, boolean custom, List<String> modifications, int descension) {
    }
}
