package forge.web.protocol;

import com.google.gson.JsonElement;
import forge.deck.DeckSection;
import forge.game.phase.PhaseType;
import forge.gamemodes.match.DrawOfferMessage;
import forge.gamemodes.match.NextGameDecision;
import forge.web.protocol.Wire.Command;
import forge.web.protocol.Wire.Nullable;
import forge.web.protocol.Wire.Ts;

import java.util.List;

/** Every message the browser sends the server, from which src/main/ts/protocol.gen.ts is generated. */
public final class FromBrowser {
    private FromBrowser() {
    }

    // ---- Start page and lobby ----------------------------------------------------------------------------------

    /** Messages that are only their name. */
    public enum Plain { decks, claimHost, join, lobby, invite, leaveLobby, addresses, cardPoolDetails, cardSets, netDecks, leave, quit, limitedLeave, poolClose, draftDiscard, gauntletNext, gauntletRestart, eventStart, eventNew,
        ok, cancel, endTurn, stopYield, autoPass, undo, concede, macroRecord, macroRepeat,
        campaignLeave, conquestMove, conquestBattle, rewardClaim, conquestParty, trading,
        conquestPlanes, campaignPrefs, campaignPrefsReset,
        editorClose, editorUndo }

    @Command
    public record Bare(Plain t) {
    }

    /** The name and face this browser plays under, both chosen before anything else. */
    @Command("setName")
    public record SetName(String name, @Nullable Integer avatar) {
    }

    @Command("chat")
    public record Say(String text) {
    }

    /** A script error in the page, which the server's log would otherwise never hear of. */
    @Command("pageError")
    public record PageError(String text) {
    }

    /** The host gives a disconnected player's seat to someone waiting, who plays on from where it was left. */
    @Command("giveSeat")
    public record GiveSeat(String from, String to) {
    }

    /** The host takes a disconnected player's seat away: it opens at the table, and the AI plays it in a match. */
    @Command("dropPlayer")
    public record DropPlayer(String name) {
    }

    @Command("ready")
    public record Ready(boolean ready) {
    }

    public enum SeatAction { openSeat, aiSeat, removeSeat }

    @Command
    public record SeatCommand(SeatAction t, int index) {
    }

    /** How many seats the table has. Seats come and go at the end, and a seat a person holds never goes. */
    @Command("setPlayerCount")
    public record SetPlayerCount(int count) {
    }

    /** Changes one or more of a seat's choices; a field left out is left alone, and a blank name gives the computer a new random one. */
    @Command("setSeat")
    public record SetSeat(int index, @Nullable String name, @Nullable String deck, @Nullable Integer avatar,
            @Nullable Integer sleeve, @Nullable Integer commander) {
    }

    /** How many games the table's match is: 1, 3 or 5. */
    @Command("setMatchLength")
    public record SetMatchLength(int games) {
    }

    /** The highest Commander bracket the table plays at, 1 to 4, or 5 for any. */
    @Command("setMaxBracket")
    public record SetMaxBracket(int bracket) {
    }

    @Command("setFormat")
    public record SetFormat(String format) {
    }

    /** A casual variant switched on or off. The engine decides what else that switches off. */
    @Command("setVariant")
    public record SetVariant(String variant, boolean on) {
    }

    @Command("setArchenemy")
    public record SetArchenemy(int index) {
    }

    /** How many teams the seats are dealt into, or 0 for a free-for-all. */
    @Command("setTeams")
    public record SetTeams(int count) {
    }

    @Command("setTeam")
    public record SetTeam(int index, int team) {
    }

    /** A seat's planar deck, scheme deck or avatar: the section's name and a choice key from its list. */
    @Command("setSeatExtra")
    public record SetSeatExtra(int index, String section, String choice) {
    }

    @Command("extraChoices")
    public record AskExtraChoices(int index, String section) {
    }

    /** A card pool for Constructed, or none to lift it. */
    @Command("setCardPool")
    public record SetCardPool(@Nullable String cardPool) {
    }

    @Command("deckDetails")
    public record AskDeckDetails(String key) {
    }

