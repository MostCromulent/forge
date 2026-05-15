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

import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import forge.Singletons;
import forge.game.card.CardView;
import forge.gui.framework.SDisplayUtil;
import forge.localinstance.properties.ForgePreferences;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.screens.match.CMatchUI;
import forge.toolbox.FButton;
import forge.toolbox.FHtmlViewer;
import forge.toolbox.FLabel;
import forge.toolbox.FMouseAdapter;
import forge.toolbox.FScrollPane;
import forge.toolbox.FSkin;
import forge.toolbox.FTextField;
import forge.util.Localizer;
import forge.util.StreamUtil;
import forge.util.collect.FCollection;
import forge.view.FDialog;
import forge.view.FFrame;
import forge.view.arcane.util.CardPanelMouseAdapter;
import forge.util.Lang;

/**
 * Generic floating window showing a list of {@link CardView}s with optional
 * selection/interaction. Replaces FloatingCardArea + FloatingZone + ListCardArea.
 *
 * Behavior is configured via two policy objects:
 *   - {@link Source} supplies cards, title, and sort/loc preferences
 *   - {@link Interaction} controls click/drag/done semantics
 *
 * Use the static factories {@link #forZone}, {@link #forManipulation}, {@link #forChoice}
 * to construct windows for the standard use cases.
 */
@SuppressWarnings("serial")
public class FloatingCardWindow extends CardArea {

    // ============================================================
    // Source policy — what cards to show, how to title, where to persist location
    // ============================================================
    public interface Source {
        /** Cards to display (null = empty). May be re-queried every refresh for live sources. */
        Iterable<CardView> getCards();

        /** Title format string. Use %d as placeholder for card count, %s for sort detail. */
        String getTitleFormat();

        /** True if {@link #getCards()} should be re-queried on every refresh. */
        default boolean isLive() { return false; }

        /** Window location preference. null = no persistence. */
        default FPref getLocPref() { return null; }

        /** True if right-click on the title bar should toggle sort-by-name. */
        default boolean supportsSortToggle() { return false; }

        /** Comparator used when sortByName is false. null = preserve source order. */
        default Comparator<CardView> defaultComparator() { return null; }

        /** Comparator used when sortByName is true. null = no sort toggle. */
        default Comparator<CardView> sortedComparator() { return null; }

        /** Skin prop for the window icon. null = leave default. */
        default forge.localinstance.skin.FSkinProp getIconSkinProp() { return null; }
    }

    // ============================================================
    // Interaction policy — what the user can do with the cards
    // ============================================================
    public interface Interaction {
        /** True if this card can be clicked (enables the panel and removes the dimmed paint). */
        default boolean canClick(FloatingCardWindow w, CardView c) { return false; }

        /** Called on left-click of a clickable card. */
        default void onLeftClick(FloatingCardWindow w, CardView c, MouseEvent e) {}

        /** Called on right-click of a clickable card. */
        default void onRightClick(FloatingCardWindow w, CardView c, MouseEvent e) {}

        /** True if this card may be drag-reordered. */
        default boolean canDrag(FloatingCardWindow w, CardView c) { return false; }

        /** Called after a drag-reorder; newIndex is the destination position. */
        default void onDragEnd(FloatingCardWindow w, CardView c, int newIndex) {}

        /** True if the window should be modal. */
        default boolean isModal() { return false; }

        /** Label for the Done button. null = no Done button. */
        default String getDoneButtonLabel() { return null; }

        /** Called when Done is clicked or Enter pressed. Window closes after. */
        default void onDone(FloatingCardWindow w) {}

        /** True if this interaction needs an isSelectable-derived prompt label. */
        default boolean showsSelectionPrompt() { return false; }

        /** True if Ctrl+1..9 hotkey selection is supported. */
        default boolean supportsHotkeys() { return false; }
    }

    // ============================================================
    // Hotkey dispatcher (shared across all instances)
    // ============================================================
    private static final Set<FloatingCardWindow> ALL_VISIBLE =
            java.util.Collections.newSetFromMap(new WeakHashMap<>());
    private static boolean hotkeyDispatcherInstalled;

