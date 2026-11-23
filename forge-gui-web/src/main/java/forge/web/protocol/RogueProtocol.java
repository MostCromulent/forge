package forge.web.protocol;

import com.google.gson.JsonElement;
import forge.web.protocol.CampaignProtocol.CampaignBar;
import forge.web.protocol.CampaignProtocol.Figure;
import forge.web.protocol.MessageJson.Command;
import forge.web.protocol.MessageJson.Message;
import forge.web.protocol.MessageJson.Nullable;
import forge.web.protocol.MessageJson.Ts;
import forge.web.protocol.ToBrowser.DeckDetails;
import forge.web.protocol.ToBrowser.EditorGroup;

import java.util.List;
import java.util.Map;

/**
 * Rogue Commander's messages in both directions. The home page and the map carry the number of the snapshot they were taken from,
 * and each command names the snapshot it was made from, so a command made from a page the player no longer sees is refused.
 * The other pages are sent only when they change, so they carry no number.
 */
public final class RogueProtocol {
    private RogueProtocol() {
    }

    /**
     * The pages shown with no run open. run is the unfinished run, if there is one. unlocked says whether Aether, Codex and History can be opened.
     * fan holds the pictures of the commanders drawn behind Start New Run. devUnlockAll and aetherMaxed are the states the dev tools switch.
     */
    @Message("rogueHome")
    public record RogueHome(long snapshot, CampaignBar bar, @Nullable RogueRunCard run, boolean unlocked, List<String> fan, boolean devUnlockAll,
            boolean aetherMaxed) {
    }

    /**
     * A dev mode tool, refused when dev mode is off: unlockAll and maxAether switch, npcProgress asks each NPC's level, echoes and sparks add 10,
     * codex unlocks the whole Codex, and nextNode and winRun move the open run on.
     */
    @Command("rogueDev")
    public record RogueDev(long snapshot, String tool) {
    }

