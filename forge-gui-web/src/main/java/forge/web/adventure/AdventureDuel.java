package forge.web.adventure;

import com.badlogic.gdx.utils.Array;
import forge.adventure.character.EnemySprite;
import forge.adventure.data.EffectData;
import forge.adventure.data.EnemyData;
import forge.adventure.data.ItemData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.util.AdventureHost;
import forge.adventure.util.AdventureQuestController;
import forge.adventure.util.Config;
import forge.adventure.util.Current;
import forge.card.ColorSet;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckFormat;
import forge.deck.DeckProxy;
import forge.deck.DeckRule;
import forge.deck.DeckRuleColorIdentity;
import forge.deck.DeckRuleSize;
import forge.deck.DeckSection;
import forge.game.GameOutcome;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.GameView;
import forge.game.Match;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.gamemodes.match.PreparedMatch;
import forge.gamemodes.quest.QuestUtil;
import forge.item.IPaperCard;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgePreferences;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.util.Aggregates;
import forge.web.protocol.CampaignProtocol.PackCard;
import forge.web.protocol.CampaignProtocol.Reward;
import forge.web.protocol.CampaignProtocol.RewardStep;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * One battle against an enemy of the world map. The match is set up as the mobile DuelScene sets it up, and its result is applied as
 * DuelScene.GameEnd and WorldStage.setWinner apply it. Those methods are copied, because they are scene code the server cannot run.
 * Arena battles and events are not played in the browser, so their branches are left out. Every call runs on Forge's GUI thread.
 */
final class AdventureDuel {
    private static final String PLACEHOLDER_MAIN = "Wastes";
    private static final String PLACEHOLDER_COMMANDER = "Atogatog";
    private static final String PLACEHOLDER_ATTRACTION = "Coin-Operated Pony";
    private static final String PLACEHOLDER_CONTRAPTION = "Automatic Fidget Spinner";

    /** What a finished battle left: the loot to show after a win, and the defeat message after a loss that costs more than life. */
    record After(Reward reward, String defeat, boolean died) {
    }

    private final EnemySprite enemy;
    private final Consumer<EnemySprite> remove;
    private final Deck playerDeck;
    private final boolean chaosBattle;
    private RegisteredPlayer humanPlayer;
    private boolean finished;

    // Copied from DuelScene.initDuels, for a battle on the world map
    AdventureDuel(final EnemySprite enemy, final Consumer<EnemySprite> remove) {
        this.enemy = enemy;
        this.remove = remove;
        playerDeck = (Deck) Current.player().getSelectedDeck().copyTo("PlayerDeckCopy");
        chaosBattle = enemy.getData().copyPlayerDeck && Current.player().isFantasyMode();
    }

    EnemySprite enemy() {
        return enemy;
    }