    /** Which listed decks hold a card (kind "card" or "sideboard") or cards from a set (kind "set"). */
    @Command("deckQuery")
    public record DeckQuery(String kind, String value) {
    }

    @Command("hostChoice")
    public record HostChoiceAnswer(int id, List<Integer> value) {
    }

    @Command("cardSearch")
    public record SearchCards(String query) {
    }

    @Command("printings")
    /** A card's printings; cardPool, when set, marks those outside it. */
    public record AskPrintings(String name, @Nullable String cardPool) {
    }

    @Command("sleeveArt")
    public record SleeveArt(int index, String key, int offset) {
    }

    /** Takes a card-art sleeve out of the saved ones. A deck already wearing it keeps it. */
    @Command("forgetSleeveArt")
    public record ForgetSleeveArt(String key) {
    }

    @Command("start")
    public record Start(boolean spectate) {
    }

    // ---- Decks: the editor and the importer --------------------------------------------------------------------

    /** The format the start page's deck finder lists, while no table is open. */
    @Command("browseFormat")
    public record BrowseFormat(String format) {
    }

    /** Opens a deck from the finder by key, or a new deck for a format. seat is the seat whose finder it came from; copy edits a copy. */
    @Command("editorOpen")
    public record EditorOpen(@Nullable String key, @Nullable String newFormat, @Nullable Integer seat, boolean copy) {
    }

    public enum EditOp { add, remove, move, commander, printings, lands, landSet, suggestLands, foil }

    /** A name and how many: a card printing's image key in a printings change, a basic land's name in a lands change. */
    public record CountedName(String name, int count) {
    }

    @Command("editorEdit")
    public record EditorEdit(EditOp op, @Nullable String name, @Nullable DeckSection from, @Nullable DeckSection to, int count,
            @Nullable List<CountedName> printings, @Nullable List<CountedName> lands) {
    }

    @Command("editorRename")
    public record EditorRename(String name) {
    }

    /** "Check legality against": a format, a card pool for Constructed, or no restriction at all. */
    @Command("editorCheck")
    public record EditorCheck(String format, @Nullable String cardPool, boolean unrestricted) {
    }

    public enum DeckOp { duplicate, delete, file }

    @Command("editorDeck")
    public record EditorDeck(DeckOp op) {
    }

    /** Deletes one of the player's own decks from the finder, without opening it. */
    @Command("deckDelete")
    public record DeckDelete(String key) {
    }

    /** Stars one of the decks the finder lists, or takes its star away. */
    @Command("deckFavourite")
    public record DeckFavourite(String key, boolean on) {
    }

    /** A page of the catalogue, where request is echoed back so a late answer to an old query is dropped. */
    @Command("catalogue")
    public record CatalogueQuery(int request, String text, String colours, String type, String filters, String sort, int offset,
            boolean showAll, @Nullable String identity, @Nullable String source, @Nullable String group,
            @Nullable List<String> sets, @Nullable String format, @Nullable DeckSection section) {
    }

    @Command("importRead")
    public record ImportRead(int request, String text, String format, @Nullable String cardPool, boolean unrestricted) {
    }

    @Command("importFetch")
    public record ImportFetch(int request, String url) {
    }

    /** use puts the deck on a seat, edit opens it, save keeps it, add and replace change the deck open in the editor. */
    public enum ImportAction { use, edit, save, add, replace }

    /** What to do when the name is taken: replace that deck, or keep both. */
    public enum Clash { replace, keep }

    @Command("importCommit")
    public record ImportCommit(String text, String name, String format, @Nullable String cardPool, boolean unrestricted,
            ImportAction action, @Nullable Integer seat, @Nullable String url, @Nullable Clash clash) {
    }

    /** A .dck file's text, saved as a deck of the format without going through the importer. */
    @Command("deckFile")
    public record DeckFile(String text, String format, @Nullable Clash clash) {
    }

    /** A deck a guest keeps in its browser: the id it is kept under, the deck as .dck text, and its format. */
    public record DeviceDeckText(String id, String text, String format) {
    }

    /** Every deck a guest keeps in its browser, sent once each time it connects. */
    @Command("deviceDecks")
    public record DeviceDecks(List<DeviceDeckText> decks) {
    }

