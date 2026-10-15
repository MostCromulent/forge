package forge.web.protocol;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import forge.card.CardRarity;
import forge.deck.DeckSection;
import forge.game.GameLogEntryType;
import forge.game.GameLogVerbosity;
import forge.game.phase.PhaseType;
import forge.game.zone.ZoneType;
import forge.gamemodes.net.DeltaPacket;
import forge.player.AutoYieldStore.TriggerDecision;
import forge.web.protocol.Wire.Event;
import forge.web.protocol.Wire.Message;
import forge.web.protocol.Wire.Name;
import forge.web.protocol.Wire.Nullable;
import forge.web.protocol.Wire.Request;
import forge.web.protocol.Wire.Ts;

import java.util.List;
import java.util.Map;

/** Every message the server sends the browser, as records. The build generates src/main/ts/protocol.gen.ts from this file, so a changed record fails the build where the browser reads it. */
public final class ToBrowser {
    private ToBrowser() {
    }

    /** A pointer to an object in the game's table, by its delta key. */
    public record Ref(int ref) {
        public static Ref card(final int id) {
            return new Ref(DeltaPacket.makeDeltaKey(DeltaPacket.TYPE_CARD_VIEW, id));
        }

        public static Ref player(final int id) {
            return new Ref(DeltaPacket.makeDeltaKey(DeltaPacket.TYPE_PLAYER_VIEW, id));
        }
    }

    @Message("hello")
    public record Hello(boolean inMatch, boolean inLobby, boolean joining, boolean spectating, boolean host,
            boolean canClaimHost, boolean networked, @Nullable String playerName, List<Integer> avatars, List<Integer> sleeves, int avatarCount,
            int sleeveCount, List<SavedSleeveArt> sleeveArt, boolean inEvent, @Nullable String eventPool, int sealedPools,
            @Nullable String eventKind, boolean drafting, int draftPools, @Nullable String campaign, @Nullable String campaignSave,
            @Nullable String currentConquest, @Nullable String currentQuest, @Nullable String version, ServerSettings settings) {
    }

    public record SavedSleeveArt(String key, int offset) {
    }

    /** Everyone on this server who has named themselves, for the panel that says who is here. */
    @Message("presence")
    public record Presence(List<Person> people) {
    }

    /** What a person is doing: waiting, joining, at a table, playing or watching one. disconnected is a guest whose browser has gone while it holds a seat. */
    public record Person(String name, int avatar, String doing, boolean host, boolean disconnected) {
    }

    @Message("error")
    public record ErrorMessage(String message) {
    }

    @Message("notice")
    public record Notice(@Nullable String title, @Nullable String message, boolean error) {
    }

    /** The auto-yields and trigger answers the player has set, and whether either kind is switched off. */
    @Message("autoDecisions")
    public record AutoDecisions(List<AutoDecision> entries, boolean yieldsOff, boolean triggersOff) {
    }

    /** One remembered decision: always yield to it, or always accept or decline the trigger. */
    public record AutoDecision(String key, AutoDecisionKind kind) {
    }

    public enum AutoDecisionKind { yield, accept, decline }

    /** A draw offer while it is open: who made it, and whether this player still has to answer. Closed, it is absent. */
    @Message("drawOffer")
    public record DrawOffer(@Nullable Ref offerer, boolean open, boolean mine, boolean waitingOnMe) {
    }

    @Message("decks")
    public record Decks(List<DeckSummary> decks, List<String> cardFormats, @Nullable String cardPool, @Nullable DeckGenOptions deckGen) {
    }

    /** How the host's generators build their decks, as desktop's lobby sets it; sent to the host only, whose preferences they are. */
    public record DeckGenOptions(boolean singletons, boolean noArtifacts) {
    }

    /**
     * A deck in the finder. A generator's entry is only a name until it is picked, so it has no counts.
     * aiSkips counts the main deck's cards the computer will not play, and is left out when there are none.
     */
    public record DeckSummary(String key, String name, String source, String colors, @Nullable Boolean generated,
            @Nullable String note, @Nullable Integer main, @Nullable Integer sideboard, @Nullable String problem,
            @Nullable List<String> legalIn, @Nullable String formats, @Nullable String sleeveArt,
            @Nullable Integer sleeveOffset, @Nullable Boolean readOnly, @Nullable String linked, @Nullable String sourceUrl,
            @Nullable Long synced, @Nullable Integer bracket, @Nullable Integer averageMana, @Nullable Boolean favourite,
            @Nullable Integer aiSkips) {
    }

    /** The keys of the listed decks a card or set filter lets through, for the query it answers. */
    @Message("deckMatches")
    public record DeckMatches(String kind, String value, List<String> keys) {
    }

    @Message("deckDetails")
    public record DeckDetailsMessage(DeckDetails deck) {
    }

