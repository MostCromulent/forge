package forge.gamemodes.rogue.flow;

import forge.deck.Deck;
import forge.game.GameType;
import forge.game.player.RegisteredPlayer;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.rogue.CardRewardHelper;
import forge.gamemodes.rogue.RogueEvent;
import forge.gamemodes.rogue.RogueRun;
import forge.gamemodes.rogue.RogueTutorial;
import forge.gamemodes.rogue.effect.BazaarItem;
import forge.gamemodes.rogue.effect.ChestEffect;
import forge.gamemodes.rogue.effect.ChoiceRerollContext;
import forge.gamemodes.rogue.effect.CursedEffect;
import forge.gamemodes.rogue.effect.NPCEffect;
import forge.gamemodes.rogue.effect.SanctumContext;
import forge.gamemodes.rogue.effect.WrathfulEffect;
import forge.gamemodes.rogue.npc.NPCContext;
import forge.gamemodes.rogue.path.NodePlanebound;
import forge.item.PaperCard;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/** What a {@link RogueFlow} asks and announces. A question or an announcement blocks until the player is done with it. */
public interface RogueDecisions extends CardRewardHelper.RewardDialog {
    /** REPLACE: the picked cards take the place of others in the deck. */
    enum CardChoiceKind { STARTING_DECK, ADD, REMOVE, REPLACE }
    enum SanctumAction { HEAL, COOK, REFLECT, CUSTOM, SKIP }
    enum ResultKind { EVENT, BOON, SANCTUM, REVIVE, DEFEAT }

    /** choice is null when the player skips. */
    record Pick<T>(boolean reroll, T choice) { }
    record SanctumOffer(String restDescription, boolean restEnabled, String restDisabledReason,
                        SanctumContext context, RogueRun run) { }
    record SanctumAnswer(SanctumAction action, SanctumContext.SanctumChoice custom) { }
    /** rerollLabel is null in a shop that has no reroll. */
    record ShopOffer(List<BazaarItem> items, RogueRun run, int gold, String title, String rerollLabel,
                     int rerollCost, boolean rerollEnabled, int tab) { }
    record ShopAnswer(boolean reroll, Set<BazaarItem> selected, int tab) { }
    /** preview makes a deck of the cards picked so far, and is null when the deck to view is the run's. */
    record CardChoice(CardChoiceKind kind, String title, String subtitle, List<PaperCard> candidates, RogueRun run,
                      int min, int max, Function<List<PaperCard>, Deck> preview) { }
    record Section(String label, String text, List<PaperCard> cards) { }
    /** recordPublicCards is for the end of the game. */
    record RogueMatch(RogueRun run, NodePlanebound node, Set<GameType> variants, List<RegisteredPlayer> players,
                      RegisteredPlayer human, Consumer<HostedMatch> recordPublicCards) { }

    /** Empty when the player cancels. */
    List<PaperCard> chooseCommanders();
    int choosePortrait(PaperCard commander, int current);
    List<PaperCard> chooseCards(CardChoice choice);
    boolean confirmReplaceRun();
    /** A story is a context with no choices. */
    Pick<NPCEffect> chooseBoon(NPCContext context, ChoiceRerollContext rerolls);
    Pick<ChestEffect> chooseChest(List<ChestEffect> loot, RogueRun run, ChoiceRerollContext rerolls);
    Pick<RogueEvent.EventChoice> chooseEvent(RogueEvent event, RogueRun run, ChoiceRerollContext rerolls);
    SanctumAnswer chooseSanctum(SanctumOffer offer);
    ShopAnswer shop(ShopOffer offer);
    /** Answers current when the player cancels. */
    <T> List<T> devPick(String title, List<T> all, List<T> current, int count);

    void showPlaneboundEffects(NodePlanebound node, List<WrathfulEffect> wrathful, List<CursedEffect> cursed);
    void showResult(ResultKind kind, String title, String text, List<Section> sections);
    void showMessage(String title, String text);
    /** The client decides whether to show it, and marks it seen. */
    void showTutorial(RogueTutorial tutorial);

    /** Returns once the match has started. It is the last thing a flow does. */
    void startMatch(RogueMatch match) throws Exception;
    void mapChanged(RogueRun run);
    void runStarted(RogueRun run);
    void runEnded();
}