    // ---- Match -------------------------------------------------------------------------------------------------

    @Command("reply")
    public record Reply(int id, @Ts("unknown") JsonElement value) {
    }

    /** A click on a card; menu is the right button, and x and y place the list of abilities it may open. */
    @Command("selectCard")
    public record SelectCard(int key, boolean menu, int x, int y) {
    }

    public enum KeyAction { selectPlayer, selectStackItem, detail, playerDetail, stackMenu }

    /** Something done to one object in the game's table, named by its delta key. */
    @Command
    public record KeyCommand(KeyAction t, int key) {
    }

    public enum YieldAction { autoYield, alwaysYes, alwaysNo, yieldToStack, yieldToEntireStack }

    @Command("stackYield")
    public record StackYield(int key, YieldAction action) {
    }

    /** Sets, not toggles, every stop of one row, so a browser can restore its remembered stops without knowing the server's. */
    @Command("setStops")
    public record SetStops(boolean mine, List<PhaseType> phases) {
    }

    public enum PhaseAction { toggleStop, toggleMarker }

    /** A stop, or pass-priority-until marker, at a phase of your turns (mine) or your opponents'. */
    @Command
    public record PhaseCommand(PhaseAction t, PhaseType phase, boolean mine) {
    }

    /** Pays with one colour of the pool: its bit, as in the player's Mana property. */
    @Command("useMana")
    public record UseMana(byte color) {
    }

    /** How the host's generators build decks: one of each card, and no artifacts. */
    @Command("setDeckGen")
    public record SetDeckGen(boolean singletons, boolean noArtifacts) {
    }

    @Command("setSetting")
    public record SetSetting(String key, String value) {
    }

    /** Forge's developer cheats, by their names in IDevModeCheats; state only asks where the two switches stand. */
    public enum DevAction { state, unlimitedLands, viewAll, generateMana, tutorForCard, addCardToHand, addCardToBattlefield,
        addTokenToBattlefield, addCardToLibrary, addCardToGraveyard, addCardToExile, repeatLastAddition, castASpell,
        exileCardsFromHand, exileCardsFromBattlefield, removeCardsFromGame, addCountersToPermanent,
        removeCountersFromPermanent, tapPermanents, untapPermanents, setPlayerLife, winGame,
        riggedPlanarRoll, planeswalkTo, setupGameState, dumpGameState }

    /** A cheat for the host's own seat. text is the game state to set up, for setupGameState. */
    @Command("dev")
    public record Dev(DevAction action, @Nullable String text) {
    }

    @Command("nextGame")
    public record NextGame(NextGameDecision decision) {
    }

    /** Offers a draw, or answers another player's offer. */
    @Command("drawOffer")
    public record DrawOfferCommand(DrawOfferMessage.Action action) {
    }

    public enum AutoDecisionAction { list, remove, clear, clearOrders, disableYields, disableTriggers }

    /** Lists or forgets the player's auto-yields and trigger answers, forgets saved ability orders, or switches either kind off when on is true. */
    @Command("autoDecisions")
    public record AutoDecisionCommand(AutoDecisionAction action, @Nullable String key, boolean on) {
    }

    // ---- Limited ---------------------------------------------------------------------------------------------

    /** Opens the Limited pages for a kind of event, or with resume, straight to the pool saved last. */
    @Command("limitedOpen")
    public record LimitedOpen(String kind, boolean resume) {
    }

    /** Opens a sealed pool, where product is a LimitedPoolType name and the fields that product does not need are null. */
    @Command("sealedCreate")
    public record SealedCreate(String product, @Nullable String block, @Nullable String combo, @Nullable String edition,
            @Nullable String template, @Nullable String cubeId, int packs, String name, boolean replace) {
    }

    /** Starts an offline booster draft. product is a LimitedPoolType name; the fields its product needs are set. combo is "A/B/C". */
    @Command("draftStart")
    public record DraftStart(String product, @Nullable String block, @Nullable String combo, @Nullable String cube, @Nullable String theme,
            @Nullable String cubeId) {
    }

    /** Picks the card at index of the pack shown in state step, so a click on a state that has moved on is ignored. */
    @Command("draftPick")
    public record DraftPick(int step, int index, boolean sideboard) {
    }

