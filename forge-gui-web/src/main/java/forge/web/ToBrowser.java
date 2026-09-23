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
    record Hello(boolean inMatch, boolean inLobby, boolean spectating, boolean host, boolean canClaimHost,
            boolean networked, @Nullable String playerName, List<Integer> avatars, List<Integer> sleeves, int avatarCount,
            int sleeveCount, List<Playmat> playmats, List<SavedSleeveArt> sleeveArt) {
    }

    record Playmat(String id, String label) {
    }

    record SavedSleeveArt(String key, int offset) {
    }

    @Message("error")
    record ErrorMessage(String message) {
    }

    @Message("notice")
    record Notice(@Nullable String title, @Nullable String message, boolean error) {
    }

    @Message("decks")
    record Decks(List<DeckSummary> decks, List<String> cardFormats) {
    }

    /** A deck in the finder. A generator's entry is only a name until it is picked, so it has no counts. */
    record DeckSummary(String key, String name, String source, String colors, @Nullable Boolean generated,
            @Nullable String note, @Nullable Integer main, @Nullable Integer sideboard, @Nullable String problem,
            @Nullable List<String> legalIn, @Nullable String formats, @Nullable String sleeveArt,
            @Nullable Integer sleeveOffset) {
    }

    @Message("deckDetails")
    record DeckDetailsMessage(DeckDetails deck) {
    }

    record DeckDetails(String key, String name, @Nullable String problem, String colors, DeckStats stats,
            List<DeckGroup> main, List<DeckCard> sideboard, @Nullable String sleeveArt, int sleeveOffset) {
    }

    record DeckStats(int main, int sideboard, int lands, float averageMana, List<Integer> curve,
            List<TypeCount> types) {
    }

    record TypeCount(String name, int count) {
    }

    record DeckGroup(String heading, List<DeckCard> cards) {
    }

    record DeckCard(String name, int count, String image) {
    }

    /** Match setup. No table means no lobby is open. */
    @Message("lobby")
    record LobbyMessage(@Nullable LobbyTable table) {
    }

    record LobbyTable(boolean host, int mySeat, boolean shareable, String format, List<Format> formats,
            int maxSeats, List<Seat> seats, List<String> problems, boolean canStart) {
    }

    record Format(String id, String name) {
    }

    /** A seat's type is a netplay lobby slot's: LOCAL, AI, OPEN or REMOTE. */
    record Seat(@Nullable String name, String type, boolean mine, boolean mayEdit, boolean ready, int avatar,
            int sleeve, @Nullable String deck, @Nullable String deckName, int deckSize, String colors,
            @Nullable String problem, @Nullable String sleeveArt, int sleeveOffset) {
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

    @Message("cardSearch")
    record CardSearch(List<String> names) {
    }

    @Message("printings")
    record Printings(String name, List<Printing> printings) {
    }

    record Printing(String name, String edition, String key) {
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

    static final List<Class<? extends Record>> EVENTS = List.of(CardMoved.class, CardDamaged.class,
            PlayerDamaged.class, AttackersDeclared.class, Shuffled.class);

    @Message("prompt")
    record Prompt(String message, boolean priority, @Nullable Ref card, PromptButton ok, PromptButton cancel,
            boolean focusOk, boolean paying, List<Ref> selectable, int selectableMin, List<Ref> selectablePlayers,
            List<Integer> highlighted) {
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

    @Message("controls")
    record Controls(List<PhaseType> myStops, List<PhaseType> otherStops, boolean autoPass, @Nullable String dayTime,
            @Nullable TurnMarker marker, ServerSettings settings) {
    }

    /** Pass priority until this phase of this side's turn. */
    record TurnMarker(PhaseType phase, boolean mine) {
    }

    /** The Forge preferences the options dialog shares with the desktop client. */
    record ServerSettings(boolean interruptAttackers, boolean interruptOpponentSpell, boolean interruptTargeting,
            boolean interruptTriggers, boolean interruptMassRemoval, boolean highlightPlayable, boolean autoTapPreview,
            boolean autoPassNoActions, String autoYieldMode, GameLogVerbosity logDetail, String arrows,
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
            boolean toBottom, boolean toAnywhere, @Name("default") List<Integer> defaultAnswer) {
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

    /** Every message record, which is what the TypeScript is generated from. */
    static final List<Class<? extends Record>> MESSAGES = List.of(Hello.class, ErrorMessage.class, Notice.class,
            Decks.class, DeckDetailsMessage.class, LobbyMessage.class, Addresses.class, ChatLine.class,
            CardSearch.class, Printings.class, HostChoice.class, StateMessage.class, Prompt.class, Playable.class,
            Zones.class, Controls.class, LogMessage.class, Detail.class, PlayerDetail.class, StackMenu.class, Sound.class,
            Flash.class, GameOver.class);

    static final List<Class<? extends Record>> REQUESTS = List.of(ChoicesRequest.class, OrderRequest.class, ManipulateRequest.class,
            OptionRequest.class, TextRequest.class, DistributeRequest.class, SideboardRequest.class, AutoPassRequest.class);

    /** The answer a request takes when nobody gives one, read back off its JSON. */
    static JsonElement defaultOf(final JsonObject request) {
        return request.has("default") ? request.get("default") : JsonNull.INSTANCE;
    }
}
