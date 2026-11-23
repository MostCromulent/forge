package forge.web.campaign.rogue;

import forge.StaticData;
import forge.deck.Deck;
import forge.gamemodes.rogue.AetherUpgrade;
import forge.gamemodes.rogue.CodexHelper;
import forge.gamemodes.rogue.CodexHelper.CodexState;
import forge.gamemodes.rogue.CodexHelper.JournalEntry;
import forge.gamemodes.rogue.CodexHelper.TraitCategory;
import forge.gamemodes.rogue.KeywordHint;
import forge.gamemodes.rogue.RogueConfig;
import forge.gamemodes.rogue.RogueDeck;
import forge.gamemodes.rogue.RogueMetaProgress;
import forge.gamemodes.rogue.RoguePlanebound;
import forge.gamemodes.rogue.RoguePlaneboundType;
import forge.gamemodes.rogue.RogueRun;
import forge.gamemodes.rogue.RogueRunHistoryEntry;
import forge.gamemodes.rogue.TextHelper;
import forge.gamemodes.rogue.effect.AetherEffect;
import forge.gamemodes.rogue.effect.ChestEffect;
import forge.gamemodes.rogue.effect.CursedEffect;
import forge.gamemodes.rogue.effect.CustomRunModification;
import forge.gamemodes.rogue.effect.DescensionLevel;
import forge.gamemodes.rogue.effect.EndlessMode;
import forge.gamemodes.rogue.effect.EventEffect;
import forge.gamemodes.rogue.effect.NPCEffect;
import forge.gamemodes.rogue.effect.PathUpdateContext;
import forge.gamemodes.rogue.effect.RogueEffect;
import forge.gamemodes.rogue.effect.RogueEffectComposite;
import forge.gamemodes.rogue.effect.WoundEffect;
import forge.gamemodes.rogue.effect.WrathfulEffect;
import forge.gamemodes.rogue.flow.RogueFlow;
import forge.gamemodes.rogue.flow.RogueStart;
import forge.gamemodes.rogue.npc.NPC;
import forge.gamemodes.rogue.path.NodePlanebound;
import forge.gamemodes.rogue.path.NodeType;
import forge.gamemodes.rogue.path.RoguePath;
import forge.gamemodes.rogue.path.RoguePathNode;
import forge.item.PaperCard;
import forge.item.PaperCardPredicates;
import forge.util.Localizer;
import forge.web.deck.CardCatalog;
import forge.web.deck.DeckCatalog;
import forge.web.deck.DeckFacts;
import forge.web.protocol.CampaignProtocol.Balance;
import forge.web.protocol.CampaignProtocol.CampaignBar;
import forge.web.protocol.CampaignProtocol.Figure;
import forge.web.protocol.RogueProtocol.AetherGroup;
import forge.web.protocol.RogueProtocol.AetherOffer;
import forge.web.protocol.RogueProtocol.AetherRank;
import forge.web.protocol.RogueProtocol.AetherWork;
import forge.web.protocol.RogueProtocol.CodexEntry;
import forge.web.protocol.RogueProtocol.CodexFact;
import forge.web.protocol.RogueProtocol.CodexGroup;
import forge.web.protocol.RogueProtocol.CodexItem;
import forge.web.protocol.RogueProtocol.CodexRow;
import forge.web.protocol.RogueProtocol.CodexStats;
import forge.web.protocol.RogueProtocol.DescensionStep;
import forge.web.protocol.RogueProtocol.HistoryRun;
import forge.web.protocol.RogueProtocol.HistoryStep;
import forge.web.protocol.RogueProtocol.HistoryTrait;
import forge.web.protocol.RogueProtocol.MapLink;
import forge.web.protocol.RogueProtocol.MapNode;
import forge.web.protocol.RogueProtocol.Modification;
import forge.web.protocol.RogueProtocol.Part;
import forge.web.protocol.RogueProtocol.RogueAether;
import forge.web.protocol.RogueProtocol.RogueCard;
import forge.web.protocol.RogueProtocol.RogueCodexPage;
import forge.web.protocol.RogueProtocol.RogueCommander;
import forge.web.protocol.RogueProtocol.RogueHistoryPage;
import forge.web.protocol.RogueProtocol.RogueHome;
import forge.web.protocol.RogueProtocol.RogueMap;
import forge.web.protocol.RogueProtocol.RogueRoster;
import forge.web.protocol.RogueProtocol.RogueRunBar;
import forge.web.protocol.RogueProtocol.RogueRunCard;
import forge.web.protocol.RogueProtocol.RogueRunDeck;
import forge.web.protocol.RogueProtocol.RogueTraits;
import forge.web.protocol.RogueProtocol.Trait;
import forge.web.protocol.ToBrowser.DeckDetails;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Turns the flow's state into Rogue's page records. */
public final class RoguePages {
    private RoguePages() {
    }

