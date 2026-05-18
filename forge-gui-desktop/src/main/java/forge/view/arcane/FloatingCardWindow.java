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
 * Abstract base for floating windows that display a list of {@link CardView}s.
 * Subclasses choose the cards, the title, and what clicks do.
 */
@SuppressWarnings("serial")
public abstract class FloatingCardWindow extends CardArea {

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
                if (!fz.supportsHotkeys()) continue;
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
            if (!fz.supportsHotkeys()) continue;
            final CardPanel target = fz.findPanelByHotkeyDigit(digit);
            if (target == null) continue;
            fz.handleLeftClick(target.getCard(),
                    new MouseEvent(fz, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(),
                            0, 0, 0, 1, false, MouseEvent.BUTTON1));
            return true;
        }
        return false;
    }

    /** Called by external observers when the selection prompt changes. */
    public static void refreshAllSelectionPrompts() {
        for (final FloatingCardWindow fz : ALL_VISIBLE) {
            if (fz.isVisible() && fz.showsSelectionPrompt()) {
                fz.updatePromptVisibility();
            }
        }
    }

    private static final String COORD_DELIM = ",";
    private static final ForgePreferences prefs = FModel.getPreferences();

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

    protected final FDialog window;

    protected FloatingCardWindow(final CMatchUI matchUI, final boolean modal, final String doneButtonLabel) {
        super(matchUI, new FScrollPane(false, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER));
        this.window = new FDialog(modal, true, "0") {
            @Override
            public void setLocationRelativeTo(Component c) {
                if (hasBeenShown || locLoaded) return;
                super.setLocationRelativeTo(c);
            }

            @Override
            public void setVisible(boolean b0) {
                if (isVisible() == b0) return;
                final FPref locPref = getLocPref();
                if (!b0 && hasBeenShown && locPref != null) {
                    prefs.setPref(locPref,
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

        if (doneButtonLabel != null) {
            doneButton = new FButton(doneButtonLabel);
            doneButton.addActionListener(e -> window.setVisible(false));
            window.add(doneButton, "growx");
        }

        getScrollPane().setViewportView(this);
        setOpaque(false);
        setVertical(true);
        setDragEnabled(true);
    }

    // ===================== Subclass hooks =====================

    /** Cards to display. May be re-queried on every refresh (live sources). */
    protected abstract Iterable<CardView> getCards();

    /** Title for the current refresh. */
    protected abstract String getTitle(int shown, int total, boolean sortedByName);

    protected void handleLeftClick(final CardView card, final MouseEvent e) {}
    protected void handleRightClick(final CardView card, final MouseEvent e) {}
    protected void onCardDragged(final CardView card, final int newIndex) {}

    protected boolean isCardClickable(final CardView card) { return false; }
    protected boolean isCardDraggable(final CardView card) { return false; }

    /** Whether {@code c} counts as selectable for hotkey / prompt purposes. */
    protected boolean isCardSelectable(final CardView c) { return getMatchUI().isSelectable(c); }

    protected boolean supportsSortToggle() { return false; }
    protected boolean supportsHotkeys() { return false; }
    protected boolean showsSelectionPrompt() { return false; }
    /** False blocks the title-bar X for mandatory game-rules dialogs. */
    protected boolean allowsCancel() { return true; }

    protected Comparator<CardView> defaultComparator() { return null; }
    protected Comparator<CardView> sortedComparator() { return null; }

    protected FPref getLocPref() { return null; }
    protected FSkinProp getIconSkinProp() { return null; }

    /** Per-refresh hook to apply source-specific overlays (e.g. Flashback zone banner). */
    protected void decoratePanel(final CardPanel panel, final CardView card) {}

    // ===================== Public window API =====================

    public boolean isVisible() { return window.isVisible(); }
    public FDialog getWindow() { return window; }

    public void showWindow() {
        ensureHotkeyDispatcherInstalled();
        onShow();
        window.setDefaultCloseOperation(allowsCancel()
                ? WindowConstants.DISPOSE_ON_CLOSE
                : WindowConstants.DO_NOTHING_ON_CLOSE);
        window.setFocusableWindowState(true);
        if (showsSelectionPrompt() && getMatchUI().isSelecting()) {
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

    public void refresh() {
        if (!window.isVisible()) return;
        doRefresh();
    }

    // ===================== Internals =====================

    private void onShow() {
        if (!hasBeenShown) {
            loadLocation();
            final FSkinProp iconProp = getIconSkinProp();
            if (iconProp != null) {
                window.setIconImage(FSkin.getImage(iconProp));
            }
            window.getTitleBar().addMouseListener(new FMouseAdapter() {
                @Override public void onLeftDoubleClick(final MouseEvent e) {
                    if (allowsCancel()) window.setVisible(false);
                }
                @Override public void onRightClick(final MouseEvent e) {
                    if (supportsSortToggle()) toggleSorted();
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
        return isCardDraggable(panel.getCard());
    }

    private void onCardDragEnd(final CardPanel dragPanel) {
        final int index = getCardPanels().indexOf(dragPanel);
        onCardDragged(dragPanel.getCard(), index);
        refresh();
    }

    @Override
    public final void mouseOver(final CardPanel panel, final MouseEvent evt) {
        getMatchUI().setCard(panel.getCard(), evt.isShiftDown());
        super.mouseOver(panel, evt);
    }

    @Override
    public final void mouseLeftClicked(final CardPanel panel, final MouseEvent evt) {
        if (isCardClickable(panel.getCard())) {
            handleLeftClick(panel.getCard(), evt);
        }
        super.mouseLeftClicked(panel, evt);
    }

    @Override
    public final void mouseRightClicked(final CardPanel panel, final MouseEvent evt) {
        if (isCardClickable(panel.getCard())) {
            handleRightClick(panel.getCard(), evt);
        }
        super.mouseRightClicked(panel, evt);
    }

    private void loadLocation() {
        final FPref locPref = getLocPref();
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

    private static final class ResolvedCards {
        final FCollection<CardView> cards;
        final int total;
        ResolvedCards(final FCollection<CardView> cards, final int total) {
            this.cards = cards;
            this.total = total;
        }
    }

    private ResolvedCards resolveCards() {
        final Iterable<CardView> raw = getCards();
        if (raw == null) return new ResolvedCards(null, 0);
        final FCollection<CardView> list = new FCollection<>(raw);
        final int total = list.size();
        final Comparator<CardView> sortComp = sortedByName ? sortedComparator() : defaultComparator();
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
                panel.setInteractive(isCardClickable(card) || isCardDraggable(card));
                decoratePanel(panel, card);
                cardPanels.add(panel);
            }
        }
        setCardPanels(cardPanels);
        window.setTitle(getTitle(cardPanels.size(), resolved.total, sortedByName));
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
        if (!showsSelectionPrompt()) {
            promptLabel.setVisible(false);
            return;
        }
        final boolean show = cards != null
                && StreamUtil.stream(cards).anyMatch(this::isCardSelectable);
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
            if (!clear && next <= 9 && isCardSelectable(panel.getCard())) {
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
}
