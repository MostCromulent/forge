package forge.adventure;

import forge.LobbyPlayer;
import forge.Singletons;
import forge.adventure.IAdventureBattleHost.AIPlayerData;
import forge.adventure.IAdventureBattleHost.BattleRequest;
import forge.adventure.IAdventureBattleHost.BattleResult;
import forge.deck.Deck;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.gamemodes.match.HostedMatch;
import forge.gui.GuiBase;
import forge.gui.framework.FScreen;
import forge.gui.interfaces.IGuiGame;
import forge.player.GamePlayerUtil;
import forge.player.PlayerControllerHuman;
import forge.sound.MusicPlaylist;
import forge.trackable.TrackableCollection;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Hosts Adventure battles on the desktop using the native Swing UI (CMatchUI).
 * Monitors for IPC battle requests from the Adventure process and executes them.
 */
public class DesktopAdventureBattleHost {
    private static Thread monitorThread;
    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static final AtomicBoolean battleInProgress = new AtomicBoolean(false);

    // The match is already decided once it's over; this only waits for the UI to return home as a focus
    // cue, so a stalled navigation shouldn't pin the result forever.
    private static final long POST_MATCH_NAV_TIMEOUT_MS = 60_000;

    private static Runnable onBattleStarting;
    private static Runnable onBattleEnded;

