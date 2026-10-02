package forge.web;

import forge.card.ColorSet;
import forge.deck.Deck;
import forge.game.GameType;
import forge.game.GameView;
import forge.gamemodes.planarconquest.ConquestBattle;
import forge.gamemodes.planarconquest.ConquestChaosBattle;
import forge.gamemodes.planarconquest.ConquestCommander;
import forge.gamemodes.planarconquest.ConquestController;
import forge.gamemodes.planarconquest.ConquestData;
import forge.gamemodes.planarconquest.ConquestEvent;
import forge.gamemodes.planarconquest.ConquestEvent.ChaosWheelOutcome;
import forge.gamemodes.planarconquest.ConquestEvent.ConquestEventRecord;
import forge.gamemodes.planarconquest.ConquestLocation;
import forge.gamemodes.planarconquest.ConquestPlane;
import forge.gamemodes.planarconquest.ConquestPlaneData;
import forge.gamemodes.planarconquest.ConquestPreferences;
import forge.gamemodes.planarconquest.ConquestPreferences.CQPref;
import forge.gamemodes.planarconquest.ConquestRecord;
import forge.gamemodes.planarconquest.ConquestRegion;
import forge.gamemodes.planarconquest.ConquestRewardStep;
import forge.gamemodes.planarconquest.ConquestUtil;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;
import forge.model.FModel;
import forge.util.Localizer;
import forge.web.FromBrowser.CatalogueQuery;
import forge.web.ToBrowser.CataloguePage;
import forge.web.ToBrowser.ConquestCollection;
import forge.web.ToBrowser.ConquestCommanderRow;
import forge.web.ToBrowser.ConquestParty;
import forge.web.ToBrowser.ConquestWalkerRow;
import forge.web.ToBrowser.DeckDetails;
import forge.web.ToBrowser.ConquestBar;
import forge.web.ToBrowser.ConquestCell;
import forge.web.ToBrowser.ConquestLead;
import forge.web.ToBrowser.ConquestPackCard;
import forge.web.ToBrowser.ConquestPlace;
import forge.web.ToBrowser.ConquestRegionRow;
import forge.web.ToBrowser.ConquestResult;
import forge.web.ToBrowser.ConquestReward;
import forge.web.ToBrowser.ConquestSave;
import forge.web.ToBrowser.ConquestSaves;
import forge.web.ToBrowser.ConquestState;
import forge.web.ToBrowser.ConquestStep;

import java.io.File;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * One browser's view of the open conquest. The conquest itself is Forge's, one for the whole process; this holds only
 * what the session knows that the save does not: which place is selected, and the result and reward the browser has
 * yet to show.
 */
final class ConquestGame {
    private ConquestLocation selection;
    /** The result of the game just ended, while its match is still open. */
    private volatile ConquestResult result;
    /** What the last won battle gave, until the browser says it has shown it. */
    private volatile ConquestReward reward;
    private volatile boolean chaosOwed;
    /** Dev mode's choice of where the next wheel stops. */
    private volatile ChaosWheelOutcome nextWheel;

    static ConquestData model() {
        return FModel.getConquest().getModel();
    }

    /** Reads every save, which loads the planes they stand on. Not for the socket thread. */
    static ConquestSaves saves() {
        final List<ConquestSave> rows = new ArrayList<>();
        for (final ConquestData data : ConquestController.listSaves()) {
            if (!readable(data)) {
                continue;
            }
            final File file = new File(data.getDirectory(), "data.xml");
            rows.add(new ConquestSave(data.getName(), data.getPlaneswalker().getDisplayName(), data.getPlaneswalker().getImageKey(false),
                    planeName(data.getCurrentPlane()), data.getProgress(), data.getUnlockedCardCount(), data.getAEtherShards(),
                    data.getPlaneswalkEmblems(),
                    file.exists() ? LocalDate.ofInstant(Instant.ofEpochMilli(file.lastModified()), ZoneId.systemDefault()).toString() : null));
        }
        return new ConquestSaves(rows, currentName());
    }

