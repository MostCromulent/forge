package forge.web;

import forge.card.ColorSet;
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
import forge.gamemodes.planarconquest.ConquestPreferences.CQPref;
import forge.gamemodes.planarconquest.ConquestRegion;
import forge.gamemodes.planarconquest.ConquestRewardStep;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;
import forge.model.FModel;
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

/**
 * One browser's view of the open conquest. The conquest itself is Forge's, one for the whole process; this holds only
 * what the session knows that the save does not: which place is selected, the last move's path, and the result and
 * reward the browser has yet to show.
 */
final class ConquestGame {
    private ConquestLocation selection;
    private List<ConquestLocation> path = List.of();
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

    static ConquestData find(final String name) {
        final File dir = name == null ? null : saveDir(name);
        return dir != null && dir.isDirectory() ? new ConquestData(dir) : null;
    }

    private static File saveDir(final String name) {
        return new File(ForgeConstants.CONQUEST_SAVE_DIR, name.replace(' ', '_'));
    }

    static String planeName(final ConquestPlane plane) {
        return plane.getName().replace('_', ' ');
    }

    void opened() {
        selection = null;
        path = List.of();
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
                art = plane.getPlaneCards().isEmpty() ? null : plane.getPlaneCards().get(0).getImageKey(false);
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

    ConquestState state() {
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
                toChosen == null ? 0 : toChosen.size() - 1, path.stream().map(ConquestGame::place).toList(),
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
            path = List.of();
        }
    }

    /** Walks to the selection. False when there is nowhere to walk. */
    boolean move() {
        final ConquestData data = model();
        if (selection == null || selection.getPlane() != data.getCurrentPlane() || selection.equals(data.getCurrentLocation())) {
            return false;
        }
        final List<ConquestLocation> walked = data.moveTo(selection);
        if (walked == null) {
            return false;
        }
        path = walked;
        selection = null;
        return true;
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
