package forge.web;

import com.google.gson.JsonElement;
import forge.deck.DeckSection;
import forge.game.phase.PhaseType;
import forge.gamemodes.match.DrawOfferMessage;
import forge.gamemodes.match.NextGameDecision;
import forge.web.Wire.Command;
import forge.web.Wire.Nullable;
import forge.web.Wire.Ts;

import java.util.List;

/**
 * Every message the browser sends the server. src/main/ts/protocol.gen.ts is generated from this file, so the
 * browser cannot send a message, or a field, that is not read here.
 */
final class FromBrowser {
    private FromBrowser() {
    }

    // ---- Start page and lobby ----------------------------------------------------------------------------------

    /** Messages that are only their name. */
    enum Plain { decks, claimHost, join, lobby, invite, leaveLobby, addSeat, addresses, netDecks, leave, quit, limitedLeave, poolClose, draftDiscard, gauntletNext, gauntletRestart, eventStart,
        ok, cancel, endTurn, autoPass, undo, concede }

    @Command
    record Bare(Plain t) {
    }

    /** The name and face this browser plays under, both chosen before anything else. */
    @Command("setName")
    record SetName(String name, @Nullable Integer avatar) {
    }

    @Command("chat")
    record Say(String text) {
    }

    @Command("ready")
    record Ready(boolean ready) {
    }

    enum SeatAction { openSeat, aiSeat, removeSeat }

    @Command
    record SeatCommand(SeatAction t, int index) {
    }

    /** Changes one or more of a seat's choices; a field left out is left alone. */
    @Command("setSeat")
    record SetSeat(int index, @Nullable String name, @Nullable String deck, @Nullable Integer avatar,
            @Nullable Integer sleeve) {
    }

    @Command("setFormat")
    record SetFormat(String format) {
    }

    /** A casual variant switched on or off. The engine decides what else that switches off. */
    @Command("setVariant")
    record SetVariant(String variant, boolean on) {
    }

    @Command("setArchenemy")
    record SetArchenemy(int index) {
    }

    /** A seat's planar deck, scheme deck or avatar: the section's name and a choice key from its list. */
    @Command("setSeatExtra")
    record SetSeatExtra(int index, String section, String choice) {
    }

    @Command("extraChoices")
    record AskExtraChoices(int index, String section) {
    }

    /** A card pool for Constructed, or none to lift it. */
    @Command("setCardPool")
    record SetCardPool(@Nullable String cardPool) {
    }

    @Command("deckDetails")
    record AskDeckDetails(String key) {
    }

    @Command("hostChoice")
    record HostChoiceAnswer(int id, List<Integer> value) {
    }

    @Command("cardSearch")
    record SearchCards(String query) {
    }

    @Command("printings")
    /** A card's printings; cardPool, when set, marks those outside it. */
    record AskPrintings(String name, @Nullable String cardPool) {
    }

    @Command("sleeveArt")
    record SleeveArt(int index, String key, int offset) {
    }

    @Command("start")
    record Start(boolean spectate) {
    }

    // ---- Decks: the editor and the importer --------------------------------------------------------------------

    /** The format the start page's deck finder lists, while no table is open. */
    @Command("browseFormat")
    record BrowseFormat(String format) {
    }

    /** Opens a deck from the finder by key, or a new deck for a format. seat is the seat whose finder it came from; copy edits a copy. */
    @Command("editorOpen")
    record EditorOpen(@Nullable String key, @Nullable String newFormat, @Nullable Integer seat, boolean copy) {
    }

    enum EditorPlain { editorClose, editorUndo }

    @Command
    record EditorBare(EditorPlain t) {
    }

    enum EditOp { add, remove, move, commander, printings, lands, landSet, suggestLands }

    /** A name and how many: a card printing's image key in a printings change, a basic land's name in a lands change. */
    record CountedName(String name, int count) {
    }

    @Command("editorEdit")
    record EditorEdit(EditOp op, @Nullable String name, @Nullable DeckSection from, @Nullable DeckSection to, int count,
            @Nullable List<CountedName> printings, @Nullable List<CountedName> lands) {
    }

    @Command("editorRename")
    record EditorRename(String name) {
    }

    /** "Check legality against": a format, a card pool for Constructed, or no restriction at all. */
    @Command("editorCheck")
    record EditorCheck(String format, @Nullable String cardPool, boolean unrestricted) {
    }