    /** The conquest played last, if its folder is still there. */
    static String currentName() {
        final String name = FModel.getConquestPreferences().getPref(CQPref.CURRENT_CONQUEST);
        return name != null && saveDir(name).isDirectory() ? name : null;
    }

    /** The save of a name, or null when there is none or it cannot be read. */
    static ConquestData find(final String name) {
        final File dir = name == null ? null : saveDir(name);
        final ConquestData data = dir != null && dir.isDirectory() ? new ConquestData(dir) : null;
        return data != null && readable(data) ? data : null;
    }

    /** A save whose file could not be read is left with nothing in it, and has no page to show. */
    private static boolean readable(final ConquestData data) {
        return data.getPlaneswalker() != null && data.getCurrentLocation() != null;
    }

    private static File saveDir(final String name) {
        return new File(ForgeConstants.CONQUEST_SAVE_DIR, name.replace(' ', '_'));
    }

    /** The open conquest's commander of a name, or null. */
    static ConquestCommander commander(final String name) {
        for (final ConquestCommander c : model().getCommanders()) {
            if (c.getName().equals(name)) {
                return c;
            }
        }
        return null;
    }

    /** What the deck editor builds a commander's deck from: the cards owned and not exiled. */
    static DeckEditor.Collection collection(final ConquestCommander commander) {
        final ConquestData data = model();
        // The commander caches its deck, and the editor saves a copy
        return new DeckEditor.Collection(data.getName(), ConquestUtil::getAvailablePool, data::isNewCard,
                ConquestUtil::getBasicLandSets, commander::reloadDeck);
    }

    static String planeName(final ConquestPlane plane) {
        return plane.getName().replace('_', ' ');
    }

    void opened() {
        selection = null;
        result = null;
        reward = null;
        chaosOwed = false;
        nextWheel = null;
    }

    void setNextWheel(final ChaosWheelOutcome outcome) {
        nextWheel = outcome;
    }

    ConquestResult result() {
        return result;
    }

    ConquestReward reward() {
        return reward;
    }

    /** A game of the active battle ended. hostGame is the host's own view, which alone knows the match. */
    ConquestResult gameOver(final GameView hostGame) {
        final ConquestController controller = FModel.getConquest();
        final ConquestBattle battle = controller.getActiveBattle();
        if (battle == null || hostGame == null) {
            return null;
        }
        final ConquestBattle.Outcome outcome = controller.recordOutcome(hostGame);
        boolean first = false;
        if (outcome == ConquestBattle.Outcome.WON) {
            final ChaosWheelOutcome wheel = nextWheel != null ? nextWheel : ChaosWheelOutcome.random();
            nextWheel = null;
            final List<ConquestRewardStep> steps = controller.claimRewards(battle, wheel);
            first = steps.stream().anyMatch(s -> s.kind() == ConquestRewardStep.Kind.CONQUER_EMBLEMS);
            chaosOwed = steps.stream().anyMatch(s -> s.kind() == ConquestRewardStep.Kind.CHAOS_BATTLE);
            reward = new ConquestReward(steps.stream().map(s -> step(s, battle)).toList());
        }
        result = new ConquestResult(outcome == ConquestBattle.Outcome.WON, outcome != ConquestBattle.Outcome.UNFINISHED,
                battle instanceof ConquestChaosBattle, battle.getEventName(), first);
        return result;
    }

    /** The match is left: its result goes with it. */
    void left() {
        result = null;
    }

    /** The reward has been shown. True when it ended in a chaos battle that is now owed. */
    boolean claim() {
        reward = null;
        final boolean owed = chaosOwed;
        chaosOwed = false;
        return owed;
    }