    /** aiSkips names the main deck's cards the computer will not play, and is left out when it plays them all. */
    public record DeckDetails(String key, String name, @Nullable String problem, String colors, DeckStats stats,
            List<EditorGroup> main, List<EditorCard> sideboard, @Nullable String sleeveArt, int sleeveOffset,
            @Nullable Bracket bracket, @Nullable List<String> aiSkips) {
        public DeckDetails(final String key, final String name, final String problem, final String colors, final DeckStats stats,
                final List<EditorGroup> main, final List<EditorCard> sideboard, final String sleeveArt, final int sleeveOffset,
                final Bracket bracket) {
            this(key, name, problem, colors, stats, main, sideboard, sleeveArt, sleeveOffset, bracket, null);
        }
    }

    /** A Commander deck's suggested minimum bracket, where clear names the kinds of card the deck has none of. */
    public record Bracket(int level, List<BracketReason> reasons, List<String> clear) {
    }

    /** One kind of card that bears on the bracket, where raises is the bracket it makes the deck, or 0 when it raises nothing on its own. */
    public record BracketReason(String kind, String title, String brief, List<String> cards, int raises, @Nullable String why) {
    }

    /** total counts the commanders with the main deck, and the last curve entry holds its mana value and above. */
    /** colours counts the main deck's spells as W, U, B, R, G, several colours and none; symbols counts the coloured mana symbols in their costs as W, U, B, R, G. */
    public record DeckStats(int total, int main, int sideboard, int lands, float averageMana, List<Integer> curve, List<Integer> creatures,
            List<TypeCount> types, List<Integer> colours, List<Integer> symbols) {
    }

    public record TypeCount(String name, int count) {
    }

    /** Match setup. No table means no lobby is open. */
    @Message("lobby")
    public record LobbyMessage(@Nullable LobbyTable table) {
    }

    public record LobbyTable(boolean host, int mySeat, boolean shareable, String format, List<Format> formats,
            @Nullable String cardPool, List<Format> casualVariants, List<String> variantsOn,
            int maxSeats, int gamesPerMatch, List<Seat> seats, List<String> problems, boolean canStart,
            List<String> illegalDecks, boolean legalityEnforced, @Nullable LimitedTable limited, int maxBracket,
            List<String> overBracket, int teams, List<Integer> teamChoices, List<String> aiProfiles) {
    }

    /** A Limited table's event, where kind is "draft" or "sealed" and phase is an EventPhase name. */
    public record LimitedTable(String kind, @Nullable String product, int podSize, @Nullable String pickRule, int timer,
            @Nullable String phase, @Nullable String activeEventId, boolean eventDecksOnly, boolean started,
            List<PastEvent> pastEvents) {
    }

    /** An earlier event whose pools the host keeps: "draft" or "sealed", its product, and when it was played (yyyy-MM-dd HH:mm). */
    public record PastEvent(String id, String kind, String product, String date) {
    }

    /** Card pools the card pool control offers, under the heading Forge files them by. Sent once per browser. */
    @Message("cardPools")
    public record CardPools(List<CardPoolGroup> groups) {
    }

    /** Every edition, newest first, for the catalogue's set filter. Asked for the first time that filter opens. */
    @Message("cardSets")
    public record CardSets(List<LimitedEdition> sets) {
    }

    /** What the card pool picker shows beyond the names: where each format's cards come from. */
    @Message("cardPoolDetails")
    public record CardPoolDetails(List<CardPoolLine> lines) {
    }

    /** A format and where its cards come from: "Every set", "Commons only", or the set it starts at. */
    public record CardPoolLine(String name, String line) {
    }

    public record CardPoolGroup(String name, List<String> formats) {
    }

    /** A format as the lobby offers it: its group, what it is, its deck and life at a glance, and what changes in a match. */
    public record Format(String id, String name, String group, String desc, List<String> facts, String play) {
    }

    /** type is a netplay lobby slot's (LOCAL, AI, OPEN or REMOTE), sittingOut says the seat sits the next match out,
     *  and aiProfile is how a computer seat plays. */
    public record Seat(@Nullable String name, String type, boolean mine, boolean mayEdit, boolean ready, int avatar,
            int sleeve, @Nullable String deck, @Nullable String deckName, int deckSize, String colors,
            @Nullable String problem, @Nullable String sleeveArt, int sleeveOffset, @Nullable String role,
            @Nullable SeatExtra planes, @Nullable SeatExtra schemes, @Nullable SeatExtra vanguard, boolean sittingOut,
            @Nullable String commander, @Nullable Integer bracket, @Nullable Integer team, List<CommanderChoice> commanderChoices,
            @Nullable String aiProfile) {
    }

    /** Who else can lead a seat's deck, as desktop's lobby offers it: the leader's name (two joined by +), the first one's picture,
     *  whether it is the deck's own, one its file suggests or another in it, and whether it leads now. */
    public record CommanderChoice(String name, String image, String kind, boolean chosen) {
    }

    /** A planar deck, scheme deck or avatar a seat brings: its name, its size, a detail such as modifiers, a fault. */
    public record SeatExtra(String label, int count, @Nullable String detail, @Nullable String problem) {
    }

