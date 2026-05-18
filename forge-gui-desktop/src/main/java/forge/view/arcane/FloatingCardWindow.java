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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
import forge.localinstance.skin.FSkinProp;
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

/**
 * Floating window for a list of {@link CardView}s, parameterised by a
 * {@link Source} (what to show) and an {@link Interaction} (what clicks do).
 */
@SuppressWarnings("serial")
public final class FloatingCardWindow extends CardArea {

    /** What cards to show, how to title, where to persist location, how to decorate panels. */
    public interface Source {
        Iterable<CardView> getCards();

        /** Title for the current refresh. */
        String getTitle(int shown, int total, boolean sortedByName);

        default FPref getLocPref() { return null; }
        default boolean supportsSortToggle() { return false; }
        default Comparator<CardView> defaultComparator() { return null; }
        default Comparator<CardView> sortedComparator() { return null; }
        default FSkinProp getIconSkinProp() { return null; }

        /** Per-refresh hook to apply source-specific overlays (e.g. Flashback zone banner). */
        default void decoratePanel(CardPanel panel, CardView card) {}
    }

    /** What the user can do with the cards. */
    public interface Interaction {
        default boolean canClick(FloatingCardWindow w, CardView c) { return false; }
        default void onLeftClick(FloatingCardWindow w, CardView c, MouseEvent e) {}
        default void onRightClick(FloatingCardWindow w, CardView c, MouseEvent e) {}
        default boolean canDrag(FloatingCardWindow w, CardView c) { return false; }
        default void onDragEnd(FloatingCardWindow w, CardView c, int newIndex) {}
        default boolean isModal() { return false; }
        /** null = no Done button. */
        default String getDoneButtonLabel() { return null; }
        default boolean showsSelectionPrompt() { return false; }
        default boolean supportsHotkeys() { return false; }
        /** False blocks the title-bar X for mandatory game-rules dialogs. */
        default boolean allowsCancel() { return true; }

        /** Whether {@code c} counts as selectable for hotkey / prompt purposes. */
        default boolean isSelectable(FloatingCardWindow w, CardView c) {
            return w.getMatchUI().isSelectable(c);
        }
    }

    private static final Set<FloatingCardWindow> ALL_VISIBLE = new LinkedHashSet<>();
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

    private static final String COORD_DELIM = ",";
    private static final ForgePreferences prefs = FModel.getPreferences();

    private final Source source;
    private final Interaction interaction;
    private boolean sortedByName = false;
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

    private final FDialog window;

    public FloatingCardWindow(final CMatchUI matchUI, final Source source, final Interaction interaction) {
        super(matchUI, new FScrollPane(false, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER));
        this.source = source;
        this.interaction = interaction;
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
            doneButton.addActionListener(e -> window.setVisible(false));
            window.add(doneButton, "growx");
        }