    private static ConquestStep step(final ConquestRewardStep s, final ConquestBattle battle) {
        List<ConquestPackCard> cards = null;
        String pack = null;
        String art = null;
        if (s.cards() != null) {
            cards = s.cards().stream().map(r -> new ConquestPackCard(r.getCard().getName(), r.getCard().getImageKey(false),
                    r.getCard().getRarity().name(), r.getReplacementShards())).toList();
            if (s.chaos()) {
                pack = ((ConquestChaosBattle) battle).getWorldName();
            } else {
                final ConquestPlane plane = model().getCurrentPlane();
                pack = planeName(plane);
                // The plane has no picture of its own, so its pack wears its first region's
                final ConquestRegion.ArtCard picture = plane.getRegions().get(0).getArtCard();
                art = picture.card().getImageKey(picture.backFace());
            }
        }
        return new ConquestStep(s.kind().name(), s.amount(), s.outcome() == null ? null : s.outcome().name(), cards,
                s.number(), s.total(), s.chaos(), pack, art);
    }

    ConquestBar bar() {
        final ConquestData data = model();
        final ConquestPlane plane = data.getCurrentPlane();
        return new ConquestBar(data.getName(), planeName(plane), data.getCurrentPlaneData().getConqueredCount(), plane.getEventCount(),
                data.getAEtherShards(), data.getPlaneswalkEmblems());
    }

    /** The commanders and planeswalkers the conquest has found. */
    ConquestParty party() {
        final ConquestData data = model();
        final ConquestCommander lead = data.getSelectedCommander();
        final List<ConquestCommanderRow> commanders = new ArrayList<>();
        for (final ConquestCommander c : data.getCommanders()) {
            final ConquestRecord record = c.getRecord();
            commanders.add(new ConquestCommanderRow(c.getName(), c.getCard().getImageKey(false),
                    colors(c.getCard().getRules().getColorIdentity()), c.getOrigin(), record.getWins(), record.getLosses(),
                    c.getDeck().getMain().countAll(), c.getDeckProblem(), c == lead));
        }
        final List<ConquestWalkerRow> walkers = new ArrayList<>();
        for (final PaperCard card : data.getSortedPlaneswalkers()) {
            walkers.add(new ConquestWalkerRow(card.getName(), card.getImageKey(false), colors(card.getRules().getColorIdentity()),
                    card.equals(data.getPlaneswalker())));
        }
        final ConquestData.Stats stats = data.getStats(null);
        return new ConquestParty(commanders, walkers, ConquestData.formatRatio(stats.commanders(), stats.allCommanders()),
                ConquestData.formatRatio(stats.planeswalkers(), stats.allPlaneswalkers()));
    }

    /** Makes an owned planeswalker the one travelled as. False when the conquest owns none of that name. */
    static boolean setPlaneswalker(final String name) {
        final ConquestData data = model();
        for (final PaperCard card : data.getSortedPlaneswalkers()) {
            if (card.getName().equals(name)) {
                data.setPlaneswalker(card);
                data.saveData();
                return true;
            }
        }
        return false;
    }

    /** A commander's deck as the deck finder's panel shows one. */
    static DeckDetails deckDetails(final ConquestCommander commander) {
        final Deck deck = commander.getDeck();
        final Legality.Result noFlags = new Legality.Result(Map.of(), Map.of(), List.of());
        return new DeckDetails("conquest:" + commander.getName(), commander.getName(), commander.getDeckProblem(),
                colors(commander.getCard().getRules().getColorIdentity()), DeckCatalog.stats(deck),
                DeckEditor.groups(deck.getMain(), noFlags), List.of(), null, 0, null);
    }

    /** The cards owned and not exiled, or those exiled. */
    private static List<PaperCard> cardsOf(final boolean exile) {
        final ConquestData data = model();
        final List<PaperCard> cards = new ArrayList<>();
        for (final PaperCard card : exile ? data.getExiledCards() : data.getUnlockedCards()) {
            if (exile || !data.isInExile(card)) {
                cards.add(card);
            }
        }
        return cards;
    }

