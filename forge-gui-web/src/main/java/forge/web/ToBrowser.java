package forge.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import forge.card.CardRarity;
import forge.game.GameLogEntryType;
import forge.game.GameLogVerbosity;
import forge.game.phase.PhaseType;
import forge.game.zone.ZoneType;
import forge.gamemodes.net.DeltaPacket;
import forge.player.AutoYieldStore.TriggerDecision;
import forge.web.Wire.Event;
import forge.web.Wire.Message;
import forge.web.Wire.Name;
import forge.web.Wire.Nullable;
import forge.web.Wire.Request;
import forge.web.Wire.Ts;

import java.util.List;
import java.util.Map;

/** The build generates src/main/ts/protocol.gen.ts from this file, so a changed record fails the build where the browser reads it. */
final class ToBrowser {
    private ToBrowser() {
    }

    /** A pointer to an object in the game's table, by its delta key. */
    record Ref(int ref) {
        static Ref card(final int id) {
            return new Ref(DeltaPacket.makeDeltaKey(DeltaPacket.TYPE_CARD_VIEW, id));
        }

        static Ref player(final int id) {
            return new Ref(DeltaPacket.makeDeltaKey(DeltaPacket.TYPE_PLAYER_VIEW, id));
        }
    }

    // ---- Start page and lobby ----------------------------------------------------------------------------------

    @Message("hello")
    record Hello(boolean inMatch, boolean inLobby, boolean joining, boolean spectating, boolean host,
            boolean canClaimHost, boolean networked, @Nullable String playerName, List<Integer> avatars, List<Integer> sleeves, int avatarCount,
            int sleeveCount, List<SavedSleeveArt> sleeveArt, boolean inEvent, @Nullable String eventPool, int sealedPools,
            @Nullable String eventKind, boolean drafting, int draftPools, @Nullable String campaign, @Nullable String campaignSave,
            @Nullable String currentConquest, ServerSettings settings) {
    }

    record SavedSleeveArt(String key, int offset) {
    }

    /** Everyone on this server who has named themselves, for the panel that says who is here. */
    @Message("presence")
    record Presence(List<Person> people) {
    }

    /** What a person is doing: waiting, joining, at a table, playing or watching one. */
    record Person(String name, int avatar, String doing, boolean host) {
    }

    @Message("error")
    record ErrorMessage(String message) {
    }

    @Message("notice")
    record Notice(@Nullable String title, @Nullable String message, boolean error) {
    }

    /** The auto-yields and trigger answers the player has set, and whether either kind is switched off. */
    @Message("autoDecisions")
    record AutoDecisions(List<AutoDecision> entries, boolean yieldsOff, boolean triggersOff) {
    }

    /** One remembered decision: always yield to it, or always accept or decline the trigger. */
    record AutoDecision(String key, AutoDecisionKind kind) {
    }

    enum AutoDecisionKind { yield, accept, decline }

    /** A draw offer while it is open: who made it, and whether this player still has to answer. Closed, it is absent. */
    @Message("drawOffer")
    record DrawOffer(@Nullable Ref offerer, boolean open, boolean mine, boolean waitingOnMe) {
    }

    @Message("decks")
    record Decks(List<DeckSummary> decks, List<String> cardFormats, @Nullable String cardPool) {
    }

    /** A deck in the finder. A generator's entry is only a name until it is picked, so it has no counts. */
    record DeckSummary(String key, String name, String source, String colors, @Nullable Boolean generated,
            @Nullable String note, @Nullable Integer main, @Nullable Integer sideboard, @Nullable String problem,
            @Nullable List<String> legalIn, @Nullable String formats, @Nullable String sleeveArt,
            @Nullable Integer sleeveOffset, @Nullable Boolean readOnly, @Nullable String linked, @Nullable String sourceUrl,
            @Nullable Long synced, @Nullable Integer bracket, @Nullable Integer averageMana, @Nullable Boolean favourite) {
    }

    /** The keys of the listed decks a card or set filter lets through, for the query it answers. */
    @Message("deckMatches")
    record DeckMatches(String kind, String value, List<String> keys) {
    }

    @Message("deckDetails")
    record DeckDetailsMessage(DeckDetails deck) {
    }