    enum DeckOp { duplicate, delete }

    @Command("editorDeck")
    record EditorDeck(DeckOp op) {
    }

    /** A page of the catalogue; request is echoed back so a late answer to an old query is dropped. */
    @Command("catalogue")
    record CatalogueQuery(int request, String text, String colours, String type, String mv, String sort, int offset,
            boolean showAll) {
    }

    @Command("importRead")
    record ImportRead(int request, String text, String format, @Nullable String cardPool, boolean unrestricted) {
    }

    @Command("importFetch")
    record ImportFetch(int request, String url) {
    }

    /** use puts the deck on a seat, edit opens it, save keeps it, add and replace change the deck open in the editor. */
    enum ImportAction { use, edit, save, add, replace }

    /** What to do when the name is taken: replace that deck, or keep both. */
    enum Clash { replace, keep }

    @Command("importCommit")
    record ImportCommit(String text, String name, String format, @Nullable String cardPool, boolean unrestricted,
            ImportAction action, @Nullable Integer seat, @Nullable String url, @Nullable Clash clash) {
    }

    /** A deck a guest keeps in its browser: the id it is kept under, the deck as .dck text, and its format. */
    record DeviceDeckText(String id, String text, String format) {
    }

    /** Every deck a guest keeps in its browser, sent once each time it connects. */
    @Command("deviceDecks")
    record DeviceDecks(List<DeviceDeckText> decks) {
    }

    // ---- Match -------------------------------------------------------------------------------------------------

    @Command("reply")
    record Reply(int id, @Ts("unknown") JsonElement value) {
    }

    /** A click on a card; menu is the right button, and x and y place the list of abilities it may open. */
    @Command("selectCard")
    record SelectCard(int key, boolean menu, int x, int y) {
    }

    enum KeyAction { selectPlayer, detail, playerDetail, stackMenu }

    /** Something done to one object in the game's table, named by its delta key. */
    @Command
    record KeyCommand(KeyAction t, int key) {
    }

    enum YieldAction { autoYield, alwaysYes, alwaysNo, yieldToStack, yieldToEntireStack }

    @Command("stackYield")
    record StackYield(int key, YieldAction action) {
    }

    /** Sets every stop of one row at once: your turns (mine) or your opponents'. Setting, not toggling, so a browser
     *  can give a server back the stops it remembers without knowing what the server has now. */
    @Command("setStops")
    record SetStops(boolean mine, List<PhaseType> phases) {
    }

    enum PhaseAction { toggleStop, toggleMarker }

    /** A stop, or pass-priority-until marker, at a phase of your turns (mine) or your opponents'. */
    @Command
    record PhaseCommand(PhaseAction t, PhaseType phase, boolean mine) {
    }

    /** Pays with one colour of the pool: its bit, as in the player's Mana property. */
    @Command("useMana")
    record UseMana(byte color) {
    }

    @Command("setSetting")
    record SetSetting(String key, String value) {
    }

    /** Forge's developer cheats, by their names in IDevModeCheats; state only asks where the two switches stand. */
    enum DevAction { state, unlimitedLands, viewAll, generateMana, tutorForCard, addCardToHand, addCardToBattlefield,
        addTokenToBattlefield, addCardToLibrary, addCardToGraveyard, addCardToExile, repeatLastAddition, castASpell,
        exileCardsFromHand, exileCardsFromBattlefield, removeCardsFromGame, addCountersToPermanent,
        removeCountersFromPermanent, tapPermanents, untapPermanents, setPlayerLife, winGame, rollbackPhase,
        riggedPlanarRoll, planeswalkTo, setupGameState, dumpGameState }

    /** A cheat for the host's own seat. text is the game state to set up, for setupGameState. */
    @Command("dev")
    record Dev(DevAction action, @Nullable String text) {
    }

    @Command("nextGame")
    record NextGame(NextGameDecision decision) {
    }

    /** Offers a draw, or answers another player's offer. */
    @Command("drawOffer")
    record DrawOfferCommand(DrawOfferMessage.Action action) {
    }

    enum AutoDecisionAction { list, remove, clear, disableYields, disableTriggers }

    /** The auto-yields and trigger answers the player has set: listed, one forgotten, all forgotten, or either
     *  kind switched off (on) or back on. */
    @Command("autoDecisions")
    record AutoDecisionCommand(AutoDecisionAction action, @Nullable String key, boolean on) {
    }