    ConquestCollection collection() {
        final ConquestPreferences prefs = FModel.getConquestPreferences();
        final double base = prefs.getPrefInt(CQPref.AETHER_BASE_DUPLICATE_VALUE);
        final List<String> planes = new ArrayList<>();
        for (final ConquestPlane plane : FModel.getPlanes()) {
            if (!plane.isUnreachable()) {
                planes.add(planeName(plane));
            }
        }
        // Mobile's line, on one line
        final String note = Localizer.getInstance().getMessage("lblExileRetrieveProportion",
                Math.round(100 * prefs.getPrefInt(CQPref.AETHER_BASE_EXILE_VALUE) / base),
                Math.round(100 * prefs.getPrefInt(CQPref.AETHER_BASE_RETRIEVE_COST) / base)).replace('\n', ' ');
        return new ConquestCollection(cardsOf(false).size(), cardsOf(true).size(), planes, note);
    }

    /** A page of the collection or of the exile, each card with what exiling or retrieving it is worth. */
    CataloguePage cards(final CatalogueQuery q) {
        final ConquestData data = model();
        final boolean exile = "exile".equals(q.source());
        final List<PaperCard> cards = cardsOf(exile);
        if (q.plane() != null) {
            for (final ConquestPlane plane : FModel.getPlanes()) {
                if (planeName(plane).equals(q.plane())) {
                    cards.removeIf(c -> !plane.getCardPool().contains(c));
                }
            }
        }
        // A card that cannot be exiled says why, and is listed all the same
        final Function<PaperCard, String> problem = exile ? c -> null : c -> data.getExileProblem(List.of(c));
        return CardCatalog.of(cards).query(q.request(), new CardCatalog.Query(q.text(), q.colours(), q.type(), q.filters(), q.sort(),
                q.offset(), true), problem, null, name -> 0, c -> new CardCatalog.Extra(data.isNewCard(c) ? Boolean.TRUE : null,
                exile ? data.getRetrieveCost(List.of(c)) : data.getExileValue(List.of(c))));
    }

    /** Exiles cards of the collection, or brings exiled ones back. Answers why it cannot, or null when it is done. */
    static String exile(final List<String> imageKeys, final boolean retrieve) {
        final ConquestData data = model();
        final List<PaperCard> cards = cardsOf(retrieve);
        cards.removeIf(c -> !imageKeys.contains(c.getImageKey(false)));
        if (cards.isEmpty()) {
            return null;
        }
        final String problem = retrieve ? data.getRetrieveProblem(cards) : data.getExileProblem(cards);
        if (problem != null) {
            return problem;
        }
        if (retrieve) {
            data.retrieve(cards);
        } else {
            data.exile(cards);
        }
        return null;
    }

    /** The map as it stands. walked is the move just made, for the marker to walk, and is empty otherwise. */
    ConquestState state(final List<ConquestLocation> walked) {
        final ConquestData data = model();
        final ConquestPlane plane = data.getCurrentPlane();
        final ConquestPlaneData planeData = data.getCurrentPlaneData();
        final ConquestLocation here = data.getCurrentLocation();
        final ConquestLocation chosen = selection != null && selection.getPlane() == plane ? selection : here;
        final List<ConquestRegionRow> regions = new ArrayList<>();
        final List<ConquestCell> cells = new ArrayList<>();
        for (int r = 0; r < plane.getRegions().size(); r++) {
            final ConquestRegion region = plane.getRegions().get(r);
            int conquered = 0;
            for (int row = 0; row < plane.getRowsPerRegion(); row++) {
                for (int col = 0; col < plane.getCols(); col++) {
                    final ConquestLocation loc = new ConquestLocation(plane, r, row, col);
                    final boolean won = planeData.hasConquered(loc);
                    if (won) {
                        conquered++;
                    }
                    cells.add(cell(loc, won, planeData));
                }
            }
            final ConquestRegion.ArtCard art = region.getArtCard();
            regions.add(new ConquestRegionRow(region.getName(), art.card().getImageKey(art.backFace()), colors(region.getColorSet()),
                    conquered, plane.getRowsPerRegion() * plane.getCols()));
        }
        final List<ConquestLocation> toChosen = chosen.equals(here) ? null : data.getPath(chosen);
        final ConquestCommander commander = data.getSelectedCommander();
        return new ConquestState(planeName(plane), plane.getRowsPerRegion(), plane.getCols(), regions, cells, place(here),
                data.getPlaneswalker().getDisplayName(), data.getPlaneswalker().getImageKey(false), place(chosen),
                toChosen == null ? 0 : toChosen.size() - 1, walked.stream().map(ConquestGame::place).toList(),
                new ConquestLead(commander.getDisplayName(), commander.getCard().getImageKey(false),
                        commander.getDeck().getMain().countAll(), commander.getDeckProblem()));
    }