    private static void ensureHotkeyDispatcherInstalled() {
        if (hotkeyDispatcherInstalled) return;
        hotkeyDispatcherInstalled = true;
        KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .addKeyEventDispatcher(FloatingCardWindow::dispatchHotkey);
    }

    private static boolean dispatchHotkey(final KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.VK_CONTROL) {
            for (final FloatingCardWindow fz : ALL_VISIBLE) {
                if (!fz.isVisible()) continue;
                if (!fz.interaction.supportsHotkeys()) continue;
                if (e.getID() == KeyEvent.KEY_RELEASED) {
                    fz.assignOwnHotkeyDigits(true);
                } else if (e.getID() == KeyEvent.KEY_PRESSED) {
                    fz.assignOwnHotkeyDigits(false);
                }
            }
        }
        if (e.getID() != KeyEvent.KEY_PRESSED) return false;

        // Esc with focused, non-empty search field clears the filter.
        if (e.getKeyCode() == KeyEvent.VK_ESCAPE && !e.isControlDown() && !e.isAltDown() && !e.isMetaDown()) {
            for (final FloatingCardWindow fz : ALL_VISIBLE) {
                if (!fz.isVisible()) continue;
                if (fz.searchField.isFocusOwner() && !fz.searchField.getText().isEmpty()) {
                    fz.searchField.setText("");
                    return true;
                }
            }
            return false;
        }
        if (!e.isControlDown() || e.isAltDown() || e.isMetaDown()) return false;
        final int digit = e.getKeyCode() - KeyEvent.VK_0;
        if (digit < 1 || digit > 9) return false;
        for (final FloatingCardWindow fz : ALL_VISIBLE) {
            if (!fz.isVisible()) continue;
            if (!fz.interaction.supportsHotkeys()) continue;
            final CardPanel target = fz.findPanelByHotkeyDigit(digit);
            if (target == null) continue;
            fz.interaction.onLeftClick(fz, target.getCard(),
                    new MouseEvent(fz, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(),
                            0, 0, 0, 1, false, MouseEvent.BUTTON1));
            return true;
        }
        return false;
    }

    /** Called by external observers when the selection prompt changes. */
    public static void refreshAllSelectionPrompts() {
        for (final FloatingCardWindow fz : ALL_VISIBLE) {
            if (fz.isVisible() && fz.interaction.showsSelectionPrompt()) {
                fz.updatePromptVisibility();
            }
        }
    }

    // ============================================================
    // Instance state
    // ============================================================
    private static final String COORD_DELIM = ",";
    private static final ForgePreferences prefs = FModel.getPreferences();

    private final Source source;
    private final Interaction interaction;

    protected String titleFormat;
    protected boolean sortedByName = false;
    protected FCollection<CardView> cardList;
    private boolean hasBeenShown, locLoaded;

    private final FTextField searchField = new FTextField.Builder()
            .ghostText(Localizer.getInstance().getMessage("lblFilterByName"))
            .build();
    private final FHtmlViewer promptLabel = new FHtmlViewer();
    private final FLabel hotkeyHint = new FLabel.Builder()
            .text(Localizer.getInstance().getMessage("lblHotkeySelectHint"))
            .fontSize(11)
            .fontAlign(SwingConstants.CENTER)
            .build();
    private FButton doneButton;
    private String filter = "";

    protected final FDialog window;

    // ============================================================
    // Construction
    // ============================================================
    public FloatingCardWindow(final CMatchUI matchUI, final Source source, final Interaction interaction) {
        super(matchUI, new FScrollPane(false, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER));
        this.source = source;
        this.interaction = interaction;
        this.titleFormat = source.getTitleFormat();
        this.window = new FDialog(interaction.isModal(), true, "0") {
            @Override
            public void setLocationRelativeTo(Component c) {
                if (hasBeenShown || locLoaded) return;
                super.setLocationRelativeTo(c);
            }

            @Override
            public void setVisible(boolean b0) {
                if (isVisible() == b0) return;
                if (!b0 && hasBeenShown && source.getLocPref() != null) {
                    prefs.setPref(source.getLocPref(),
                            getX() + COORD_DELIM + getY() + COORD_DELIM +
                                    getWidth() + COORD_DELIM + getHeight());
                }
                if (b0) {
                    doRefresh();
                    hasBeenShown = true;
                    ALL_VISIBLE.add(FloatingCardWindow.this);
                } else {
                    ALL_VISIBLE.remove(FloatingCardWindow.this);
                }
                super.setVisible(b0);
            }
        };

        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(final DocumentEvent e) { onFilterChanged(); }
            @Override public void removeUpdate(final DocumentEvent e) { onFilterChanged(); }
            @Override public void changedUpdate(final DocumentEvent e) { onFilterChanged(); }
        });
        promptLabel.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
        promptLabel.setVisible(false);
        window.add(promptLabel, "growx, wmin 10, gapbottom 4, wrap, hidemode 3");
        window.add(searchField, "growx, wrap");
        window.add(getScrollPane(), "grow, push, wrap");
        hotkeyHint.setVisible(false);
        window.add(hotkeyHint, "growx, wrap");

        if (interaction.getDoneButtonLabel() != null) {
            doneButton = new FButton(interaction.getDoneButtonLabel());
            doneButton.addActionListener(e -> {
                interaction.onDone(this);
                window.setVisible(false);
            });
            window.add(doneButton, "growx");
        }

        window.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        getScrollPane().setViewportView(this);
        setOpaque(false);
        if (source.getIconSkinProp() != null) {
            window.setIconImage(FSkin.getImage(source.getIconSkinProp()));
        }
        setVertical(true);
        setDragEnabled(true);
    }

    public Source getSource() { return source; }
    public Interaction getInteraction() { return interaction; }
    public boolean isVisible() { return window.isVisible(); }
    public FDialog getWindow() { return window; }

    // ============================================================
    // Window lifecycle
    // ============================================================
    public void showWindow() {
        ensureHotkeyDispatcherInstalled();
        onShow();
        window.setFocusableWindowState(true);
        if (interaction.showsSelectionPrompt() && getMatchUI().isSelecting()) {
            window.setDefaultFocus(searchField);
        }
        window.setVisible(true);
    }

    public void hideWindow() {
        onShow();
        window.setFocusableWindowState(false);
        window.setVisible(false);
        window.dispose();
        if (!searchField.getText().isEmpty()) {
            searchField.setText("");
        }
    }

    public void showOrHideWindow() {
        if (window.isVisible()) hideWindow();
        else showWindow();
    }

    protected void onShow() {
        if (!hasBeenShown) {
            loadLocation();
            window.getTitleBar().addMouseListener(new FMouseAdapter() {
                @Override public void onLeftDoubleClick(final MouseEvent e) {
                    window.setVisible(false);
                }
                @Override public void onRightClick(final MouseEvent e) {
                    if (source.supportsSortToggle()) toggleSorted();
                }
            });
            if (doneButton != null) {
                addKeyListener(new KeyAdapter() {
                    @Override public void keyPressed(final KeyEvent e) {
                        if (e.getKeyCode() == KeyEvent.VK_ENTER) doneButton.doClick();
                    }
                });
            }
            addCardPanelMouseListener(new CardPanelMouseAdapter() {
                @Override public void mouseDragEnd(final CardPanel dragPanel, final MouseEvent evt) {
                    onCardDragEnd(dragPanel);
                }
            });
        }
    }

    @Override
    protected boolean cardPanelDraggable(final CardPanel panel) {
        return interaction.canDrag(this, panel.getCard());
    }

    private void onCardDragEnd(final CardPanel dragPanel) {
        final int index = getCardPanels().indexOf(dragPanel);
        interaction.onDragEnd(this, dragPanel.getCard(), index);
        refresh();
    }

    // ============================================================
    // Mouse handlers — delegate to Interaction
    // ============================================================
    @Override
    public final void mouseOver(final CardPanel panel, final MouseEvent evt) {
        getMatchUI().setCard(panel.getCard(), evt.isShiftDown());
        super.mouseOver(panel, evt);
    }

    @Override
    public final void mouseLeftClicked(final CardPanel panel, final MouseEvent evt) {
        if (interaction.canClick(this, panel.getCard())) {
            interaction.onLeftClick(this, panel.getCard(), evt);
        }
        super.mouseLeftClicked(panel, evt);
    }

    @Override
    public final void mouseRightClicked(final CardPanel panel, final MouseEvent evt) {
        if (interaction.canClick(this, panel.getCard())) {
            interaction.onRightClick(this, panel.getCard(), evt);
        }
        super.mouseRightClicked(panel, evt);
    }

    // ============================================================
    // Location persistence
    // ============================================================
    protected void loadLocation() {
        final FPref locPref = source.getLocPref();
        if (locPref != null) {
            final String value = prefs.getPref(locPref);
            if (value != null && value.length() > 0) {
                final String[] coords = value.split(COORD_DELIM);
                if (coords.length == 4) {
                    try {
                        int x = Integer.parseInt(coords[0]);
                        int y = Integer.parseInt(coords[1]);
                        int w = Integer.parseInt(coords[2]);
                        int h = Integer.parseInt(coords[3]);
                        int centerX = x + w / 2;
                        int centerY = y + h / 2;
                        Rectangle screenBounds = SDisplayUtil.getScreenBoundsForPoint(new Point(centerX, centerY));
                        if (centerX < screenBounds.x) x = screenBounds.x;
                        else if (centerX > screenBounds.x + screenBounds.width) {
                            x = screenBounds.x + screenBounds.width - w;
                            if (x < screenBounds.x) x = screenBounds.x;
                        }
                        if (centerY < screenBounds.y) y = screenBounds.y;
                        else if (centerY > screenBounds.y + screenBounds.height) {
                            y = screenBounds.y + screenBounds.height - h;
                            if (y < screenBounds.y) y = screenBounds.y;
                        }
                        window.setBounds(x, y, w, h);
                        locLoaded = true;
                        return;
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }
                prefs.setPref(locPref, "");
                prefs.save();
            }
        }
        final FFrame mainFrame = Singletons.getView().getFrame();
        window.setSize(mainFrame.getWidth() / 5, mainFrame.getHeight() / 2);
    }

    // ============================================================
    // Refresh
    // ============================================================
    public void refresh() {
        if (!window.isVisible()) return;
        doRefresh();
    }

    protected Iterable<CardView> getCards() {
        Iterable<CardView> raw = source.getCards();
        if (raw == null) return null;
        cardList = new FCollection<>(raw);
        final Comparator<CardView> sortComp = sortedByName
                ? source.sortedComparator()
                : source.defaultComparator();
        if (sortComp != null) cardList.sort(sortComp);
        if (!filter.isEmpty()) {
            final String needle = filter.toLowerCase(Locale.ROOT);
            cardList.removeIf(card -> !getMatchUI().mayView(card)
                    || !card.getName().toLowerCase(Locale.ROOT).contains(needle));
        }
        return cardList;
    }

    protected void doRefresh() {
        final List<CardPanel> cardPanels = new ArrayList<>();
        final Iterable<CardView> cards = getCards();
        if (cards != null) {
            for (final CardView card : cards) {
                CardPanel panel = getCardPanel(card.getId());
                if (panel == null) {
                    panel = new CardPanel(getMatchUI(), card);
                    panel.setDisplayEnabled(true);
                } else {
                    panel.setCard(card);
                }
                panel.setInteractive(interaction.canClick(this, card) || interaction.canDrag(this, card));
                cardPanels.add(panel);
            }
        }
        setCardPanels(cardPanels);
        final int shown = cardPanels.size();
        if (titleFormat != null) {
            try {
                window.setTitle(String.format(titleFormat, shown));
            } catch (Exception ex) {
                window.setTitle(titleFormat);
            }
        }
        updatePromptVisibility();
    }

    private void onFilterChanged() {
        filter = searchField.getText();
        refresh();
    }

    private void toggleSorted() {
        sortedByName = !sortedByName;
        refresh();
        window.repaint();
    }

    // ============================================================
    // Selection prompt + hotkey digits
    // ============================================================
    private void updatePromptVisibility() {
        if (!interaction.showsSelectionPrompt()) {
            promptLabel.setVisible(false);
            return;
        }
        final Iterable<CardView> cards = getCards();
        final boolean show = cards != null
                && StreamUtil.stream(cards).anyMatch(c -> getMatchUI().isSelectable(c));
        final String prompt = show ? getMatchUI().getPromptMessage() : null;
        if (prompt != null && !prompt.isEmpty()) {
            promptLabel.setText(FSkin.encodeSymbols(prompt, false));
            promptLabel.setVisible(true);
        } else {
            promptLabel.setText("");
            promptLabel.setVisible(false);
        }
        window.revalidate();
    }

    private CardPanel findPanelByHotkeyDigit(final int digit) {
        for (final CardPanel panel : getCardPanels()) {
            if (panel.getHotkeyDigit() == digit) return panel;
        }
        return null;
    }

    private void assignOwnHotkeyDigits(final boolean clear) {
        int next = 1;
        for (final CardPanel panel : getCardPanels()) {
            if (!clear && next <= 9 && getMatchUI().isSelectable(panel.getCard())) {
                panel.setHotkeyDigit(next++);
            } else {
                panel.setHotkeyDigit(0);
            }
        }
        if (!clear) hotkeyHint.setVisible(next > 1);
        else hotkeyHint.setVisible(false);
    }

    // ============================================================
    // Layout (inherited debounce)
    // ============================================================
    @Override
    public void doLayout() {
        if (getWindow().isResizing()) {
            layoutTimer.restart();
        } else {
            super.doLayout();
        }
    }

    private final Timer layoutTimer = new Timer(250, new ActionListener() {
        @Override public void actionPerformed(ActionEvent arg0) {
            layoutTimer.stop();
            FloatingCardWindow.super.doLayout();
        }
    });

    // ============================================================
    // Factories — public entry points
    // ============================================================

    /** Zone-backed window. Delegates to {@link FloatingZoneRegistry}. */
    public static FloatingCardWindow forZone(final CMatchUI matchUI,
                                              final forge.game.player.PlayerView player,
                                              final forge.game.zone.ZoneType zone) {
        return FloatingZoneRegistry.getOrCreate(matchUI, player, zone);
    }

    /**
     * Manipulation window (move cards to top/bottom/anywhere). Modal with Done button.
     * Preserves the legacy ListCardArea UX.
     */
    public static FloatingCardWindow forManipulation(final CMatchUI matchUI,
                                                      final String title,
                                                      final List<CardView> cardList,
                                                      final List<CardView> moveableCards,
                                                      final boolean toTop,
                                                      final boolean toBottom,
                                                      final boolean toAnywhere) {
        final ManipulationState state = new ManipulationState(cardList, moveableCards, toTop, toBottom, toAnywhere);
        final Source src = new Source() {
            @Override public Iterable<CardView> getCards() { return state.cardList; }
            @Override public String getTitleFormat() { return title; }
        };
        final Interaction inter = new ManipulationInteraction(state);
        return new FloatingCardWindow(matchUI, src, inter);
    }

    /**
     * Ephemeral choice window for cards that have no zone (e.g. Jhoira's random copies).
     * Modal. Click a selectable card → callback fires and window closes.
     */
    public static FloatingCardWindow forChoice(final CMatchUI matchUI,
                                                final String title,
                                                final Iterable<CardView> cards,
                                                final boolean isOptional,
                                                final Consumer<CardView> onPick) {
        final List<CardView> snapshot = new ArrayList<>();
        for (final CardView cv : cards) snapshot.add(cv);
        final Source src = new Source() {
            @Override public Iterable<CardView> getCards() { return snapshot; }
            @Override public String getTitleFormat() {
                return title + " (" + Lang.nounWithNumeral(snapshot.size(), "card") + ")";
            }
        };
        final Interaction inter = new ChoiceInteraction(onPick, isOptional);
        return new FloatingCardWindow(matchUI, src, inter);
    }

    // ============================================================
    // ManipulationState + ManipulationInteraction (was ListCardArea)
    // ============================================================
    private static final class ManipulationState {
        final List<CardView> cardList;
        final List<CardView> moveableCards;
        final boolean toTop, toBottom, toAnywhere;
        ManipulationState(final List<CardView> all, final List<CardView> moveable,
                          final boolean toTop, final boolean toBottom, final boolean toAnywhere) {
            this.cardList = new ArrayList<>(all);
            this.moveableCards = new ArrayList<>();
            for (final CardView c : moveable) if (this.cardList.contains(c)) this.moveableCards.add(c);
            this.toTop = toTop;
            this.toBottom = toBottom;
            this.toAnywhere = toAnywhere;
        }

        boolean validIndex(final CardView card, final int newIndex) {
            if (toAnywhere) return true;
            final int oldIndex = cardList.indexOf(card);
            boolean topMove = true;
            for (int i = 0; i < newIndex + (oldIndex < newIndex ? 1 : 0); i++) {
                if (!moveableCards.contains(cardList.get(i))) { topMove = false; break; }
            }
            if (toTop && topMove) return true;
            boolean bottomMove = true;
            for (int i = newIndex + 1 - (oldIndex > newIndex ? 1 : 0); i < cardList.size(); i++) {
                if (!moveableCards.contains(cardList.get(i))) { bottomMove = false; break; }
            }
            return toBottom && bottomMove;
        }
    }

    public List<CardView> getDestList() {
        if (interaction instanceof ManipulationInteraction) {
            return new ArrayList<>(((ManipulationInteraction) interaction).state.cardList);
        }
        return null;
    }

    private static final class ManipulationInteraction implements Interaction {
        final ManipulationState state;
        ManipulationInteraction(final ManipulationState s) { this.state = s; }
        @Override public boolean isModal() { return true; }
        @Override public String getDoneButtonLabel() {
            return Localizer.getInstance().getMessage("lblDone");
        }
        @Override public boolean canDrag(final FloatingCardWindow w, final CardView c) {
            return state.moveableCards.contains(c);
        }
        @Override public boolean canClick(final FloatingCardWindow w, final CardView c) {
            return state.moveableCards.contains(c) && (state.toTop || state.toBottom);
        }
        @Override public void onDragEnd(final FloatingCardWindow w, final CardView c, final int newIndex) {
            if (!state.moveableCards.contains(c)) return;
            if (!state.validIndex(c, newIndex)) return;
            synchronized (state.cardList) {
                state.cardList.remove(c);
                state.cardList.add(newIndex, c);
            }
        }
        @Override public void onLeftClick(final FloatingCardWindow w, final CardView c, final MouseEvent e) {
            if (!state.toTop && !state.toBottom) return;
            synchronized (state.cardList) {
                state.cardList.remove(c);
                int position;
                if (state.toTop) {
                    position = 0;
                } else {
                    for (position = state.cardList.size();
                         position > 0 && state.moveableCards.contains(state.cardList.get(position - 1));
                         position--) {}
                }
                state.cardList.add(position, c);
            }
            w.refresh();
        }
        @Override public void onRightClick(final FloatingCardWindow w, final CardView c, final MouseEvent e) {
            if (!state.toTop && !state.toBottom) return;
            synchronized (state.cardList) {
                state.cardList.remove(c);
                int position;
                if (state.toBottom) {
                    position = state.cardList.size();
                } else {
                    for (position = 0;
                         position < state.cardList.size() && state.moveableCards.contains(state.cardList.get(position));
                         position++) {}
                }
                state.cardList.add(position, c);
            }
            w.refresh();
        }
    }

    // ============================================================
    // ChoiceInteraction (Jhoira-style ephemeral pickers)
    // ============================================================
    private static final class ChoiceInteraction implements Interaction {
        final Consumer<CardView> onPick;
        ChoiceInteraction(final Consumer<CardView> onPick, final boolean isOptional) {
            this.onPick = onPick;
        }
        @Override public boolean isModal() { return true; }
        @Override public boolean showsSelectionPrompt() { return true; }
        @Override public boolean canClick(final FloatingCardWindow w, final CardView c) { return true; }
        @Override public void onLeftClick(final FloatingCardWindow w, final CardView c, final MouseEvent e) {
            onPick.accept(c);
            w.hideWindow();
        }
    }
}
