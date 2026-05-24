package forge.adventure;

import java.io.IOException;
import java.util.List;

import com.badlogic.gdx.Gdx;

import forge.adventure.util.Current;
import forge.deck.Deck;
import forge.game.GameType;
import forge.game.player.RegisteredPlayer;
import forge.localinstance.properties.ForgePreferences;
import forge.model.FModel;
import forge.screens.LoadingOverlay;

/**
 * Mobile-side glue for the experimental Desktop Adventure Mode. Replaces the
 * normal mobile match flow with an IPC handoff to the desktop process that
 * spawned us, which renders the match in its native Swing UI.
 *
 * All knowledge of the IPC protocol, polling, and waiting overlay lives here.
 * Callers tell us to run a battle and react to the result via the listener.
 */
public final class DesktopAdventureBridge {

    private static LoadingOverlay waitingOverlay;

    // Last-resort cap so a dead or orphaned desktop host can't pin the waiter thread forever. Far longer than
    // any real match, so it never fires during normal play — the desktop signals completion long before this.
    private static final long MAX_BATTLE_WAIT_MS = 6L * 60 * 60 * 1000;

    private DesktopAdventureBridge() {}

    public static boolean isActive() {
        return DesktopAdventureMode.isActive();
    }

    public static void runBattle(final BattleParams params, final BattleResultListener listener) {
        try {
            final IAdventureBattleHost.BattleRequest request = buildRequest(params);
            request.write();

            waitingOverlay = new LoadingOverlay("Waiting for desktop to resolve battle...", true);
            waitingOverlay.show();
            listener.onWaitStart();

            new Thread(() -> waitForResult(params, listener), "DesktopBattle-Waiter").start();
        } catch (Exception e) {
            System.err.println("Failed to start desktop battle: " + e.getMessage());
            e.printStackTrace();
            hideOverlay();
            Gdx.app.postRunnable(listener::onError);
        }
    }

    private static IAdventureBattleHost.BattleRequest buildRequest(final BattleParams params) {
        final IAdventureBattleHost.BattleRequest request = new IAdventureBattleHost.BattleRequest();

        request.humanPlayerName = params.humanPlayer.getPlayer().getName();
        request.humanStartingLife = params.humanPlayer.getStartingLife();
        request.humanManaShards = params.humanPlayer.getManaShards();

        IAdventureBattleHost.saveHumanDeck(params.playerDeck);

        int aiIndex = 0;
        for (final RegisteredPlayer p : params.players) {
            if (p == params.humanPlayer) {
                continue;
            }
            IAdventureBattleHost.saveAiDeck(p.getDeck(), aiIndex);

            final IAdventureBattleHost.AIPlayerData aiData = new IAdventureBattleHost.AIPlayerData();
            aiData.name = p.getPlayer().getName();
            aiData.deckIndex = aiIndex;
            aiData.startingLife = p.getStartingLife();
            aiData.teamNumber = p.getTeamNumber();
            aiData.aiType = "";
            request.aiPlayers.add(aiData);
            aiIndex++;
        }

        request.gameType = params.gameType.name();
        request.gamesPerMatch = params.gamesPerMatch;
        request.playForAnte = FModel.getPreferences().getPrefBoolean(ForgePreferences.FPref.UI_ANTE);
        request.matchAnteRarity = FModel.getPreferences().getPrefBoolean(ForgePreferences.FPref.UI_ANTE_MATCH_RARITY);
        request.isBossBattle = params.bossBattle;
        request.enemyName = params.enemyName;
        return request;
    }

    private static void waitForResult(final BattleParams params, final BattleResultListener listener) {
        try {
            long deadline = System.currentTimeMillis() + MAX_BATTLE_WAIT_MS;
            while (!IAdventureBattleHost.isBattleComplete()) {
                if (System.currentTimeMillis() > deadline) {
                    throw new IOException("Timed out waiting for desktop to resolve battle");
                }
                Thread.sleep(500);
            }

            final IAdventureBattleHost.BattleResult result = IAdventureBattleHost.BattleResult.read();

            if (params.allowsShards) {
                Current.player().setShards(result.shardsAfterBattle);
            }

            IAdventureBattleHost.clearBattleCompleteSignal();

            Gdx.app.postRunnable(() -> {
                hideOverlay();
                DesktopAdventureMode.requestFocus();
                listener.onComplete(result.humanWon);
            });
        } catch (Throwable e) {
            System.err.println("Error waiting for desktop battle: " + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace();
            Gdx.app.postRunnable(() -> {
                hideOverlay();
                listener.onError();
            });
        }
    }

    private static void hideOverlay() {
        if (waitingOverlay != null) {
            waitingOverlay.hide();
            waitingOverlay = null;
        }
    }

    public static final class BattleParams {
        public final List<RegisteredPlayer> players;
        public final RegisteredPlayer humanPlayer;
        public final Deck playerDeck;
        public final boolean bossBattle;
        public final GameType gameType;
        public final int gamesPerMatch;
        public final String enemyName;
        public final boolean allowsShards;

        public BattleParams(final List<RegisteredPlayer> players, final RegisteredPlayer humanPlayer,
                            final Deck playerDeck, final boolean bossBattle, final GameType gameType,
                            final int gamesPerMatch, final String enemyName, final boolean allowsShards) {
            this.players = players;
            this.humanPlayer = humanPlayer;
            this.playerDeck = playerDeck;
            this.bossBattle = bossBattle;
            this.gameType = gameType;
            this.gamesPerMatch = gamesPerMatch;
            this.enemyName = enemyName;
            this.allowsShards = allowsShards;
        }
    }

    public interface BattleResultListener {
        void onWaitStart();
        void onComplete(boolean humanWon);
        void onError();
    }
}
