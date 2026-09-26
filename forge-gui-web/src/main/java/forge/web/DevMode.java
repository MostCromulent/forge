package forge.web;

import forge.game.Game;
import forge.game.GameState;
import forge.game.player.Player;
import forge.gamemodes.net.server.RemoteClientGuiGame;
import forge.gui.interfaces.IGuiGame;
import forge.interfaces.IDevModeCheats;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.PlayerControllerHuman;
import forge.web.FromBrowser.Dev;
import forge.web.ToBrowser.DevDump;
import forge.web.ToBrowser.DevState;
import forge.web.ToBrowser.Notice;
import org.tinylog.Logger;

import java.util.Arrays;

/**
 * Forge's developer cheats, for the host's own seat. Every web seat plays as a netplay client, and Forge gives a
 * netplay client no cheats; the host shares its process with the server, though, so its player's own controller is
 * at hand. Only the host can cheat; guests never can.
 *
 * <p>A cheat asks its questions as the game does, so they reach the browser as the game's own prompts, and the
 * thread that runs one waits for the answers.
 */
final class DevMode {
    private DevMode() {
    }

    /** Why the host can't cheat in the game now, or null when it can. */
    static String refusal(final LocalGame local) {
        if (!FModel.getPreferences().getPrefBoolean(FPref.DEV_MODE_ENABLED)) {
            return "Dev mode is off. Turn it on in the options.";
        }
        return local.ownPlayer() == null ? "Dev mode needs a game you are playing yourself." : null;
    }

    /** Runs one cheat. Call off the socket and interface threads: a cheat waits on the browser's answers. */
    static void run(final LocalGame local, final Dev dev, final BrowserChannel channel) {
        final String refused = refusal(local);
        if (refused != null) {
            channel.send(new Notice("No cheating here", refused, false));
            return;
        }
        final Player own = local.ownPlayer();
        final PlayerControllerHuman controller = (PlayerControllerHuman) own.getController();
        final IDevModeCheats cheat = controller.cheat();
        switch (dev.action()) {
            case state -> { }
            case unlimitedLands -> cheat.setCanPlayUnlimitedLands(!controller.canPlayUnlimitedLands());
            case viewAll -> cheat.setViewAllCards(!controller.mayLookAtAllCards());
            case generateMana -> cheat.generateMana();
            case tutorForCard -> cheat.tutorForCard();
            case addCardToHand -> cheat.addCardToHand();
            case addCardToBattlefield -> cheat.addCardToBattlefield();
            case addTokenToBattlefield -> cheat.addTokenToBattlefield();
            case addCardToLibrary -> cheat.addCardToLibrary();
            case addCardToGraveyard -> cheat.addCardToGraveyard();
            case addCardToExile -> cheat.addCardToExile();
            case repeatLastAddition -> cheat.repeatLastAddition();
            case castASpell -> cheat.castASpell();
            case exileCardsFromHand -> cheat.exileCardsFromHand();
            case exileCardsFromBattlefield -> cheat.exileCardsFromBattlefield();
            case removeCardsFromGame -> cheat.removeCardsFromGame();
            case addCountersToPermanent -> cheat.addCountersToPermanent();
            case removeCountersFromPermanent -> cheat.removeCountersFromPermanent();
            case tapPermanents -> cheat.tapPermanents();
            case untapPermanents -> cheat.untapPermanents();
            case setPlayerLife -> cheat.setPlayerLife();
            case winGame -> cheat.winGame();
            case rollbackPhase -> cheat.rollbackPhase();
            case riggedPlanarRoll -> cheat.riggedPlanarRoll();
            case planeswalkTo -> cheat.planeswalkTo();
            case setupGameState -> setUp(own.getGame(), controller.getGui(), dev.text(), channel);
            case dumpGameState -> dump(own.getGame(), channel);
        }
        channel.send(new DevState(controller.canPlayUnlimitedLands(), controller.mayLookAtAllCards()));
    }

    // Desktop reads the state from a file on the machine running Forge; here the browser sends its text
    private static void setUp(final Game game, final IGuiGame gui, final String text, final BrowserChannel channel) {
        if (game.getPhaseHandler().getPriorityPlayer() == null) {
            channel.send(new Notice("Game state not set up", "Wait until a player has priority.", false));
            return;
        }
        final GameState state = new GameState();
        try {
            state.parse(Arrays.asList((text == null ? "" : text).split("\\R")));
        } catch (final RuntimeException e) {
            Logger.warn(e, "Could not read a game state");
            channel.send(new Notice("Game state not set up", "That text is not a game state Forge can read.", true));
            return;
        }
        game.getAction().invoke(() -> {
            try {
                state.applyToGame(game);
            } catch (final RuntimeException e) {
                Logger.warn(e, "Could not apply a game state");
                channel.send(new Notice("Game state not set up", String.valueOf(e.getMessage()), true));
                return;
            }
            // Placing cards fires no game event, so nothing else would send the new board. It is sent from the same
            // task, after the state: a second task can run on another game thread at once and send the board unchanged
            if (gui instanceof RemoteClientGuiGame remote) {
                remote.updateGameView();
            }
        });
    }

    private static void dump(final Game game, final BrowserChannel channel) {
        final GameState state = new GameState();
        state.initFromGame(game);
        channel.send(new DevDump(state.toString()));
    }
}
