package forge.web;

import forge.gamemodes.rogue.RogueEvent;
import forge.gamemodes.rogue.RogueRun;
import forge.gamemodes.rogue.RogueTutorial;
import forge.gamemodes.rogue.effect.ChestEffect;
import forge.gamemodes.rogue.effect.ChoiceRerollContext;
import forge.gamemodes.rogue.effect.CursedEffect;
import forge.gamemodes.rogue.effect.NPCEffect;
import forge.gamemodes.rogue.effect.WrathfulEffect;
import forge.gamemodes.rogue.flow.RogueDecisions;
import forge.gamemodes.rogue.npc.NPCContext;
import forge.gamemodes.rogue.path.NodePlanebound;
import forge.item.PaperCard;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Answers a flow's questions from a script. Each method's name has its own queue of answers, and a question with none left fails the test. */
final class ScriptedDecisions implements RogueDecisions {
    /** The methods called so far, without the ones that only tell the client to redraw. */
    final List<String> asked = new ArrayList<>();
    private final Map<String, Deque<Function<Object[], Object>>> script = new HashMap<>();

    ScriptedDecisions then(final String question, final Object answer) {
        return then(question, args -> answer);
    }

    /** The function gets the method's arguments. It may also be given for an announcement. */
    ScriptedDecisions then(final String question, final Function<Object[], Object> answer) {
        script.computeIfAbsent(question, q -> new ArrayDeque<>()).add(answer);
        return this;
    }

    @SuppressWarnings("unchecked")
    private <T> T ask(final String question, final Object... args) {
        asked.add(question);
        final Function<Object[], Object> answer = next(question);
        if (answer == null) {
            throw new AssertionError("no answer left for " + question + " in " + asked);
        }
        return (T) answer.apply(args);
    }

    private void announce(final String announcement, final Object... args) {
        asked.add(announcement);
        redraw(announcement, args);
    }

    private void redraw(final String announcement, final Object... args) {
        final Function<Object[], Object> reaction = next(announcement);
        if (reaction != null) {
            reaction.apply(args);
        }
    }

    private Function<Object[], Object> next(final String name) {
        final Deque<Function<Object[], Object>> answers = script.get(name);
        return answers == null ? null : answers.poll();
    }

    @Override
    public List<PaperCard> chooseCommanders() {
        return ask("chooseCommanders");
    }

    @Override
    public int choosePortrait(final PaperCard commander, final int current) {
        return this.<Integer>ask("choosePortrait", commander, current);
    }

    @Override
    public List<PaperCard> chooseCards(final CardChoice choice) {
        return ask("chooseCards", choice);
    }

    @Override
    public boolean confirmReplaceRun() {
        return this.<Boolean>ask("confirmReplaceRun");
    }

    @Override
    public Pick<NPCEffect> chooseBoon(final NPCContext context, final ChoiceRerollContext rerolls) {
        return ask("chooseBoon", context, rerolls);
    }

    @Override
    public Pick<ChestEffect> chooseChest(final List<ChestEffect> loot, final RogueRun run, final ChoiceRerollContext rerolls) {
        return ask("chooseChest", loot, run, rerolls);
    }

    @Override
    public Pick<RogueEvent.EventChoice> chooseEvent(final RogueEvent event, final RogueRun run, final ChoiceRerollContext rerolls) {
        return ask("chooseEvent", event, run, rerolls);
    }

    @Override
    public SanctumAnswer chooseSanctum(final SanctumOffer offer) {
        return ask("chooseSanctum", offer);
    }

    @Override
    public ShopAnswer shop(final ShopOffer offer) {
        return ask("shop", offer);
    }

    @Override
    public List<PaperCard> show(final String title, final List<PaperCard> cards, final int maxSelections, final String rerollLabel,
            final boolean rerollEnabled, final int gold) {
        return ask("show", title, cards, maxSelections, rerollLabel, rerollEnabled, gold);
    }

    @Override
    public <T> List<T> devPick(final String title, final List<T> all, final List<T> current, final int count) {
        return ask("devPick", title, all, current, count);
    }

    @Override
    public void showPlaneboundEffects(final NodePlanebound node, final List<WrathfulEffect> wrathful, final List<CursedEffect> cursed) {
        announce("showPlaneboundEffects", node, wrathful, cursed);
    }

    @Override
    public void showResult(final ResultKind kind, final String title, final String text, final List<Section> sections) {
        announce("showResult", kind, title, text, sections);
    }

    @Override
    public void showMessage(final String title, final String text) {
        announce("showMessage", title, text);
    }

    @Override
    public void showTutorial(final RogueTutorial tutorial) {
        redraw("showTutorial", tutorial);
    }

    @Override
    public void startMatch(final RogueMatch match) {
        announce("startMatch", match);
    }

    @Override
    public void mapChanged(final RogueRun run) {
        redraw("mapChanged", run);
    }

    @Override
    public void runStarted(final RogueRun run) {
        redraw("runStarted", run);
    }

    @Override
    public void runEnded() {
        announce("runEnded");
    }
}