    // ---- Limited ---------------------------------------------------------------------------------------------

    /** Opens the Limited pages for a kind of event: sealed. */
    @Command("limitedOpen")
    record LimitedOpen(String kind) {
    }

    /**
     * Opens a sealed pool from the setup form's answers. product is a LimitedPoolType name; the fields its product needs
     * are set and the rest are null. replace says the player agreed to replace a pool of the same name.
     */
    @Command("sealedCreate")
    record SealedCreate(String product, @Nullable String block, @Nullable String combo, @Nullable String edition,
            @Nullable String template, @Nullable String cubeId, int packs, String name, boolean replace) {
    }

    /** Starts an offline booster draft. product is a LimitedPoolType name; the fields its product needs are set. combo is "A/B/C". */
    @Command("draftStart")
    record DraftStart(String product, @Nullable String block, @Nullable String combo, @Nullable String cube, @Nullable String theme,
            @Nullable String cubeId) {
    }

    /** Picks the card at index of the pack shown in state step, so a click on a state that has moved on is ignored. */
    @Command("draftPick")
    record DraftPick(int step, int index) {
    }

    /** Saves a finished draft; replace says the player agreed to replace a draft of the same name. */
    @Command("draftSave")
    record DraftSave(String name, boolean replace) {
    }

    /** A saved pool, by name: its opponents screen, its deck in the editor, or removing it. */
    @Command("poolOpen")
    record PoolOpen(String name) {
    }

    @Command("poolEdit")
    record PoolEdit(String name) {
    }

    @Command("poolDelete")
    record PoolDelete(String name) {
    }

    /** Plays a pool's deck against one of its opponents, 0-based, for games in the match. */
    @Command("poolPlay")
    record PoolPlay(String name, @Nullable String mode, int opponent, int count, int games) {
    }

    /** The table's Limited switch, the host's: "draft" or "sealed" for an event, null for Constructed. */
    @Command("setLimited")
    record SetLimited(@Nullable String kind) {
    }

    /**
     * Sets the table's event up, or sets it up again. product is a LimitedPoolType name and the fields its product needs
     * are set, as in sealedCreate and draftStart. A draft also takes the pod size, a DoublePick name, and the pick timer
     * and disconnect grace in seconds.
     */
    @Command("eventSetup")
    record EventSetup(String product, @Nullable String block, @Nullable String combo, @Nullable String edition,
            @Nullable String template, @Nullable String cube, @Nullable String theme, @Nullable String cubeId, int packs,
            int podSize, @Nullable String pickRule, int timer, int grace) {
    }

    /** Keeps a seat at the table while it sits the next match out. */
    @Command("benchSeat")
    record BenchSeat(int index, boolean benched) {
    }

    /** Whether the deck finder lists only the event's decks. */
    @Command("eventDecksOnly")
    record EventDecksOnly(boolean on) {
    }

    /** Every command record, which is what the TypeScript is generated from. */
    static final List<Class<? extends Record>> COMMANDS = List.of(Bare.class, SetName.class, Say.class, Ready.class,
            SeatCommand.class, SetSeat.class, SetFormat.class, SetCardPool.class, SetVariant.class, SetArchenemy.class, SetSeatExtra.class, AskExtraChoices.class, AskDeckDetails.class, HostChoiceAnswer.class,
            SearchCards.class, AskPrintings.class, SleeveArt.class, Start.class, Reply.class, SelectCard.class,
            KeyCommand.class, StackYield.class, PhaseCommand.class, SetStops.class, UseMana.class, SetSetting.class, Dev.class,
            NextGame.class, DrawOfferCommand.class, AutoDecisionCommand.class, BrowseFormat.class, EditorOpen.class,
            EditorBare.class, EditorEdit.class, EditorRename.class, EditorCheck.class, EditorDeck.class, CatalogueQuery.class,
            ImportRead.class, ImportFetch.class, ImportCommit.class, DeviceDecks.class, LimitedOpen.class, SealedCreate.class,
            PoolOpen.class, PoolEdit.class, PoolDelete.class, PoolPlay.class,
            DraftStart.class, DraftPick.class, DraftSave.class, SetLimited.class, EventSetup.class, BenchSeat.class,
            EventDecksOnly.class);
}