    /** What a seat may choose for one extra section. An avatar carries its image and its two modifiers. */
    @Message("extraChoices")
    public record ExtraChoices(String section, int index, List<ExtraChoice> choices) {
    }

    public record ExtraChoice(String key, String label, @Nullable Integer count, @Nullable String problem,
            @Nullable String image, @Nullable Integer hand, @Nullable Integer life) {
    }

    @Message("addresses")
    public record Addresses(List<Address> list) {
    }

    public record Address(String label, String url) {
    }

    /** A line with no sender is one of netplay's own announcements, and earlier marks a line replayed from before this browser arrived. */
    @Message("chat")
    public record ChatLine(@Nullable String from, String text, boolean earlier) {
    }

    /** hiddenBySwitch counts the matching cards the deck can't use, sent only when nothing else matched. */
    @Message("catalogue")
    public record CataloguePage(int request, List<CatalogueRow> rows, int total, int offset, int hiddenBySwitch, boolean ranked,
            @Nullable String source) {
        /** The same page, saying which of a mode's lists it is of, so the browser never shows one list's cards as another's. */
        public CataloguePage from(final String list) {
            return new CataloguePage(request, rows, total, offset, hiddenBySwitch, ranked, list);
        }
    }

    /** The deck open in the editor, or none when the editor is closed. */
    @Message("editor")
    public record EditorMessage(@Nullable EditorState state) {
    }

    /**
     * target is storage (the host's decks) or device (a guest's browser), and copyOf names a read-only deck the first change will copy.
     * commanderZone says the format has a commander, and copyLimit is its most copies of a card other than a basic land, null for no limit.
     */
    public record EditorState(String name, String check, String format, @Nullable String cardPool, boolean unrestricted,
            String target, @Nullable String copyOf, List<EditorCard> commanders, boolean commanderZone, boolean commanderWanted,
            @Nullable Integer copyLimit, String identity,
            List<EditorGroup> main, List<EditorCard> sideboard, List<EditorLand> lands, DeckStats stats,
            @Nullable String verdict, boolean canUndo, @Nullable String landed,
            boolean limited, @Nullable String landSet, List<LandSet> landSets, @Nullable String collection, boolean mainOnly,
            List<EditorSection> sections) {
    }

    /** A section beyond the main deck, sideboard and commander, such as planes or schemes, which a deck may carry. */
    public record EditorSection(DeckSection section, String name, List<EditorCard> cards) {
    }

    /** An edition basic lands can come from, for a limited deck's land row. */
    public record LandSet(String code, String name) {
    }

    public record EditorGroup(String heading, List<EditorCard> cards) {
    }

    /** One name in a section, however many printings it is split over. mv and colors let the browser group it differently. */
    public record EditorCard(String name, int count, String image, String cost, int mv, String colors, int printings,
            List<EditorPrinting> split, @Nullable String problem, boolean foil) {
    }

    /** How many copies of a card in one section are of one printing, named by its image key. */
    public record EditorPrinting(String key, int count) {
    }

    /** A basic land in the row under the main deck. allowed is false outside the commander's colours. */
    public record EditorLand(String name, String letter, int count, boolean allowed) {
    }

    /** What reading a pasted, fetched or dropped list found: a mark per line, the problems with their fixes, and the deck it makes. */
    @Message("importResult")
    public record ImportResult(int request, List<ImportLine> lines, List<ImportProblem> problems, ImportSummary summary,
            @Nullable String name, @Nullable Fetched fetched) {
    }

    /** kind is read, problem, ignored, or heading (a section heading or the deck's name). */
    public record ImportLine(String kind) {
    }

    /** line counts from 0, and is -1 for a problem no one line has, such as a missing commander. */
    public record ImportProblem(int line, String title, String detail, List<ImportFix> fixes) {
    }

    /** kind is use (text is the name to use), leaveOut, commander (text is the card) or other. */
    public record ImportFix(String kind, String label, @Nullable String text) {
    }

    public record ImportSummary(int cards, int sideboard, int notImported, @Nullable String commander, boolean commanderChosen,
            String colors, @Nullable String verdict, List<EditorGroup> main, List<EditorCard> sideboardCards) {
    }

    /** A list fetched from a site: which site, the link, the text the list was read from, and the format the site gave it. */
    public record Fetched(String site, String url, String text, String format) {
    }

    /** An import's name is already a deck's, and the browser asks whether to replace it or keep both. */
    @Message("nameTaken")
    public record NameTaken(String name) {
    }

    /** In a collection, value is what exiling or retrieving the card is worth and problem is why it can't be exiled. */
    public record CatalogueRow(String name, String image, String cost, int mv, String colors, String type, @Nullable String pt,
            String heading, int inDeck, @Nullable String problem, @Nullable Boolean isNew, @Nullable Integer value, @Nullable Integer count,
            @Nullable Integer owned, @Nullable String note, @Nullable String key) {
    }

    @Message("cardSearch")
    public record CardSearch(List<String> names) {
    }

