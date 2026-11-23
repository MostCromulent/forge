package forge.gamemodes.rogue;

import forge.item.PaperCard;
import forge.util.Localizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Removal credits and undo for editing a run's deck. The client moves the cards itself, through the callbacks. */
public final class RogueDeckEdits {

    public record Edit(boolean removal, List<Map.Entry<PaperCard, Integer>> cards, int cost) { }

    private final RogueRun run;
    private final Deque<Edit> edits = new ArrayDeque<>();

    public RogueDeckEdits(RogueRun run) {
        this.run = run;
    }

    public void added(Iterable<Map.Entry<PaperCard, Integer>> cards) {
        edits.push(new Edit(false, copy(cards), 0));
        RogueIO.saveRun(run);
    }

    /** Why the cards cannot be removed, or null when they can. */
    public String refusal(Iterable<Map.Entry<PaperCard, Integer>> cards) {
        return RogueRun.removalCost(cards) > run.getRemovalCredits() ? Localizer.getInstance().getMessage("lblRogueRemovalLimitHint") : null;
    }

    /** Null when the cards were removed; otherwise why not, and nothing changed. */
    public String remove(Iterable<Map.Entry<PaperCard, Integer>> cards, Runnable moveCards) {
        String refusal = refusal(cards);
        if (refusal != null) {
            return refusal;
        }
        int cost = RogueRun.removalCost(cards);
        moveCards.run();
        run.setRemovalCredits(run.getRemovalCredits() - cost);
        edits.push(new Edit(true, copy(cards), cost));
        RogueIO.saveRun(run);
        return null;
    }

    public boolean canUndo() {
        return !edits.isEmpty();
    }

    /** The client puts the cards back: adds them after a removal, takes them out after an addition. */
    public void undo(Consumer<Edit> moveCards) {
        if (edits.isEmpty()) {
            return;
        }
        Edit edit = edits.pop();
        moveCards.accept(edit);
        run.setRemovalCredits(run.getRemovalCredits() + edit.cost());
        RogueIO.saveRun(run);
    }

    public void clear() {
        edits.clear();
    }

    private static List<Map.Entry<PaperCard, Integer>> copy(Iterable<Map.Entry<PaperCard, Integer>> cards) {
        List<Map.Entry<PaperCard, Integer>> list = new ArrayList<>();
        for (Map.Entry<PaperCard, Integer> entry : cards) {
            list.add(entry);
        }
        return list;
    }
}
