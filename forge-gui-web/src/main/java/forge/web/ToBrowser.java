package forge.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
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

/**
 * Every message the server sends the browser, and the shapes inside them. src/main/ts/protocol.gen.ts is
 * generated from this file: change a record here and regenerate (see ProtocolTypesTest).
 */
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
            @Nullable String eventKind, boolean drafting, int draftPools) {
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

    /** Cards put before the player without holding the game up, such as those the AI plays poorly; a notice offers them. */
    @Message("aside")
    record Aside(String title, List<RequestOption> cards) {
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
            @Nullable Long synced) {
    }

    @Message("deckDetails")
    record DeckDetailsMessage(DeckDetails deck) {
    }

    record DeckDetails(String key, String name, @Nullable String problem, String colors, DeckStats stats,
            List<EditorGroup> main, List<EditorCard> sideboard, @Nullable String sleeveArt, int sleeveOffset) {
    }

    record DeckStats(int main, int sideboard, int lands, float averageMana, List<Integer> curve,
            List<TypeCount> types) {
    }

    record TypeCount(String name, int count) {
    }

    /** Match setup. No table means no lobby is open. */
    @Message("lobby")
    record LobbyMessage(@Nullable LobbyTable table) {
    }

    record LobbyTable(boolean host, int mySeat, boolean shareable, String format, List<Format> formats,
            @Nullable String cardPool, List<CardPoolGroup> cardPools, List<Format> casualVariants, List<String> variantsOn,
            int maxSeats, List<Seat> seats, List<String> problems, boolean canStart) {
    }

    /** Card pools the card pool control offers, under the heading Forge files them by. */
    record CardPoolGroup(String name, List<String> formats) {
    }

    /** A format as the lobby offers it: its group, what it is, its deck and life at a glance, and what changes in a match. */
    record Format(String id, String name, String group, String desc, List<String> facts, String play) {
    }

    /** A seat's type is a netplay lobby slot's: LOCAL, AI, OPEN or REMOTE. */
    record Seat(@Nullable String name, String type, boolean mine, boolean mayEdit, boolean ready, int avatar,
            int sleeve, @Nullable String deck, @Nullable String deckName, int deckSize, String colors,
            @Nullable String problem, @Nullable String sleeveArt, int sleeveOffset, @Nullable String role,
            @Nullable SeatExtra planes, @Nullable SeatExtra schemes, @Nullable SeatExtra vanguard) {
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

    /** A line of lobby or match chat. Netplay's own announcements (a player joining) have no sender. */
    @Message("chat")
    record ChatLine(@Nullable String from, String text) {
    }

    /**
     * A page of the deck editor's catalogue. hiddenBySwitch counts the cards the deck can't use that matched, sent only
     * when nothing else did. ranked says the rows are in best-match order for a name, rather than in the sort asked for.
     */
    @Message("catalogue")
    record CataloguePage(int request, List<CatalogueRow> rows, int total, int offset, int hiddenBySwitch, boolean ranked) {
    }

    /** The deck open in the editor, or none when the editor is closed. */
    @Message("editor")
    record EditorMessage(@Nullable EditorState state) {
    }

    /**
     * Everything the editor shows about its deck. check is the "Check legality against" label; format, cardPool and
     * unrestricted are its parts, for the control. target is storage (the host's decks) or device (a guest's browser).
     * copyOf names a deck that can't be changed in place, until the first change copies it.
     */
    record EditorState(String name, String check, String format, @Nullable String cardPool, boolean unrestricted,
            String target, @Nullable String copyOf, List<EditorCard> commanders, boolean commanderWanted, String identity,
            List<EditorGroup> main, List<EditorCard> sideboard, List<EditorLand> lands, DeckStats stats,
            @Nullable String verdict, int problemCount, boolean canUndo, @Nullable String landed, boolean onSeat,
            boolean limited, @Nullable String landSet, List<LandSet> landSets) {
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

    /** One card in the catalogue: how many the open deck holds, and why it can't be added, when it can't. */
    record CatalogueRow(String name, String image, String cost, int mv, String colors, String type, @Nullable String pt,
            String heading, int inDeck, @Nullable String problem) {
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

    /** A question the host asks outside a match; the browser answers with the indices chosen. */
    @Message("hostChoice")
    record HostChoice(int id, String kind, @Nullable String message, int min, int max, List<String> options) {
    }

    // ---- Match -------------------------------------------------------------------------------------------------

    /** Changes to the game's object table, and what happened in the game to cause them. A property set to null in
     *  a delta has gone back to its default. The events are in the order the game fired them. */
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

    /** A card went from one zone to another. No from means it came into being there (a token, a copy). */
    @Event("cardMoved")
    record CardMoved(Ref card, @Nullable Place from, @Nullable Place to) {
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

    static final List<Class<? extends Record>> EVENTS = List.of(CardMoved.class, CardDamaged.class,
            PlayerDamaged.class, AttackersDeclared.class, Shuffled.class, GameStarted.class);

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

    record CardFace(@Nullable String name, String cost, String type, @Nullable String pt, String text,
            @Nullable String imageKey) {
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

    @Message("gameOver")
    record GameOver() {
    }

    // ---- Requests: questions the game waits on, answered with {t: 'reply', id, value} -------------------------

    /** One thing to choose. Which fields are set depends on what it is: a card on the table, one that is not
     *  (a split pile, a card face), or a player. */
    record RequestOption(String label, @Nullable Ref card, @Nullable String name, @Nullable String imageKey,
            @Nullable Ref player) {
    }

    enum ChoiceKind { choices, reveal }

    /** Pick from a list. A reveal only shows the list. When every option is a spell on the stack, stackKeys
     *  says which, and the browser picks it there instead. */
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

    /**
     * Priority is about to pass by itself, which the browser shows coming on its pass button for as long as the player
     * likes, never less than delay; the answer is whether to go ahead. Stopping it asks for priority as usual.
     */
    @Request("autoPass")
    record AutoPassRequest(int delay, @Name("default") boolean defaultAnswer) {
    }

    /** What the sealed setup form can offer, as desktop's sealed dialogs list it. */
    @Message("limitedOptions")
    record LimitedOptions(List<SealedBlock> blocks, List<SealedBlock> fantasyBlocks, List<LimitedEdition> prereleases,
            List<String> templates, List<DraftBlockOption> draftBlocks, List<DraftBlockOption> draftFantasyBlocks, List<String> cubes,
            List<String> themes, @Nullable String lastCube) {
    }

    /** A draftable block: its sets, and desktop's preset combinations, or none when each pack's set is chosen. */
    record DraftBlockOption(String name, int packs, List<String> sets, List<String> combos) {
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

    /** One saved pool: whether a deck has been built from it, and the opponents its match can be played against. */
    record PoolRow(String name, boolean built, int deckSize, List<Opponent> opponents) {
    }

    record Opponent(String name, String colors) {
    }

    /**
     * An offline draft as the player sees it after a step. pick counts from 1 within the pack; direction is 1 while packs
     * go to the next seat and -1 while they go to the previous one; passed says every pack moved on one seat since the last
     * state; done says the draft is over and waits for a name.
     */
    @Message("draft")
    record DraftState(String product, int pack, int packs, int pick, int packSize, int direction, List<DraftSeat> seats,
            List<DraftCard> cards, List<DraftCard> picks, boolean passed, boolean done) {
    }

    /** A seat in pass order, seat 0 being the player: how many packs it holds, and whether its player has gone away. */
    record DraftSeat(String name, boolean ai, int packs, boolean held) {
    }

    /** A card in the pack or among the picks, with the pack and pick it was drafted at. rank is the draft ranking, when known. */
    record DraftCard(String name, String image, String cost, int mv, String colors, String type, @Nullable String pt,
            String rarity, @Nullable Integer rank, int pack, int pick) {
    }

    /**
     * A gauntlet game's result and where the gauntlet stands, as desktop's limited result shows them. nextRound says the
     * match was won with rounds still to play.
     */
    @Message("limitedResult")
    record LimitedResult(int round, int rounds, int wins, int losses, boolean matchOver, boolean wonMatch, boolean nextRound) {
    }

    /** Every message record, which is what the TypeScript is generated from. */
    static final List<Class<? extends Record>> MESSAGES = List.of(Hello.class, Presence.class, ErrorMessage.class, Notice.class,
            Decks.class, DeckDetailsMessage.class, ExtraChoices.class, LobbyMessage.class, Addresses.class, ChatLine.class,
            CardSearch.class, Printings.class, HostChoice.class, StateMessage.class, Prompt.class, Playable.class,
            Zones.class, Controls.class, DevState.class, DevDump.class, LogMessage.class, Detail.class, PlayerDetail.class, StackMenu.class, Sound.class,
            Flash.class, GameOver.class, DrawOffer.class, AutoDecisions.class, Aside.class, CataloguePage.class, EditorMessage.class,
            ImportResult.class, NameTaken.class, DeviceDeck.class, LimitedOptions.class, LimitedPools.class,
            DraftState.class, LimitedResult.class);

    static final List<Class<? extends Record>> REQUESTS = List.of(ChoicesRequest.class, OrderRequest.class, ManipulateRequest.class,
            OptionRequest.class, TextRequest.class, DistributeRequest.class, SideboardRequest.class, AutoPassRequest.class);

    /** The answer a request takes when nobody gives one, read back off its JSON. */
    static JsonElement defaultOf(final JsonObject request) {
        return request.has("default") ? request.get("default") : JsonNull.INSTANCE;
    }
}