    @Message("printings")
    public record Printings(String name, List<Printing> printings) {
    }

    /** One printing of a card: its set's code and name, the year it came out, the part of the set it is in (cards, borderless, showcase and so on), and why it can't be used, when it can't. */
    public record Printing(String name, String edition, String key, String setName, int year, @Nullable String style, @Nullable String problem) {
    }

    /** A guest's deck changed: the deck as .dck text to keep in its browser, or no text when it was deleted. */
    @Message("deviceDeck")
    public record DeviceDeck(String id, @Nullable String text, String format) {
    }

    /** The deck open in the editor as .dck text, for the browser to save as a file. */
    @Message("deckFileText")
    public record DeckFileText(String name, String text) {
    }

    /** A question outside a match, answered with the indices chosen, where kind is "confirm" (a few buttons) or "choices" (a list). */
    @Message("hostChoice")
    public record HostChoice(int id, String kind, @Nullable String title, @Nullable String message, int min, int max, List<String> options,
            @Nullable List<String> images, @Nullable String pictured) {
    }

    /** A property set to null in a delta has gone back to its default, and events are in the order the game fired them. */
    @Message("state")
    public record StateMessage(boolean full, long seq, int root,
            @Ts("Record<string, TrackedProps>") Map<String, JsonObject> newObjects,
            @Ts("Record<string, TrackedDelta>") Map<String, JsonObject> deltas,
            List<Integer> visible, List<Integer> localPlayers, @Ts("GameEvent[]") List<Record> events) {
    }

    // Game events say what happened, never how to show it

    /** A zone of one player's, or of nobody's (the stack). */
    public record Place(ZoneType zone, @Nullable Ref player) {
    }

    /** No from means the card came into being there, and caster is set for a card put on the stack, which the browser does not have yet. */
    @Event("cardMoved")
    public record CardMoved(Ref card, @Nullable Place from, @Nullable Place to, @Nullable Ref caster) {
    }

    @Event("cardDamaged")
    public record CardDamaged(Ref card, @Nullable Ref source, int amount) {
    }

    @Event("playerDamaged")
    public record PlayerDamaged(Ref player, @Nullable Ref source, int amount, boolean combat) {
    }

    public record Attack(Ref attacker, @Nullable Ref defender) {
    }

    @Event("attackersDeclared")
    public record AttackersDeclared(Ref player, List<Attack> attacks) {
    }

    public record Block(Ref blocker, Ref attacker) {
    }

    /** The blocks one defending player made, which is none when that player let every attacker through. */
    @Event("blockersDeclared")
    public record BlockersDeclared(Ref player, List<Block> blocks) {
    }

    /** A spell or an ability went onto the stack. */
    @Event("stackAdded")
    public record StackAdded(@Nullable Ref player) {
    }

    /** The card a spell or an ability came from, and whether it did nothing because its targets had gone. */
    @Event("stackResolved")
    public record StackResolved(@Nullable Ref player, @Nullable Ref source, boolean fizzled) {
    }

    /** Life gained or lost in any way, damage included. */
    @Event("lifeChanged")
    public record LifeChanged(Ref player, int from, int to) {
    }

    @Event("playerCounters")
    public record PlayerCounters(Ref player) {
    }

    @Event("cardCounters")
    public record CardCounters(Ref card) {
    }

    /** No to means the card came off what it was attached to. */
    @Event("cardAttached")
    public record CardAttached(Ref card, @Nullable Ref to) {
    }

    @Event("shuffled")
    public record Shuffled(Ref player) {
    }

    /** A game began. Who takes the first turn is settled before anyone looks at a hand, so it is said here. */
    @Event("gameStarted")
    public record GameStarted(Ref first) {
    }

    /** The toss before a match's first game, which the player it names won. */
    @Event("coinTossed")
    public record CoinTossed(Ref winner) {
    }

    static final List<Class<? extends Record>> EVENTS = Wire.marked(ToBrowser.class, Event.class);


    @Message("prompt")
    public record Prompt(String message, boolean priority, @Nullable Ref card, PromptButton ok, PromptButton cancel,
            boolean focusOk, boolean paying, List<Ref> selectable, int selectableMin, List<Ref> selectablePlayers,
            List<Integer> selectableStack, List<Integer> highlighted, @Nullable String starterChoice) {
    }

    public record PromptButton(String label, boolean enabled) {
    }

    @Message("playable")
    public record Playable(List<Ref> cards, List<Ref> autoTap) {
    }

    @Message("zones")
    public record Zones(List<ShownZone> show) {
    }

    public record ShownZone(Ref player, ZoneType zone) {
    }

    /** Where dev mode's two switches stand for the host's seat. */
    @Message("devState")
    public record DevState(boolean unlimitedLands, boolean viewAll) {
    }

    /** The game written out as a game state, for the browser to save as a file. */
    @Message("devDump")
    public record DevDump(String text) {
    }