    /** Reading the decks takes longer than the rest of a snapshot, and they do not change while the server runs. */
    private static final class Decks {
        static final List<RogueDeck> ALL = RogueConfig.loadRogueDecks();
    }

    private static final Map<String, Part> MAP_TERMS = Arrays.stream(new KeywordHint[] {KeywordHint.PLANEBOUND, KeywordHint.WRATHFUL, KeywordHint.CURSED,
            KeywordHint.CARD_REWARD}).collect(Collectors.toMap(KeywordHint::name, h -> new Part("term", h.getTitle(), null, h.getTitle(), h.getHintText())));

    /** The cards whose art is drawn behind the Aether, and behind the Codex where its tab has no subject of its own. */
    private static final String AETHER_ART = "Teferi, Temporal Archmage";
    private static final String CODEX_ART = "The Maelstrom";

    /** The Codex's trait groups in the order desktop lists them. */
    private static final List<TraitCategory> TRAIT_GROUPS = List.of(TraitCategory.CHEST, TraitCategory.NPC, TraitCategory.EVENT, TraitCategory.WOUND);

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
            pages.add(roster(progress));
            pages.add(aether(progress));
            return new Snapshot(number, null, flow.timedRun(), -1, pages);
        }
        run.getPath().revealVisible(run);
        pages.add(home(run, progress, number));
        pages.add(roster(progress));
        pages.add(aether(progress));
        pages.add(map(flow, run, progress, number));
        pages.add(new RogueRunDeck(details("rogue:run", run.getCurrentCommanderName(), run.getCurrentDeck())));
        pages.add(new RogueTraits(RogueEffectComposite.getAllEffects(run).stream().map(e -> trait(e, run))
                .sorted(Comparator.comparing(t -> Source.valueOf(t.source()))).toList(), carry(run)));
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
        final List<String> fan = RogueStart.roster(Decks.ALL, false).stream().filter(d -> d.isUnlocked() && !d.getCommanderCardName().equals(commander))
                .limit(3).map(d -> d.getStartDeck().getCommanders().get(0).getImageKey(false)).collect(Collectors.toList());
        return new RogueHome(number, bar, run == null ? null : runCard(run), RogueStart.tabsUnlocked(progress), fan, progress.isDevUnlockAll(),
                progress.getAetherUpgradeLevel() >= AetherUpgrade.getMaxLevel());
    }

    /** Localizer passes its arguments as text, so a choice format cannot pick the singular. */
    private static String record(final int runs, final int won) {
        return runs == 1 ? Localizer.getInstance().getMessage("lblWebRogueRecordOne", won) : Localizer.getInstance().getMessage("lblWebRogueRecord", runs, won);
    }

    private static RogueRoster roster(final RogueMetaProgress progress) {
        final List<DescensionStep> levels = Arrays.stream(DescensionLevel.values()).map(l -> new DescensionStep(l.name, l.description)).toList();
        final List<Modification> modifications = new ArrayList<>();
        for (final CustomRunModification m : CustomRunModification.values()) {
            // Desktop names Experiment as the one that swaps the roster
            modifications.add(new Modification(m.name(), m.getDisplayName(), m.getRawDescription(), m.getGroup().getDisplayName(),
                    m.isGeneratedCommander(), m == CustomRunModification.EXPERIMENT));
        }
        return new RogueRoster(commanders(progress, false), commanders(progress, true), progress.isCustomRunsUnlocked(),
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

    private static RogueAether aether(final RogueMetaProgress progress) {
        final int level = progress.getAetherUpgradeLevel();
        final Set<AetherEffect> active = progress.getActiveAetherEffects();
        final List<AetherWork> works = new ArrayList<>();
        for (final AetherEffect work : AetherEffect.values()) {
            if (!work.isAccessibleAt(level)) {
                continue;
            }
            final int rank = progress.getAetherEffectRank(work);
            final List<AetherRank> ranks = new ArrayList<>();
            for (int r = 1; r <= work.getEffectiveMaxRank(level); r++) {
                ranks.add(new AetherRank(work.getDescriptionForRank(r), work.getEchoCostForRank(r)));
            }
            works.add(new AetherWork(work.name(), work.getDisplayName(), work.getDescriptionWithAllRanks(rank, level), work.getRankValues(level), rank,
                    active.contains(work), progress.canToggleAether(work), progress.nextRankCost(work), progress.canAffordNextRank(work), ranks));
        }
        final boolean descension = progress.isDescensionModeUnlocked();
        final AetherUpgrade next = descension ? AetherUpgrade.forLevel(level + 1) : null;
        final List<AetherGroup> locked = new ArrayList<>();
        for (final AetherUpgrade upgrade : AetherUpgrade.values()) {
            if (!descension || upgrade.level <= level) {
                continue;
            }
            final List<String> names = Arrays.stream(AetherEffect.values()).filter(w -> w.isAccessibleAt(upgrade.level) && !w.isAccessibleAt(upgrade.level - 1))
                    .map(AetherEffect::getDisplayName).toList();
            if (!names.isEmpty()) {
                locked.add(new AetherGroup(Localizer.getInstance().getMessage("lblWebRogueUnlocks", upgrade.name), names));
            }
        }
        return new RogueAether(works, progress.getActiveAetherEffectCount(), progress.getActiveAetherEffectSlots(), descension,
                next == null ? null : new AetherOffer(next.name, next.description, next.sparkCost, next.echoCost, progress.canAffordUpgrade(next)),
                locked, picture(AETHER_ART));
    }

    /** One tab of the Codex, with the entry named by id where the tab lists entries. Null while the Codex is locked, or for an unknown tab. */
    public static RogueCodexPage codex(final RogueMetaProgress progress, final String tab, final String id) {
        if (!RogueStart.tabsUnlocked(progress)) {
            return null;
        }
        final String art = picture(CODEX_ART);
        return switch (tab) {
            case "stats" -> new RogueCodexPage(tab, null, art, List.of(), stats(progress), null);
            case "commanders" -> commanderCodex(progress, id, art);
            case "planebounds" -> planeboundCodex(progress, id, art);
            case "traits" -> new RogueCodexPage(tab, null, art, List.of(), List.of(), traitCodex(progress));
            case "journal" -> journal(progress, id, art);
            default -> null;
        };
    }

    private static List<CodexStats> stats(final RogueMetaProgress progress) {
        final Localizer text = Localizer.getInstance();
        final int completed = progress.getTotalRunsCompleted();
        final int won = progress.getTotalRunsWon();
        final int matchesWon = progress.getTotalMatchesWon();
        final int matchesLost = progress.getTotalMatchesLost();
        return List.of(
                new CodexStats(text.getMessage("lblRogueRunStatistics"), List.of(figure("lblWebRogueRunsStarted", progress.getTotalRunsStarted()),
                        figure("lblWebRogueRunsCompleted", completed), new Figure(text.getMessage("lblWebRogueRunsWon"), won, completed, String.valueOf(won)),
                        figure("lblWebRogueRunsLost", completed - won),
                        new Figure(text.getMessage("lblWebRogueTotalPlaytime"), 0, null, TextHelper.formatHoursMinutesSeconds(progress.getTotalPlaytimeMillis())))),
                new CodexStats(text.getMessage("lblRogueMatchStatistics"), List.of(
                        new Figure(text.getMessage("lblWebRogueMatchesWon"), matchesWon, matchesWon + matchesLost, String.valueOf(matchesWon)),
                        figure("lblWebRogueMatchesLost", matchesLost))),
                new CodexStats(text.getMessage("lblRogueMilestones"), List.of(figure("lblWebRogueMaxLifeReached", progress.getMaxLifeInRun()),
                        figure("lblWebRogueMaxGoldEarned", progress.getMaxGoldInRun()), figure("lblWebRogueMaxCreatureTypes", progress.getMaxCreatureTypesInDeck()),
                        figure("lblWebRogueMaxArtifacts", progress.getMaxArtifactsInDeck()),
                        figure("lblWebRogueMaxSharedCreatureType", progress.getMaxSharedCreatureTypeInDeck()),
                        figure("lblWebRogueMaxLegendaryPermanents", progress.getMaxLegendaryPermanentsInDeck()),
                        figure("lblWebRogueMaxNonbasicLands", progress.getMaxNonbasicLandsInDeck()))));
    }

    private static Figure figure(final String label, final int amount) {
        return new Figure(Localizer.getInstance().getMessage(label), amount, null, null);
    }

    private static RogueCodexPage commanderCodex(final RogueMetaProgress progress, final String id, final String art) {
        final Localizer text = Localizer.getInstance();
        final List<RogueDeck> decks = CodexHelper.codexCommanders();
        final List<CodexRow> rows = new ArrayList<>();
        for (final RogueDeck deck : decks) {
            if (!deck.isUnlocked()) {
                rows.add(new CodexRow(null, text.getMessage("lblRogueLockedCommander"), null, "", null));
                continue;
            }
            final List<CodexState> states = deck.getRewardPoolCards().stream().map(c -> CodexHelper.state(progress, deck, c)).toList();
            final int acquired = CodexHelper.countAcquired(states);
            rows.add(new CodexRow(deck.getName(), deck.getName(), commanderImage(deck), acquired == 0
                    ? text.getMessage("lblWebRogueSeenCount", CodexHelper.countSeen(states))
                    : text.getMessage("lblWebRogueSeenAcquiredCount", CodexHelper.countSeen(states), acquired), null));
        }
        final RogueDeck chosen = decks.stream().filter(d -> d.isUnlocked() && d.getName().equals(id)).findFirst()
                .orElse(decks.stream().filter(RogueDeck::isUnlocked).findFirst().orElse(null));
        if (chosen == null) {
            return new RogueCodexPage("commanders", null, art, rows, List.of(), null);
        }
        final List<PaperCard> nonMythics = new ArrayList<>();
        final List<PaperCard> mythics = new ArrayList<>();
        chosen.getRewardPoolCards().forEach(c -> (PaperCardPredicates.IS_MYTHIC_RARE.test(c) ? mythics : nonMythics).add(c));
        final List<CodexState> states = new ArrayList<>();
        final List<CodexGroup> groups = List.of(
                new CodexGroup(text.getMessage("lblRogueRewardPoolNonMythics"), items(nonMythics, c -> CodexHelper.state(progress, chosen, c), states)),
                new CodexGroup(text.getMessage("lblRogueRewardPoolMythics"), items(mythics, c -> CodexHelper.state(progress, chosen, c), states)),
                new CodexGroup(text.getMessage("lblRogueStartDeck"), items(eachBasicOnce(chosen.getStartDeck().getMain().toFlatList()), c -> CodexState.ACQUIRED,
                        new ArrayList<>())));
        final String name = chosen.getCommanderCardName();
        final List<CodexFact> facts = List.of(new CodexFact("runs", String.valueOf(progress.getRunsStartedWithCommander(name))),
                new CodexFact("wins", String.valueOf(progress.getRunsWonWithCommander(name))),
                new CodexFact("descension", String.valueOf(progress.getMaxDescensionWon(name))),
                new CodexFact("endless", String.valueOf(progress.getMaxEndlessCompleted(name))),
                new CodexFact("seen", CodexHelper.countSeen(states) + " / " + states.size()),
                new CodexFact("acquired", CodexHelper.countAcquired(states) + " / " + states.size()));
        final String image = commanderImage(chosen);
        return new RogueCodexPage("commanders", chosen.getName(), image, rows, List.of(),
                new CodexEntry(chosen.getName(), image, null, null, null, facts, groups, List.of()));
    }

    private static String commanderImage(final RogueDeck deck) {
        return deck.getStartDeck().getCommanders().get(0).getImageKey(false);
    }

    private static RogueCodexPage planeboundCodex(final RogueMetaProgress progress, final String id, final String art) {
        final Localizer text = Localizer.getInstance();
        final List<RoguePlanebound> planebounds = CodexHelper.codexPlanebounds();
        final List<CodexRow> rows = new ArrayList<>();
        for (final RoguePlaneboundType type : RoguePlaneboundType.values()) {
            String group = switch (type) {
                case NORMAL -> text.getMessage("lblNormal");
                case ELITE -> text.getMessage("lblElite");
                case BOSS -> text.getMessage("lblWebRogueBoss");
            };
            for (final RoguePlanebound planebound : planebounds.stream().filter(p -> p.type() == type).toList()) {
                rows.add(progress.hasEncounteredPlanebound(planebound)
                        ? new CodexRow(planebound.deckPath(), planebound.planeboundName(), planeboundArt(planebound), record(progress, planebound), group)
                        : new CodexRow(null, text.getMessage("lblRogueUnknownPlanebound"), null, "", group));
                group = null;
            }
        }
        final RoguePlanebound chosen = planebounds.stream().filter(p -> progress.hasEncounteredPlanebound(p) && p.deckPath().equals(id)).findFirst()
                .orElse(planebounds.stream().filter(progress::hasEncounteredPlanebound).findFirst().orElse(null));
        if (chosen == null) {
            return new RogueCodexPage("planebounds", null, art, rows, List.of(), null);
        }
        final Deck deck = RogueConfig.loadPlaneboundDeck(chosen);
        final List<CodexState> states = new ArrayList<>();
        final List<CodexItem> items = items(deck == null ? List.of() : eachBasicOnce(deck.getAllCardsInASinglePool(false, false).toFlatList()),
                c -> CodexHelper.state(progress, chosen, c), states);
        final PaperCard plane = CodexHelper.getPlaneCard(chosen);
        final List<CodexFact> facts = List.of(new CodexFact("slain", String.valueOf(progress.getPlaneboundWins(chosen))),
                new CodexFact("defeats", String.valueOf(progress.getPlaneboundLosses(chosen))), new CodexFact("seen", CodexHelper.countSeen(states) + " / " + states.size()));
        final String image = planeboundArt(chosen);
        return new RogueCodexPage("planebounds", chosen.deckPath(), image, rows, List.of(), new CodexEntry(chosen.planeboundName() + " - " + chosen.planeName(),
                image, plane == null ? null : plane.getImageKey(false), chosen.planeName(), null, facts, List.of(new CodexGroup(text.getMessage("lblDeck"), items)),
                List.of()));
    }

    /** "2 slain · 1 defeat", leaving out a count of none. */
    private static String record(final RogueMetaProgress progress, final RoguePlanebound planebound) {
        final Localizer text = Localizer.getInstance();
        final List<String> line = new ArrayList<>();
        final int wins = progress.getPlaneboundWins(planebound);
        final int losses = progress.getPlaneboundLosses(planebound);
        if (wins > 0) {
            line.add(text.getMessage("lblWebRogueSlainCount", wins));
        }
        if (losses > 0) {
            line.add(losses == 1 ? text.getMessage("lblWebRogueDefeatOne") : text.getMessage("lblWebRogueDefeatCount", losses));
        }
        return String.join(" · ", line);
    }

    /** The cards in the Codex's order, by type and then name, each as its state shows it. Each state is also added to states. */
    private static List<CodexItem> items(final List<PaperCard> cards, final Function<PaperCard, CodexState> state, final List<CodexState> states) {
        return cards.stream().sorted(Comparator.comparingInt(CodexHelper::codexCardTypeOrder).thenComparing(PaperCard::getName, String.CASE_INSENSITIVE_ORDER))
                .map(c -> {
                    final CodexState s = state.apply(c);
                    states.add(s);
                    return s == CodexState.UNKNOWN ? new CodexItem("unknown", null, null, null)
                            : new CodexItem(s.name().toLowerCase(Locale.ROOT), c.getName(), c.getImageKey(false), null);
                }).toList();
    }

    private static List<PaperCard> eachBasicOnce(final List<PaperCard> cards) {
        final Set<String> basics = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        return cards.stream().filter(c -> c.getRules() != null && (!c.getRules().getType().isBasicLand() || basics.add(c.getName()))).toList();
    }

    private static CodexEntry traitCodex(final RogueMetaProgress progress) {
        final Localizer text = Localizer.getInstance();
        final Map<TraitCategory, List<RogueEffect>> traits = CodexHelper.getCodexTraitsByCategory();
        final List<CodexGroup> groups = new ArrayList<>();
        final List<CodexState> states = new ArrayList<>();
        for (final TraitCategory category : TRAIT_GROUPS) {
            final List<CodexItem> items = new ArrayList<>();
            for (final RogueEffect effect : traits.getOrDefault(category, List.of())) {
                final CodexState state = CodexHelper.state(progress, effect);
                states.add(state);
                items.add(state == CodexState.UNKNOWN ? new CodexItem("unknown", null, null, null)
                        : new CodexItem(state.name().toLowerCase(Locale.ROOT), null, null, trait(effect, null)));
            }
            groups.add(new CodexGroup(text.getMessage(switch (category) {
                case CHEST -> "lblRogueChest";
                case NPC -> "lblRogueNpc";
                case EVENT -> "lblRogueEvent";
                case WOUND -> "lblRogueWound";
            }), items));
        }
        return new CodexEntry(null, null, null, null, null, List.of(new CodexFact("seen", CodexHelper.countSeen(states) + " / " + states.size()),
                new CodexFact("acquired", CodexHelper.countAcquired(states) + " / " + states.size())), groups, List.of());
    }

    /** The tutorials already shown, then the story, each group headed. The art is that of the card named as the entry's teller, where there is one. */
    private static RogueCodexPage journal(final RogueMetaProgress progress, final String id, final String art) {
        final Localizer text = Localizer.getInstance();
        final List<JournalEntry> journal = CodexHelper.journal(progress);
        final List<CodexRow> rows = new ArrayList<>();
        for (int i = 0; i < journal.size(); i++) {
            final JournalEntry entry = journal.get(i);
            final boolean starts = i == 0 || journal.get(i - 1).story() != entry.story();
            rows.add(new CodexRow(entry.id(), entry.title(), null, entry.story() ? entry.speaker().name : null,
                    starts ? text.getMessage(entry.story() ? "lblRogueJournalStory" : "lblRogueJournalTutorials") : null));
        }
        final JournalEntry chosen = journal.stream().filter(e -> e.id().equals(id)).findFirst().orElse(journal.isEmpty() ? null : journal.get(0));
        if (chosen == null) {
            return new RogueCodexPage("journal", null, art, rows, List.of(), null);
        }
        final String speaker = picture(chosen.speaker().name);
        return new RogueCodexPage("journal", chosen.id(), speaker == null ? art : speaker, rows, List.of(), new CodexEntry(chosen.title(), null, null, null,
                chosen.speaker().avatarIndex, List.of(), List.of(), List.of(chosen.text().split("\n\n"))));
    }

    /** The finished runs, newest first, behind the latest run's commander. Null while History is locked. */
    public static RogueHistoryPage history(final RogueMetaProgress progress) {
        if (!RogueStart.tabsUnlocked(progress)) {
            return null;
        }
        final List<RogueRunHistoryEntry> entries = progress.getRunHistory();
        final List<HistoryRun> runs = new ArrayList<>();
        for (int i = entries.size() - 1; i >= 0; i--) {
            final RogueRunHistoryEntry entry = entries.get(i);
            final List<String> detail = new ArrayList<>();
            if (!entry.getOutcomeDetail().isEmpty()) {
                detail.add(entry.getOutcomeDetail());
            }
            final List<HistoryStep> steps = entry.getPathSteps().stream().map(step -> new HistoryStep(step.type().name(),
                    step.planeName() == null ? null : picture(step.planeName()), step.planeboundName())).toList();
            final List<HistoryTrait> traits = entry.getActiveEffects().stream().map(e -> new HistoryTrait(e.getDisplayText(), e.getTooltipText())).toList();
            runs.add(new HistoryRun(i, entry.getCommanderName(), picture(entry.getCommanderName()), entry.getShownOutcome(),
                    line(detail, entry.getDescensionLevel(), entry.getEndlessLevel()), steps, traits, entry.getTimestamp(), entry.getFormattedRunTime(),
                    entry.getDeckSnapshot() != null));
        }
        return new RogueHistoryPage(runs, runs.isEmpty() ? null : runs.get(0).image());
    }

    /** The deck a finished run ended with, read only, or null for one not kept or while History is locked. */
    public static DeckDetails historyDeck(final RogueMetaProgress progress, final int run) {
        final List<RogueRunHistoryEntry> entries = progress.getRunHistory();
        if (!RogueStart.tabsUnlocked(progress) || run < 0 || run >= entries.size() || entries.get(run).getDeckSnapshot() == null) {
            return null;
        }
        return details("rogue:history:" + run, entries.get(run).getCommanderName(), entries.get(run).getDeckSnapshot());
    }

    /** A card's picture by its name, for the art behind a page with no subject of its own. Null when no card has that name. */
    static String picture(final String name) {
        PaperCard card = StaticData.instance().getCommonCards().getCard(name);
        if (card == null) {
            card = StaticData.instance().getVariantCards().getCard(name);
        }
        return card == null ? null : card.getImageKey(false);
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
        return details("rogue:start:" + name, deck.getCommanderCardName(), deck.getStartDeck());
    }

    private static DeckDetails details(final String key, final String name, final Deck deck) {
        return new DeckDetails(key, name, null, DeckFacts.colors(deck), DeckFacts.stats(deck), CardCatalog.groups(deck.getMain(), DeckCatalog.NO_FLAGS),
                List.of(), null, 0, null);
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
                hiding.isEmpty() ? null : String.join("\n", hiding), MAP_TERMS);
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

    /** The plane of the last battle the run won, which a side node's scene is drawn over, or null before the first. */
    static String lastPlane(final RogueRun run) {
        return run.getPath().getNodes().stream().filter(n -> n.isCompleted() && n.getType() == NodeType.PLANEBOUND)
                .max(Comparator.comparingInt(RoguePathNode::getRowIndex)).map(n -> CodexHelper.getPlaneCard(((NodePlanebound) n).getRoguePlanebound()))
                .map(c -> c.getImageKey(false)).orElse(null);
    }

    static String planeboundArt(final RoguePlanebound planebound) {
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
        return line(new ArrayList<>(), run.getDescensionLevel(), run.getEndlessLevel());
    }

    /** line followed by the Descension and Endless levels, joined. It is empty with none of them. */
    private static String line(final List<String> line, final int descensionLevel, final int endlessLevel) {
        final Localizer text = Localizer.getInstance();
        final DescensionLevel descension = DescensionLevel.forLevel(descensionLevel);
        if (descensionLevel > 0 && descension != null) {
            line.add(text.getMessage("lblRogueHistoryDescension", descension.level, descension.name));
        }
        if (endlessLevel > 0) {
            line.add(text.getMessage("lblRogueEndlessLevel", endlessLevel));
        }
        return String.join(" · ", line);
    }

    private static RogueRunCard runCard(final RogueRun run) {
        final List<PaperCard> commanders = run.getCurrentDeck().getCommanders();
        final PaperCard commander = commanders.isEmpty() ? run.getSelectedRogueDeck().getStartDeck().getCommanders().get(0) : commanders.get(0);
        final List<RogueCard> carry = run.getCarryCards().stream().map(RogueRun.CarryCard::toPaperCard).filter(Objects::nonNull)
                .map(c -> new RogueCard(c.getName(), c.getImageKey(false), 1, null)).toList();
        return new RogueRunCard(run.getCurrentCommanderName(), commander.getImageKey(false), line(run), run.getCurrentLife(),
                run.getMaxLife(), run.getCurrentGold(), run.getCurrentDeck().getMain().countAll(), run.getRemovalCredits(), carry);
    }

    /** The run's carry cards, each with the term of its type. */
    private static List<RogueCard> carry(final RogueRun run) {
        final List<RogueCard> carry = new ArrayList<>();
        for (final RogueRun.CarryCard c : run.getCarryCards()) {
            final PaperCard card = c.toPaperCard();
            if (card != null) {
                carry.add(new RogueCard(card.getName(), card.getImageKey(false), 1, carryType(c.type())));
            }
        }
        return carry;
    }

    /** A carry card's type as the term that explains it, or as plain text where the language names no term. */
    static Part carryType(final RogueRun.CarryCardType type) {
        return WebRogueDecisions.parts("{{" + type.label() + "}}").get(0);
    }

    /** Where a trait came from, in the order the traits page lists them. TRAIT is any other source, which the page draws without a mark. */
    private enum Source { AETHER, CUSTOM, EVENT, CHEST, NPC, WRATHFUL, CURSED, TRAIT, WOUND, DESCENSION, ENDLESS }

    private static Source source(final RogueEffect effect) {
        return effect instanceof AetherEffect ? Source.AETHER : effect instanceof CustomRunModification ? Source.CUSTOM
                : effect instanceof EventEffect ? Source.EVENT : effect instanceof ChestEffect ? Source.CHEST : effect instanceof NPCEffect ? Source.NPC
                : effect instanceof WrathfulEffect ? Source.WRATHFUL : effect instanceof CursedEffect ? Source.CURSED
                : effect instanceof WoundEffect ? Source.WOUND : effect instanceof DescensionLevel ? Source.DESCENSION
                : effect instanceof EndlessMode ? Source.ENDLESS : Source.TRAIT;
    }

    /**
     * A trait as the traits page, a chest, a bazaar and the Codex draw it: its name without the card's prefix, its text for the run, and its source.
     * The Codex has no run, and passes null, which only Aetherworks and Endless Mode need.
     */
    static Trait trait(final RogueEffect effect, final RogueRun run) {
        final Localizer text = Localizer.getInstance();
        final String card = effect.getEffectCardDisplayName();
        final String name = card.isEmpty() ? effect.getDisplayName() : card;
        final List<Part> words = WebRogueDecisions.parts(effect.getActiveDescription(run).replace("\\n", "\n"));
        final boolean loot = effect.getEffectType() == RogueEffect.EffectType.ONESHOT;
        final Source source = source(effect);
        return switch (source) {
            case AETHER -> hinted(name, words, source, KeywordHint.AETHERWORK, loot);
            case CHEST -> hinted(name, words, source, KeywordHint.CHEST, loot);
            case WRATHFUL -> hinted(name, words, source, KeywordHint.WRATHFUL, loot);
            case CURSED -> hinted(name, words, source, KeywordHint.CURSED, loot);
            case WOUND -> hinted(name, words, source, KeywordHint.WOUND, loot);
            case TRAIT -> hinted(name, words, source, KeywordHint.TRAIT, loot);
            case CUSTOM -> new Trait(name, words, source.name(), null, null, text.getMessage("lblRogueCustomRun"),
                    text.getMessage("lblWebRogueCustomTrait"), loot);
            case EVENT -> new Trait(name, words, source.name(), null, null, text.getMessage("lblRogueEvent"), text.getMessage("lblWebRogueEventTrait"), loot);
            case NPC -> {
                final NPC npc = ((NPCEffect) effect).getOwnerNpc();
                yield new Trait(name, words, source.name(), npc.avatarIndex, null, npc.name, text.getMessage("lblWebRogueGivenBy", npc.name), loot);
            }
            case DESCENSION -> {
                final DescensionLevel level = (DescensionLevel) effect;
                yield new Trait(level.name, words, source.name(), null, String.valueOf(level.level), text.getMessage("lblRogueDescensionLevel", level.level),
                        text.getMessage("lblRogueDescensionTooltip", level.level, level.name, level.description), loot);
            }
            case ENDLESS -> new Trait(text.getMessage("lblRogueEndlessLevel", run.getEndlessLevel()), words, source.name(), null, null,
                    effect.getDisplayName(), effect.getDescription(), loot);
        };
    }

    private static Trait hinted(final String name, final List<Part> text, final Source source, final KeywordHint hint, final boolean loot) {
        return new Trait(name, text, source.name(), null, null, hint.getTitle(), hint.getHintText(), loot);
    }
}