    record DeckDetails(String key, String name, @Nullable String problem, String colors, DeckStats stats,
            List<EditorGroup> main, List<EditorCard> sideboard, @Nullable String sleeveArt, int sleeveOffset,
            @Nullable Bracket bracket) {
    }

    /** A Commander deck's suggested minimum bracket, where clear names the kinds of card the deck has none of. */
    record Bracket(int level, List<BracketReason> reasons, List<String> clear) {
    }

    /** One kind of card that bears on the bracket, where raises is the bracket it makes the deck, or 0 when it raises nothing on its own. */
    record BracketReason(String kind, String title, String brief, List<String> cards, int raises, @Nullable String why) {
    }

    /** total counts the commanders with the main deck, and the last curve entry holds its mana value and above. */
    record DeckStats(int total, int main, int sideboard, int lands, float averageMana, List<Integer> curve, List<Integer> creatures,
            List<TypeCount> types) {
    }

    record TypeCount(String name, int count) {
    }

    /** Match setup. No table means no lobby is open. */
    @Message("lobby")
    record LobbyMessage(@Nullable LobbyTable table) {
    }

    record LobbyTable(boolean host, int mySeat, boolean shareable, String format, List<Format> formats,
            @Nullable String cardPool, List<Format> casualVariants, List<String> variantsOn,
            int maxSeats, int gamesPerMatch, List<Seat> seats, List<String> problems, boolean canStart,
            List<String> illegalDecks, boolean legalityEnforced, @Nullable LimitedTable limited, int maxBracket,
            List<String> overBracket) {
    }

    /** A Limited table's event, where kind is "draft" or "sealed" and phase is an EventPhase name. */
    record LimitedTable(String kind, @Nullable String product, int podSize, @Nullable String pickRule, int timer,
            @Nullable String phase, @Nullable String activeEventId, boolean eventDecksOnly, boolean started,
            List<PastEvent> pastEvents) {
    }

    /** An earlier event whose pools the host keeps: "draft" or "sealed", its product, and when it was played (yyyy-MM-dd HH:mm). */
    record PastEvent(String id, String kind, String product, String date) {
    }

    /** Card pools the card pool control offers, under the heading Forge files them by. Sent once per browser. */
    @Message("cardPools")
    record CardPools(List<CardPoolGroup> groups) {
    }

    /** What the card pool picker shows beyond the names: where each format's cards come from. */
    @Message("cardPoolDetails")
    record CardPoolDetails(List<CardPoolLine> lines) {
    }

    /** A format and where its cards come from: "Every set", "Commons only", or the set it starts at. */
    record CardPoolLine(String name, String line) {
    }

    record CardPoolGroup(String name, List<String> formats) {
    }

    /** A format as the lobby offers it: its group, what it is, its deck and life at a glance, and what changes in a match. */
    record Format(String id, String name, String group, String desc, List<String> facts, String play) {
    }

    /** type is a netplay lobby slot's (LOCAL, AI, OPEN or REMOTE), and benched says the seat sits the next match out. */
    record Seat(@Nullable String name, String type, boolean mine, boolean mayEdit, boolean ready, int avatar,
            int sleeve, @Nullable String deck, @Nullable String deckName, int deckSize, String colors,
            @Nullable String problem, @Nullable String sleeveArt, int sleeveOffset, @Nullable String role,
            @Nullable SeatExtra planes, @Nullable SeatExtra schemes, @Nullable SeatExtra vanguard, boolean benched,
            @Nullable String commander, @Nullable Integer bracket) {
    }

    /** A planar deck, scheme deck or avatar a seat brings: its name, its size, a detail such as modifiers, a fault. */
    record SeatExtra(String label, int count, @Nullable String detail, @Nullable String problem) {
    }

    /** What a seat may choose for one extra section. An avatar carries its image and its two modifiers. */
    @Message("extraChoices")
    record ExtraChoices(String section, int index, List<ExtraChoice> choices) {
    }

    record ExtraChoice(String key, String label, @Nullable Integer count, @Nullable String problem,
            @Nullable String image, @Nullable Integer hand, @Nullable Integer life) {
    }

    @Message("addresses")
    record Addresses(List<Address> list) {
    }

