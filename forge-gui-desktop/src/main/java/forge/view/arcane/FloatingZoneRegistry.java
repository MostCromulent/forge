/*
 * Forge: Play Magic: the Gathering.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package forge.view.arcane;

import java.awt.Color;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;

import forge.game.card.CardView;
import forge.game.player.PlayerView;
import forge.game.zone.ZoneType;
import forge.gui.FThreads;
import forge.gui.framework.DragCell;
import forge.gui.framework.EDocID;
import forge.gui.framework.ICDoc;
import forge.gui.framework.IVDoc;
import forge.gui.framework.SDisplayUtil;
import forge.gui.framework.SLayoutConstants;
import forge.gui.framework.SLayoutIO;
import forge.gui.framework.SRearrangingUtil;
import forge.localinstance.properties.ForgePreferences;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.screens.match.CMatchUI;
import forge.screens.match.views.VHand;
import forge.screens.match.views.VZone;
import forge.toolbox.MouseTriggerEvent;
import forge.toolbox.special.PlayerDetailsPanel;
import forge.util.Localizer;
import forge.util.collect.FCollectionView;
import forge.view.FView;

/**
 * Static registry and orchestrator for zone-backed {@link FloatingCardWindow} instances.
 * Owns the per-(player, zone) instance map, tab-vs-float decisions, drag-to-dock detection,
 * and VZone layout-persistence registration. Callers use {@link FloatingCardWindow#forZone}
 * which delegates here.
 */
public final class FloatingZoneRegistry {

    private FloatingZoneRegistry() {}

    private static final Map<Integer, FloatingCardWindow> floatingAreas = new HashMap<>();
    private static final Map<Integer, VZone> dockedZones = new HashMap<>();

    private static int getKey(final PlayerView player, final ZoneType zone) {
        return 40 * player.getId() + zone.hashCode();
    }

    // ===================== Tab mode preference =====================

    public static boolean isTabMode(final ZoneType zone, final boolean isOwn) {
        final FPref prefKey = isOwn ? FPref.UI_ZONE_DOCK_ZONES : FPref.UI_ZONE_DOCK_ZONES_OTHER;
        final String pref = FModel.getPreferences().getPref(prefKey);
        if (pref == null || pref.isEmpty()) return false;
        for (final String s : pref.split(",")) {
            if (s.trim().equals(zone.name())) return true;
        }
        return false;
    }

    public static void setTabMode(final ZoneType zone, final boolean tabMode, final boolean isOwn) {
        final ForgePreferences prefs = FModel.getPreferences();
        final FPref prefKey = isOwn ? FPref.UI_ZONE_DOCK_ZONES : FPref.UI_ZONE_DOCK_ZONES_OTHER;
        final String current = prefs.getPref(prefKey);
        final LinkedHashSet<String> zones = new LinkedHashSet<>();
        if (current != null && !current.isEmpty()) {
            for (final String s : current.split(",")) {
                final String trimmed = s.trim();
                if (!trimmed.isEmpty()) zones.add(trimmed);
            }
        }
        if (tabMode) zones.add(zone.name());
        else zones.remove(zone.name());
        prefs.setPref(prefKey, String.join(",", zones));
        prefs.save();
    }

    // ===================== Public API mirroring legacy FloatingZone =====================

    public static FloatingCardWindow getOrCreate(final CMatchUI matchUI, final PlayerView player, final ZoneType zone) {
        final int key = getKey(player, zone);
        FloatingCardWindow window = floatingAreas.get(key);
        if (window == null || window.getMatchUI() != matchUI) {
            final ZoneSource src = new ZoneSource(matchUI, player, zone);
            final BrowseInteraction inter = new BrowseInteraction();
            window = new FloatingCardWindow(matchUI, src, inter);
            installDockDetection(window);
            floatingAreas.put(key, window);
        } else {
            ((ZoneSource) window.getSource()).setPlayer(player);
        }
        return window;
    }