    @Message("controls")
    public record Controls(List<PhaseType> myStops, List<PhaseType> otherStops, boolean autoPass, @Nullable String dayTime,
            @Nullable TurnMarker marker, boolean untilEndOfTurn, boolean untilStackEmpty, ServerSettings settings) {
    }

    /** Pass priority until this phase of this side's turn. */
    public record TurnMarker(PhaseType phase, boolean mine) {
    }

    /** The Forge preferences the options dialog shares with the desktop client. */
    public record ServerSettings(boolean interruptAttackers, boolean interruptOpponentSpell, boolean interruptTargeting,
            boolean interruptTriggers, boolean interruptMassRemoval, boolean interruptReveal,
            String suggestStackYield, String suggestNoActions, boolean suppressAfterYield, boolean suppressOwnTurn,
            boolean skipPhaseDelay, boolean skipResolveDelay, boolean autoTapPreview,
            String autoYieldMode, GameLogVerbosity logDetail, String arrows, boolean devMode,
            String highlightColor, int soundVolume, int musicVolume, boolean filteredHands, String mulliganRule,
            String aiProfile, boolean ante) {
    }

    @Message("log")
    public record LogMessage(boolean full, List<LogEntry> entries) {
    }

    public record LogEntry(GameLogEntryType type, String message, @Nullable Integer card, @Nullable String imageKey) {
    }

    @Message("detail")
    public record Detail(int key, List<CardFace> faces) {
    }

    /** One face of a card as the preview shows it. colors is the face's colour mask, as the Colors property carries it. */
    public record CardFace(@Nullable String name, String cost, String type, @Nullable String pt, String text,
            @Nullable String imageKey, int colors, @Nullable String set, @Nullable CardRarity rarity) {
    }

    @Message("playerDetail")
    public record PlayerDetail(int key, @Nullable String name, List<String> lines) {
    }

    /** What a right-click on a stack item offers; a field is there only when that choice applies. */
    @Message("stackMenu")
    public record StackMenu(int key, @Nullable Boolean autoYield, @Nullable TriggerDecision trigger) {
    }

    @Message("sound")
    public record Sound(String name, boolean sync) {
    }

    @Message("flash")
    public record Flash() {
    }

    /** The game has ended. score is each player's games won in the match so far, once the engine has counted them. */
    @Message("gameOver")
    public record GameOver(List<MatchScore> score) {
    }

    public record MatchScore(Ref player, int won) {
    }

    // Requests are questions the game waits on, answered with {t: 'reply', id, value}

    /**
     * Which fields are set depends on what it is: a card on the table, one that is not (a split pile, a card face), or a player.
     * An ability names the card it comes from as its source, and a colour its mana symbol's letter.
     */
    public record RequestOption(String label, @Nullable Ref card, @Nullable String name, @Nullable String imageKey,
            @Nullable Ref player, @Nullable Ref source, @Nullable String symbol) {
        public RequestOption(final String label, final Ref card, final String name, final String imageKey, final Ref player) {
            this(label, card, name, imageKey, player, null, null);
        }
    }

    public enum ChoiceKind { choices, reveal }

    /**
     * A reveal only shows the list, and stackKeys is set when every option is a spell on the stack, so the browser picks it there.
     * aiSkipsOf is set on the reveal that warns a computer will not play some of its deck, and names that player.
     */
    @Request
    public record ChoicesRequest(ChoiceKind kind, @Nullable String message, int min, int max, List<RequestOption> options,
            List<Integer> selected, @Nullable List<Integer> stackKeys, @Nullable Integer atX, @Nullable Integer atY,
            @Name("default") List<Integer> defaultAnswer, @Nullable Ref aiSkipsOf) {
        public ChoicesRequest(final ChoiceKind kind, final String message, final int min, final int max, final List<RequestOption> options,
                final List<Integer> selected, final List<Integer> stackKeys, final Integer atX, final Integer atY,
                final List<Integer> defaultAnswer) {
            this(kind, message, min, max, options, selected, stackKeys, atX, atY, defaultAnswer, null);
        }

        public ChoicesRequest warningOf(final Ref player) {
            return new ChoicesRequest(kind, message, min, max, options, selected, stackKeys, atX, atY, defaultAnswer, player);
        }
    }

    public record OrderAnswer(List<Integer> indices, boolean remember) {
    }

    @Request("order")
    public record OrderRequest(@Nullable String title, @Nullable String top, int min, int max, List<RequestOption> options,
            List<Integer> selected, boolean remember, @Nullable Ref card, @Name("default") OrderAnswer defaultAnswer) {
    }

    /** Scry and friends: the whole library, with only the top cards movable. */
    @Request("manipulate")
    public record ManipulateRequest(@Nullable String title, List<RequestOption> options, List<Integer> movable, boolean toTop,
            boolean toBottom, @Name("default") List<Integer> defaultAnswer) {
    }

    /** A row of buttons; the answer is the index of the one pressed. */
    @Request("option")
    public record OptionRequest(@Nullable String title, @Nullable String message, @Nullable Ref card, List<String> labels,
            @Name("default") int defaultAnswer) {
    }