    record Address(String label, String url) {
    }

    /** A line with no sender is one of netplay's own announcements, and earlier marks a line replayed from before this browser arrived. */
    @Message("chat")
    record ChatLine(@Nullable String from, String text, boolean earlier) {
    }

    /** hiddenBySwitch counts the matching cards the deck can't use, sent only when nothing else matched. */
    @Message("catalogue")
    record CataloguePage(int request, List<CatalogueRow> rows, int total, int offset, int hiddenBySwitch, boolean ranked,
            @Nullable String source) {
    }

    /** The deck open in the editor, or none when the editor is closed. */
    @Message("editor")
    record EditorMessage(@Nullable EditorState state) {
    }

    /** target is storage (the host's decks) or device (a guest's browser), and copyOf names a read-only deck the first change will copy. */
    record EditorState(String name, String check, String format, @Nullable String cardPool, boolean unrestricted,
            String target, @Nullable String copyOf, List<EditorCard> commanders, boolean commanderWanted, String identity,
            List<EditorGroup> main, List<EditorCard> sideboard, List<EditorLand> lands, DeckStats stats,
            @Nullable String verdict, boolean canUndo, @Nullable String landed,
            boolean limited, @Nullable String landSet, List<LandSet> landSets, @Nullable String collection) {
    }

    /** An edition basic lands can come from, for a limited deck's land row. */
    record LandSet(String code, String name) {
    }

    record EditorGroup(String heading, List<EditorCard> cards) {
    }

    /** One name in a section, however many printings it is split over. mv and colors let the browser group it differently. */
    record EditorCard(String name, int count, String image, String cost, int mv, String colors, int printings,
            List<EditorPrinting> split, @Nullable String problem) {
    }

    /** How many copies of a card in one section are of one printing, named by its image key. */
    record EditorPrinting(String key, int count) {
    }

    /** A basic land in the row under the main deck. allowed is false outside the commander's colours. */
    record EditorLand(String name, String letter, int count, boolean allowed) {
    }

    /** What reading a pasted, fetched or dropped list found: a mark per line, the problems with their fixes, and the deck it makes. */
    @Message("importResult")
    record ImportResult(int request, List<ImportLine> lines, List<ImportProblem> problems, ImportSummary summary,
            @Nullable String name, @Nullable Fetched fetched) {
    }

    /** kind is read, problem, ignored, or heading (a section heading or the deck's name). */
    record ImportLine(String kind) {
    }

    /** line counts from 0, and is -1 for a problem no one line has, such as a missing commander. */
    record ImportProblem(int line, String title, String detail, List<ImportFix> fixes) {
    }

    /** kind is use (text is the name to use), leaveOut, commander (text is the card) or other. */
    record ImportFix(String kind, String label, @Nullable String text) {
    }

    record ImportSummary(int cards, int sideboard, int notImported, @Nullable String commander, boolean commanderChosen,
            String colors, @Nullable String verdict, List<EditorGroup> main, List<EditorCard> sideboardCards) {
    }

    /** A list fetched from a site: which site, the link, the text the list was read from, and the format the site gave it. */
    record Fetched(String site, String url, String text, String format) {
    }

    /** An import's name is already a deck's, and the browser asks whether to replace it or keep both. */
    @Message("nameTaken")
    record NameTaken(String name) {
    }

    /** In a collection, value is what exiling or retrieving the card is worth and problem is why it can't be exiled. */
    record CatalogueRow(String name, String image, String cost, int mv, String colors, String type, @Nullable String pt,
            String heading, int inDeck, @Nullable String problem, @Nullable Boolean isNew, @Nullable Integer value) {
    }

    @Message("cardSearch")
    record CardSearch(List<String> names) {
    }

    @Message("printings")
    record Printings(String name, List<Printing> printings) {
    }

    /** One printing of a card: its set's code and name, the year it came out, and why it can't be used, when it can't. */
    record Printing(String name, String edition, String key, String setName, int year, @Nullable String problem) {
    }

    /** A guest's deck changed: the deck as .dck text to keep in its browser, or no text when it was deleted. */
    @Message("deviceDeck")
    record DeviceDeck(String id, @Nullable String text, String format) {
    }

