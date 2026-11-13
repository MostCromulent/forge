package forge.web;

import forge.adventure.IAdventureBattleHost;
import forge.adventure.IAdventureBattleHost.AIPlayerData;
import forge.adventure.IAdventureBattleHost.BattleRequest;
import forge.adventure.IAdventureBattleHost.BattleResult;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.match.PreparedMatch;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.player.GamePlayerUtil;
import forge.util.IHasForgeLog;
import forge.util.Localizer;
import forge.util.MyRandom;
import forge.web.WebSession.Menu;
import forge.web.WebSession.Opening;
import forge.web.WebSession.Playing;
import forge.web.WebSession.Stage;
import forge.web.images.SkinSprites;
import forge.web.match.NetplaySeat;
import forge.web.match.WebGuiGame;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Plays the battles of an adventure that runs in another process. That process starts this server and names a folder in the environment.
 * Each battle it writes there is played in the host's browser. The result is written back when the player leaves the match.
 */
final class AdventureBattles implements IHasForgeLog {
    private static final long POLL_MILLIS = 500;
    private static final int AWAY_POLLS = 10;

    private AdventureBattles() {}

    /** Whether an adventure started this server. The browser then opens at the first battle, because a window opened now would sit behind adventure's. */
    static boolean asked() {
        final String dir = System.getenv(IAdventureBattleHost.IPC_DIR_ENV);
        return dir != null && !dir.isEmpty();
    }

    static void startIfAsked(final ServerControl service, final WebGuiBase ui) {
        if (!asked()) {
            return;
        }
        final String dir = IAdventureBattleHost.getIpcDir();
        // The server waits between battles with no browser attached
        service.quitWhenEmpty(false);
        // Adventure may end without running its shutdown hooks, and nothing else would stop this server
        ProcessHandle.current().parent().ifPresent(p -> p.onExit().thenRun(() -> System.exit(0)));
        final Thread watcher = new Thread(() -> watch(service, ui), "AdventureBattles");
        watcher.setDaemon(true);
        watcher.start();
        netLog.info("Playing adventure battles from {}", dir);
    }

    private static void watch(final ServerControl service, final WebGuiBase ui) {
        while (true) {
            try {
                if (IAdventureBattleHost.isBattlePending()) {
                    play(service, ui, BattleRequest.read()).write();
                }
                Thread.sleep(POLL_MILLIS);
            } catch (final InterruptedException e) {
                return;
            } catch (final Exception e) {
                netLog.error(e, "Could not play the adventure battle");
                writeLoss();
            }
        }
    }

    private static void writeLoss() {
        final BattleResult loss = new BattleResult();
        loss.shardsAfterBattle = -1;
        try {
            loss.write();
        } catch (final Exception e) {
            netLog.error(e, "Could not write the adventure battle's result");
        }
    }

    private static BattleResult play(final ServerControl service, final WebGuiBase ui, final BattleRequest request) throws InterruptedException {
        final BattleResult result = new BattleResult();
        result.shardsAfterBattle = request.humanManaShards;
        final PreparedMatch prepared = prepare(request);
        final WebSession session = awaitHost(service, ui);
        final Playing[] playing = new Playing[1];
        ui.invokeInEdtAndWait(() -> playing[0] = start(session, prepared));
        // Captured now, because the lobby forgets its match once the match is over
        final HostedMatch match = playing[0] == null ? null : session.local().hostedMatch();
        if (match == null) {
            result.shardsAfterBattle = -1;
            return result;
        }
        int pollsAway = 0;
        // Leaving the match is the sign that the player is done with the browser
        while (session.stage() == playing[0]) {
            // Quitting a match clears its human controllers, so the shards are read while the game runs
            final Game game = match.getGame();
            if (game != null) {
                for (final Player p : game.getRegisteredPlayers()) {
                    if (p.getRegisteredPlayer() == prepared.human()) {
                        result.shardsAfterBattle = p.getNumManaShards();
                    }
                }
            }
            pollsAway = session.attached() ? 0 : pollsAway + 1;
            // A closed window leaves the session in the match. A reload comes back within this time
            if (pollsAway >= AWAY_POLLS && match.getMatch().isMatchOver()) {
                ui.invokeInEdtAndWait(() -> leave(session, playing[0]));
            } else if (pollsAway == AWAY_POLLS && service.running()) {
                WebMain.openBrowser(service.url(), ui);
            }
            Thread.sleep(POLL_MILLIS);
        }
        // Stopping the server ends the game and names the browser's player the winner
        if (!service.running()) {
            result.shardsAfterBattle = -1;
            return result;
        }
        result.humanWon = match.getMatch().isMatchOver() && match.getMatch().getWinner() == prepared.human();
        result.ante = match.getAnteResult(prepared.human());
        return result;
    }

    /** Leaves the match for a browser that has gone, as its leave button would. */
    private static void leave(final WebSession session, final Playing playing) {
        if (session.stage() == playing) {
            session.local().endMatch();
            session.move(playing, new Menu());
        }
    }

