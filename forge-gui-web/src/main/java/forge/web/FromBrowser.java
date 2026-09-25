package forge.web;

import com.google.gson.JsonElement;
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
    enum Plain { decks, claimHost, join, lobby, invite, leaveLobby, addSeat, addresses, netDecks, leave, quit,
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

    /** A card pool for Constructed, or none to lift it. */
    @Command("setLegality")
    record SetLegality(@Nullable String legality) {
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
    record AskPrintings(String name) {
    }

    @Command("sleeveArt")
    record SleeveArt(int index, String key, int offset) {
    }

    @Command("start")
    record Start(boolean spectate) {
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

    /** Every command record, which is what the TypeScript is generated from. */
    static final List<Class<? extends Record>> COMMANDS = List.of(Bare.class, SetName.class, Say.class, Ready.class,
            SeatCommand.class, SetSeat.class, SetFormat.class, SetLegality.class, AskDeckDetails.class, HostChoiceAnswer.class,
            SearchCards.class, AskPrintings.class, SleeveArt.class, Start.class, Reply.class, SelectCard.class,
            KeyCommand.class, StackYield.class, PhaseCommand.class, SetStops.class, UseMana.class, SetSetting.class,
            NextGame.class, DrawOfferCommand.class, AutoDecisionCommand.class);
}