    // Copied from DuelScene.enter, without the scene, avatars, music and intro dialogs
    PreparedMatch prepare() {
        final AdventurePlayer advPlayer = Current.player();
        final GameType mainGameType = advPlayer.isCommanderMode() ? GameType.Commander : GameType.Adventure;
        final Set<GameType> appliedVariants = EnumSet.of(mainGameType);
        final List<RegisteredPlayer> players = new ArrayList<>();

        int playerCount = 1;
        EnemyData currentEnemy = enemy.getData();
        for (int i = 0; i < 8 && currentEnemy != null; i++) {
            playerCount++;
            currentEnemy = currentEnemy.nextEnemy;
        }

        final Array<EffectData> playerEffects = new Array<>();
        final Array<EffectData> oppEffects = new Array<>();
        for (final Long id : advPlayer.getEquippedItems()) {
            final ItemData item = Current.player().getEquippedItem(id);
            if (item != null && item.effect != null) {
                playerEffects.add(item.effect);
                if (item.effect.opponent != null) {
                    oppEffects.add(item.effect.opponent);
                }
            }
        }
        if (advPlayer.getBlessing() != null) {
            playerEffects.add(advPlayer.getBlessing());
            if (advPlayer.getBlessing().opponent != null) {
                oppEffects.add(advPlayer.getBlessing().opponent);
            }
        }
        if (enemy.effect != null) {
            oppEffects.add(enemy.effect);
            if (enemy.effect.opponent != null) {
                playerEffects.add(enemy.effect.opponent);
            }
        }

        applyAdventureDeckRules(mainGameType.getDeckFormat(), allowsIllegalCommander(playerEffects));
        humanPlayer = RegisteredPlayer.forVariants(playerCount, appliedVariants, playerDeck, null, false, null, null);
        humanPlayer.setPlayer(GamePlayerUtil.getGuiPlayer());
        humanPlayer.setTeamNumber(0);
        humanPlayer.setStartingLife(advPlayer.getLife());
        humanPlayer.setManaShards(advPlayer.getShards());

        Map<DeckProxy, Pair<List<String>, List<String>>> challenges = null;
        DeckProxy challenge = null;
        if (chaosBattle) {
            challenges = DeckProxy.getAllQuestChallenges();
            challenge = Aggregates.random(challenges.keySet());
            final List<IPaperCard> playerCards = new ArrayList<>();
            for (final String s : challenges.get(challenge).getLeft()) {
                playerCards.add(QuestUtil.readExtraCard(s));
            }
            humanPlayer.addExtraCardsOnBattlefield(playerCards);
        }
        addEffects(humanPlayer, playerEffects);

        currentEnemy = enemy.getData();
        final boolean bossBattle = currentEnemy.boss;
        for (int i = 0; i < playerCount && currentEnemy != null; i++) {
            Deck deck;
            List<IPaperCard> aiExtras = List.of();
            if (chaosBattle) {
                if (challenges == null) {
                    continue;
                }
                aiExtras = new ArrayList<>();
                for (final String s : challenges.get(challenge).getRight()) {
                    aiExtras.add(QuestUtil.readExtraCard(s));
                }
                deck = challenge.getDeck();
            } else if (currentEnemy.copyPlayerDeck) {
                deck = playerDeck;
            } else {
                deck = generateEnemyDeck(currentEnemy);
            }
            if (deck == null) {
                deck = Config.instance().getConfigData().enableGeneticAI ? Aggregates.random(DeckProxy.getAllGeneticAIDecks()).getDeck() : playerDeck;
            }
            if (deck != playerDeck && mainGameType.getDeckFormat().hasCommander() && !allowsIllegalCommander(oppEffects)) {
                deck = withoutIllegalCommanders(deck, mainGameType.getDeckFormat());
            }
            final RegisteredPlayer aiPlayer = RegisteredPlayer.forVariants(playerCount, appliedVariants, deck, null, false, null, null);
            aiPlayer.setPlayer(GamePlayerUtil.createAiPlayer(currentEnemy.getName(), selectAI(currentEnemy.ai, bossBattle)));
            aiPlayer.getPlayer().setName(enemy.getDisplayName());
            aiPlayer.setTeamNumber(currentEnemy.teamNumber);
            aiPlayer.setStartingLife(Math.round((float) currentEnemy.life * advPlayer.getDifficulty().enemyLifeFactor));
            addEffects(aiPlayer, oppEffects);
            if (chaosBattle) {
                aiPlayer.addExtraCardsOnBattlefield(aiExtras);
            }
            players.add(aiPlayer);
            Current.setLatestDeck(deck);
            currentEnemy = currentEnemy.nextEnemy;
        }
        players.add(humanPlayer);

        final GameRules rules = new GameRules(GameType.Adventure);
        rules.setGamesPerMatch(enemy.getData().gamesPerMatch);
        rules.setPlayForAnte(FModel.getPreferences().getPrefBoolean(ForgePreferences.FPref.UI_ANTE));
        rules.setMatchAnteRarity(FModel.getPreferences().getPrefBoolean(ForgePreferences.FPref.UI_ANTE_MATCH_RARITY));
        rules.setAnteIncludeBasicLands(FModel.getPreferences().getPrefBoolean(ForgePreferences.FPref.UI_ANTE_INCLUDE_BASIC_LANDS));
        rules.setManaBurn(false);
        rules.setWarnAboutAICards(false);
        return new PreparedMatch(rules, appliedVariants, players, humanPlayer);
    }

    // Copied from DuelScene.generateEnemyDeck, for a battle that is not an arena challenge
    private static Deck generateEnemyDeck(final EnemyData e) {
        final boolean useGeneticAI = Config.instance().getConfigData().enableGeneticAI
                && (Current.player().isUsingCustomDeck() || Current.player().isHardorInsaneDifficulty());
        return e.generateDeck(Current.player().isFantasyMode(), useGeneticAI);
    }

    // Copied from DuelScene.selectAI
    private static String selectAI(final String ai, final boolean boss) {
        String chosen = "";
        if (ai != null) {
            chosen = switch (ai.toLowerCase()) {
                case "default" -> "Default";
                case "reckless" -> "Reckless";
                case "cautious" -> "Cautious";
                case "experimental" -> "Experimental";
                case "hard" -> "Hard";
                case "hardest" -> "Hardest";
                default -> "";
            };
        }
        if (chosen.isEmpty() && boss) {
            chosen = "Hardest";
        }
        return chosen;
    }