    public static void showOrHide(final CMatchUI matchUI, final PlayerView player, final ZoneType zone) {
        if (zone == ZoneType.Hand && isTabMode(zone, matchUI.isLocalPlayer(player))) {
            final VHand existingHand = matchUI.getHandFor(player);
            if (existingHand != null && existingHand.getParentCell() != null) {
                SDisplayUtil.showTab(existingHand);
                return;
            }
        }

        final int key = getKey(player, zone);
        final VZone docked = dockedZones.get(key);
        if (docked != null) {
            final DragCell cell = docked.getParentCell();
            if (cell != null) {
                cell.removeDoc(docked);
                if (cell.getDocs().isEmpty()) {
                    SRearrangingUtil.fillGap(cell);
                    FView.SINGLETON_INSTANCE.removeDragCell(cell);
                }
                docked.setParentCell(null);
            } else {
                showDockedTab(docked);
            }
            return;
        }

        final boolean isOwn = matchUI.isLocalPlayer(player);
        if (isTabMode(zone, isOwn)) {
            showAsTab(matchUI, player, zone);
            return;
        }

        final FloatingCardWindow window = getOrCreate(matchUI, player, zone);
        window.showOrHideWindow();
    }

    public static boolean show(final CMatchUI matchUI, final PlayerView player, final ZoneType zone) {
        final FloatingCardWindow window = getOrCreate(matchUI, player, zone);
        if (window.isVisible()) return false;
        FThreads.invokeInEdtNowOrLater(window::showWindow);
        return true;
    }

    public static boolean hide(final CMatchUI matchUI, final PlayerView player, final ZoneType zone) {
        final FloatingCardWindow window = getOrCreate(matchUI, player, zone);
        if (!window.isVisible()) return false;
        FThreads.invokeInEdtNowOrLater(window::hideWindow);
        return true;
    }

    public static void closeExisting(final CMatchUI matchUI, final PlayerView player, final ZoneType zone) {
        final int key = getKey(player, zone);
        final VZone docked = dockedZones.get(key);
        if (docked != null) removeDocked(docked);
        final FloatingCardWindow floating = floatingAreas.get(key);
        if (floating != null && floating.isVisible()) {
            floating.hideWindow();
            floatingAreas.remove(key);
        }
    }

    public static CardPanel getCardPanel(final CMatchUI matchUI, final CardView card) {
        final int key = getKey(card.getController(), card.getZone());
        final VZone docked = dockedZones.get(key);
        if (docked != null) {
            final CardPanel panel = docked.getCardPanel(card);
            if (panel != null) return panel;
        }
        final FloatingCardWindow window = getOrCreate(matchUI, card.getController(), card.getZone());
        return window.getCardPanel(card.getId());
    }

    public static void refresh(final PlayerView player, final ZoneType zone) {
        final FloatingCardWindow window = floatingAreas.get(getKey(player, zone));
        if (window != null) {
            ((ZoneSource) window.getSource()).setPlayer(player);
            window.refresh();
        }
        final VZone docked = dockedZones.get(getKey(player, zone));
        if (docked != null) docked.refresh();

        switch (zone) {
            case Graveyard:
            case Library:
            case Exile:
            case Command:
                refresh(player, ZoneType.Flashback);
                break;
            default:
                break;
        }
    }

    public static void closeAll() {
        for (final FloatingCardWindow window : floatingAreas.values()) {
            window.getWindow().setVisible(false);
        }
        floatingAreas.clear();
        for (final VZone vZone : dockedZones.values()) {
            final DragCell cell = vZone.getParentCell();
            if (cell != null) {
                cell.removeDoc(vZone);
                if (cell.getDocs().isEmpty()) {
                    SRearrangingUtil.fillGap(cell);
                    FView.SINGLETON_INSTANCE.removeDragCell(cell);
                }
            }
            final EDocID docID = vZone.getDocumentID();
            if (docID != null) docID.setDoc(null);
        }
        dockedZones.clear();
    }