    @Request("text")
    public record TextRequest(@Nullable String title, @Nullable String message, @Nullable String initial, boolean numeric,
            @Name("default") @Nullable String defaultAnswer) {
    }

    /** A whole number from min up, to max when there is one; null picks none when maySkip allows it. */
    @Request("number")
    public record NumberRequest(@Nullable String message, int min, @Nullable Integer max, boolean maySkip,
            @Name("default") @Nullable Integer defaultAnswer) {
    }

    /** Divide an amount among the options; null skips when maySkip allows it. For combat damage, lethal is what each option takes to destroy it. */
    @Request("distribute")
    public record DistributeRequest(@Nullable String message, int amount, int perMin, List<RequestOption> options, @Nullable Ref card,
            boolean maySkip, @Name("default") List<Integer> defaultAnswer, @Nullable List<Integer> lethal) {
    }

    public record SideboardEntry(String name, String imageKey, int total) {
    }

    /** Between games: how many copies of each card go in the main deck. */
    @Request("sideboard")
    public record SideboardRequest(@Nullable String message, List<SideboardEntry> entries, List<Integer> main,
            @Name("default") List<Integer> defaultAnswer) {
    }

    /** What the sealed setup form can offer, as desktop's sealed dialogs list it. */
    @Message("limitedOptions")
    public record LimitedOptions(List<SealedBlock> blocks, List<SealedBlock> fantasyBlocks, List<LimitedEdition> prereleases,
            List<String> templates, List<DraftBlockOption> draftBlocks, List<DraftBlockOption> draftFantasyBlocks, List<String> cubes,
            List<String> themes, @Nullable String lastCube) {
    }

    /** A draftable block, which has no combos when each pack's set is chosen, and whose podSize is what a draft of it starts at. */
    public record DraftBlockOption(String name, int packs, List<String> sets, List<String> combos, int podSize) {
    }

    /** A block's sealed product: how many packs, and the set combinations desktop offers for them. */
    public record SealedBlock(String name, int packs, List<String> combos) {
    }

    public record LimitedEdition(String code, String name) {
    }

    /** The saved offline pools. */
    @Message("limitedPools")
    public record LimitedPools(List<PoolRow> sealed, List<PoolRow> draft) {
    }

    /** One saved pool, where changed is the day it was last saved or played, as yyyy-MM-dd. */
    public record PoolRow(String name, boolean built, int deckSize, String colors, @Nullable String changed, List<Opponent> opponents) {
    }

    public record Opponent(String name, String colors) {
    }

    /** pick counts from 1 within the pack, and direction is 1 while packs go to the next seat and -1 while they go to the previous one. */
    @Message("draft")
    public record DraftState(int step, String product, int pack, int packs, int pick, int packSize, int direction, List<DraftSeat> seats,
            List<DraftCard> cards, List<DraftCard> picks, List<Integer> moved, int clockSeconds, int clockLeftMillis,
            List<String> log, boolean done) {
    }

    /** A seat in pass order, seat 0 being the player: how many packs it holds, and whether its player has gone away. */
    public record DraftSeat(String name, boolean ai, int packs, boolean held) {
    }

    /** rank is desktop's draft score to 99, higher being better, and text is the rules text drawn when there is no picture. */
    public record DraftCard(String name, String image, String cost, int mv, String colors, String type, String text, @Nullable String pt,
            String rarity, @Nullable Integer rank, int pack, int pick, boolean sideboard) {
    }

    /** A gauntlet game's result, where nextRound says the match was won with rounds still to play. */
    @Message("limitedResult")
    public record LimitedResult(int round, int rounds, int wins, int losses, boolean matchOver, boolean nextRound) {
    }

    /** One of a campaign's currencies: its skin icon's name, how much the player has, and its name for a reader who cannot see the icon. */
    public record Balance(String icon, int amount, String label) {
    }

    /** What the bar over every page of a campaign shows: the save's name, a line under it, and its balances. */
    @Message("campaignBar")
    public record CampaignBar(String name, String line, List<Balance> balances) {
    }

    /** A button of the result screen: action is nextGame, leave, restart, quit, which leaves a match that is not over, or forfeit, a quit the player is asked about first. */
    public record ResultButton(String label, String action, boolean primary) {
    }

    /** A game of a campaign's match ended. line is what the result screen says under its heading, when the mode has something to say. */
    @Message("campaignResult")
    public record CampaignResult(boolean won, boolean matchOver, @Nullable String line, List<ResultButton> buttons) {
    }

    /** A figure of the statistics: its amount, and what it is out of when it is a share. */
    public record Figure(String label, int amount, @Nullable Integer of) {
    }

    /** A table of the statistics, as text. */
    public record StatTable(List<String> headers, List<List<String>> rows) {
    }

