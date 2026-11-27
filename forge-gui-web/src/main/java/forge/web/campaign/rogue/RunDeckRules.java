package forge.web.campaign.rogue;

import forge.deck.CardPool;
import forge.deck.Deck;
import forge.gamemodes.rogue.RogueDeckEdits;
import forge.gamemodes.rogue.RogueRun;
import forge.item.PaperCard;
import forge.util.IHasForgeLog;
import forge.util.Localizer;
import forge.web.deck.DeckEditor;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The run's deck editor rules: removal credits and undo, as the shared RogueDeckEdits keeps them. The editor has moved the cards already,
 * so each change and each undo is one edit there, and an undo takes back the last one with its credits.
 * RogueDeckEdits saves the run with each edit, so an edit waits for the editor's save, which puts the new deck in the run first.
 */
final class RunDeckRules implements DeckEditor.Rules, IHasForgeLog {
    private final RogueRun run;
    private final RogueDeckEdits edits;
    /** The edit of the change the editor is about to save. */
    private Runnable pending = () -> { };

    RunDeckRules(final RogueRun run) {
        this.run = run;
        this.edits = new RogueDeckEdits(run);
    }

    @Override
    public String refuse(final CardPool removed) {
        return edits.refusal(removed);
    }

    @Override
    public void changed(final CardPool removed, final boolean undo) {
        pending = undo ? () -> edits.undo(edit -> { }) : removed.isEmpty() ? () -> edits.added(List.of()) : () -> edits.remove(removed, () -> { });
    }

    /** Puts the editor's deck in the run and makes the change's edit, which saves the run. Returns why the save failed, or null. */
    String save(final Deck deck) {
        run.setCurrentDeck(deck);
        final Runnable edit = pending;
        pending = () -> { };
        try {
            edit.run();
            return null;
        } catch (final RuntimeException e) {
            netLog.error(e, "Could not save the Rogue Commander run after a deck change");
            return Localizer.getInstance().getMessage("lblWebEditorSaveFailed", String.valueOf(e.getMessage()));
        }
    }

    @Override
    public String counter() {
        return Localizer.getInstance().getMessage("lblWebRogueRemovalCreditCount", run.getRemovalCredits());
    }

    @Override
    public boolean spent() {
        return run.getRemovalCredits() == 0;
    }

    @Override
    public int credits() {
        return run.getRemovalCredits();
    }

    @Override
    public boolean free(final PaperCard card) {
        return RogueRun.removalCost(List.of(Map.entry(card, 1))) == 0;
    }

    @Override
    public Set<String> basics() {
        return run.basicLands().stream().map(PaperCard::getName).collect(Collectors.toSet());
    }
}