    /** A question outside a match, answered with the indices chosen, where kind is "confirm" (a few buttons) or "choices" (a list). */
    @Message("hostChoice")
    record HostChoice(int id, String kind, @Nullable String title, @Nullable String message, int min, int max, List<String> options) {
    }

    // ---- Match -------------------------------------------------------------------------------------------------

    /** A property set to null in a delta has gone back to its default, and events are in the order the game fired them. */
    @Message("state")
    record StateMessage(boolean full, long seq, int root,
            @Ts("Record<string, TrackedProps>") Map<String, JsonObject> newObjects,
            @Ts("Record<string, TrackedDelta>") Map<String, JsonObject> deltas,
            List<Integer> visible, List<Integer> localPlayers, @Ts("GameEvent[]") List<Record> events) {
    }

    // ---- Game events: what happened, never how to show it ------------------------------------------------------

    /** A zone of one player's, or of nobody's (the stack). */
    record Place(ZoneType zone, @Nullable Ref player) {
    }

    /** No from means the card came into being there, and caster is set for a card put on the stack, which the browser does not have yet. */
    @Event("cardMoved")
    record CardMoved(Ref card, @Nullable Place from, @Nullable Place to, @Nullable Ref caster) {
    }

    @Event("cardDamaged")
    record CardDamaged(Ref card, @Nullable Ref source, int amount) {
    }

    @Event("playerDamaged")
    record PlayerDamaged(Ref player, @Nullable Ref source, int amount, boolean combat) {
    }

    record Attack(Ref attacker, @Nullable Ref defender) {
    }

    @Event("attackersDeclared")
    record AttackersDeclared(Ref player, List<Attack> attacks) {
    }

    @Event("shuffled")
    record Shuffled(Ref player) {
    }

    /** A game began. Who takes the first turn is settled before anyone looks at a hand, so it is said here. */
    @Event("gameStarted")
    record GameStarted(Ref first) {
    }

    static final List<Class<? extends Record>> EVENTS = Wire.marked(ToBrowser.class, Event.class);


    @Message("prompt")
    record Prompt(String message, boolean priority, @Nullable Ref card, PromptButton ok, PromptButton cancel,
            boolean focusOk, boolean paying, List<Ref> selectable, int selectableMin, List<Ref> selectablePlayers,
            List<Integer> highlighted, @Nullable String starterChoice) {
    }

    record PromptButton(String label, boolean enabled) {
    }

    @Message("playable")
    record Playable(List<Ref> cards, List<Ref> autoTap) {
    }

    @Message("zones")
    record Zones(List<ShownZone> show) {
    }

    record ShownZone(Ref player, ZoneType zone) {
    }

    /** Where dev mode's two switches stand for the host's seat. */
    @Message("devState")
    record DevState(boolean unlimitedLands, boolean viewAll) {
    }

    /** The game written out as a game state, for the browser to save as a file. */
    @Message("devDump")
    record DevDump(String text) {
    }

    @Message("controls")
    record Controls(List<PhaseType> myStops, List<PhaseType> otherStops, boolean autoPass, @Nullable String dayTime,
            @Nullable TurnMarker marker, boolean untilEndOfTurn, boolean untilStackEmpty, ServerSettings settings) {
    }

    /** Pass priority until this phase of this side's turn. */
    record TurnMarker(PhaseType phase, boolean mine) {
    }

    /** The Forge preferences the options dialog shares with the desktop client. */
    record ServerSettings(boolean interruptAttackers, boolean interruptOpponentSpell, boolean interruptTargeting,
            boolean interruptTriggers, boolean interruptMassRemoval, boolean autoTapPreview,
            String autoYieldMode, GameLogVerbosity logDetail, String arrows, boolean devMode,
            String highlightColor, int soundVolume, int musicVolume) {
    }

    @Message("log")
    record LogMessage(boolean full, List<LogEntry> entries) {
    }

    record LogEntry(GameLogEntryType type, String message, @Nullable Integer card, @Nullable String imageKey) {
    }

    @Message("detail")
    record Detail(int key, List<CardFace> faces) {
    }

    /** One face of a card as the preview shows it. colors is the face's colour mask, as the Colors property carries it. */
    record CardFace(@Nullable String name, String cost, String type, @Nullable String pt, String text,
            @Nullable String imageKey, int colors, @Nullable String set, @Nullable CardRarity rarity) {
    }