    /** line gives the run's Descension and Endless levels, and is empty with neither. */
    public record RogueRunCard(String commander, String image, String line, int life, int maxLife, int gold, int cards, int credits,
            List<RogueCard> carry) {
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
    public record RogueRoster(List<RogueCommander> commanders, List<RogueCommander> experimental, boolean custom, boolean descension,
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

    /**
     * The open run's map, its rows from the first to the boss's. links join each node to the nodes of the next row it leads to, by index.
     * selected is the current node. rerolls counts the plane rerolls left, and rerollReason says why none can be made now, or is null when one can.
     * hidden says why planes are not revealed when they are reached, and is null when they are. terms are the terms the map explains,
     * by KeywordHint name.
     */
    @Message("rogueMap")
    public record RogueMap(long snapshot, List<List<MapNode>> rows, List<MapLink> links, int selected, RogueRunBar bar, int rerolls,
            @Nullable String rerollReason, @Nullable String hidden, Map<String, Part> terms) {
    }

    /** The open run's deck, read only. It is a page of its own, so it is sent only when it changes. */
    @Message("rogueRunDeck")
    public record RogueRunDeck(DeckDetails deck) {
    }

    /**
     * kind is a NodeType name, and state one of walked, open, selected, past, far or dead. A face-down plane has no name, plane, art,
     * planeImage or text, but its life and marks are known. name is the planebound's or the side node's. art is the planebound's card and
     * planeImage the plane's. enter is the label of the button that enters the node.
     */
    public record MapNode(int index, String kind, String state, boolean faceDown, @Nullable String name, @Nullable String plane, @Nullable String art,
            @Nullable String planeImage, @Nullable String text, int life, boolean elite, boolean boss, int wrathful, int cursed, int gold, int echoes,
            String enter) {
    }

    public record MapLink(int from, int to) {
    }

    /** The bar over the run's pages. tip explains its line. traits counts the run's traits, Descension and Endless left out. */
    public record RogueRunBar(CampaignBar bar, @Nullable String tip, int traits) {
    }

    /** The run's traits, in the order they are listed, and its carry cards. */
    @Message("rogueTraits")
    public record RogueTraits(List<Trait> traits, List<RogueCard> carry) {
    }

    /**
     * source is where the trait came from: AETHER, CUSTOM, EVENT, CHEST, NPC, WRATHFUL, CURSED, WOUND, DESCENSION or ENDLESS.
     * avatar is the face of the NPC who gave it, and mark the text drawn in place of an icon, such as a Descension level.
     * term and tip explain the source. loot means a reward taken once, not a lasting trait.
     */
    public record Trait(String name, List<Part> text, String source, @Nullable Integer avatar, @Nullable String mark, String term, String tip,
            boolean loot) {
    }

    /**
     * The Aether. works are the Aetherworks the upgrade level reaches, and used and slots count the Aether Energy in use and in all.
     * descension says whether Descension Mode is unlocked, before which there are no Sparks and no upgrades. upgrade is the next Aether Upgrade,
     * or null with none left or before Descension. locked are the Aetherworks later upgrades unlock. art is the picture behind the page.
     */
    @Message("rogueAether")
    public record RogueAether(List<AetherWork> works, int used, int slots, boolean descension, @Nullable AetherOffer upgrade, List<AetherGroup> locked,
            @Nullable String art) {
    }

    /**
     * id is an AetherEffect name. text is its description with the value of every rank, values those values, and rank the rank built, 0 for none.
     * ranks give each rank's sentence and price. cost is the next rank's price, or -1 at the highest. toggle says whether it can be switched now,
     * and afford whether the Echoes pay for the next rank.
     */
    public record AetherWork(String id, String name, String text, List<Integer> values, int rank, boolean on, boolean toggle, int cost, boolean afford,
            List<AetherRank> ranks) {
    }

    public record AetherRank(String text, int cost) {
    }

    public record AetherOffer(String name, String text, int sparks, int echoes, boolean afford) {
    }

    /** title names the upgrade that unlocks the works. */
    public record AetherGroup(String title, List<String> works) {
    }

    /** Builds an Aetherwork or raises its rank. One built first is switched on when there is Aether Energy to spare. */
    @Command("rogueAetherUpgrade")
    public record RogueAetherUpgrade(long snapshot, String work) {
    }

    @Command("rogueAetherToggle")
    public record RogueAetherToggle(long snapshot, String work, boolean on) {
    }

    /** Buys the next Aether Upgrade. */
    @Command("rogueAetherExpand")
    public record RogueAetherExpand(long snapshot) {
    }

    /**
     * kind is "aether", which refunds the Echoes spent on Aetherworks, "history", "tutorials", or "progress", which keeps the run and the tutorials
     * and resets everything else.
     */
    @Command("rogueReset")
    public record RogueReset(long snapshot, String kind) {
    }

    /**
     * Asks for one tab of the Codex: stats, commanders, planebounds, traits or journal. id names an entry of the tab's list, or is null for the first.
     * Answered with a rogueCodexPage, and not at all while the Codex is locked.
     */
    @Command("rogueCodex")
    public record RogueCodex(String tab, @Nullable String id) {
    }

    /**
     * One tab of the Codex. id is the entry shown, or null with none. art is the picture behind it. rows are the tab's list, stats its figures
     * by group, and entry the entry shown, or the traits on the traits tab.
     */
    @Message("rogueCodexPage")
    public record RogueCodexPage(String tab, @Nullable String id, @Nullable String art, List<CodexRow> rows, List<CodexStats> stats,
            @Nullable CodexEntry entry) {
    }

    /** id is null for a locked commander or a planebound not met yet. line is null where the row has none. group heads the rows from this one on. */
    public record CodexRow(@Nullable String id, String name, @Nullable String art, @Nullable String line, @Nullable String group) {
    }

    public record CodexStats(String title, List<Figure> figures) {
    }

    /**
     * image is its card, and plane its plane's card. face is the avatar of the one who tells it. facts are its figures by kind: runs, wins,
     * descension, endless, seen, acquired, slain or defeats. groups are its cards or traits, and paragraphs its text.
     */
    public record CodexEntry(@Nullable String title, @Nullable String image, @Nullable String plane, @Nullable String planeName, @Nullable Integer face,
            List<CodexFact> facts, List<CodexGroup> groups, List<String> paragraphs) {
    }

    public record CodexFact(String kind, String value) {
    }

    public record CodexGroup(String title, List<CodexItem> items) {
    }

    /** state is unknown, seen or acquired. An unknown card or trait has no name, image or trait. */
    public record CodexItem(String state, @Nullable String name, @Nullable String image, @Nullable Trait trait) {
    }

    /** Asks for the finished runs. Answered with a rogueHistoryPage, and not at all while History is locked. */
    @Command("rogueHistory")
    public record RogueHistory() {
    }

    /** The finished runs, newest first, and the picture behind them. */
    @Message("rogueHistoryPage")
    public record RogueHistoryPage(List<HistoryRun> runs, @Nullable String art) {
    }

    /**
     * id is the run's place in the history, for its deck. outcome is VICTORY, DEFEAT or ABANDONED, as History shows it. line says who was beaten
     * or won, and the Descension and Endless levels. date is yyyy-mm-dd and time how long the run took. deck says whether its deck was kept.
     */
    public record HistoryRun(int id, String commander, @Nullable String image, String outcome, String line, List<HistoryStep> steps,
            List<HistoryTrait> traits, String date, String time, boolean deck) {
    }

    /** kind is a NodeType name. image is the plane's picture, null for a side node or a plane not known, and name the planebound's. */
    public record HistoryStep(String kind, @Nullable String image, @Nullable String name) {
    }

    /** tip explains the trait, or is null. */
    public record HistoryTrait(String name, @Nullable String tip) {
    }

    /** Answered with a DeckDetailsMessage of the deck a finished run ended with. */
    @Command("rogueHistoryDeck")
    public record RogueHistoryDeck(int run) {
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

    /** Opens the run's deck in the deck editor. */
    @Command("rogueEditDeck")
    public record RogueEditDeck(long snapshot) {
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
    public record RogueQuestion(int id, @Nullable AskChoice choice, @Nullable AskCards cards, @Nullable AskCommander commander,
            @Nullable AskShop shop, @Nullable TellOmen omen, @Nullable Tell tell) {
    }

    /** A piece of text. kind is "text", "card" or "term"; image is a card's picture, term a keyword's name and tip what the keyword means. */
    public record Part(String kind, String text, @Nullable String image, @Nullable String term, @Nullable String tip) {
    }

    /** count is how many equal copies it stands for, and type the term of a carry card's type, or null for another card. */
    public record RogueCard(String name, String image, int count, @Nullable Part type) {
    }

    /** reason says why a choice that is not enabled cannot be taken. trait is set when the option is a trait or a reward to take, drawn as a card. */
    public record Option(String label, List<Part> text, boolean enabled, @Nullable String reason, @Nullable Trait trait) {
    }

    /**
     * Answered with an option's index, "reroll" while rerolls is above 0, or "skip" where skip is true; or, when count is above 0, with the indexes
     * of count options. face is an avatar, and art the picture behind it. label names the kind of scene beside the face on a wide screen,
     * where title is used when it is null, and heading heads the text there.
     */
    public record AskChoice(String kind, @Nullable Integer face, String title, @Nullable String label, @Nullable String heading,
            List<List<Part>> paragraphs, List<Option> options, int rerolls, boolean skip, @Nullable String art, int count) {
    }

    /**
     * Answered with the ids of the cards picked, or "reroll" where rerollCost is set and rerollEnabled is true. deck is the deck the picks go into
     * or come out of, as it is before them, and is null when there is none to show. art is the picture behind them.
     */
    public record AskCards(String kind, String title, @Nullable String subtitle, List<PickCard> cards, int min, int max,
            @Nullable Integer rerollCost, boolean rerollEnabled, @Nullable PickDeck deck, @Nullable String art) {
    }

    /** id is the card's place in the offer, so two equal copies are told apart. cmc is its mana value. */
    public record PickCard(int id, String name, String image, int cmc, boolean mythic) {
    }

    /** total counts the deck's cards, groups included. */
    public record PickDeck(String title, int total, List<EditorGroup> groups) {
    }

    /**
     * One of total commanders, read a page at a time from the catalogue with source "rogue-commanders", whose rows are keyed by index.
     * Answered with {"commander": index, "avatar": one of avatars, or null when there are none}, "skip" where skip is true, or null to cancel.
     * art is the picture behind them, or null for the picture of the commander picked.
     */
    public record AskCommander(String title, int total, List<Integer> avatars, boolean skip, @Nullable String art) {
    }

    /**
     * Answered with {"selected": item ids, "reroll": boolean, "tab": number}. rerollCost is null in a shop with no reroll. tab is the tab shown first:
     * 0 for the cards, 1 for the traits and carry cards. art is the picture behind it.
     */
    public record AskShop(String title, List<ShopItem> items, int gold, @Nullable Integer rerollCost, boolean rerollEnabled, int tab, @Nullable String art) {
    }

    /**
     * id is the item's place in the offer. trait is set for a trait, and type, the term of a carry card's type, for a carry card. was is the price before
     * any discount. special means it is offered with the traits and carry cards, apart from the cards.
     */
    public record ShopItem(int id, String name, @Nullable String image, @Nullable Trait trait, int price, int was, boolean mythic, @Nullable Part type,
            boolean special) {
    }

    /** The Wrathful and Cursed effects of the battle about to start. art is the planebound's picture, and plane the plane's behind it. */
    public record TellOmen(String title, @Nullable String art, @Nullable String plane, List<Omen> omens) {
    }

    /** kind is "wrathful" or "cursed", and label its name. */
    public record Omen(String kind, String label, String name, List<Part> text) {
    }

    /**
     * A result or a message. kind is a ResultKind name, "MESSAGE", or "SUMMARY" for what a won battle gave, which alone has gains.
     * art is the picture behind a result.
     */
    public record Tell(String kind, String title, List<Part> text, List<TellSection> sections, @Nullable TellGains gains, @Nullable String art) {
    }

    /** The gold, Echoes, life and removal credits a won battle gave. */
    public record TellGains(int gold, int echoes, int life, int credits) {
    }

    /** removed means the cards left the deck. */
    public record TellSection(@Nullable String label, List<Part> text, List<RogueCard> cards, boolean removed) {
    }
}