    /**
     * Starts monitoring for Adventure battle requests.
     */
    public static void startMonitoring() {
        if (running.get()) {
            return;
        }

        running.set(true);

        monitorThread = new Thread(() -> {
            while (running.get()) {
                try {
                    if (IAdventureBattleHost.isBattlePending() && !battleInProgress.get()) {
                        handleBattleRequest();
                    }
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    System.err.println("DesktopAdventureBattleHost: Error in monitor loop: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }, "AdventureBattle-Monitor");
        monitorThread.setDaemon(true);
        monitorThread.start();
    }

    /**
     * Stops monitoring for battle requests.
     */
    public static void stopMonitoring() {
        running.set(false);
        if (monitorThread != null) {
            monitorThread.interrupt();
            monitorThread = null;
        }
    }

    /**
     * Sets callback for when a battle is starting.
     */
    public static void setOnBattleStarting(Runnable callback) {
        onBattleStarting = callback;
    }

    /**
     * Sets callback for when a battle has ended.
     */
    public static void setOnBattleEnded(Runnable callback) {
        onBattleEnded = callback;
    }

    /**
     * Handles a pending battle request.
     */
    private static void handleBattleRequest() {
        battleInProgress.set(true);

        try {
            BattleRequest request = BattleRequest.read();

            if (onBattleStarting != null) {
                javax.swing.SwingUtilities.invokeLater(onBattleStarting);
            }

            BattleResult result = hostBattle(request);
            result.write();

            if (onBattleEnded != null) {
                javax.swing.SwingUtilities.invokeLater(onBattleEnded);
            }

        } catch (Exception e) {
            System.err.println("DesktopAdventureBattleHost: Error handling battle: " + e.getMessage());
            e.printStackTrace();

            // Write a default loss result so Adventure does not hang waiting
            try {
                BattleResult result = new BattleResult();
                result.humanWon = false;
                result.shardsAfterBattle = 0;
                result.write();
            } catch (Exception e2) {
                e2.printStackTrace();
            }
        } finally {
            battleInProgress.set(false);
        }
    }

    /**
     * Hosts a battle with the desktop GUI and returns the result.
     */
    private static BattleResult hostBattle(BattleRequest request) {
        BattleResult result = new BattleResult();

        try {
            GameType gameType;
            try {
                gameType = GameType.valueOf(request.gameType);
            } catch (IllegalArgumentException e) {
                gameType = GameType.Adventure;
            }

            Set<GameType> appliedVariants = EnumSet.of(gameType);
            int playerCount = 1 + request.aiPlayers.size();

            Deck humanDeck = IAdventureBattleHost.loadHumanDeck();

            RegisteredPlayer humanPlayer = RegisteredPlayer.forVariants(playerCount, appliedVariants, humanDeck, null, false, null, null);
            LobbyPlayer humanLobby = GamePlayerUtil.getGuiPlayer();
            humanPlayer.setPlayer(humanLobby);
            humanPlayer.setTeamNumber(0);
            humanPlayer.setStartingLife(request.humanStartingLife);
            humanPlayer.setManaShards(request.humanManaShards);

            List<RegisteredPlayer> players = new ArrayList<>();

            for (AIPlayerData aiData : request.aiPlayers) {
                Deck aiDeck = IAdventureBattleHost.loadAiDeck(aiData.deckIndex);
                RegisteredPlayer aiPlayer = RegisteredPlayer.forVariants(playerCount, appliedVariants, aiDeck, null, false, null, null);

                LobbyPlayer aiLobby = GamePlayerUtil.createAiPlayer(aiData.name, aiData.aiType);
                aiPlayer.setPlayer(aiLobby);
                aiPlayer.setTeamNumber(aiData.teamNumber);
                aiPlayer.setStartingLife(aiData.startingLife);

                players.add(aiPlayer);
            }

            players.add(humanPlayer);

            GameRules rules = new GameRules(gameType);
            rules.setGamesPerMatch(request.gamesPerMatch);
            rules.setPlayForAnte(request.playForAnte);
            rules.setMatchAnteRarity(request.matchAnteRarity);
            rules.setManaBurn(false);
            rules.setWarnAboutAICards(false);

            MusicPlaylist playlist = request.isBossBattle ? MusicPlaylist.BOSS : MusicPlaylist.MATCH;

            // Holders let the EDT lambdas pass values back: lambdas require effectively final locals.
            final HostedMatch[] hostedMatchHolder = new HostedMatch[1];
            final IGuiGame[] guiHolder = new IGuiGame[1];
            final RegisteredPlayer humanPlayerFinal = humanPlayer;

            // Match start must happen on the EDT for Swing UI initialization
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                guiHolder[0] = GuiBase.getInterface().getNewGuiGame();
                Map<RegisteredPlayer, IGuiGame> guiMap = new HashMap<>();
                guiMap.put(humanPlayerFinal, guiHolder[0]);

                hostedMatchHolder[0] = GuiBase.getInterface().hostMatch();
                hostedMatchHolder[0].startMatch(rules, appliedVariants, players, guiMap, playlist);
            });

            HostedMatch hostedMatch = hostedMatchHolder[0];
            IGuiGame gui = guiHolder[0];

            // Controller wiring also touches Swing, so also on the EDT
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                for (Player p : hostedMatch.getGame().getPlayers()) {
                    if (p.getController() instanceof PlayerControllerHuman) {
                        PlayerControllerHuman humanController = (PlayerControllerHuman) p.getController();
                        humanController.setGui(gui);
                        gui.setOriginalGameController(p.getView(), humanController);
                        gui.openView(new TrackableCollection<>(p.getView()));
                    }
                }
            });

            // Unbounded on purpose: this is paced by the human actually playing the match out.
            while (!hostedMatch.getGame().getMatch().isMatchOver()) {
                Thread.sleep(100);
            }

            // Hold the result until Quit Match returns the screen to HOME_SCREEN — that click is the focus-handoff
            // cue. Bounded so a navigation that never reaches home doesn't hang the host (and the waiting Adventure).
            long navDeadline = System.currentTimeMillis() + POST_MATCH_NAV_TIMEOUT_MS;
            while (Singletons.getControl().getCurrentScreen() != FScreen.HOME_SCREEN
                    && System.currentTimeMillis() < navDeadline) {
                Thread.sleep(100);
            }

            result.humanWon = humanPlayer == hostedMatch.getGame().getMatch().getWinner();

            List<PlayerControllerHuman> humans = hostedMatch.getHumanControllers();
            if (!humans.isEmpty()) {
                result.shardsAfterBattle = humans.get(0).getPlayer().getNumManaShards();
            } else {
                result.shardsAfterBattle = request.humanManaShards;
            }

        } catch (Exception e) {
            System.err.println("DesktopAdventureBattleHost: Error in hostBattle: " + e.getMessage());
            e.printStackTrace();
            result.humanWon = false;
            result.shardsAfterBattle = request.humanManaShards;
        }

        return result;
    }

    /**
     * @return true if a battle is currently in progress
     */
    public static boolean isBattleInProgress() {
        return battleInProgress.get();
    }

    /**
     * @return true if the monitor is running
     */
    public static boolean isMonitoring() {
        return running.get();
    }
}
