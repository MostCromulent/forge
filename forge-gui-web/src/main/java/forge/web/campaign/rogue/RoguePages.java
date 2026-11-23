package forge.web.campaign.rogue;

import forge.deck.Deck;
import forge.gamemodes.rogue.RogueMetaProgress;
import forge.gamemodes.rogue.RogueRun;
import forge.gamemodes.rogue.effect.RogueEffect;
import forge.gamemodes.rogue.effect.RogueEffectComposite;
import forge.gamemodes.rogue.flow.RogueFlow;
import forge.web.deck.CardCatalog;
import forge.web.deck.DeckCatalog;
import forge.web.deck.DeckFacts;
import forge.web.protocol.RogueProtocol.RogueHome;
import forge.web.protocol.RogueProtocol.RogueMap;
import forge.web.protocol.RogueProtocol.RogueRunCard;
import forge.web.protocol.RogueProtocol.RogueTraits;
import forge.web.protocol.ToBrowser.DeckDetails;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Turns the flow's state into Rogue's page records. */
public final class RoguePages {
    private RoguePages() {
    }

    /** One numbered set of pages. Commands made from it are checked against its number and its run's current node, which is -1 with no run. */
    public record Snapshot(long number, RogueRun run, int node, List<Record> pages) {
    }

    /** Runs on the flow thread only, because it reads the run and reveals the planes now in view. */
    public static Snapshot build(final RogueFlow flow, final RogueMetaProgress progress, final long number) {
        final RogueRun run = flow.run();
        final List<Record> pages = new ArrayList<>();
        if (run == null) {
            pages.add(home(null, progress, number));
            return new Snapshot(number, null, -1, pages);
        }
        run.getPath().revealVisible(run);
        pages.add(home(run, progress, number));
        pages.add(new RogueMap(number, run.getCurrentNodeIndex(), flow.rerollsLeft(), deck(run)));
        pages.add(new RogueTraits(number, RogueEffectComposite.getAllEffects(run).stream()
                .map(RogueEffect::getEffectCardDisplayName).collect(Collectors.toList())));
        return new Snapshot(number, run, run.getCurrentNodeIndex(), pages);
    }

    private static RogueHome home(final RogueRun run, final RogueMetaProgress progress, final long number) {
        final RogueRunCard card = run == null ? null : new RogueRunCard(run.getCurrentCommanderName(), run.getCurrentLife(), run.getMaxLife(),
                run.getCurrentGold(), run.getCurrentDeck().getMain().countAll());
        final boolean started = progress.getTotalRunsStarted() > 0;
        return new RogueHome(number, card, started, progress.getTotalRunsStarted(), progress.getTotalRunsWon(), started,
                progress.getTotalRunsCompleted() > 0, started, progress.getTotalEchoes(),
                progress.isDescensionModeUnlocked() ? progress.getTotalSparks() : null);
    }

    private static DeckDetails deck(final RogueRun run) {
        final Deck deck = run.getCurrentDeck();
        return new DeckDetails("rogue:run", run.getCurrentCommanderName(), null, DeckFacts.colors(deck), DeckFacts.stats(deck),
                CardCatalog.groups(deck.getMain(), DeckCatalog.NO_FLAGS), List.of(), null, 0, null);
    }
}