    /** Moves the pick at index, in the order picked, into the sideboard or back into the main deck. */
    @Command("draftMove")
    public record DraftMove(int index, boolean sideboard) {
    }

    /** Saves a finished draft; replace says the player agreed to replace a draft of the same name. */
    @Command("draftSave")
    public record DraftSave(String name, boolean replace) {
    }

    /** A saved pool, by name: its opponents screen, its deck in the editor, or removing it. */
    @Command("poolOpen")
    public record PoolOpen(String name) {
    }

    @Command("poolEdit")
    public record PoolEdit(String name) {
    }

    @Command("poolDelete")
    public record PoolDelete(String name) {
    }

    /** Plays a pool's deck against one of its opponents, 0-based, for games in the match. */
    @Command("poolPlay")
    public record PoolPlay(String name, @Nullable String mode, int opponent, int count, int games) {
    }

    /** The table's Limited switch, the host's: "draft" or "sealed" for an event, null for Constructed. */
    @Command("setLimited")
    public record SetLimited(@Nullable String kind) {
    }

    /** Sets the table's event up or sets it up again, with timer and grace in seconds and pickRule a DoublePick name. */
    @Command("eventSetup")
    public record EventSetup(String product, @Nullable String block, @Nullable String combo, @Nullable String edition,
            @Nullable String template, @Nullable String cube, @Nullable String theme, @Nullable String cubeId, int packs,
            int podSize, @Nullable String pickRule, int timer, int grace) {
    }

    /** Keeps a seat at the table while it sits the next match out. */
    @Command("sitOut")
    public record SitOut(int index, boolean sittingOut) {
    }

    /** Plays a past event's decks again at this table, as desktop's Load Past Event does. */
    @Command("eventHostAgain")
    public record EventHostAgain(String eventId) {
    }

    /** Deletes a past event's pools from the host's event decks. */
    @Command("eventForget")
    public record EventForget(String eventId) {
    }

    /** Whether the deck finder lists only the event's decks. */
    @Command("eventDecksOnly")
    public record EventDecksOnly(boolean on) {
    }

    // ---- Campaigns: what Planar Conquest and any mode like it share ---------------------------------------------

    /** Opens a campaign mode's saved games, or with resume, straight into the one played last. mode is "conquest". */
    @Command("campaignOpen")
    public record CampaignOpen(String mode, boolean resume) {
    }

    @Command("campaignLoad")
    public record CampaignLoad(String name) {
    }

    @Command("campaignRename")
    public record CampaignRename(String name, String to) {
    }

    @Command("campaignDelete")
    public record CampaignDelete(String name) {
    }

    /** Asks for a campaign's statistics, narrowed to a scope the mode offers, or all of them. */
    @Command("campaignStats")
    public record CampaignStatsQuery(@Nullable String scope) {
    }

    /** Sets one of a mode's preferences, by the key its row was sent with. */
    @Command("campaignPref")
    public record CampaignPref(String key, String value) {
    }

    /** A card picked from one of a campaign's lists, by its image key, which names a printing. */
    public record TradePick(String key, int count) {
    }

    /** Trades the picks out of a list: source is the list's name, as a catalogue query's is. */
    @Command("trade")
    public record Trade(String source, List<TradePick> picks) {
    }

    // ---- Planar Conquest ---------------------------------------------------------------------------------------

    /** Selects a place on the map. The player does not move. */
    @Command("conquestSelect")
    public record ConquestSelect(int region, int row, int col) {
    }

    /** The Aether's filters, where an empty type asks for a visit's starting filters and pull spends the shards to take a card. */
    @Command("conquestAether")
    public record ConquestAetherQuery(String colors, String type, String rarity, String cmc, boolean pull) {
    }

    /** Travels to an unlocked plane, or with unlock spends the emblems to unlock it first. */
    @Command("conquestPlaneswalk")
    public record ConquestPlaneswalk(String plane, boolean unlock) {
    }

    /** Asks what a new conquest may start with: the planes, with a plane its commanders, and with a commander its planeswalkers. */
    @Command("conquestOptions")
    public record ConquestOptionsQuery(@Nullable String plane, @Nullable String commander) {
    }