    /** A place can be fought when it is the plane's first, is conquered, or touches a conquered one. */
    private static boolean reachable(final ConquestLocation loc, final ConquestPlaneData planeData) {
        if (loc.isAt(0, 0, 0) || planeData.hasConquered(loc)) {
            return true;
        }
        for (final ConquestLocation neighbor : loc.getNeighbors()) {
            if (planeData.hasConquered(neighbor)) {
                return true;
            }
        }
        return false;
    }

    private static ConquestCell cell(final ConquestLocation loc, final boolean won, final ConquestPlaneData planeData) {
        if (!reachable(loc, planeData)) {
            return new ConquestCell(loc.getRegionIndex(), loc.getRow(), loc.getCol(), "fog", null, null, null, null, 0, 0, null);
        }
        final ConquestEvent event = loc.getEvent();
        final PaperCard avatar = event.getAvatarCard();
        final ConquestEventRecord record = planeData.getEventRecord(loc);
        return new ConquestCell(loc.getRegionIndex(), loc.getRow(), loc.getCol(), won ? "won" : "open", event.getName(),
                event.getOpponentName(), avatar == null ? null : avatar.getImageKey(false),
                event.getVariants().stream().map(GameType::name).toList(),
                record == null ? 0 : record.getTotalWins(), record == null ? 0 : record.getTotalLosses(),
                event.getTemporaryUnlock() == null ? null : event.getTemporaryUnlock().replace('_', ' '));
    }

    private static ConquestPlace place(final ConquestLocation loc) {
        return new ConquestPlace(loc.getRegionIndex(), loc.getRow(), loc.getCol());
    }

    private static String colors(final ColorSet set) {
        final StringBuilder out = new StringBuilder();
        if (set.hasWhite()) { out.append('W'); }
        if (set.hasBlue()) { out.append('U'); }
        if (set.hasBlack()) { out.append('B'); }
        if (set.hasRed()) { out.append('R'); }
        if (set.hasGreen()) { out.append('G'); }
        return out.toString();
    }

    /** Selects a place the player could walk to; anything else is ignored. */
    void select(final int region, final int row, final int col) {
        final ConquestData data = model();
        final ConquestPlane plane = data.getCurrentPlane();
        if (region < 0 || region >= plane.getRegions().size() || row < 0 || row >= plane.getRowsPerRegion()
                || col < 0 || col >= plane.getCols()) {
            return;
        }
        final ConquestLocation loc = new ConquestLocation(plane, region, row, col);
        if (loc.equals(data.getCurrentLocation()) || data.getPath(loc) != null) {
            selection = loc;
        }
    }

    /** Walks to the selection and returns the path walked, or null when there is nowhere to walk. */
    List<ConquestLocation> move() {
        final ConquestData data = model();
        if (selection == null || selection.getPlane() != data.getCurrentPlane() || selection.equals(data.getCurrentLocation())) {
            return null;
        }
        final List<ConquestLocation> walked = data.moveTo(selection);
        if (walked != null) {
            selection = null;
        }
        return walked;
    }

    /** Why a battle cannot start where the player stands, or null if it can. */
    String battleProblem() {
        return model().getSelectedCommander().getDeckProblem();
    }

    ConquestBattle battle() {
        final ConquestLocation loc = model().getCurrentLocation();
        return loc.getEvent().createBattle(loc, 0);
    }
}
