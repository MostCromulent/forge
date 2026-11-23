package forge.web.campaign.rogue;

import forge.deck.Deck;
import forge.gamemodes.rogue.KeywordHint;
import forge.gamemodes.rogue.RogueConfig;
import forge.gamemodes.rogue.RogueDeck;
import forge.gamemodes.rogue.RogueMetaProgress;
import forge.gamemodes.rogue.RogueRun;
import forge.gamemodes.rogue.effect.CustomRunModification;
import forge.gamemodes.rogue.effect.DescensionLevel;
import forge.gamemodes.rogue.effect.RogueEffect;
import forge.gamemodes.rogue.effect.RogueEffectComposite;
import forge.gamemodes.rogue.flow.RogueFlow;
import forge.gamemodes.rogue.flow.RogueStart;
import forge.item.PaperCard;
import forge.util.Localizer;
import forge.web.deck.CardCatalog;
import forge.web.deck.DeckCatalog;
import forge.web.deck.DeckFacts;
import forge.web.protocol.CampaignProtocol.Balance;
import forge.web.protocol.CampaignProtocol.CampaignBar;
import forge.web.protocol.RogueProtocol.CarryCard;
import forge.web.protocol.RogueProtocol.DescensionStep;
import forge.web.protocol.RogueProtocol.Modification;
import forge.web.protocol.RogueProtocol.RogueCommander;
import forge.web.protocol.RogueProtocol.RogueHome;
import forge.web.protocol.RogueProtocol.RogueMap;
import forge.web.protocol.RogueProtocol.RogueRoster;
import forge.web.protocol.RogueProtocol.RogueRunCard;
import forge.web.protocol.RogueProtocol.RogueTraits;
import forge.web.protocol.ToBrowser.DeckDetails;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Turns the flow's state into Rogue's page records. */
public final class RoguePages {
    private RoguePages() {
    }

    /** Reading the decks takes longer than the rest of a snapshot, and they do not change while the server runs. */
    private static final class Decks {
        static final List<RogueDeck> ALL = RogueConfig.loadRogueDecks();
        static final List<RogueDeck> OFFICIAL = ALL.stream().filter(d -> !d.isExperimental())
                .sorted(Comparator.comparing(RogueDeck::getName)).collect(Collectors.toList());
    }

    /**
     * One numbered set of pages. Commands made from it are checked against its number and its run's current node, which is -1 with no run.
     * timed is the run whose time counts, which is a new run while it asks its start-of-run questions.
     */
    public record Snapshot(long number, RogueRun run, RogueRun timed, int node, List<Record> pages) {
    }

    /** Runs on the flow thread only, because it reads the run and reveals the planes now in view. */
    public static Snapshot build(final RogueFlow flow, final RogueMetaProgress progress, final long number) {
        final RogueRun run = flow.run();
        final List<Record> pages = new ArrayList<>();
        if (run == null) {
            pages.add(home(null, progress, number));
            pages.add(roster(progress, number));
            return new Snapshot(number, null, flow.timedRun(), -1, pages);
        }
        run.getPath().revealVisible(run);
        pages.add(home(run, progress, number));
        pages.add(roster(progress, number));
        pages.add(new RogueMap(number, run.getCurrentNodeIndex(), flow.rerollsLeft(), deck(run)));
        pages.add(new RogueTraits(number, RogueEffectComposite.getAllEffects(run).stream()
                .map(RogueEffect::getEffectCardDisplayName).collect(Collectors.toList())));
        return new Snapshot(number, run, flow.timedRun(), run.getCurrentNodeIndex(), pages);
    }

    private static RogueHome home(final RogueRun run, final RogueMetaProgress progress, final long number) {
        final Localizer text = Localizer.getInstance();
        final int runs = progress.getTotalRunsCompleted();
        final List<Balance> balances = new ArrayList<>();
        balances.add(new Balance("ICO_QUEST_GOLD", progress.getTotalEchoes(), KeywordHint.ECHOES.getTitle(), null, null, KeywordHint.ECHOES.getHintText()));
        if (progress.isDescensionModeUnlocked()) {
            balances.add(new Balance("ICO_QUEST_ELIXIR", progress.getTotalSparks(), text.getMessage("lblWebRogueSparks"), null, null, null));
        }
        final CampaignBar bar = new CampaignBar(text.getMessage("lblRogueCommander"),
                runs == 0 ? text.getMessage("lblWebRogueNoRuns") : record(runs, progress.getTotalRunsWon()), balances, Map.of());
        final String commander = run == null ? null : run.getCurrentCommanderName();
        final List<String> fan = Decks.OFFICIAL.stream().filter(d -> d.isUnlocked() && !d.getCommanderCardName().equals(commander))
                .limit(3).map(d -> d.getStartDeck().getCommanders().get(0).getImageKey(false)).collect(Collectors.toList());
        // Desktop opens Aether, History and Codex once a run is completed, in its own start page
        return new RogueHome(number, bar, run == null ? null : runCard(run), runs > 0, fan);
    }

    /** Localizer passes its arguments as text, so a choice format cannot pick the singular. */
    private static String record(final int runs, final int won) {
        return runs == 1 ? Localizer.getInstance().getMessage("lblWebRogueRecordOne", won) : Localizer.getInstance().getMessage("lblWebRogueRecord", runs, won);
    }