    @Message("playerDetail")
    record PlayerDetail(int key, @Nullable String name, List<String> lines) {
    }

    /** What a right-click on a stack item offers; a field is there only when that choice applies. */
    @Message("stackMenu")
    record StackMenu(int key, @Nullable Boolean autoYield, @Nullable TriggerDecision trigger) {
    }

    @Message("sound")
    record Sound(String name, boolean sync) {
    }

    @Message("flash")
    record Flash() {
    }

    /** The game has ended. score is each player's games won in the match so far, once the engine has counted them. */
    @Message("gameOver")
    record GameOver(List<MatchScore> score) {
    }

    record MatchScore(Ref player, int won) {
    }

    // ---- Requests: questions the game waits on, answered with {t: 'reply', id, value} -------------------------

    /** Which fields are set depends on what it is: a card on the table, one that is not (a split pile, a card face), or a player. */
    record RequestOption(String label, @Nullable Ref card, @Nullable String name, @Nullable String imageKey,
            @Nullable Ref player) {
    }

    enum ChoiceKind { choices, reveal }

    /** A reveal only shows the list, and stackKeys is set when every option is a spell on the stack, so the browser picks it there. */
    @Request
    record ChoicesRequest(ChoiceKind kind, @Nullable String message, int min, int max, List<RequestOption> options,
            List<Integer> selected, @Nullable List<Integer> stackKeys, @Nullable Integer atX, @Nullable Integer atY,
            @Name("default") List<Integer> defaultAnswer) {
    }

    record OrderAnswer(List<Integer> indices, boolean remember) {
    }

    @Request("order")
    record OrderRequest(@Nullable String title, @Nullable String top, int min, int max, List<RequestOption> options,
            List<Integer> selected, boolean remember, @Nullable Ref card, @Name("default") OrderAnswer defaultAnswer) {
    }

    /** Scry and friends: the whole library, with only the top cards movable. */
    @Request("manipulate")
    record ManipulateRequest(@Nullable String title, List<RequestOption> options, List<Integer> movable, boolean toTop,
            boolean toBottom, @Name("default") List<Integer> defaultAnswer) {
    }

    /** A row of buttons; the answer is the index of the one pressed. */
    @Request("option")
    record OptionRequest(@Nullable String title, @Nullable String message, @Nullable Ref card, List<String> labels,
            @Name("default") int defaultAnswer) {
    }

    @Request("text")
    record TextRequest(@Nullable String title, @Nullable String message, @Nullable String initial, boolean numeric,
            @Name("default") @Nullable String defaultAnswer) {
    }

    /** Divide an amount among the options; null skips when maySkip allows it. */
    @Request("distribute")
    record DistributeRequest(@Nullable String message, int amount, int perMin, List<RequestOption> options, @Nullable Ref card,
            boolean maySkip, @Name("default") List<Integer> defaultAnswer) {
    }

    record SideboardEntry(String name, String imageKey, int total) {
    }

    /** Between games: how many copies of each card go in the main deck. */
    @Request("sideboard")
    record SideboardRequest(@Nullable String message, List<SideboardEntry> entries, List<Integer> main,
            @Name("default") List<Integer> defaultAnswer) {
    }

    /** Priority is about to pass by itself after at least delay, and the answer is whether to go ahead. */
    @Request("autoPass")
    record AutoPassRequest(int delay, @Name("default") boolean defaultAnswer) {
    }

    /** What the sealed setup form can offer, as desktop's sealed dialogs list it. */
    @Message("limitedOptions")
    record LimitedOptions(List<SealedBlock> blocks, List<SealedBlock> fantasyBlocks, List<LimitedEdition> prereleases,
            List<String> templates, List<DraftBlockOption> draftBlocks, List<DraftBlockOption> draftFantasyBlocks, List<String> cubes,
            List<String> themes, @Nullable String lastCube) {
    }

    /** A draftable block, which has no combos when each pack's set is chosen, and whose podSize is what a draft of it starts at. */
    record DraftBlockOption(String name, int packs, List<String> sets, List<String> combos, int podSize) {
    }