    // Copied from DuelScene.addEffects
    private static void addEffects(final RegisteredPlayer player, final Array<EffectData> effects) {
        if (effects == null) {
            return;
        }
        int lifeMod = 0;
        int changeStartCards = 0;
        int extraManaShards = 0;
        final Array<IPaperCard> startCards = new Array<>();
        final Array<IPaperCard> startCardsInCommandZone = new Array<>();
        for (final EffectData data : effects) {
            lifeMod += data.lifeModifier;
            changeStartCards += data.changeStartCards;
            startCards.addAll(data.startBattleWithCards());
            startCardsInCommandZone.addAll(data.startBattleWithCardsInCommandZone());
            if (data.startBattleScript != null) {
                player.addStartGameScript(Arrays.asList(data.startBattleScript));
            }
            extraManaShards += data.extraManaShards;
        }
        player.addExtraCardsOnBattlefield(startCards);
        player.addExtraCardsInCommandZone(startCardsInCommandZone);
        if (lifeMod != 0) {
            player.setStartingLife(Math.max(1, lifeMod + player.getStartingLife()));
        }
        player.setStartingHand(player.getStartingHand() + changeStartCards);
        player.setManaShards(player.getManaShards() + extraManaShards);
        player.setEnableETBCountersEffect(true);
    }

    /**
     * A game of the match ended. When the match is over, its shards and ante are kept as DuelScene.GameEnd keeps them, and the
     * result is applied. Returns what the battle left, or null while the match goes on.
     */
    After gameOver(final GameView hostGame) {
        if (finished || !hostGame.isMatchOver()) {
            return null;
        }
        final Match match = hostGame.getMatch();
        final boolean winner = humanPlayer != null && humanPlayer == match.getWinner();
        // Copied from DuelScene.GameEnd
        for (final Player p : hostGame.getGame().getPlayers()) {
            if (p.getRegisteredPlayer() == humanPlayer) {
                Current.player().setShards(p.getNumManaShards());
            }
        }
        final GameOutcome.AnteResult ante = humanPlayer == null ? null : match.getAnteResult(humanPlayer);
        if (ante != null) {
            for (final PaperCard card : ante.wonCards) {
                Current.player().addCard(card);
            }
            for (final PaperCard card : ante.lostCards) {
                Current.player().removeLostCardFromPools(card);
            }
        }
        return finish(winner);
    }

    /** The match was left before it ended, which mobile also counts as a loss. Returns null once the battle is finished. */
    After forfeit() {
        return finished ? null : finish(false);
    }

    /** The name the human plays the match under, which the game names its winner by. */
    String humanName() {
        return humanPlayer == null ? null : humanPlayer.getPlayer().getName();
    }

    // Copied from the end of DuelScene.GameEnd and from WorldStage.setWinner, without the animations
    After finish(final boolean won) {
        finished = true;
        Current.player().clearBlessing();
        Current.player().getStatistic().setResult(enemy.getName(), won);
        if (won) {
            Current.player().win();
            final Array<forge.adventure.util.Reward> loot = enemy.getRewards();
            remove.accept(enemy);
            AdventureQuestController.instance().updateQuestsWin(enemy);
            AdventureHost.get().showQuestDialogs();
            return new After(collect(loot), null, false);
        }
        final boolean died = Current.player().defeated();
        AdventureQuestController.instance().updateQuestsLose(enemy);
        AdventureHost.get().showQuestDialogs();
        final boolean boss = enemy.getData().boss;
        remove.accept(enemy);
        if (died) {
            return new After(null, Current.generateDefeatMessage(true), true);
        }
        if (boss && Current.player().hasEquippedItem()) {
            return new After(null, Current.generateDefeatMessage(false), false);
        }
        return new After(null, null, false);
    }

    /** Gives the loot to the player, as RewardScene does when it closes, and describes it for the reward page. */
    static Reward collect(final Array<forge.adventure.util.Reward> loot) {
        final List<RewardStep> steps = new ArrayList<>();
        final List<PackCard> cards = new ArrayList<>();
        for (final forge.adventure.util.Reward r : loot) {
            Current.player().addReward(r);
            switch (r.getType()) {
                case Card -> cards.add(new PackCard(r.getCard().getName(), r.getCard().getImageKey(false), r.getCard().getRarity().name(), 0));
                case Gold -> steps.add(message(r.getCount() + " gold"));
                case Life -> steps.add(message("+" + r.getCount() + " life"));
                case Shards -> steps.add(message(r.getCount() + " shards"));
                case Item -> steps.add(message(r.getItem().getDisplayName()));
                default -> { }
            }
        }
        if (!cards.isEmpty()) {
            steps.add(0, new RewardStep("CARDS", cards.size(), null, cards, 0, 0, false, null, null, null, null, null, null));
        }
        return steps.isEmpty() ? null : new Reward(steps);
    }