    public static void refreshAll() {
        for (final FloatingCardWindow window : floatingAreas.values()) window.refresh();
        for (final VZone vZone : dockedZones.values()) vZone.refresh();
    }

    public static void refreshSelectionPrompts() {
        FloatingCardWindow.refreshAllSelectionPrompts();
    }

    public static VZone getDockedZone(final PlayerView player, final ZoneType zone) {
        return dockedZones.get(getKey(player, zone));
    }

    public static void registerZoneDocs(final CMatchUI matchUI, final Iterable<PlayerView> localPlayers) {
        for (final PlayerView player : localPlayers) {
            for (final ZoneType zone : CMatchUI.FLOATING_ZONE_TYPES) {
                final EDocID docID = EDocID.fromZoneType(zone);
                if (docID != null) {
                    final VZone vZone = new VZone(matchUI, player, zone);
                    docID.setDoc(vZone);
                    dockedZones.put(getKey(player, zone), vZone);
                }
            }
            break; // Only the first local player's zones
        }
    }

    public static void deregisterZoneDocs() {
        for (final VZone vZone : dockedZones.values()) {
            final EDocID docID = vZone.getDocumentID();
            if (docID != null) docID.setDoc(null);
        }
    }

    public static void pruneUnparentedDocks() {
        dockedZones.values().removeIf(vZone -> vZone.getParentCell() == null);
    }

    public static void undockZone(final VZone vZone) {
        final DragCell cell = vZone.getParentCell();
        if (cell != null) {
            cell.removeDoc(vZone);
            if (cell.getDocs().isEmpty()) {
                SRearrangingUtil.fillGap(cell);
                FView.SINGLETON_INSTANCE.removeDragCell(cell);
            }
        }
        final EDocID docID = vZone.getDocumentID();
        if (docID != null) docID.setDoc(null);
        final int key = getKey(vZone.getPlayer(), vZone.getZone());
        dockedZones.remove(key);
        SLayoutIO.saveLayout(null);
        show(vZone.getMatchUI(), vZone.getPlayer(), vZone.getZone());
    }

    // ===================== Internal helpers =====================

    private static void showAsTab(final CMatchUI matchUI, final PlayerView player, final ZoneType zone) {
        final EDocID docID = EDocID.fromZoneType(zone);
        if (docID == null) return;
        final VZone vZone = new VZone(matchUI, player, zone);
        docID.setDoc(vZone);
        dockedZones.put(getKey(player, zone), vZone);
        showDockedTab(vZone);
    }

    private static void showDockedTab(final VZone vZone) {
        DragCell target = null;
        final IVDoc<? extends ICDoc> handDoc = EDocID.HAND_0.getDoc();
        if (handDoc != null) target = handDoc.getParentCell();
        if (target == null) {
            final List<DragCell> cells = FView.SINGLETON_INSTANCE.getDragCells();
            if (!cells.isEmpty()) target = cells.get(0);
        }
        if (target != null) {
            target.addDoc(vZone);
            target.setSelected(vZone);
            vZone.refresh();
        }
    }

    private static void removeDocked(final VZone vZone) {
        final DragCell cell = vZone.getParentCell();
        if (cell != null) {
            cell.removeDoc(vZone);
            if (cell.getDocs().isEmpty()) {
                SRearrangingUtil.fillGap(cell);
                FView.SINGLETON_INSTANCE.removeDragCell(cell);
            }
        }
        final EDocID docID = vZone.getDocumentID();
        if (docID != null) docID.setDoc(null);
        dockedZones.remove(getKey(vZone.getPlayer(), vZone.getZone()));
        SLayoutIO.saveLayout(null);
    }

    // ===================== Drag-to-dock detection =====================

    private static final Border DOCK_HIGHLIGHT_BORDER =
            BorderFactory.createLineBorder(new Color(70, 130, 230), 2);