    /** A block's sealed product: how many packs, and the set combinations desktop offers for them. */
    record SealedBlock(String name, int packs, List<String> combos) {
    }

    record LimitedEdition(String code, String name) {
    }

    /** The saved offline pools. */
    @Message("limitedPools")
    record LimitedPools(List<PoolRow> sealed, List<PoolRow> draft) {
    }

    /** One saved pool, where changed is the day it was last saved or played, as yyyy-MM-dd. */
    record PoolRow(String name, boolean built, int deckSize, String colors, @Nullable String changed, List<Opponent> opponents) {
    }

    record Opponent(String name, String colors) {
    }

    /** pick counts from 1 within the pack, and direction is 1 while packs go to the next seat and -1 while they go to the previous one. */
    @Message("draft")
    record DraftState(int step, String product, int pack, int packs, int pick, int packSize, int direction, List<DraftSeat> seats,
            List<DraftCard> cards, List<DraftCard> picks, List<Integer> moved, int clockSeconds, int clockLeftMillis,
            List<String> log, boolean done) {
    }

    /** A seat in pass order, seat 0 being the player: how many packs it holds, and whether its player has gone away. */
    record DraftSeat(String name, boolean ai, int packs, boolean held) {
    }

    /** rank is desktop's draft score to 99, higher being better, and text is the rules text drawn when there is no picture. */
    record DraftCard(String name, String image, String cost, int mv, String colors, String type, String text, @Nullable String pt,
            String rarity, @Nullable Integer rank, int pack, int pick, boolean sideboard) {
    }

    /** A gauntlet game's result, where nextRound says the match was won with rounds still to play. */
    @Message("limitedResult")
    record LimitedResult(int round, int rounds, int wins, int losses, boolean matchOver, boolean nextRound) {
    }

    // ---- Campaigns: what Planar Conquest and any mode like it share ---------------------------------------------

    /** One of a campaign's currencies: its skin icon's name, how much the player has, and its name for a reader who cannot see the icon. */
    record Balance(String icon, int amount, String label) {
    }

    /** What the bar over every page of a campaign shows: the save's name, a line under it, and its balances. */
    @Message("campaignBar")
    record CampaignBar(String name, String line, List<Balance> balances) {
    }

    // ---- Planar Conquest ---------------------------------------------------------------------------------------

    /** The saved conquests, and the one played last. */
    @Message("conquestSaves")
    record ConquestSaves(List<ConquestSave> saves, @Nullable String current) {
    }

    /** conquered and total count the events of the save's plane, and saved is the day its file last changed, as yyyy-MM-dd. */
    record ConquestSave(String name, String planeswalker, String walkerImage, String plane, String art, int conquered, int total, int cards,
            int shards, int emblems, @Nullable String saved) {
    }

    record ConquestPlaneOption(String name, String art, String description, int events) {
    }

    /** A commander or a planeswalker a new conquest may start with. region is the commander's own. */
    record ConquestCardOption(String name, String image, String colors, @Nullable String region) {
    }

    /** plane and commander are those asked about, so an answer can be told from an older one. */
    @Message("conquestOptions")
    record ConquestOptions(List<ConquestPlaneOption> planes, @Nullable List<ConquestCardOption> commanders,
            @Nullable List<ConquestCardOption> planeswalkers, int startShards, @Nullable String plane, @Nullable String commander) {
    }

    record ConquestPlace(int region, int row, int col) {
    }

    /** A region of the plane: art is its art card's image key. */
    record ConquestRegionRow(String name, String art, String colors, int conquered, int total) {
    }

    /** state is won, open (it can be fought) or fog, and a fogged cell carries nothing else. */
    record ConquestCell(int region, int row, int col, String state, @Nullable String name, @Nullable String opponent,
            @Nullable String avatar, @Nullable List<String> variants, int wins, int losses, @Nullable String opens) {
    }

    /** The selected commander: problem is why its deck cannot be played, when it cannot. */
    record ConquestLead(String name, String image, int deckSize, @Nullable String problem) {
    }