    /** scopes are what the figures can be narrowed to and scope the one they are for, null for all of them, which allScopes names. */
    @Message("campaignStats")
    public record CampaignStats(String title, List<Figure> figures, List<StatTable> tables, List<String> scopes, @Nullable String scope,
            @Nullable String allScopes) {
    }

    /** A preference: column, when given, is which of several values of one row it is, as a difficulty's own value is. */
    public record PrefRow(String key, String label, String group, String value, @Nullable String column) {
    }

    /** A mode's preferences, each saved as it is changed. note says whose they are, and problem why the last change was refused. */
    @Message("campaignPrefs")
    public record CampaignPrefs(String title, List<PrefRow> rows, String note, @Nullable String problem, boolean canReset) {
    }

    /** One of a campaign's lists of cards, by the source its cards are asked for, and how many it holds. */
    public record TradeList(String source, int count) {
    }

    /** The lists a campaign trades between, a line on what a trade pays, and what the lists can also be narrowed by, if anything. */
    @Message("trading")
    public record Trading(List<TradeList> lists, String note, @Nullable List<String> groups, @Nullable List<Product> products,
            @Nullable List<CatalogueRow> extras) {
    }

    /** Something for sale that is not a card: a booster, a box, a deck. Its key is "p:" and its name, which a trade's pick names it by. */
    public record Product(String key, String kind, String name, String image, int price) {
    }

    /** The saved conquests, and the one played last. */
    @Message("conquestSaves")
    public record ConquestSaves(List<ConquestSave> saves, @Nullable String current) {
    }

    /** conquered and total count the events of the save's plane, and saved is the day its file last changed, as yyyy-MM-dd. */
    public record ConquestSave(String name, String planeswalker, String walkerImage, String plane, String art, int conquered, int total, int cards,
            int shards, int emblems, @Nullable String saved) {
    }

    public record ConquestPlaneOption(String name, String art, String description, int events) {
    }

    /** A commander or a planeswalker a new conquest may start with. region is the commander's own. */
    public record ConquestCardOption(String name, String image, String colors, @Nullable String region) {
    }

    /** plane and commander are those asked about, so an answer can be told from an older one. */
    @Message("conquestOptions")
    public record ConquestOptions(List<ConquestPlaneOption> planes, @Nullable List<ConquestCardOption> commanders,
            @Nullable List<ConquestCardOption> planeswalkers, int startShards, @Nullable String plane, @Nullable String commander) {
    }

    public record ConquestPlace(int region, int row, int col) {
    }

    /** A region of the plane: art is its art card's image key. */
    public record ConquestRegionRow(String name, String art, String colors, int conquered, int total) {
    }

    /** state is won, open (it can be fought) or fog, and a fogged cell carries nothing else. */
    public record ConquestCell(int region, int row, int col, String state, @Nullable String name, @Nullable String opponent,
            @Nullable String avatar, @Nullable List<String> variants, int wins, int losses, @Nullable String opens) {
    }

    /** The selected commander: problem is why its deck cannot be played, when it cannot. */
    public record ConquestLead(String name, String image, int deckSize, @Nullable String problem) {
    }

    /** steps is how far the selection is from where the player stands, and path is empty in every map but the one that answers a move. */
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

    /** strict counts the cards of exactly the rarity asked for, without which nothing can be pulled, and cost is 0 when nothing matches. */
    @Message("conquestAether")
    public record ConquestAetherState(int locked, int matching, int strict, List<Integer> byRarity, int cost, List<ConquestOption> types,
            List<ConquestOption> rarities, List<ConquestOption> cmcs, String colors, String type, String rarity, String cmc,
            String commanderColors, @Nullable PackCard pulled, @Nullable String problem) {
    }

    public record ConquestPlaneRow(String name, String art, String description, boolean unlocked, boolean current, int conquered,
            int events, List<String> regions) {
    }

    /** Every plane that can be reached, with what the next unlock costs and the emblems held. */
    @Message("conquestPlanes")
    public record ConquestPlanes(List<ConquestPlaneRow> planes, int unlockCost, int emblems) {
    }

    /** A card of a booster: shards is what a duplicate became, 0 for a card that is new. */
    public record PackCard(String name, String image, String rarity, int shards) {
    }

    /** One thing a reward shows: kind is the mode's name for it, icon the balance it pays into; MESSAGE and CARDS bring their own words, and outcome "lost" marks cards lost to ante. */
    public record RewardStep(String kind, int amount, @Nullable String outcome, @Nullable List<PackCard> cards,
            int number, int total, boolean chaos, @Nullable String pack, @Nullable String art, @Nullable String icon,
            @Nullable String title, @Nullable String text, @Nullable List<String> lines) {
    }

    /** What a match gave, already in the save, for the browser to reveal step by step. */
    @Message("reward")
    public record Reward(List<RewardStep> steps) {
    }

    /** A quest on the shelf: its mode, difficulty and rank, its record, cards and credits, the world it is in, and when it was saved. */
    public record QuestSave(String name, String mode, String difficulty, String rank, int wins, int losses, int cards, long credits,
            String world, @Nullable String saved) {
    }

