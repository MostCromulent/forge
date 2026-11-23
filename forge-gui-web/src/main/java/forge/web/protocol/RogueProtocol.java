package forge.web.protocol;

import com.google.gson.JsonElement;
import forge.web.protocol.CampaignProtocol.CampaignBar;
import forge.web.protocol.MessageJson.Command;
import forge.web.protocol.MessageJson.Message;
import forge.web.protocol.MessageJson.Nullable;
import forge.web.protocol.MessageJson.Ts;
import forge.web.protocol.ToBrowser.DeckDetails;

import java.util.List;

/**
 * Rogue Commander's messages in both directions. Each page carries the number of the snapshot it was taken from,
 * and each command names the snapshot it was made from, so a command made from a page the player no longer sees is refused.
 */
public final class RogueProtocol {
    private RogueProtocol() {
    }

    /**
     * The pages shown with no run open. run is the unfinished run, if there is one. unlocked says whether Aether, Codex and History can be opened.
     * fan holds the pictures of the commanders drawn behind Start New Run.
     */
    @Message("rogueHome")
    public record RogueHome(long snapshot, CampaignBar bar, @Nullable RogueRunCard run, boolean unlocked, List<String> fan) {
    }

    /** line gives the run's Descension and Endless levels, and is empty with neither. */
    public record RogueRunCard(String commander, String image, String line, int life, int maxLife, int gold, int cards, int credits,
            List<CarryCard> carry) {
    }

    /** type is a CarryCardType name. */
    public record CarryCard(String name, String image, String type) {
    }

    /**
     * Something won or unlocked, shown over whichever Rogue page is open. kind is "unlock" or "spark".
     * The first line of text is its name. image is a card's picture, or null.
     */
    @Message("rogueAnnounce")
    public record RogueAnnounce(String kind, String title, String text, @Nullable String image) {
    }

    /**
     * What a new run can start with. commanders and experimental are the official roster and the one Experiment shows, in the shared order.
     * custom and descension say whether Custom Run and Descension Mode are unlocked. customLock says why a custom run cannot have Descension,
     * and is null when it can. levels are the Descension levels from level 1.
     */
    @Message("rogueRoster")
    public record RogueRoster(long snapshot, List<RogueCommander> commanders, List<RogueCommander> experimental, boolean custom, boolean descension,
            @Nullable String customLock, List<DescensionStep> levels, List<Modification> modifications) {
    }

    /**
     * deck is the Rogue deck's name. A locked commander has no name, image, colours, text or themes, and unlock says how to unlock it.
     * record is the line above the name. seen counts the reward pool's cards seen, as the Codex does.
     * startable is the highest Descension level a run outside Custom Run may start with, 0 for none, and -1 when no run may start with it.
     * lock says why it cannot have Descension, and is null when it can. next says how the level after startable is unlocked, and is null with none.
     */
    public record RogueCommander(String deck, @Nullable String name, @Nullable String image, String colors, String text, List<String> themes, String record,
            int runs, int wins, int maxDescension, int seen, int startable, @Nullable String lock, @Nullable String next, @Nullable String unlock) {
    }

    public record DescensionStep(String name, String text) {
    }

    /**
     * id is a CustomRunModification name, and group the heading it is listed under.
     * generatesCommander means the run's commander is not the one chosen; experimental means the experimental roster is shown.
     */
    public record Modification(String id, String name, String text, String group, boolean generatesCommander, boolean experimental) {
    }

    /** Answered with a DeckDetailsMessage of the deck's start deck. */
    @Command("rogueStartDeck")
    public record RogueStartDeck(String deck) {
    }

    /**
     * Asks about a custom run before it starts, after ticking or unticking toggle when it is set. deck is the commander in front.
     * Answered with a rogueChoice.
     */
    @Command("rogueChoose")
    public record RogueChoose(String deck, List<String> modifications, int descension, @Nullable String toggle) {
    }

    /** The custom run asked about, with modifications as they are after the toggle. forced are those that cannot be changed. */
    @Message("rogueChoice")
    public record RogueChoice(String deck, List<String> modifications, int descension, List<String> forced, boolean ready) {
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

    /** Answers the open question. A Tell is answered with null. */
    @Command("rogueAnswer")
    public record RogueAnswer(int id, @Nullable @Ts("unknown") JsonElement value) {
    }

    /**
     * The question the flow waits on, sent again after every hello until it is answered. Exactly one shape is set.
     * It is not a request of the match's, because those are cleared by every hello and a default answers an unknown one.
     */
    @Message("rogueQuestion")
    public record RogueQuestion(int id, String kind, @Nullable AskChoice choice, @Nullable AskCards cards, @Nullable AskCommander commander,
            @Nullable AskPortrait portrait, @Nullable AskShop shop, @Nullable TellOmen omen, @Nullable Tell tell) {
    }

    /** A piece of text. kind is "text", "card" or "term"; image is a card's picture and term a keyword's name. */
    public record Part(String kind, String text, @Nullable String image, @Nullable String term) {
    }

    public record RogueCard(String name, String image) {
    }

    /** reason says why a choice that is not enabled cannot be taken. */
    public record Option(String label, List<Part> text, boolean enabled, @Nullable String reason) {
    }

    /** Answered with an option's index, "reroll" while rerolls is above 0, or "skip" where skip is true. face is an avatar. */
    public record AskChoice(String kind, @Nullable Integer face, String title, List<List<Part>> paragraphs, List<Option> options, int rerolls,
            boolean skip) {
    }

    /** Answered with the indexes of the cards picked, or "reroll" where rerollCost is set. */
    public record AskCards(String kind, String title, @Nullable String subtitle, List<RogueCard> cards, int min, int max,
            @Nullable Integer rerollCost, @Nullable Integer gold) {
    }

    /** Answered with the indexes of one commander or two that partner, or none to cancel. */
    public record AskCommander(String title, List<RogueCard> commanders) {
    }

    /** Answered with one of the avatars. */
    public record AskPortrait(String commander, int current, List<Integer> avatars) {
    }

    /** Answered with {"selected": item ids, "reroll": boolean, "tab": number}. rerollCost is null in a shop with no reroll. */
    public record AskShop(String title, List<ShopItem> items, int gold, @Nullable Integer rerollCost, boolean rerollEnabled, int tab) {
    }

    public record ShopItem(int id, String name, @Nullable String image, List<Part> text, int price) {
    }

    /** The Wrathful and Cursed effects of the battle about to start. */
    public record TellOmen(String title, String text, List<Omen> omens) {
    }

    /** kind is "wrathful" or "cursed". */
    public record Omen(String kind, String name, List<Part> text) {
    }

    /** A result or a message. kind is a ResultKind name, or "MESSAGE". */
    public record Tell(String kind, @Nullable String icon, String title, List<Part> text, List<String> gains, List<TellSection> sections) {
    }

    public record TellSection(String label, List<Part> text, List<RogueCard> cards) {
    }
}