    /** steps is how far the selection is from where the player stands, and path is empty in every map but the one that answers a move. */
    @Message("conquestState")
    record ConquestState(String plane, int rows, int cols, List<ConquestRegionRow> regions, List<ConquestCell> cells,
            ConquestPlace at, String walker, String walkerImage, ConquestPlace selected, int steps, List<ConquestPlace> path,
            ConquestLead commander) {
    }

    /** A commander as the Commanders page lists it. problem is why its deck cannot be played. */
    record ConquestCommanderRow(String name, String image, String colors, String origin, int wins, int losses, int deckSize,
            @Nullable String problem, boolean selected) {
    }

    record ConquestWalkerRow(String name, String image, String colors, boolean selected) {
    }

    @Message("conquestParty")
    record ConquestParty(List<ConquestCommanderRow> commanders, List<ConquestWalkerRow> planeswalkers) {
    }

    /** The Collection page's two lists by size, the planes its filter offers, and mobile's line on what exile pays. */
    @Message("conquestCollection")
    record ConquestCollection(int collection, int exiled, List<String> planes, String note) {
    }

    /** A battle's game ended. matchOver is false between the games of a chaos battle. */
    @Message("conquestResult")
    record ConquestResult(boolean won, boolean matchOver, boolean chaos, String event, boolean firstConquest) {
    }

    /** An option of one of the Aether's filters. cost is what a pull at that rarity costs. */
    record ConquestOption(String key, String label, @Nullable Integer cost) {
    }

    /** strict counts the cards of exactly the rarity asked for, without which nothing can be pulled, and cost is 0 when nothing matches. */
    @Message("conquestAether")
    record ConquestAetherState(int locked, int matching, int strict, List<Integer> byRarity, int cost, List<ConquestOption> types,
            List<ConquestOption> rarities, List<ConquestOption> cmcs, String colors, String type, String rarity, String cmc,
            String commanderColors, @Nullable ConquestPackCard pulled, @Nullable String problem) {
    }

    record ConquestPlaneRow(String name, String art, String description, boolean unlocked, boolean current, int conquered,
            int events, List<String> regions) {
    }

    /** Every plane that can be reached, with what the next unlock costs and the emblems held. */
    @Message("conquestPlanes")
    record ConquestPlanes(List<ConquestPlaneRow> planes, int unlockCost, int emblems) {
    }

    /** A figure of the statistics: its amount, and what it is out of when it is a share. */
    record ConquestFigure(String label, int amount, @Nullable Integer of) {
    }

    record ConquestRegionStat(String name, int conquered, int events, int wins, int losses) {
    }

    record ConquestCommanderStat(String name, int wins, int losses) {
    }

    /** plane is the one the figures are for, null for all of them. planes are those that can be asked for. */
    @Message("conquestStats")
    record ConquestStats(List<ConquestFigure> figures, List<String> planes, @Nullable String plane,
            List<ConquestRegionStat> regions, List<ConquestCommanderStat> commanders) {
    }

    record ConquestPrefRow(String key, String label, String group, int value) {
    }

    /** Conquest's preferences, which every conquest shares. problem is why the last change was refused. */
    @Message("conquestPrefs")
    record ConquestPrefs(List<ConquestPrefRow> rows, @Nullable String problem) {
    }

    /** A card of a booster: shards is what a duplicate became, 0 for a card that is new. */
    record ConquestPackCard(String name, String image, String rarity, int shards) {
    }

    /** kind is a ConquestRewardStep.Kind name, and a chaos booster has no art. */
    record ConquestStep(String kind, int amount, @Nullable String outcome, @Nullable List<ConquestPackCard> cards,
            int number, int total, boolean chaos, @Nullable String pack, @Nullable String art) {
    }

    /** What a won battle gave, already in the save, for the browser to reveal. */
    @Message("conquestReward")
    record ConquestReward(List<ConquestStep> steps) {
    }

    /** Every message record, which is what the TypeScript is generated from. */
    static final List<Class<? extends Record>> MESSAGES = Wire.marked(ToBrowser.class, Message.class);



    static final List<Class<? extends Record>> REQUESTS = Wire.marked(ToBrowser.class, Request.class);

    /** The answer a request takes when nobody gives one, read back off its JSON. */
    static JsonElement defaultOf(final JsonObject request) {
        return request.has("default") ? request.get("default") : JsonNull.INSTANCE;
    }
}