    /** The saved quests, and the one played last. */
    @Message("questSaves")
    public record QuestSaves(List<QuestSave> saves, @Nullable String current) {
    }

    /** A duel on offer: its place in the list, which the browser sends back, and a card of the opponent's deck to show as its face. */
    public record QuestDuelRow(int index, String title, int difficulty, String description, String colors, @Nullable String face, boolean random) {
    }

    /** A pet slot's choice: the owned pets by name, and the one chosen, or null for none. */
    public record QuestPetChoice(int slot, List<String> pets, @Nullable String chosen) {
    }

    /** The Duels page: the duels, the player's record, the line about the next challenge, the pets and the match lengths the charms allow. */
    @Message("questDuels")
    public record QuestDuels(List<QuestDuelRow> duels, int wins, int losses, int streak, int bestStreak, @Nullable String nextChallenge,
            List<QuestPetChoice> pets, List<Integer> matchLengths, int matchLength, String deck, @Nullable String deckProblem, boolean canUnlock) {
    }

    /** A quest deck: its picture, colours and size, why it cannot be played if it cannot, and whether it is the one duels use. */
    public record QuestDeckRow(String name, @Nullable String image, String colors, int size, int sideboard, @Nullable String problem, boolean current) {
    }

    @Message("questDecks")
    public record QuestDecks(List<QuestDeckRow> decks) {
    }

    /** A challenge on offer: its terms as its file sets them, the cards each side starts with in play, and whether the player's deck is its own. */
    public record QuestChallengeRow(String id, String title, int difficulty, String description, @Nullable String face, int aiLife, @Nullable Integer humanLife,
            int credits, List<String> cardReward, boolean repeatable, List<String> humanCards, List<String> aiCards, boolean fixedDeck) {
    }

    /** The Challenges page: the challenges, how many are open of how many the wins allow, the line about the next, and the zeppelin. */
    @Message("questChallenges")
    public record QuestChallenges(List<QuestChallengeRow> challenges, int open, int max, @Nullable String nextIn, boolean zeppelin, boolean zeppelinUsed) {
    }

    /** A stall of the bazaar: its name, which the browser sends back, the merchant's name, a line about it, and its skin icon. */
    public record QuestStallRow(String name, String displayName, String fluff, String icon) {
    }

    /** Something for sale at a stall: a skin icon or a card to picture it, its price, its level of its most, and for a pet what it is now and what the next level gives. */
    public record QuestItemRow(String name, String description, @Nullable String icon, @Nullable String card, int price, int level, int maxLevel,
            @Nullable String now, @Nullable String next) {
    }

    /** The bazaar: its stalls, the one open, and what that stall sells. */
    @Message("questBazaar")
    public record QuestBazaar(List<QuestStallRow> stalls, @Nullable String stall, List<QuestItemRow> items) {
    }

    /** A tournament on offer: its title, which the browser sends back, the packs drafted by set name with each pack's picture, and its fee. */
    public record QuestTournamentRow(String title, List<String> packs, List<String> packImages, int fee, boolean affordable) {
    }

    /** A place in the bracket: who holds it (null while undecided, empty for the player), whether it is the player, and whether it went on to the next round (won), lost there (out) or is still to be played (open). */
    public record QuestSeat(@Nullable String name, boolean you, String state) {
    }

    /** The tournament entered: its rounds from the first eight seats to the winner, the player's next opponent while the player has a match left (empty when not yet known), the player's placing, and whether its draft was finished into a pool. */
    public record QuestBracket(String title, List<List<QuestSeat>> rounds, @Nullable String next, String placing, boolean started, boolean drafted) {
    }

    /** The Tournaments page: those on offer while none is entered, the tokens to make one, the past placings from first to fourth, and the one entered. */
    @Message("questTournaments")
    public record QuestTournaments(List<QuestTournamentRow> offered, int tokens, List<Integer> placings, @Nullable QuestBracket bracket) {
    }

    /** A difficulty and what a quest started at it is given: credits, and the starting pool's commons, uncommons and rares. */
    public record QuestDifficultyRow(String name, int credits, int commons, int uncommons, int rares) {
    }

    /** What the new-quest form offers, each list by name. */
    @Message("questOptions")
    public record QuestOptions(List<String> worlds, List<String> formatWorlds, List<String> sanctioned, List<String> casual, List<String> precons,
            List<String> sealedDecks, List<String> draftDecks, List<String> cubes, List<QuestDifficultyRow> difficulties) {
    }

    /** Every message record, which is what the TypeScript is generated from. */
    static final List<Class<? extends Record>> MESSAGES = Wire.marked(ToBrowser.class, Message.class);



    static final List<Class<? extends Record>> REQUESTS = Wire.marked(ToBrowser.class, Request.class);

    /** The answer a request takes when nobody gives one, read back off its JSON. */
    public static JsonElement defaultOf(final JsonObject request) {
        return request.has("default") ? request.get("default") : JsonNull.INSTANCE;
    }
}
