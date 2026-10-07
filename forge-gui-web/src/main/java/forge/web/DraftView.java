package forge.web;

import forge.card.CardRules;
import forge.gamemodes.limited.CardRanker;
import forge.item.PaperCard;
import forge.web.ToBrowser.DraftCard;
import forge.web.ToBrowser.DraftState;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/** The draft as the browser sees it, with a step that numbers the pack in hand so a pick names exactly the pack it was made from. */
final class DraftView {
    private int step;
    private volatile DraftState latest;
    private final List<String> log = new ArrayList<>();

    /** The seat whose pack passed on, or none when the counts show anything but one pass, so the dial redraws rather than slides. */
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
        return card(card, packNumber, pickNumber, false);
    }

    /** The same pick, moved to the sideboard or to the main deck. */
    static DraftCard moved(final DraftCard c, final boolean sideboard) {
        return new DraftCard(c.name(), c.image(), c.cost(), c.mv(), c.colors(), c.type(), c.text(), c.pt(), c.rarity(), c.rank(), c.pack(),
                c.pick(), sideboard);
    }

    static DraftCard card(final PaperCard card, final int packNumber, final int pickNumber, final boolean sideboard) {
        final CardRules rules = card.getRules();
        // A ranked card scores at least 1, where desktop's rounding can show 0
        final double score = CardRanker.getRawScore(card);
        return new DraftCard(card.getName(), Foil.key(card), JsonCodec.manaCost(rules.getManaCost()),
                rules.getManaCost().getCMC(), CardCatalog.letters(rules.getColor()), rules.getType().toString(),
                rules.getOracleText().replace("\\n", "\n").replace("\r\n", "\n"), CardCatalog.pt(rules),
                card.getRarity().toString(), score <= 0 ? null : (int) Math.max(1, Math.round(Math.min(99, score))), packNumber, pickNumber, sideboard);
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