        // Mandatory game-rules dialogs swallow X/Esc; the player must pick.
        window.setDefaultCloseOperation(interaction.allowsCancel()
                ? WindowConstants.DISPOSE_ON_CLOSE
                : WindowConstants.DO_NOTHING_ON_CLOSE);
        getScrollPane().setViewportView(this);
        setOpaque(false);
        if (source.getIconSkinProp() != null) {
            window.setIconImage(FSkin.getImage(source.getIconSkinProp()));
        }
        setVertical(true);
        setDragEnabled(true);
    }

    public Source getSource() { return source; }
    public boolean isVisible() { return window.isVisible(); }
    public FDialog getWindow() { return window; }

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

    private void onShow() {
        if (!hasBeenShown) {
            loadLocation();
            window.getTitleBar().addMouseListener(new FMouseAdapter() {
                @Override public void onLeftDoubleClick(final MouseEvent e) {
                    if (interaction.allowsCancel()) window.setVisible(false);
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

    private void loadLocation() {
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

    public void refresh() {
        if (!window.isVisible()) return;
        doRefresh();
    }

    private static final class ResolvedCards {
        final FCollection<CardView> cards;
        final int total;
        ResolvedCards(final FCollection<CardView> cards, final int total) {
            this.cards = cards;
            this.total = total;
        }
    }

    private ResolvedCards resolveCards() {
        final Iterable<CardView> raw = source.getCards();
        if (raw == null) return new ResolvedCards(null, 0);
        final FCollection<CardView> list = new FCollection<>(raw);
        final int total = list.size();
        final Comparator<CardView> sortComp = sortedByName
                ? source.sortedComparator()
                : source.defaultComparator();
        if (sortComp != null) list.sort(sortComp);
        if (!filter.isEmpty()) {
            final String needle = filter.toLowerCase(Locale.ROOT);
            list.removeIf(card -> !getMatchUI().mayView(card)
                    || !card.getName().toLowerCase(Locale.ROOT).contains(needle));
        }
        return new ResolvedCards(list, total);
    }

    private void doRefresh() {
        final ResolvedCards resolved = resolveCards();
        final List<CardPanel> cardPanels = new ArrayList<>();
        if (resolved.cards != null) {
            for (final CardView card : resolved.cards) {
                CardPanel panel = getCardPanel(card.getId());
                if (panel == null) {
                    panel = new CardPanel(getMatchUI(), card);
                    panel.setDisplayEnabled(true);
                } else {
                    panel.setCard(card);
                }
                panel.setInteractive(interaction.canClick(this, card) || interaction.canDrag(this, card));
                source.decoratePanel(panel, card);
                cardPanels.add(panel);
            }
        }
        setCardPanels(cardPanels);
        window.setTitle(source.getTitle(cardPanels.size(), resolved.total, sortedByName));
        updatePromptVisibility(resolved.cards);
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

    private void updatePromptVisibility() {
        updatePromptVisibility(resolveCards().cards);
    }

    private void updatePromptVisibility(final Iterable<CardView> cards) {
        if (!interaction.showsSelectionPrompt()) {
            promptLabel.setVisible(false);
            return;
        }
        final boolean show = cards != null
                && StreamUtil.stream(cards).anyMatch(c -> interaction.isSelectable(this, c));
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
            if (!clear && next <= 9 && interaction.isSelectable(this, panel.getCard())) {
                panel.setHotkeyDigit(next++);
            } else {
                panel.setHotkeyDigit(0);
            }
        }
        // Leave hint visible on Ctrl-release to avoid flicker on rapid press/release.
        if (!clear) hotkeyHint.setVisible(next > 1);
    }

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

    /** Manipulation window: drag/click cards to top/bottom/anywhere. Modal with Done button. */
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
            @Override public String getTitle(final int shown, final int total, final boolean sorted) { return title; }
        };
        final Interaction inter = new ManipulationInteraction(state);
        return new FloatingCardWindow(matchUI, src, inter);
    }

    /** Ephemeral pick: click a card to commit. {@code isOptional=false} disables the X. */
    public static FloatingCardWindow forChoice(final CMatchUI matchUI,
                                                final String title,
                                                final Iterable<CardView> cards,
                                                final boolean isOptional,
                                                final Consumer<CardView> onPick) {
        final List<CardView> snapshot = new ArrayList<>();
        for (final CardView cv : cards) snapshot.add(cv);
        final Source src = new Source() {
            @Override public Iterable<CardView> getCards() { return snapshot; }
            @Override public String getTitle(final int shown, final int total, final boolean sorted) {
                return title + " (" + shown + ")";
            }
            @Override public boolean supportsSortToggle() { return true; }
            @Override public Comparator<CardView> sortedComparator() {
                return Comparator.comparing(CardView::getName);
            }
        };
        final Interaction inter = new ChoiceInteraction(onPick, isOptional);
        return new FloatingCardWindow(matchUI, src, inter);
    }

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
            state.cardList.remove(c);
            state.cardList.add(newIndex, c);
        }
        @Override public void onLeftClick(final FloatingCardWindow w, final CardView c, final MouseEvent e) {
            if (!state.toTop && !state.toBottom) return;
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
            w.refresh();
        }
        @Override public void onRightClick(final FloatingCardWindow w, final CardView c, final MouseEvent e) {
            if (!state.toTop && !state.toBottom) return;
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
            w.refresh();
        }
    }

    private static final class ChoiceInteraction implements Interaction {
        final Consumer<CardView> onPick;
        final boolean isOptional;
        ChoiceInteraction(final Consumer<CardView> onPick, final boolean isOptional) {
            this.onPick = onPick;
            this.isOptional = isOptional;
        }
        @Override public boolean isModal() { return true; }
        @Override public boolean showsSelectionPrompt() { return true; }
        @Override public boolean supportsHotkeys() { return true; }
        @Override public boolean allowsCancel() { return isOptional; }
        @Override public boolean canClick(final FloatingCardWindow w, final CardView c) { return true; }
        @Override public boolean isSelectable(final FloatingCardWindow w, final CardView c) { return true; }
        @Override public void onLeftClick(final FloatingCardWindow w, final CardView c, final MouseEvent e) {
            onPick.accept(c);
            w.hideWindow();
        }
        @Override public void onRightClick(final FloatingCardWindow w, final CardView c, final MouseEvent e) {
            onPick.accept(c);
            w.hideWindow();
        }
    }
}