    /** The host's session once it can take a match. If no browser is open, one is opened. */
    private static WebSession awaitHost(final ServerControl service, final WebGuiBase ui) throws InterruptedException {
        boolean opened = false;
        while (true) {
            final WebHub sessions = service.sessions();
            final WebSession host = sessions == null ? null : sessions.host();
            if (host != null && host.attached() && !(host.stage() instanceof Playing) && !(host.stage() instanceof Opening)) {
                return host;
            }
            // A server that failed to start can be started again from its console
            if (!opened && service.running() && sessions != null && sessions.playersHere() == 0) {
                WebMain.openBrowser(service.url(), ui);
                opened = true;
            }
            Thread.sleep(POLL_MILLIS);
        }
    }

    /** Moves the session into the match. Leaving it returns to the start page. Null if the match could not start. */
    private static Playing start(final WebSession session, final PreparedMatch prepared) {
        final Stage from = session.stage();
        if (from instanceof Playing || from instanceof Opening) {
            return null;
        }
        final Playing playing = new Playing(new WebGuiGame(session.settings()), false, false, new Menu());
        if (!session.move(from, playing)) {
            playing.gui().close();
            return null;
        }
        session.attach(playing.gui());
        final NetplaySeat local = session.local();
        try {
            // The board finds the browser's seat by the session's player name
            prepared.human().getPlayer().setName(session.playerName());
            prepared.human().getPlayer().setAvatarIndex(session.avatarIndex());
            prepared.human().getPlayer().setSleeveIndex(NetplaySeat.storedIndex(FPref.UI_SLEEVES, 0));
            for (final RegisteredPlayer p : prepared.players()) {
                if (p != prepared.human()) {
                    p.getPlayer().setAvatarIndex(randomAvatarExcept(session.avatarIndex()));
                }
            }
            local.openAlone(session.playerName(), playing.gui()).startPrepared(prepared, local.webSeat());
            return playing;
        } catch (final RuntimeException e) {
            netLog.error(e, "Could not start the adventure battle");
            local.endMatch();
            session.move(playing, new Menu());
            session.tell(WebSession.error(Localizer.getInstance().getMessage("lblWebSessionMatchFailed", String.valueOf(e.getMessage()))));
            return null;
        }
    }

    private static int randomAvatarExcept(final int taken) {
        final int count = SkinSprites.avatarCount();
        if (count < 2) {
            return 0;
        }
        final int pick = MyRandom.getRandom().nextInt(count - 1);
        return pick >= taken ? pick + 1 : pick;
    }

    /** Builds the match as adventure's own duel scene does, from what it wrote to the folder. */
    static PreparedMatch prepare(final BattleRequest request) {
        final Set<GameType> variants = EnumSet.of(GameType.valueOf(request.gameType));
        final int playerCount = 1 + request.aiPlayers.size();

        final RegisteredPlayer human = RegisteredPlayer.forVariants(playerCount, variants, IAdventureBattleHost.loadHumanDeck(), null, false, null, null);
        human.setPlayer(GamePlayerUtil.getGuiPlayer());
        human.setTeamNumber(0);
        human.setStartingLife(request.humanStartingLife);
        human.setManaShards(request.humanManaShards);
        human.setStartingHand(request.humanStartingHand);
        human.setEnableETBCountersEffect(request.humanEnableETBCountersEffect);
        human.addExtraCardsOnBattlefield(IAdventureBattleHost.resolveCards(request.humanExtraCardsOnBattlefield));
        human.addExtraCardsInCommandZone(IAdventureBattleHost.resolveCards(request.humanExtraCardsInCommandZone));
        request.humanStartGameScripts.forEach(human::addStartGameScript);
        if (request.assignConspiracies) {
            human.assignConspiracies();
        }

        final List<RegisteredPlayer> players = new ArrayList<>();
        for (final AIPlayerData data : request.aiPlayers) {
            final RegisteredPlayer ai = RegisteredPlayer.forVariants(playerCount, variants, IAdventureBattleHost.loadAiDeck(data.deckIndex), null, false, null, null);
            ai.setPlayer(GamePlayerUtil.createAiPlayer(data.name, data.aiType));
            ai.setTeamNumber(data.teamNumber);
            ai.setStartingLife(data.startingLife);
            ai.setStartingHand(data.startingHand);
            ai.setManaShards(data.manaShards);
            ai.setEnableETBCountersEffect(data.enableETBCountersEffect);
            ai.addExtraCardsOnBattlefield(IAdventureBattleHost.resolveCards(data.extraCardsOnBattlefield));
            ai.addExtraCardsInCommandZone(IAdventureBattleHost.resolveCards(data.extraCardsInCommandZone));
            data.startGameScripts.forEach(ai::addStartGameScript);
            if (request.assignConspiracies) {
                ai.assignConspiracies();
            }
            players.add(ai);
        }
        players.add(human);

        final GameRules rules = new GameRules(GameType.valueOf(request.rulesType));
        rules.setGamesPerMatch(request.gamesPerMatch);
        rules.setPlayForAnte(request.playForAnte);
        rules.setMatchAnteRarity(request.matchAnteRarity);
        rules.setAnteIncludeBasicLands(request.anteIncludeBasicLands);
        rules.setManaBurn(false);
        rules.setWarnAboutAICards(false);
        return new PreparedMatch(rules, variants, players, human);
    }
}
