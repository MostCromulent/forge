package forge.web.campaign.rogue;

import forge.deck.Deck;
import forge.gamemodes.rogue.CodexHelper;
import forge.gamemodes.rogue.KeywordHint;
import forge.gamemodes.rogue.RogueConfig;
import forge.gamemodes.rogue.RogueDeck;
import forge.gamemodes.rogue.RogueMetaProgress;
import forge.gamemodes.rogue.RoguePlanebound;
import forge.gamemodes.rogue.RoguePlaneboundType;
import forge.gamemodes.rogue.RogueRun;
import forge.gamemodes.rogue.effect.CustomRunModification;
import forge.gamemodes.rogue.effect.DescensionLevel;
import forge.gamemodes.rogue.effect.EndlessMode;
import forge.gamemodes.rogue.effect.PathUpdateContext;
import forge.gamemodes.rogue.effect.RogueEffect;
import forge.gamemodes.rogue.effect.RogueEffectComposite;
import forge.gamemodes.rogue.flow.RogueFlow;
import forge.gamemodes.rogue.flow.RogueStart;
import forge.gamemodes.rogue.path.NodePlanebound;
import forge.gamemodes.rogue.path.RoguePath;
import forge.gamemodes.rogue.path.RoguePathNode;
import forge.item.PaperCard;
import forge.util.Localizer;
import forge.web.deck.CardCatalog;
import forge.web.deck.DeckCatalog;
import forge.web.deck.DeckFacts;
import forge.web.protocol.CampaignProtocol.Balance;
import forge.web.protocol.CampaignProtocol.CampaignBar;
import forge.web.protocol.RogueProtocol.CarryCard;
import forge.web.protocol.RogueProtocol.DescensionStep;
import forge.web.protocol.RogueProtocol.MapLink;
import forge.web.protocol.RogueProtocol.MapNode;
import forge.web.protocol.RogueProtocol.Modification;
import forge.web.protocol.RogueProtocol.Part;
import forge.web.protocol.RogueProtocol.RogueCommander;
import forge.web.protocol.RogueProtocol.RogueHome;
import forge.web.protocol.RogueProtocol.RogueMap;
import forge.web.protocol.RogueProtocol.RogueRoster;
import forge.web.protocol.RogueProtocol.RogueRunBar;
import forge.web.protocol.RogueProtocol.RogueRunCard;
import forge.web.protocol.RogueProtocol.RogueTraits;
import forge.web.protocol.ToBrowser.DeckDetails;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

    private static final Map<String, Part> MAP_TERMS = Arrays.stream(new KeywordHint[] {KeywordHint.PLANEBOUND, KeywordHint.WRATHFUL, KeywordHint.CURSED,
            KeywordHint.CARD_REWARD}).collect(Collectors.toMap(KeywordHint::name, h -> new Part("term", h.getTitle(), null, h.getTitle(), h.getHintText())));

    /** The picture of each planebound's commander by its deck's path, read from the deck file the first time it is drawn. */
    private static final Map<String, String> PLANEBOUND_ART = new ConcurrentHashMap<>();

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
        pages.add(map(flow, run, progress, number));
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
        return new RogueHome(number, bar, run == null ? null : runCard(run), RogueStart.tabsUnlocked(progress), fan);
    }

    /** Localizer passes its arguments as text, so a choice format cannot pick the singular. */
    private static String record(final int runs, final int won) {
        return runs == 1 ? Localizer.getInstance().getMessage("lblWebRogueRecordOne", won) : Localizer.getInstance().getMessage("lblWebRogueRecord", runs, won);
    }

    private static RogueRoster roster(final RogueMetaProgress progress, final long number) {
        final List<DescensionStep> levels = Arrays.stream(DescensionLevel.values()).map(l -> new DescensionStep(l.name, l.description)).toList();
        final List<Modification> modifications = new ArrayList<>();
        for (final CustomRunModification m : CustomRunModification.values()) {
            // Desktop names Experiment as the one that swaps the roster
            modifications.add(new Modification(m.name(), m.getDisplayName(), m.getRawDescription(), m.getGroup().getDisplayName(),
                    m.isGeneratedCommander(), m == CustomRunModification.EXPERIMENT));
        }
        return new RogueRoster(number, commanders(progress, false), commanders(progress, true), progress.isCustomRunsUnlocked(),
                progress.isDescensionModeUnlocked(), RogueStart.descensionLockText(progress, null, 0, true), levels, modifications);
    }

    private static List<RogueCommander> commanders(final RogueMetaProgress progress, final boolean experimental) {
        final Localizer text = Localizer.getInstance();
        final List<RogueCommander> commanders = new ArrayList<>();
        for (final RogueDeck deck : RogueStart.roster(Decks.ALL, experimental)) {
            if (!deck.isUnlocked()) {
                final String unlock = deck.getUnlockDescription();
                commanders.add(new RogueCommander(deck.getName(), null, null, "", "", List.of(), text.getMessage("lblRogueLocked"), 0, 0, 0, 0, -1,
                        null, null, unlock == null || unlock.isEmpty() ? text.getMessage("lblRogueLocked") : unlock));
                continue;
            }
            final String name = deck.getCommanderCardName();
            final PaperCard card = deck.getStartDeck().getCommanders().get(0);
            final int runs = progress.getRunsStartedWithCommander(name);
            final int wins = progress.getRunsWonWithCommander(name);
            final int seen = CodexHelper.countSeen(deck.getRewardPoolCards().stream().map(c -> CodexHelper.state(progress, deck, c)).toList());
            int startable = -1;
            while (startable < DescensionLevel.getMaxLevel() && new RogueStart(deck, false, Set.of(), startable + 1).canBegin(progress)) {
                startable++;
            }
            final String lock = RogueStart.descensionLockText(progress, deck, 0, false);
            final String next = lock == null && startable < DescensionLevel.getMaxLevel()
                    ? RogueStart.descensionLockText(progress, deck, startable + 1, false) : null;
            final String theme = deck.getThemeDescription();
            final List<String> themes = theme == null ? List.of() : Arrays.stream(theme.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
            commanders.add(new RogueCommander(deck.getName(), name, card.getImageKey(false), CardCatalog.letters(card.getRules().getColorIdentity()),
                    deck.getDescription() == null ? "" : deck.getDescription(), themes,
                    runs == 0 ? text.getMessage("lblWebRogueNotPlayed") : record(runs, wins),
                    runs, wins, progress.getMaxDescensionWon(name), seen, startable, lock, next, null));
        }
        return commanders;
    }

    /** The Rogue deck of that name, or null for an unknown one. */
    static RogueDeck deck(final String name) {
        return Decks.ALL.stream().filter(d -> d.getName().equals(name)).findFirst().orElse(null);
    }

    /** A deck's start deck, read only, or null for a locked or unknown deck. */
    public static DeckDetails startDeck(final String name) {
        final RogueDeck deck = deck(name);
        if (deck == null || !deck.isUnlocked()) {
            return null;
        }
        final Deck start = deck.getStartDeck();
        return new DeckDetails("rogue:start:" + name, deck.getCommanderCardName(), null, DeckFacts.colors(start), DeckFacts.stats(start),
                CardCatalog.groups(start.getMain(), DeckCatalog.NO_FLAGS), List.of(), null, 0, null);
    }

    private static RogueMap map(final RogueFlow flow, final RogueRun run, final RogueMetaProgress progress, final long number) {
        final RoguePath path = run.getPath();
        final RoguePathNode current = run.getCurrentNode();
        final int row = current == null ? -1 : current.getRowIndex();
        final List<Integer> open = current == null ? List.of() : path.getVisibleNodesInCurrentRow(row, flow.pathContext());
        final List<List<MapNode>> rows = new ArrayList<>();
        final List<MapLink> links = new ArrayList<>();
        for (int r = 0; r <= path.getMaxRow(); r++) {
            final List<MapNode> nodes = new ArrayList<>();
            for (final RoguePathNode node : path.getNodesInRow(r)) {
                final int index = path.getNodes().indexOf(node);
                final String state = node.isCompleted() ? "walked" : node == current ? "selected"
                        : r == row ? (open.contains(index) ? "open" : "dead") : r < row ? "past" : "far";
                nodes.add(node(flow, run, node, index, state));
                path.getReachableNodeIndices(index).forEach(to -> links.add(new MapLink(index, to)));
            }
            rows.add(nodes);
        }
        final RogueFlow.Reroll reroll = flow.reroll();
        final List<String> hiding = RogueEffectComposite.getAllEffects(run).stream().filter(e -> {
            final PathUpdateContext context = new PathUpdateContext();
            e.onPathUpdate(context, run);
            return context.hidePlanes;
        }).map(RogueEffect::getDescription).toList();
        return new RogueMap(number, rows, links, run.getCurrentNodeIndex(), runBar(run, progress), reroll.left(), reroll.enabled() ? null : reroll.reason(),
                hiding.isEmpty() ? null : String.join("\n", hiding), MAP_TERMS, deck(run));
    }

    private static MapNode node(final RogueFlow flow, final RogueRun run, final RoguePathNode node, final int index, final String state) {
        final Localizer text = Localizer.getInstance();
        final String enter = flow.enterText(node);
        return switch (node.getType()) {
            case PLANEBOUND -> {
                final NodePlanebound plane = (NodePlanebound) node;
                final RoguePlanebound planebound = plane.getRoguePlanebound();
                final boolean elite = plane.getPlaneboundType() == RoguePlaneboundType.ELITE;
                final boolean boss = plane.getPlaneboundType() == RoguePlaneboundType.BOSS;
                final int life = plane.getPlaneboundLife(run.getPath().countPlaneboundRowsUpTo(node.getRowIndex()));
                if (run.getPath().isFaceDown(run, node)) {
                    yield new MapNode(index, node.getType().name(), state, true, null, null, null, null, null, life, elite, boss,
                            plane.getWrathfulCount(), plane.getCursedCount(), plane.getGoldReward(), plane.getEchoReward(), enter);
                }
                final PaperCard card = CodexHelper.getPlaneCard(planebound);
                yield new MapNode(index, node.getType().name(), state, false, planebound.planeboundName(), planebound.planeName(), planeboundArt(planebound),
                        card == null ? null : card.getImageKey(false), card == null ? null : card.getRules().getOracleText().replace("\\n", "\n"),
                        life, elite, boss, plane.getWrathfulCount(), plane.getCursedCount(), plane.getGoldReward(), plane.getEchoReward(), enter);
            }
            case SANCTUM -> side(node, index, state, text.getMessage("lblRogueSanctum"), text.getMessage("lblRogueSanctumTooltip"), enter);
            case BAZAAR -> side(node, index, state, text.getMessage("lblRogueBazaar"), text.getMessage("lblRogueBazaarTooltip"), enter);
            case EVENT -> side(node, index, state, text.getMessage("lblRogueEvent"), text.getMessage("lblRogueEventTooltip"), enter);
            case CHEST -> side(node, index, state, text.getMessage("lblRogueChest"), text.getMessage("lblRogueChestTooltip"), enter);
        };
    }

    private static MapNode side(final RoguePathNode node, final int index, final String state, final String name, final String text, final String enter) {
        return new MapNode(index, node.getType().name(), state, false, name, null, null, null, text, 0, false, false, 0, 0, 0, 0, enter);
    }

    private static String planeboundArt(final RoguePlanebound planebound) {
        return PLANEBOUND_ART.computeIfAbsent(planebound.deckPath(), path -> {
            final Deck deck = RogueConfig.loadPlaneboundDeck(planebound);
            return deck == null || deck.getCommanders().isEmpty() ? "" : deck.getCommanders().get(0).getImageKey(false);
        });
    }

    private static RogueRunBar runBar(final RogueRun run, final RogueMetaProgress progress) {
        final Localizer text = Localizer.getInstance();
        final List<Balance> balances = List.of(
                new Balance("rogueLife", run.getCurrentLife(), KeywordHint.MAX_LIFE.getTitle(), run.getCurrentLife() + " / " + run.getMaxLife(), "heart",
                        KeywordHint.MAX_LIFE.getHintText()),
                new Balance("ICO_QUEST_COIN", run.getCurrentGold(), KeywordHint.GOLD.getTitle(), null, null, KeywordHint.GOLD.getHintText()),
                new Balance("ICO_QUEST_GOLD", progress.getTotalEchoes(), KeywordHint.ECHOES.getTitle(), null, null, KeywordHint.ECHOES.getHintText()),
                new Balance("rogueCredits", run.getRemovalCredits(), KeywordHint.REMOVAL_CREDITS.getTitle(), null, "cut",
                        KeywordHint.REMOVAL_CREDITS.getHintText()));
        final List<String> tip = new ArrayList<>();
        for (int level = 1; level <= run.getDescensionLevel(); level++) {
            final DescensionLevel descension = DescensionLevel.forLevel(level);
            if (descension != null) {
                tip.add(text.getMessage("lblRogueDescensionTooltip", level, descension.name, descension.description));
            }
        }
        if (run.getEndlessLevel() > 0) {
            tip.add(text.getMessage("lblRogueEndlessLevel", run.getEndlessLevel()));
            tip.add(EndlessMode.getModifiersDescription(run.getEndlessLevel()));
        }
        final int traits = (int) RogueEffectComposite.getAllEffects(run).stream()
                .filter(e -> !(e instanceof DescensionLevel || e instanceof EndlessMode)).count();
        return new RogueRunBar(new CampaignBar(run.getCurrentCommanderName(), line(run), balances, Map.of()),
                tip.isEmpty() ? null : String.join("\n", tip), traits);
    }

    /** The run's Descension and Endless levels, or an empty line with neither. */
    private static String line(final RogueRun run) {
        final Localizer text = Localizer.getInstance();
        final List<String> line = new ArrayList<>();
        final DescensionLevel descension = DescensionLevel.forLevel(run.getDescensionLevel());
        if (run.getDescensionLevel() > 0 && descension != null) {
            line.add(text.getMessage("lblRogueHistoryDescension", descension.level, descension.name));
        }
        if (run.getEndlessLevel() > 0) {
            line.add(text.getMessage("lblRogueEndlessLevel", run.getEndlessLevel()));
        }
        return String.join(" · ", line);
    }

    private static RogueRunCard runCard(final RogueRun run) {
        final List<PaperCard> commanders = run.getCurrentDeck().getCommanders();
        final PaperCard commander = commanders.isEmpty() ? run.getSelectedRogueDeck().getStartDeck().getCommanders().get(0) : commanders.get(0);
        final List<CarryCard> carry = new ArrayList<>();
        for (final RogueRun.CarryCard c : run.getCarryCards()) {
            final PaperCard card = c.toPaperCard();
            if (card != null) {
                carry.add(new CarryCard(card.getName(), card.getImageKey(false), c.type().name()));
            }
        }
        return new RogueRunCard(run.getCurrentCommanderName(), commander.getImageKey(false), line(run), run.getCurrentLife(),
                run.getMaxLife(), run.getCurrentGold(), run.getCurrentDeck().getMain().countAll(), run.getRemovalCredits(), carry);
    }

    private static DeckDetails deck(final RogueRun run) {
        final Deck deck = run.getCurrentDeck();
        return new DeckDetails("rogue:run", run.getCurrentCommanderName(), null, DeckFacts.colors(deck), DeckFacts.stats(deck),
                CardCatalog.groups(deck.getMain(), DeckCatalog.NO_FLAGS), List.of(), null, 0, null);
    }
}