    private static RewardStep message(final String title) {
        return new RewardStep("MESSAGE", 0, null, null, 0, 0, false, null, null, null, title, null, null);
    }

    private static boolean allowsIllegalCommander(final Array<EffectData> effects) {
        for (final EffectData effect : effects) {
            if (effect.allowIllegalCommander) {
                return true;
            }
        }
        return false;
    }

    // Copied from DuelScene.withoutIllegalCommanders
    private static Deck withoutIllegalCommanders(final Deck deck, final DeckFormat format) {
        final List<PaperCard> illegal = new ArrayList<>();
        for (final PaperCard commander : deck.getCommanders()) {
            if (!format.isLegalCommander(commander.getRules())) {
                illegal.add(commander);
            }
        }
        if (illegal.isEmpty()) {
            return deck;
        }
        final Deck copy = new Deck(deck);
        copy.get(DeckSection.Commander).removeAllFlat(illegal);
        copy.getMain().add(illegal);
        return copy;
    }

    // Copied from DuelScene.applyAdventureDeckRules
    private void applyAdventureDeckRules(final DeckFormat format, final boolean allowIllegalCommander) {
        if (FModel.getPreferences().getPrefBoolean(ForgePreferences.FPref.DEV_MODE_ENABLED)
                && !FModel.getPreferences().getPrefBoolean(ForgePreferences.FPref.ENFORCE_DECK_LEGALITY)) {
            return;
        }
        final CardPool mainSection = playerDeck.getMain();
        final CardPool attractions = playerDeck.get(DeckSection.Attractions);
        final CardPool contraptions = playerDeck.get(DeckSection.Contraptions);
        if (format.hasCommander()) {
            applyAdventureCommandZoneRules(playerDeck, format, allowIllegalCommander);
        }
        removeExcessCopies(mainSection, format);
        removeExcessCopies(attractions, format);
        removeExcessCopies(contraptions, format);
        int mainSize = mainSection.countAll();
        int maxDeckSize = format == DeckFormat.Adventure ? Integer.MAX_VALUE : format.getMainRange().getMaximum();
        final int minDeckSize = format == DeckFormat.Adventure ? Config.instance().getConfigData().minDeckSize : format.getMainRange().getMinimum();
        if (format.hasCommander() && playerDeck.has(DeckSection.Commander)) {
            mainSize += Math.max(0, playerDeck.get(DeckSection.Commander).countAll() - 1);
            maxDeckSize = applyCommanderSizeRule(playerDeck.getCommanders(), maxDeckSize);
        }
        final int excessCards = mainSize - maxDeckSize;
        if (excessCards > 0) {
            mainSection.removeAllFlat(Aggregates.random(mainSection.toFlatList(), excessCards));
        }
        final int missingCards = minDeckSize - mainSize;
        if (missingCards > 0) {
            mainSection.add(PLACEHOLDER_MAIN, missingCards);
        }
        if (attractions != null && !attractions.isEmpty() && 10 - attractions.countAll() > 0) {
            attractions.add(PLACEHOLDER_ATTRACTION, 10 - attractions.countAll());
        }
        if (contraptions != null && !contraptions.isEmpty() && 15 - contraptions.countAll() > 0) {
            contraptions.add(PLACEHOLDER_CONTRAPTION, 15 - contraptions.countAll());
        }
    }

    // Copied from DuelScene.removeExcessCopies
    private static void removeExcessCopies(final CardPool section, final DeckFormat format) {
        if (section == null) {
            return;
        }
        final Map<String, List<PaperCard>> removals = new HashMap<>();
        for (final Map.Entry<PaperCard, Integer> e : section) {
            final PaperCard card = e.getKey();
            final String cardName = card.getCardName();
            if (removals.containsKey(cardName)) {
                continue;
            }
            final int amount = section.countByName(cardName);
            final int limit = format.getMaxCardCopies(card);
            if (amount > limit) {
                removals.put(cardName, getItemsToRemove(section, cardName, amount - limit));
            }
        }
        for (final List<PaperCard> list : removals.values()) {
            section.removeAllFlat(list);
        }
    }