    private static void installDockDetection(final FloatingCardWindow window) {
        final DockTracker tracker = new DockTracker(window);
        window.getWindow().getTitleBar().addMouseListener(new MouseAdapter() {
            @Override public void mouseReleased(final MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) return;
                if (tracker.dockTargetCell != null) {
                    tracker.clearHighlight();
                    dockIntoCell(window, tracker.dockTargetCell);
                    tracker.dockTargetCell = null;
                }
            }
        });
        window.getWindow().getTitleBar().addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseDragged(final MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) return;
                tracker.detectTarget(e);
            }
        });
    }

    private static final class DockTracker {
        DragCell dockTargetCell;
        DragCell highlightedCell;
        Border dockOriginalBorder;
        final FloatingCardWindow window;
        DockTracker(final FloatingCardWindow w) { this.window = w; }

        void detectTarget(final MouseEvent e) {
            final int ex = (int) e.getLocationOnScreen().getX();
            final int ey = (int) e.getLocationOnScreen().getY();
            DragCell newTarget = null;
            for (final DragCell cell : FView.SINGLETON_INSTANCE.getDragCells()) {
                final int cx = cell.getAbsX();
                final int cy = cell.getAbsY();
                final int cw = cell.getW();
                if (ex >= cx && ey >= cy && ex <= cx + cw && ey <= cy + SLayoutConstants.HEAD_H * 3 / 2) {
                    newTarget = cell;
                    break;
                }
            }
            if (newTarget != dockTargetCell) {
                clearHighlight();
                dockTargetCell = newTarget;
                if (dockTargetCell != null) applyHighlight();
            }
        }

        void applyHighlight() {
            if (dockTargetCell == null) return;
            highlightedCell = dockTargetCell;
            dockOriginalBorder = dockTargetCell.getBody().getBorder();
            dockTargetCell.getBody().setBorder(DOCK_HIGHLIGHT_BORDER);
        }

        void clearHighlight() {
            if (highlightedCell != null) {
                highlightedCell.getBody().setBorder(dockOriginalBorder);
                highlightedCell = null;
            }
            dockOriginalBorder = null;
        }
    }

    private static void dockIntoCell(final FloatingCardWindow window, final DragCell targetCell) {
        final ZoneSource src = (ZoneSource) window.getSource();
        final EDocID docID = EDocID.fromZoneType(src.getZone());
        if (docID == null) return;
        final VZone vZone = new VZone(window.getMatchUI(), src.getPlayer(), src.getZone());
        docID.setDoc(vZone);
        final int key = getKey(src.getPlayer(), src.getZone());
        dockedZones.put(key, vZone);
        window.hideWindow();
        floatingAreas.remove(key);

        targetCell.addDoc(vZone);
        targetCell.setSelected(vZone);
        vZone.refresh();
        SLayoutIO.saveLayout(null);
    }

    // ===================== Sort comparator (zone-specific: Flashback ordering) =====================

    private static int zoneOrder(final ZoneType zone) {
        if (zone == null) return 99;
        switch (zone) {
            case Command:   return 0;
            case Graveyard: return 1;
            case Exile:     return 2;
            case Library:   return 3;
            case Sideboard: return 4;
            default:        return 5;
        }
    }

    public static final Comparator<CardView> ZONE_ORDER_COMPARATOR =
            Comparator.comparingInt((CardView cv) -> zoneOrder(cv.getZone()))
                    .thenComparingInt(cv -> cv.getCurrentState().getManaCost().getCMC())
                    .thenComparing(cv -> cv.getCurrentState().getColors().getOrderWeight())
                    .thenComparing(cv -> cv.getCurrentState().getName());

    // ===================== ZoneSource — live-zone-backed FloatingCardWindow.Source =====================

    static final class ZoneSource implements FloatingCardWindow.Source {
        private final CMatchUI matchUI;
        private final ZoneType zone;
        private PlayerView player;
        private FPref locPref;

        ZoneSource(final CMatchUI matchUI, final PlayerView player, final ZoneType zone) {
            this.matchUI = matchUI;
            this.zone = zone;
            setPlayer(player);
        }

        PlayerView getPlayer() { return player; }
        ZoneType getZone() { return zone; }

        void setPlayer(final PlayerView player0) {
            if (player == player0) return;
            player = player0;
            final boolean isAi = player0.isAI();
            switch (zone) {
                case Exile:     locPref = isAi ? FPref.ZONE_LOC_AI_EXILE     : FPref.ZONE_LOC_HUMAN_EXILE; break;
                case Graveyard: locPref = isAi ? FPref.ZONE_LOC_AI_GRAVEYARD : FPref.ZONE_LOC_HUMAN_GRAVEYARD; break;
                case Hand:      locPref = isAi ? FPref.ZONE_LOC_AI_HAND      : FPref.ZONE_LOC_HUMAN_HAND; break;
                case Library:   locPref = isAi ? FPref.ZONE_LOC_AI_LIBRARY   : FPref.ZONE_LOC_HUMAN_LIBRARY; break;
                case Flashback: locPref = isAi ? FPref.ZONE_LOC_AI_FLASHBACK : FPref.ZONE_LOC_HUMAN_FLASHBACK; break;
                case Command:   locPref = isAi ? FPref.ZONE_LOC_AI_COMMAND   : FPref.ZONE_LOC_HUMAN_COMMAND; break;
                case Ante:      locPref = isAi ? FPref.ZONE_LOC_AI_ANTE      : FPref.ZONE_LOC_HUMAN_ANTE; break;
                case Sideboard: locPref = isAi ? FPref.ZONE_LOC_AI_SIDEBOARD : FPref.ZONE_LOC_HUMAN_SIDEBOARD; break;
                default:        locPref = null; break;
            }
        }

        @Override
        public Iterable<CardView> getCards() {
            final FCollectionView<CardView> zoneCards = player.getCards(zone);
            if (zoneCards == null) return null;
            return matchUI.isNetGame() ? zoneCards.threadSafeIterable() : zoneCards;
        }

        @Override
        public String getTitleFormat() {
            return Localizer.getInstance().getMessage("lblPlayerZoneNCardSortStatus",
                    player.getName(), zone.getTranslatedName(), "%d",
                    Localizer.getInstance().getMessage("lblRightClickToSort"));
        }

        @Override public boolean isLive() { return true; }
        @Override public FPref getLocPref() { return locPref; }
        @Override public boolean supportsSortToggle() { return true; }

        @Override
        public Comparator<CardView> defaultComparator() {
            if (zone == ZoneType.Flashback) return ZONE_ORDER_COMPARATOR;
            return null;
        }

        @Override
        public Comparator<CardView> sortedComparator() {
            return (lhs, rhs) -> {
                if (!matchUI.mayView(lhs)) return matchUI.mayView(rhs) ? 1 : 0;
                if (!matchUI.mayView(rhs)) return -1;
                return lhs.getName().compareTo(rhs.getName());
            };
        }

        @Override
        public forge.localinstance.skin.FSkinProp getIconSkinProp() {
            return PlayerDetailsPanel.iconFromZone(zone);
        }
    }

    // ===================== BrowseInteraction — zone-window default behavior =====================

    static final class BrowseInteraction implements FloatingCardWindow.Interaction {
        @Override public boolean canClick(final FloatingCardWindow w, final CardView c) {
            // Cards in zones are always clickable to allow the gameController to handle
            // selection (legality is checked by the controller, not the widget).
            return true;
        }
        @Override public void onLeftClick(final FloatingCardWindow w, final CardView c, final MouseEvent evt) {
            w.getMatchUI().getGameController().selectCard(c, null, new MouseTriggerEvent(evt));
        }
        @Override public void onRightClick(final FloatingCardWindow w, final CardView c, final MouseEvent evt) {
            w.getMatchUI().getGameController().selectCard(c, null, new MouseTriggerEvent(evt));
        }
        @Override public boolean showsSelectionPrompt() { return true; }
        @Override public boolean supportsHotkeys() { return true; }
    }
}
