package forge.adventure;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.Gdx;

import forge.adventure.util.Current;
import forge.ai.AiProfileUtil;
import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.game.GameType;
import forge.game.player.RegisteredPlayer;
import forge.item.IPaperCard;
import forge.item.PaperToken;
import forge.localinstance.properties.ForgePreferences;
import forge.model.FModel;
import forge.screens.LoadingOverlay;

/** Hands a battle to the web server that adventure started, shows a waiting overlay, and reports the result it writes back. */
public final class DesktopAdventureBridge {

    private static LoadingOverlay waitingOverlay;

    // Far longer than any real match, so it only frees the waiter thread when the server hangs
    private static final long MAX_BATTLE_WAIT_MS = 6L * 60 * 60 * 1000;

    private DesktopAdventureBridge() {}

    public static boolean isActive() {
        return DesktopAdventureMode.isActive();
    }

    public static void runBattle(final BattleParams params, final BattleResultListener listener) {
        try {
            final IAdventureBattleHost.BattleRequest request = buildRequest(params);
            request.write();

            waitingOverlay = new LoadingOverlay("Playing the battle in the browser...", true);
            waitingOverlay.show();
            listener.onWaitStart();

            new Thread(() -> waitForResult(params, listener), "WebBattle-Waiter").start();
        } catch (Exception e) {
            System.err.println("Failed to hand the battle to the web server: " + e.getMessage());
            e.printStackTrace();
            hideOverlay();
            Gdx.app.postRunnable(listener::onError);
        }
    }

    private static IAdventureBattleHost.BattleRequest buildRequest(final BattleParams params) {
        final IAdventureBattleHost.BattleRequest request = new IAdventureBattleHost.BattleRequest();

        request.humanStartingLife = params.humanPlayer.getStartingLife();
        request.humanManaShards = params.humanPlayer.getManaShards();
        request.humanStartingHand = params.humanPlayer.getStartingHand();
        request.humanEnableETBCountersEffect = params.humanPlayer.hasEnableETBCountersEffect();
        request.humanExtraCardsOnBattlefield = cardNames(params.humanPlayer.getCardsOnBattlefield());
        request.humanExtraCardsInCommandZone = cardNames(params.humanPlayer.getExtraCardsInCommandZone());
        request.humanStartGameScripts = params.humanPlayer.getStartGameScripts();

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
            aiData.aiType = resolveAiProfile(p);
            aiData.startingHand = p.getStartingHand();
            aiData.manaShards = p.getManaShards();
            aiData.enableETBCountersEffect = p.hasEnableETBCountersEffect();
            aiData.extraCardsOnBattlefield = cardNames(p.getCardsOnBattlefield());
            aiData.extraCardsInCommandZone = cardNames(p.getExtraCardsInCommandZone());
            aiData.startGameScripts = p.getStartGameScripts();
            request.aiPlayers.add(aiData);
            aiIndex++;
        }

        request.gameType = params.gameType.name();
        request.rulesType = params.rulesType.name();
        request.gamesPerMatch = params.gamesPerMatch;
        request.playForAnte = FModel.getPreferences().getPrefBoolean(ForgePreferences.FPref.UI_ANTE);
        request.matchAnteRarity = FModel.getPreferences().getPrefBoolean(ForgePreferences.FPref.UI_ANTE_MATCH_RARITY);
        request.anteIncludeBasicLands = FModel.getPreferences().getPrefBoolean(ForgePreferences.FPref.UI_ANTE_INCLUDE_BASIC_LANDS);
        request.assignConspiracies = params.assignConspiracies;
        return request;
    }

    private static List<String> cardNames(final Iterable<? extends IPaperCard> cards) {
        final List<String> names = new ArrayList<>();
        if (cards != null) {
            for (final IPaperCard card : cards) {
                // The server looks tokens up by script key (e.g. "komas_coil"), not by display name
                if (card instanceof PaperToken token) {
                    names.add(token.getImageFilename(1).split("\\|")[0]);
                } else {
                    names.add(card.getName());
                }
            }
        }
        return names;
    }

    private static String resolveAiProfile(final RegisteredPlayer p) {
        if (!(p.getPlayer() instanceof LobbyPlayerAi ai)) {
            return "";
        }
        final String profile = ai.getAiProfile();
        // The server would not resolve a "random" profile, so it is resolved here
        if (AiProfileUtil.AI_PROFILE_RANDOM_DUEL.equals(profile) || AiProfileUtil.AI_PROFILE_RANDOM_MATCH.equals(profile)) {
            return AiProfileUtil.getRandomProfile();
        }
        return profile;
    }

    private static void waitForResult(final BattleParams params, final BattleResultListener listener) {
        try {
            long deadline = System.currentTimeMillis() + MAX_BATTLE_WAIT_MS;
            while (!IAdventureBattleHost.isBattleComplete()) {
                if (System.currentTimeMillis() > deadline) {
                    throw new IOException("Timed out waiting for the web server's battle result");
                }
                // Checked again, because the server may write its result just before it exits
                if (!DesktopAdventureMode.isActive() && !IAdventureBattleHost.isBattleComplete()) {
                    throw new IOException("The web server stopped before the battle ended");
                }
                Thread.sleep(500);
            }

            final IAdventureBattleHost.BattleResult result = IAdventureBattleHost.BattleResult.read();

            if (params.allowsShards && result.shardsAfterBattle >= 0) {
                Current.player().setShards(result.shardsAfterBattle);
            }

            IAdventureBattleHost.clearBattleCompleteSignal();

            Gdx.app.postRunnable(() -> {
                hideOverlay();
                DesktopAdventureMode.requestFocus();
                listener.onComplete(result);
            });
        } catch (Throwable e) {
            System.err.println("Error waiting for the web server's battle result: " + e.getClass().getName() + ": " + e.getMessage());
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
        public final GameType gameType;
        public final GameType rulesType;
        public final int gamesPerMatch;
        public final boolean allowsShards;
        public final boolean assignConspiracies;

        public BattleParams(final List<RegisteredPlayer> players, final RegisteredPlayer humanPlayer,
                            final Deck playerDeck, final GameType gameType, final GameType rulesType,
                            final int gamesPerMatch, final boolean allowsShards, final boolean assignConspiracies) {
            this.players = players;
            this.humanPlayer = humanPlayer;
            this.playerDeck = playerDeck;
            this.gameType = gameType;
            this.rulesType = rulesType;
            this.gamesPerMatch = gamesPerMatch;
            this.allowsShards = allowsShards;
            this.assignConspiracies = assignConspiracies;
        }
    }

    public interface BattleResultListener {
        void onWaitStart();
        void onComplete(IAdventureBattleHost.BattleResult result);
        void onError();
    }
}