    // Copied from DuelScene.getItemsToRemove
    private static List<PaperCard> getItemsToRemove(final CardPool section, final String cardName, final int copies) {
        final List<PaperCard> matched = new ArrayList<>();
        for (final PaperCard card : section.toFlatList()) {
            if (card != null && card.getCardName().equals(cardName)) {
                matched.add(card);
            }
        }
        Collections.shuffle(matched);
        return matched.size() > copies ? matched.subList(0, copies) : matched;
    }

    // Copied from DuelScene.applyCommanderSizeRule
    private static int applyCommanderSizeRule(final List<PaperCard> commanders, int maxDeckSize) {
        for (final PaperCard commander : commanders) {
            for (final DeckRule rule : DeckRule.parseAll(commander)) {
                if (!(rule instanceof DeckRuleSize sizeRule) || !rule.isActiveFor(DeckSection.Commander)) {
                    continue;
                }
                if (sizeRule.removesMaxDeckSize()) {
                    maxDeckSize = Integer.MAX_VALUE;
                } else if (maxDeckSize != Integer.MAX_VALUE) {
                    maxDeckSize += sizeRule.getMaxDelta();
                }
            }
        }
        return maxDeckSize;
    }

    // Copied from DuelScene.applyAdventureCommandZoneRules
    private static void applyAdventureCommandZoneRules(final Deck playerDeck, final DeckFormat format, final boolean allowIllegalCommander) {
        final CardPool commandPool = playerDeck.getOrCreate(DeckSection.Commander);
        final List<PaperCard> removals = new ArrayList<>();
        List<PaperCard> commanders = playerDeck.getCommanders();
        final boolean enforceLegality = !allowIllegalCommander && FModel.getPreferences().getPrefBoolean(ForgePreferences.FPref.ENFORCE_DECK_LEGALITY);
        if (commanders.size() > 2) {
            removals.addAll(commanders.subList(2, commanders.size()));
            commanders = commanders.subList(0, 2);
        }
        if (!commanders.isEmpty()) {
            final PaperCard mainCommander = commanders.get(0);
            if (!format.isAllowedCommander(mainCommander.getRules(), enforceLegality)) {
                removals.add(mainCommander);
            }
            if (commanders.size() > 1) {
                final PaperCard partnerCommander = commanders.get(1);
                if (removals.contains(mainCommander)) {
                    if (!format.isAllowedCommander(partnerCommander.getRules(), enforceLegality)) {
                        removals.add(partnerCommander);
                    }
                } else if (!format.isAllowedPartnership(mainCommander.getRules(), partnerCommander.getRules(), enforceLegality)) {
                    removals.add(partnerCommander);
                }
            }
        }
        commandPool.removeAllFlat(removals);
        final CardPool mainPool = playerDeck.getMain();
        mainPool.add(removals);
        if (commandPool.isEmpty()) {
            commandPool.add(PLACEHOLDER_COMMANDER, 1);
        }
        for (final Map.Entry<PaperCard, Integer> e : commandPool) {
            final PaperCard card = e.getKey();
            final int limit = format.getMaxCardCopies(card);
            final int amountMain = mainPool.countByName(card);
            if (amountMain > 0) {
                final int toRemove = Math.max(0, amountMain + commandPool.countByName(card) - limit);
                if (toRemove > 0) {
                    mainPool.removeAllFlat(getItemsToRemove(mainPool, card.getCardName(), toRemove));
                }
            }
        }
        byte cmdCI = 0;
        int wildColors = 0;
        final List<DeckRuleColorIdentity> ciRules = new ArrayList<>();
        for (final PaperCard commander : playerDeck.getCommanders()) {
            cmdCI |= commander.getRules().getColorIdentity().getColor();
            wildColors += commander.getRules().getAddsWildCardColor() ? 1 : 0;
            for (final DeckRule rule : DeckRule.parseAll(commander)) {
                if (rule instanceof DeckRuleColorIdentity ci && rule.isActiveFor(DeckSection.Commander)) {
                    ciRules.add(ci);
                }
            }
        }
        for (final Map.Entry<PaperCard, Integer> e : mainPool) {
            final PaperCard card = e.getKey();
            if (DeckFormat.allowsOffColorIdentity(ciRules, card.getRules()) || DeckFormat.approvesAdditionalColor(ciRules, card.getRules(), cmdCI)) {
                continue;
            }
            final ColorSet missingColors = card.getRules().getColorIdentity().getMissingColors(cmdCI);
            if (missingColors.countColors() > 0) {
                if (missingColors.countColors() <= wildColors) {
                    wildColors -= missingColors.countColors();
                    cmdCI |= missingColors.getColor();
                } else {
                    mainPool.removeAll(card);
                }
            }
        }
    }
}
