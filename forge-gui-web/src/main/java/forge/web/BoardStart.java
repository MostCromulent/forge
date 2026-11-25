package forge.web;

import forge.deck.Deck;
import forge.game.GameRules;
import forge.game.GameState;
import forge.game.GameType;
import forge.game.player.RegisteredPlayer;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.net.server.FServerManager;
import forge.gamemodes.net.server.RemoteClientGuiGame;
import forge.gamemodes.net.server.ServerGameLobby;
import forge.gui.GuiBase;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.player.PlayerControllerHuman;
import forge.util.IHasForgeLog;
import forge.util.Localizer;
import forge.web.WebSession.Playing;
import forge.web.WebSession.Stage;
import forge.web.match.NetplaySeat;
import forge.web.match.WebGuiGame;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** For the browser tests only: starts a match against the computer at a given board, with no match setup, coin toss or mulligan. */
final class BoardStart implements IHasForgeLog {
    static final boolean ENABLED = Boolean.getBoolean("forge.web.boardStart");

    private BoardStart() {
    }

    /** text is a game state as the dev menu reads it. It has a pNlife line for each player when there are more than two. */
    static void start(final WebSession session, final Stage from, final WebGuiGame gui, final String text) {
        final Playing playing = new Playing(gui, false, false, null);
        if (!session.move(from, playing)) {
            gui.close();
            return;
        }
        session.attach(gui);
        final NetplaySeat local = session.local();
        try {
            startMatch(local, gui, session.playerName(), text.lines().toList());
        } catch (final RuntimeException e) {
            netLog.error(e, "Could not start the board");
            local.endMatch();
            session.move(playing, from);
            session.tell(WebSession.error(Localizer.getInstance().getMessage("lblWebSessionMatchFailed", String.valueOf(e.getMessage()))));
        }
    }

    private static void startMatch(final NetplaySeat local, final WebGuiGame gui, final String name, final List<String> lines) {
        final GameState board = new GameState();
        board.parse(lines);
        // A puzzle has no coin toss or mulligan
        final GameRules rules = new GameRules(GameType.Puzzle);
        rules.setGamesPerMatch(3);
        rules.setWarnAboutAICards(false);
        final RegisteredPlayer human = new RegisteredPlayer(deck()).setPlayer(GamePlayerUtil.getGuiPlayer(name, 0, 0, false));
        final List<RegisteredPlayer> players = new ArrayList<>(List.of(human));
        final long numbered = lines.stream().filter(l -> l.startsWith("p") && l.contains("life=")).count();
        while (players.size() < Math.max(2, numbered)) {
            players.add(new RegisteredPlayer(deck()).setPlayer(GamePlayerUtil.createAiPlayer()));
        }
        final ServerGameLobby table = local.openAlone(name, gui);
        final int seat = local.webSeat();
        final HostedMatch match = GuiBase.getInterface().hostMatch();
        match.setStartGameHook(() -> {
            board.applyToGame(match.getGame());
            // Placing cards fires no game event, so the whole board is sent here
            match.getGame().getPlayers().forEach(p -> {
                if (p.getController() instanceof PlayerControllerHuman h && h.getGui() instanceof RemoteClientGuiGame remote) {
                    remote.updateGameView();
                }
            });
        });
        match.startMatch(rules, Set.of(), players, Map.of(human, FServerManager.getInstance().getGui(seat)), null);
        table.adopt(match, Map.of(human, table.getSlot(seat)));
    }

    private static Deck deck() {
        final Deck deck = new Deck("Board");
        for (final String land : List.of("Mountain", "Forest")) {
            deck.getMain().add(FModel.getMagicDb().getCommonCards().getCard(land), 30);
        }
        return deck;
    }
}