    @Command("conquestCreate")
    public record ConquestCreate(String name, String plane, String commander, String planeswalker) {
    }

    public enum CommanderAction { conquestLead, conquestViewDeck, conquestEditDeck }

    /** Something done with a commander: made the battle leader, its deck asked for, or its deck opened in the editor. */
    @Command
    public record ConquestCommanderCommand(CommanderAction t, String commander) {
    }

    /** Makes an owned planeswalker the one the player travels as. */
    @Command("conquestWalker")
    public record ConquestWalker(String planeswalker) {
    }

    /** Dev mode: the outcome the next wheel stops on, a ChaosWheelOutcome name, or empty to leave it to chance. */
    @Command("devConquestWheel")
    public record DevConquestWheel(String outcome) {
    }

    /** Chooses a quest's pet for a slot, the plant's being slot 0; null summons none. */
    @Command("questPet")
    public record QuestPet(int slot, @Nullable String name) {
    }

    @Command("questMatchLength")
    public record QuestMatchLength(int games) {
    }

    public enum QuestDeckAction { questDeckCurrent, questDeckView, questDeckEdit, questDeckDelete }

    /** Something done with a quest deck, by name: made the one duels use, its list asked for, opened in the editor, or deleted. */
    @Command
    public record QuestDeckCommand(QuestDeckAction t, String deck) {
    }

    /** Makes an empty quest deck of a name and opens it in the editor. */
    @Command("questDeckNew")
    public record QuestDeckNew(String name) {
    }

    @Command("questDeckRename")
    public record QuestDeckRename(String deck, String to) {
    }

    @Command("questOptions")
    public record QuestOptionsQuery() {
    }

    /** A new quest, as NewQuestRules.Choices with its enums by name and the distribution's colours as WUBRGC letters. */
    @Command("questCreate")
    public record QuestCreate(String name, int difficulty, boolean fantasy, boolean commander, String world, String pool, @Nullable String format,
            @Nullable String precon, @Nullable String savedDeck, String poolType, String colors, boolean artifacts, boolean completeSet,
            boolean duplicates, int boosters, @Nullable String prizes, @Nullable String prizeFormat, boolean allowUnlocks) {
    }

    /** Opens a stall of the bazaar, or with no name the first. */
    @Command("questStall")
    public record QuestStall(@Nullable String name) {
    }

    @Command("questBuy")
    public record QuestBuy(String stall, String item) {
    }

    /** Fights a challenge, by its id. */
    @Command("questChallenge")
    public record QuestChallenge(String id) {
    }

    /** Flies the zeppelin: the challenges are drawn again, once between two matches. */
    @Command("questZeppelin")
    public record QuestZeppelin() {
    }

    /** Enters a tournament on offer, by its title, which pays its fee and opens its draft. */
    @Command("questEnter")
    public record QuestEnter(String title) {
    }

    /** Spends a draft token on a tournament of a format the host asks for. */
    @Command("questToken")
    public record QuestToken() {
    }

    /** Starts the tournament entered, once its deck is built. */
    @Command("questTournamentStart")
    public record QuestTournamentStart() {
    }

    /** Plays the player's next tournament match, deciding the computer's matches before it. */
    @Command("questTournamentNext")
    public record QuestTournamentNext() {
    }

    /** Opens the tournament's deck in the editor. */
    @Command("questTournamentDeck")
    public record QuestTournamentDeck() {
    }

    /** Leaves the tournament entered, collecting its prizes once it has started. */
    @Command("questTournamentLeave")
    public record QuestTournamentLeave() {
    }

    /** Travels to another world, which the host asks for. */
    @Command("questTravel")
    public record QuestTravel() {
    }

    /** Unlocks a set for the quest's format, which the host asks for. */
    @Command("questUnlock")
    public record QuestUnlock() {
    }

    /** Fights a duel of the Duels page, by its place in the list that page was sent. */
    @Command("questDuel")
    public record QuestDuel(int index) {
    }

    /** Every command record, which is what the TypeScript is generated from. */
    static final List<Class<? extends Record>> COMMANDS = Wire.marked(FromBrowser.class, Command.class);
}
