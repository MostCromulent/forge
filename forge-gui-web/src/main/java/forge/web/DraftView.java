package forge.web;

import forge.card.CardRules;
import forge.gamemodes.limited.CardRanker;
import forge.item.PaperCard;
import forge.web.ToBrowser.DraftCard;
import forge.web.ToBrowser.DraftState;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/**
 * The draft as the browser sees it, shared by offline and online drafts: the latest state, and the step that numbers
 * the pack in hand so a pick names exactly the pack it was made from.
 */
final class DraftView {
    private int step;
    private volatile DraftState latest;
    private final List<String> log = new ArrayList<>();

    /**
     * Seats whose head pack went on to the next seat between two readings of every seat's pack count, taken around
     * pickingSeat's pick. Anything that is not plainly one pass, such as a pack running out or a new round, is no move,
     * so the dial redraws rather than slides.
     */
    static List<Integer> moved(final int[] before, final int[] after, final int pickingSeat, final int direction) {
        if (before == null || after == null || before.length != after.length || pickingSeat < 0 || pickingSeat >= after.length) {
            return List.of();
        }
        final int next = Math.floorMod(pickingSeat + direction, after.length);
        for (int i = 0; i < after.length; i++) {
            final int expected = i == pickingSeat ? -1 : i == next ? 1 : 0;
            if (after[i] - before[i] != expected) {
                return List.of();
            }
        }
        return List.of(pickingSeat);
    }

    static DraftCard card(final PaperCard card, final int packNumber, final int pickNumber) {
        final CardRules rules = card.getRules();
        // Desktop's draft ranking overlay: a score to 99, higher is better, and none for a card nobody ranked
        final double score = CardRanker.getRawScore(card);
        return new DraftCard(card.getName(), card.getImageKey(false), JsonCodec.manaCost(rules.getManaCost()),
                rules.getManaCost().getCMC(), CardCatalog.letters(rules.getColor()), rules.getType().toString(), CardCatalog.pt(rules),
                card.getRarity().toString(), score <= 0 ? null : (int) Math.round(Math.min(99, score)), packNumber, pickNumber);
    }

    /** Builds and remembers the next state from its step, which goes up only when newPack says the pack in hand changed. */
    DraftState state(final boolean newPack, final IntFunction<DraftState> build) {
        if (newPack) {
            step++;
        }
        latest = build.apply(step);
        return latest;
    }

    /** Adds a line to the draft's log, as desktop's draft log words it. */
    void log(final String line) {
        log.add(line);
    }

    List<String> lines() {
        return List.copyOf(log);
    }

    /** The state last built, for a browser that arrives again; null before the first pack. */
    DraftState latest() {
        return latest;
    }
}