    private static RogueRoster roster(final RogueMetaProgress progress, final long number) {
        final List<DescensionStep> levels = Arrays.stream(DescensionLevel.values()).map(l -> new DescensionStep(l.name, l.description)).toList();
        final List<Modification> modifications = new ArrayList<>();
        for (final CustomRunModification m : CustomRunModification.values()) {
            final List<String> excludes = Arrays.stream(CustomRunModification.values()).filter(m::conflictsWith).map(Enum::name).toList();
            // Desktop names Experiment as the one that swaps the roster
            modifications.add(new Modification(m.name(), m.getDisplayName(), m.getRawDescription(), m.getGroup().getDisplayName(), excludes,
                    m.isGeneratedCommander(), m == CustomRunModification.EXPERIMENT, m.isIncludedWithGeneratedCommander()));
        }
        return new RogueRoster(number, commanders(progress, false), commanders(progress, true), progress.isCustomRunsUnlocked(),
                progress.isDescensionModeUnlocked(), levels, modifications);
    }

    private static List<RogueCommander> commanders(final RogueMetaProgress progress, final boolean experimental) {
        final Localizer text = Localizer.getInstance();
        final List<RogueCommander> commanders = new ArrayList<>();
        for (final RogueDeck deck : RogueStart.roster(Decks.ALL, experimental)) {
            if (!deck.isUnlocked()) {
                final String unlock = deck.getUnlockDescription();
                commanders.add(new RogueCommander(deck.getName(), null, null, "", "", List.of(), text.getMessage("lblRogueLocked"), 0, 0, 0, 0, 0,
                        unlock == null || unlock.isEmpty() ? text.getMessage("lblRogueLocked") : unlock));
                continue;
            }
            final String name = deck.getCommanderCardName();
            final PaperCard card = deck.getStartDeck().getCommanders().get(0);
            final int runs = progress.getRunsStartedWithCommander(name);
            final int wins = progress.getRunsWonWithCommander(name);
            // Replace with shared code from the mobile branch: the Codex counts a card as seen once it is seen or acquired
            final int seen = (int) deck.getRewardPoolCards().stream()
                    .filter(c -> progress.hasSeenCommanderRewardCard(name, c) || progress.hasAcquiredCommanderRewardCard(name, c)).count();
            final String theme = deck.getThemeDescription();
            final List<String> themes = theme == null ? List.of() : Arrays.stream(theme.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
            commanders.add(new RogueCommander(deck.getName(), name, card.getImageKey(false), CardCatalog.letters(card.getRules().getColorIdentity()),
                    deck.getDescription() == null ? "" : deck.getDescription(), themes,
                    runs == 0 ? text.getMessage("lblWebRogueNotPlayed") : record(runs, wins),
                    runs, wins, progress.getMaxDescensionWon(name), seen, progress.getMaxDescensionUnlocked(name), null));
        }
        return commanders;
    }

    /** A deck's start deck, read only, or null for a locked or unknown deck. */
    public static DeckDetails startDeck(final String name) {
        final RogueDeck deck = Decks.ALL.stream().filter(d -> d.getName().equals(name) && d.isUnlocked()).findFirst().orElse(null);
        if (deck == null) {
            return null;
        }
        final Deck start = deck.getStartDeck();
        return new DeckDetails("rogue:start:" + name, deck.getCommanderCardName(), null, DeckFacts.colors(start), DeckFacts.stats(start),
                CardCatalog.groups(start.getMain(), DeckCatalog.NO_FLAGS), List.of(), null, 0, null);
    }

    private static RogueRunCard runCard(final RogueRun run) {
        final Localizer text = Localizer.getInstance();
        final List<String> line = new ArrayList<>();
        final DescensionLevel descension = DescensionLevel.forLevel(run.getDescensionLevel());
        if (run.getDescensionLevel() > 0 && descension != null) {
            line.add(text.getMessage("lblRogueHistoryDescension", descension.level, descension.name));
        }
        if (run.getEndlessLevel() > 0) {
            line.add(text.getMessage("lblRogueEndlessLevel", run.getEndlessLevel()));
        }
        final List<PaperCard> commanders = run.getCurrentDeck().getCommanders();
        final PaperCard commander = commanders.isEmpty() ? run.getSelectedRogueDeck().getStartDeck().getCommanders().get(0) : commanders.get(0);
        final List<CarryCard> carry = new ArrayList<>();
        for (final RogueRun.CarryCard c : run.getCarryCards()) {
            final PaperCard card = c.toPaperCard();
            if (card != null) {
                carry.add(new CarryCard(card.getName(), card.getImageKey(false), c.type().name()));
            }
        }
        return new RogueRunCard(run.getCurrentCommanderName(), commander.getImageKey(false), String.join(" · ", line), run.getCurrentLife(),
                run.getMaxLife(), run.getCurrentGold(), run.getCurrentDeck().getMain().countAll(), run.getRemovalCredits(), carry);
    }

    private static DeckDetails deck(final RogueRun run) {
        final Deck deck = run.getCurrentDeck();
        return new DeckDetails("rogue:run", run.getCurrentCommanderName(), null, DeckFacts.colors(deck), DeckFacts.stats(deck),
                CardCatalog.groups(deck.getMain(), DeckCatalog.NO_FLAGS), List.of(), null, 0, null);
    }
}
