package forge.web.protocol;

import forge.web.protocol.CampaignProtocol.PackCard;
import forge.web.protocol.MessageJson.Command;
import forge.web.protocol.MessageJson.Message;
import forge.web.protocol.MessageJson.Nullable;

import java.util.List;

/** Planar Conquest's messages in both directions. */
public final class ConquestProtocol {
    private ConquestProtocol() {
    }

    /** Conquest commands that are only their name. */
    public enum ConquestPlain { conquestMove, conquestBattle, conquestParty, conquestPlanes }

    @Command
    public record ConquestBare(ConquestPlain t) {
    }

    /** Selects a place on the map. The player does not move. */
    @Command("conquestSelect")
    public record ConquestSelect(int region, int row, int col) {
    }

    /** The Aether's filters. An empty type asks for the starting filters of a visit. pull spends the shards to take a card. */
    @Command("conquestAether")
    public record ConquestAetherQuery(String colors, String type, String rarity, String cmc, boolean pull) {
    }

    /** Travels to an unlocked plane. With unlock, it first spends the emblems to unlock the plane. */
    @Command("conquestPlaneswalk")
    public record ConquestPlaneswalk(String plane, boolean unlock) {
    }

    /** Asks what a new conquest may start with. The answer lists the planes, the plane's commanders if a plane is given, and the planeswalkers if a commander is given. */
    @Command("conquestOptions")
    public record ConquestOptionsQuery(@Nullable String plane, @Nullable String commander) {
    }

    @Command("conquestCreate")
    public record ConquestCreate(String name, String plane, String commander, String planeswalker) {
    }

    public enum CommanderAction { conquestLead, conquestViewDeck, conquestEditDeck }

    /** An action on a commander: make it the battle leader, ask for its deck, or open its deck in the editor. */
    @Command
    public record ConquestCommanderCommand(CommanderAction t, String commander) {
    }

    /** Makes an owned planeswalker the one the player travels as. */
    @Command("conquestWalker")
    public record ConquestWalker(String planeswalker) {
    }

    /** Dev mode: the outcome the next wheel stops on. It is a ChaosWheelOutcome name, or empty for a random outcome. */
    @Command("devConquestWheel")
    public record DevConquestWheel(String outcome) {
    }

    /** The saved conquests, and the one played last. */
    @Message("conquestSaves")
    public record ConquestSaves(List<ConquestSave> saves, @Nullable String current) {
    }

    /** conquered and total count the events of the save's plane. saved is the day its file last changed, as yyyy-MM-dd. */
    public record ConquestSave(String name, String planeswalker, String walkerImage, String plane, String art, int conquered, int total, int cards,
            int shards, int emblems, @Nullable String saved) {
    }

    public record ConquestPlaneOption(String name, String art, String description, int events) {
    }

    /** A commander or a planeswalker a new conquest may start with. region is the commander's own. */
    public record ConquestCardOption(String name, String image, String colors, @Nullable String region) {
    }

    /** plane and commander are the ones asked about, so the browser can tell this answer from an older one. */
    @Message("conquestOptions")
    public record ConquestOptions(List<ConquestPlaneOption> planes, @Nullable List<ConquestCardOption> commanders,
            @Nullable List<ConquestCardOption> planeswalkers, int startShards, @Nullable String plane, @Nullable String commander) {
    }

    public record ConquestPlace(int region, int row, int col) {
    }

    /** A region of the plane. art is the image key of its art card. */
    public record ConquestRegionRow(String name, String art, String colors, int conquered, int total) {
    }

    /** state is won, open (it can be fought) or fog. A fogged cell has no other fields set. */
    public record ConquestCell(int region, int row, int col, String state, @Nullable String name, @Nullable String opponent,
            @Nullable String avatar, @Nullable List<String> variants, int wins, int losses, @Nullable String opens) {
    }

    /** The selected commander. problem says why its deck cannot be played, if it cannot. */
    public record ConquestLead(String name, String image, int deckSize, @Nullable String problem) {
    }

    /** steps is the distance from the player's position to the selection. path is empty in every map except the one that answers a move. */
    @Message("conquestState")
    public record ConquestState(String plane, int rows, int cols, List<ConquestRegionRow> regions, List<ConquestCell> cells,
            ConquestPlace at, String walker, String walkerImage, ConquestPlace selected, int steps, List<ConquestPlace> path,
            ConquestLead commander) {
    }

    /** A commander as the Commanders page lists it. problem is why its deck cannot be played. */
    public record ConquestCommanderRow(String name, String image, String colors, String origin, int wins, int losses, int deckSize,
            @Nullable String problem, boolean selected) {
    }

    public record ConquestWalkerRow(String name, String image, String colors, boolean selected) {
    }

    @Message("conquestParty")
    public record ConquestParty(List<ConquestCommanderRow> commanders, List<ConquestWalkerRow> planeswalkers) {
    }

    /** An option of one of the Aether's filters. cost is what a pull at that rarity costs. */
    public record ConquestOption(String key, String label, @Nullable Integer cost) {
    }

    /** strict counts the cards of exactly the rarity asked for. Nothing can be pulled when it is 0. cost is 0 when nothing matches. */
    @Message("conquestAether")
    public record ConquestAetherState(int locked, int matching, int strict, List<Integer> byRarity, int cost, List<ConquestOption> types,
            List<ConquestOption> rarities, List<ConquestOption> cmcs, String colors, String type, String rarity, String cmc,
            String commanderColors, @Nullable PackCard pulled, @Nullable String problem) {
    }

    public record ConquestPlaneRow(String name, String art, String description, boolean unlocked, boolean current, int conquered,
            int events, List<String> regions) {
    }

    /** Every plane that can be reached, the cost of the next unlock, and the emblems held. */
    @Message("conquestPlanes")
    public record ConquestPlanes(List<ConquestPlaneRow> planes, int unlockCost, int emblems) {
    }
}
