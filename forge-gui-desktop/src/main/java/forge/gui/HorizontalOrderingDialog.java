package forge.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.DefaultListSelectionModel;
import javax.swing.DropMode;
import javax.swing.Icon;
import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;

import org.apache.commons.lang3.tuple.Pair;

import forge.ImageCache;
import forge.game.card.CardView;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.screens.match.CMatchUI;
import forge.toolbox.FButton;
import forge.toolbox.FLabel;
import forge.toolbox.FList;
import forge.toolbox.FPanel;
import forge.toolbox.FScrollPane;
import forge.util.ImageFetcher;
import forge.util.Localizer;
import forge.view.FDialog;
import forge.view.arcane.CardPanel;

/**
 * Horizontal full-card ordering dialog. Two horizontal rows of full card thumbnails
 * (Choices on top, Selected on bottom). Drag between rows or within Selected to reorder.
 * Order is implicit in left-to-right position; no per-card order badge needed.
 *
 * <p>Drop-in replacement for DualListBox when all items are CardView. The dialog API
 * (setSecondColumnLabelText, getOrderedList, getRemainingSourceList) matches DualListBox
 * so GuiChoose.order can route between them by item type.
 */
@SuppressWarnings("serial")
public final class HorizontalOrderingDialog extends FDialog {
    private static final int CARD_W = 150;
    private static final int CARD_H = 210;
    private static final int CELL_PADDING = 4;
    private static final int CELL_GAP = 2;          // BorderLayout vgap between image and name labels.
    private static final int FOOTER_H = 20;         // tight to the name font height; reduces gap below text.
    private static final int CELL_W = CARD_W + CELL_PADDING * 2;
    private static final int CELL_H = CARD_H + FOOTER_H + CELL_PADDING * 2 + CELL_GAP;
    // Visible-row cap: width scales with card count up to MAX_VISIBLE; past that, horizontal-scroll.
    private static final int MAX_VISIBLE = 5;

    private static final DataFlavor DND_FLAVOR = new DataFlavor(
            DataFlavor.javaJVMLocalObjectMimeType + ";class=" + DnDPayload.class.getName(),
            "HorizontalOrderingItems");

    private static final class DnDPayload {
        final FList<?> source;
        final int[] indices;
        DnDPayload(final FList<?> source, final int[] indices) {
            this.source = source;
            this.indices = indices;
        }
    }

    private final DefaultListModel<CardView> choicesModel = new DefaultListModel<>();
    private final FList<CardView> choicesList;
    private final DefaultListModel<CardView> selectedModel = new DefaultListModel<>();
    private final FList<CardView> selectedList;

    private final FButton addButton;       // Choices -> Selected (selected items)
    private final FButton addAllButton;    // Choices -> Selected (all)
    private final FButton removeButton;    // Selected -> Choices (selected items)
    private final FButton removeAllButton; // Selected -> Choices (all)
    private final FButton okButton;
    private final FButton autoButton;

    private final FLabel choicesLabel;
    private final FLabel selectedLabel;
    private String selectedLabelBase;

    private final int minRemaining;
    private final int maxRemaining;
    private final int targetSelected;

    private final Map<String, Icon> iconCache = new HashMap<>();
    private final CMatchUI matchUI;

    // Zoom overlay sits in the dialog's layered pane (above the content). Self-contained so it
    // appears on top of this dialog regardless of the in-match CardZoomer's overlay attaching to
    // the main frame's glass pane.
    private final JLabel zoomOverlay = new JLabel();

    public HorizontalOrderingDialog(final int min, final int max,
            final List<CardView> choices, final List<CardView> selected, final CMatchUI matchUI) {
        this.matchUI = matchUI;
        this.minRemaining = min;
        this.maxRemaining = max;

        final int totalCards = (choices != null ? choices.size() : 0)
                + (selected != null ? selected.size() : 0);
        this.targetSelected = Math.max(0, totalCards - min);

        choicesList = buildList(choicesModel, false, null);
        selectedList = buildList(selectedModel, true, Localizer.getInstance().getMessage("lblDragCardsHere"));

        // Cross-row buttons. Top-row → bottom uses down-arrow glyph; bottom → top uses up-arrow.
        addButton = new FButton("↓");
        addButton.addActionListener(e -> _moveSelectedFromChoices());
        addAllButton = new FButton("↓↓");
        addAllButton.addActionListener(e -> _addAll());
        removeButton = new FButton("↑");
        removeButton.addActionListener(e -> _moveSelectedFromSelected());
        removeAllButton = new FButton("↑↑");
        removeAllButton.addActionListener(e -> _removeAll());

        // OK / Auto buttons.
        final Localizer l = Localizer.getInstance();
        okButton = new FButton(l.getMessage("lblOK"));
        okButton.addActionListener(e -> _finish());
        autoButton = new FButton(l.getMessage("lblAuto"));
        autoButton.addActionListener(e -> { _addAll(); _finish(); });

        // Headers.
        choicesLabel = new FLabel.Builder()
                .text(l.getMessage("lblChoices") + ":")
                .fontAlign(SwingConstants.CENTER).build();
        choicesLabel.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        selectedLabel = new FLabel.Builder().fontAlign(SwingConstants.CENTER).build();
        selectedLabel.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));

        // Populate models.
        if (choices != null) {
            for (final CardView cv : choices) choicesModel.addElement(cv);
        }
        if (selected != null) {
            for (final CardView cv : selected) selectedModel.addElement(cv);
        }
        _refreshSelectedHeader();

        // DnD wiring (shared handler discriminates by component).
        final TransferHandler dnd = new HorizontalTransferHandler();
        for (final FList<CardView> list : Arrays.asList(choicesList, selectedList)) {
            list.setDragEnabled(true);
            list.setDropMode(DropMode.INSERT);
            list.setTransferHandler(dnd);
            list.setSelectionModel(new ToggleSelectionModel());
        }

        // Refresh OK button state when selections change (canAdd depends on selection size).
        choicesList.addListSelectionListener(ev -> _setButtonState());

        // Mirror DualListBox: selecting a card updates the match UI's inspector / card-image panel.
        choicesList.addListSelectionListener(ev -> _showSelectedCard(choicesList.getSelectedValue()));
        selectedList.addListSelectionListener(ev -> _showSelectedCard(selectedList.getSelectedValue()));
        final java.awt.event.FocusAdapter focusReshow = new java.awt.event.FocusAdapter() {
            @Override
            public void focusGained(final java.awt.event.FocusEvent e) {
                final Object src = e.getSource();
                if (src == choicesList) _showSelectedCard(choicesList.getSelectedValue());
                else if (src == selectedList) _showSelectedCard(selectedList.getSelectedValue());
            }
        };
        choicesList.addFocusListener(focusReshow);
        selectedList.addFocusListener(focusReshow);

        // Zoom shortcut (configurable via Forge prefs; default Z): when pressed while the dialog has
        // window focus, zoom the card under the mouse pointer. Bound on the root pane with
        // WHEN_IN_FOCUSED_WINDOW so it fires regardless of which child component owns keyboard focus.
        int zoomKey;
        try {
            zoomKey = Integer.parseInt(FModel.getPreferences().getPref(FPref.SHORTCUT_CARD_ZOOM));
        } catch (final NumberFormatException e) {
            zoomKey = KeyEvent.VK_Z;
        }
        final InputMap im = getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        final ActionMap am = getRootPane().getActionMap();
        im.put(KeyStroke.getKeyStroke(zoomKey, 0, false), "zoom-toggle");
        am.put("zoom-toggle", new AbstractAction() {
            @Override
            public void actionPerformed(final ActionEvent ev) {
                _toggleZoomUnderCursor();
            }
        });

        // Zoom overlay (hidden by default) lives on the dialog's layered pane.
        zoomOverlay.setHorizontalAlignment(SwingConstants.CENTER);
        zoomOverlay.setOpaque(false);
        zoomOverlay.setVisible(false);
        getLayeredPane().add(zoomOverlay, JLayeredPane.POPUP_LAYER);

        // Double-click moves a single card across.
        choicesList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(final MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1 && e.getClickCount() == 2) {
                    final int idx = choicesList.locationToIndex(e.getPoint());
                    if (idx >= 0 && idx < choicesModel.getSize()) {
                        choicesList.addSelectionInterval(idx, idx);
                        _moveSelectedFromChoices();
                    }
                }
            }
        });
        selectedList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(final MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1 && e.getClickCount() == 2) {
                    final int idx = selectedList.locationToIndex(e.getPoint());
                    if (idx >= 0 && idx < selectedModel.getSize()) {
                        selectedList.addSelectionInterval(idx, idx);
                        _moveSelectedFromSelected();
                    }
                }
            }
        });

        // Layout: two stacked panels (Choices top, Selected bottom) + button row.
        final FPanel topPanel = new FPanel(new BorderLayout(0, 3));
        topPanel.add(choicesLabel, BorderLayout.NORTH);
        topPanel.add(makeHorizontalScroll(choicesList), BorderLayout.CENTER);

        final FPanel bottomPanel = new FPanel(new BorderLayout(0, 3));
        bottomPanel.add(selectedLabel, BorderLayout.NORTH);
        bottomPanel.add(makeHorizontalScroll(selectedList), BorderLayout.CENTER);

        // Middle row of arrow buttons (↓ ↓↓ then ↑ ↑↑) for moving cards between rows.
        final Dimension arrowSize = new Dimension(56, 26);
        addButton.setPreferredSize(arrowSize);
        addAllButton.setPreferredSize(arrowSize);
        removeButton.setPreferredSize(arrowSize);
        removeAllButton.setPreferredSize(arrowSize);
        final JPanel middleRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
        middleRow.setOpaque(false);
        middleRow.add(addButton);
        middleRow.add(addAllButton);
        // Visual gap between the down-group and up-group via a horizontal strut.
        middleRow.add(javax.swing.Box.createHorizontalStrut(24));
        middleRow.add(removeAllButton);
        middleRow.add(removeButton);

        final Dimension buttonSize = new Dimension(140, 24);
        okButton.setPreferredSize(buttonSize);
        autoButton.setPreferredSize(buttonSize);
        final JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 60, 0));
        buttonRow.setOpaque(false);
        buttonRow.add(okButton);
        buttonRow.add(autoButton);

        final int initialChoices = choices != null ? choices.size() : 0;
        final int initialSelected = selected != null ? selected.size() : 0;
        final int visibleSlots = Math.max(1, Math.min(MAX_VISIBLE, Math.max(initialChoices, initialSelected)));
        final int rowWidth = visibleSlots * CELL_W + 24;  // include scrollbar reservation slack
        // Headroom for: label (~26px with top pad), BorderLayout vgap (3), horizontal scrollbar
        // (~18px when shown). Fixed total so the cell isn't clipped when the scrollbar appears.
        final int rowHeight = CELL_H + 50;

        add(topPanel, "w " + rowWidth + ", h " + rowHeight + ", wrap");
        add(middleRow, "growx, gaptop 6, gapbottom 6, wrap");
        add(bottomPanel, "w " + rowWidth + ", h " + rowHeight + ", wrap");
        add(buttonRow, "growx, gaptop 16");

        _setButtonState();
    }

    private FList<CardView> buildList(final DefaultListModel<CardView> model, final boolean showOrder, final String emptyHint) {
        final FList<CardView> list = new FList<CardView>(model) {
            @Override
            public Dimension getPreferredScrollableViewportSize() {
                return new Dimension(MAX_VISIBLE * CELL_W, CELL_H);
            }

            @Override
            public Dimension getPreferredSize() {
                final Dimension d = super.getPreferredSize();
                // Keep the empty list at the row's full visible size so paintComponent can render
                // the empty-state hint in the right place; default would collapse to ~0,0.
                if (getModel().getSize() == 0) {
                    return new Dimension(MAX_VISIBLE * CELL_W, CELL_H);
                }
                return d;
            }

            @Override
            protected void paintComponent(final Graphics g) {
                super.paintComponent(g);
                if (emptyHint == null || getModel().getSize() != 0) {
                    return;
                }
                final Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g2.setColor(new Color(170, 170, 170, 180));
                    final Font base = g2.getFont();
                    g2.setFont(base.deriveFont(Font.ITALIC, base.getSize2D() + 2f));
                    final FontMetrics fm = g2.getFontMetrics();
                    final int textW = fm.stringWidth(emptyHint);
                    final int textH = fm.getAscent();
                    final int x = (getWidth() - textW) / 2;
                    final int y = (getHeight() + textH) / 2;
                    g2.drawString(emptyHint, x, y);
                } finally {
                    g2.dispose();
                }
            }
        };
        list.setLayoutOrientation(JList.HORIZONTAL_WRAP);
        list.setVisibleRowCount(1);                   // single horizontal row; scroll horizontally for overflow.
        list.setFixedCellWidth(CELL_W);
        list.setFixedCellHeight(CELL_H);
        list.setCellRenderer(new CardCellRenderer(showOrder));
        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        return list;
    }

    private FScrollPane makeHorizontalScroll(final FList<CardView> list) {
        final FScrollPane scroll = new FScrollPane(list, true,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scroll.setWheelScrollingEnabled(true);
        // Mouse wheel scrolls horizontally inside the row.
        list.addMouseWheelListener(e -> {
            scroll.getHorizontalScrollBar().setValue(
                    scroll.getHorizontalScrollBar().getValue() + e.getUnitsToScroll() * CELL_W / 2);
        });
        return scroll;
    }

    public void setSecondColumnLabelText(final String label) {
        if (label != null) {
            String t = label.trim();
            if (t.endsWith(":")) {
                t = t.substring(0, t.length() - 1).trim();
            }
            selectedLabelBase = t;
        } else {
            selectedLabelBase = null;
        }
        _refreshSelectedHeader();
    }

    public List<CardView> getOrderedList() {
        setVisible(false);
        final List<CardView> out = new ArrayList<>(selectedModel.getSize());
        for (int i = 0; i < selectedModel.getSize(); i++) {
            out.add(selectedModel.get(i));
        }
        return out;
    }

    public List<CardView> getRemainingSourceList() {
        final List<CardView> out = new ArrayList<>(choicesModel.getSize());
        for (int i = 0; i < choicesModel.getSize(); i++) {
            out.add(choicesModel.get(i));
        }
        return out;
    }

    private void _refreshSelectedHeader() {
        if (selectedLabelBase == null) {
            selectedLabel.setText(String.format(" (%d/%d):", selectedModel.getSize(), targetSelected));
        } else {
            selectedLabel.setText(selectedLabelBase
                    + String.format(" (%d/%d):", selectedModel.getSize(), targetSelected));
        }
    }

    private void _setButtonState() {
        final boolean anySize = maxRemaining < 0;
        final int srcSize = choicesModel.getSize();
        final int srcSelected = choicesList.getSelectedIndices().length;

        // ↓ moves the current top-row selection: enable only when post-move size respects min.
        final boolean canAdd = srcSelected > 0 && (anySize || (srcSize - srcSelected) >= minRemaining);
        // ↓↓ moves all top-row items: needs min<=0 (the dialog must allow emptying the source).
        final boolean canAddAll = srcSize > 0 && (anySize || minRemaining <= 0);
        final boolean canRemove = selectedModel.getSize() != 0;
        final boolean targetReached = anySize
                || (minRemaining <= srcSize && maxRemaining >= srcSize);

        addButton.setEnabled(canAdd);
        addAllButton.setEnabled(canAddAll);
        removeButton.setEnabled(canRemove);
        removeAllButton.setEnabled(canRemove);
        autoButton.setEnabled(maxRemaining == 0 && !targetReached);
        okButton.setEnabled(targetReached);
        if (targetReached) {
            okButton.requestFocusInWindow();
        }
        _refreshSelectedHeader();
    }

    private void _addAll() {
        // Move every choice into selected; preserves their current relative order.
        final List<CardView> moving = new ArrayList<>(choicesModel.getSize());
        for (int i = 0; i < choicesModel.getSize(); i++) {
            moving.add(choicesModel.get(i));
        }
        choicesModel.clear();
        for (final CardView cv : moving) {
            selectedModel.addElement(cv);
        }
        _setButtonState();
        _scrollToEnd(selectedList, selectedModel);
    }

    private void _removeAll() {
        // Move every selected back to choices; preserves their current relative order.
        final List<CardView> moving = new ArrayList<>(selectedModel.getSize());
        for (int i = 0; i < selectedModel.getSize(); i++) {
            moving.add(selectedModel.get(i));
        }
        selectedModel.clear();
        for (final CardView cv : moving) {
            choicesModel.addElement(cv);
        }
        _setButtonState();
        _scrollToEnd(choicesList, choicesModel);
    }

    // After items land at the end of a model, ensure the row scrolls so they're visible. JList only
    // updates the viewport on selection changes by default; ensureIndexIsVisible forces it.
    private static void _scrollToEnd(final FList<CardView> list, final DefaultListModel<CardView> model) {
        if (model.getSize() == 0) return;
        SwingUtilities.invokeLater(() -> list.ensureIndexIsVisible(model.getSize() - 1));
    }

    private void _finish() {
        setVisible(false);
    }

    private void _showSelectedCard(final CardView cv) {
        if (matchUI == null || cv == null) return;
        matchUI.clearPanelSelections();
        matchUI.setCard(cv);
        matchUI.setPanelSelection(cv);
    }

    private void _toggleZoomUnderCursor() {
        if (zoomOverlay.isVisible()) {
            zoomOverlay.setVisible(false);
            return;
        }
        final Point screen = MouseInfo.getPointerInfo().getLocation();
        for (final FList<CardView> list : Arrays.asList(choicesList, selectedList)) {
            final Point pt = new Point(screen);
            SwingUtilities.convertPointFromScreen(pt, list);
            if (pt.x < 0 || pt.y < 0 || pt.x >= list.getWidth() || pt.y >= list.getHeight()) {
                continue;
            }
            final int idx = list.locationToIndex(pt);
            if (idx < 0) continue;
            final Rectangle bounds = list.getCellBounds(idx, idx);
            if (bounds == null || !bounds.contains(pt)) continue;
            final CardView cv = list.getModel().getElementAt(idx);
            if (cv == null) continue;
            _showZoomOverlay(cv);
            return;
        }
    }

    private void _showZoomOverlay(final CardView cv) {
        final int paneW = getLayeredPane().getWidth();
        final int paneH = getLayeredPane().getHeight();
        if (paneH <= 0) return;
        // Display at 90% of dialog height, maintaining 5:7 aspect.
        final int displayH = (int) (paneH * 0.9);
        final int displayW = (int) (displayH * 5.0 / 7.0);
        final String imageKey = cv.getCurrentState().getImageKey();
        final Pair<BufferedImage, Boolean> info = ImageCache.getCardOriginalImageInfo(imageKey, true);
        final BufferedImage src = info.getLeft();
        if (src == null) return;
        final int srcCorner = Math.max(4, Math.round(src.getWidth() * CardPanel.ROUNDED_CORNER_SIZE));
        final BufferedImage rounded = ImageCache.makeRoundedCorner(src, srcCorner);
        zoomOverlay.setIcon(new HighQualityScaledIcon(rounded, displayW, displayH));
        zoomOverlay.setBounds((paneW - displayW) / 2, (paneH - displayH) / 2, displayW, displayH);
        zoomOverlay.setVisible(true);
        zoomOverlay.repaint();
    }

    private void _moveSelectedFromChoices() {
        final int[] sortedIndices = choicesList.getSelectedIndices();
        if (sortedIndices.length == 0) return;
        Arrays.sort(sortedIndices);

        // Min-source guard: post-move size must be >= min (skip if anySize).
        final boolean anySize = maxRemaining < 0;
        if (!anySize && (choicesModel.getSize() - sortedIndices.length) < minRemaining) {
            return;
        }

        final List<CardView> moving = new ArrayList<>(sortedIndices.length);
        for (final int idx : sortedIndices) moving.add(choicesModel.get(idx));
        for (int i = sortedIndices.length - 1; i >= 0; i--) {
            choicesModel.remove(sortedIndices[i]);
        }
        for (final CardView cv : moving) {
            selectedModel.addElement(cv);
        }
        _setButtonState();
        _scrollToEnd(selectedList, selectedModel);
    }

    private void _moveSelectedFromSelected() {
        final int[] sortedIndices = selectedList.getSelectedIndices();
        if (sortedIndices.length == 0) return;
        Arrays.sort(sortedIndices);

        final List<CardView> moving = new ArrayList<>(sortedIndices.length);
        for (final int idx : sortedIndices) moving.add(selectedModel.get(idx));
        for (int i = sortedIndices.length - 1; i >= 0; i--) {
            selectedModel.remove(sortedIndices[i]);
        }
        for (final CardView cv : moving) {
            choicesModel.addElement(cv);
        }
        _setButtonState();
        _scrollToEnd(choicesList, choicesModel);
    }

    private Icon getOrComputeIcon(final CardView cv, final JList<? extends CardView> source) {
        final String imageKey = cv.getCurrentState().getImageKey();
        final String cacheKey = imageKey + "#" + CARD_W + "x" + CARD_H;
        final Icon cached = iconCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        final Pair<BufferedImage, Boolean> info = ImageCache.getCardOriginalImageInfo(imageKey, true);
        final BufferedImage src = info.getLeft();
        if (src == null) {
            return null;
        }
        final boolean isPlaceholder = Boolean.TRUE.equals(info.getRight());
        final int srcCorner = Math.max(4, Math.round(src.getWidth() * CardPanel.ROUNDED_CORNER_SIZE));
        final BufferedImage rounded = ImageCache.makeRoundedCorner(src, srcCorner);
        final Icon icon = new HighQualityScaledIcon(rounded, CARD_W, CARD_H);
        iconCache.put(cacheKey, icon);
        if (isPlaceholder) {
            final ImageFetcher fetcher = GuiBase.getInterface().getImageFetcher();
            if (fetcher != null) {
                fetcher.fetchImage(imageKey, () -> {
                    iconCache.remove(cacheKey);
                    SwingUtilities.invokeLater(source::repaint);
                });
            }
        }
        return icon;
    }

    // Click on an already-selected sole cell unselects it; otherwise narrow as default. Same convention
    // as DualListBox-with-thumbnails; keeps the single-pick swap one click and Ctrl-click for additive
    // multi-select.
    private static final class ToggleSelectionModel extends DefaultListSelectionModel {
        @Override
        public void setSelectionInterval(final int index0, final int index1) {
            if (index0 == index1
                    && isSelectedIndex(index0)
                    && getMinSelectionIndex() == index0
                    && getMaxSelectionIndex() == index0) {
                removeSelectionInterval(index0, index0);
            } else {
                super.setSelectionInterval(index0, index1);
            }
        }
    }

    private final class CardCellRenderer extends JPanel implements ListCellRenderer<CardView> {
        private static final int BADGE_DIAMETER = 26;

        private final boolean showOrder;
        private final JLabel imageLabel = new JLabel();
        private final JLabel nameLabel = new JLabel();
        private boolean cellSelected;
        private int currentIndex = -1;

        CardCellRenderer(final boolean showOrder) {
            super(new BorderLayout(0, CELL_GAP));
            this.showOrder = showOrder;
            setOpaque(true);
            setBorder(BorderFactory.createEmptyBorder(CELL_PADDING, CELL_PADDING, CELL_PADDING, CELL_PADDING));
            imageLabel.setHorizontalAlignment(SwingConstants.CENTER);
            imageLabel.setPreferredSize(new Dimension(CARD_W, CARD_H));
            nameLabel.setHorizontalAlignment(SwingConstants.CENTER);
            nameLabel.setVerticalAlignment(SwingConstants.CENTER);
            nameLabel.setPreferredSize(new Dimension(CARD_W, FOOTER_H));
            add(imageLabel, BorderLayout.CENTER);
            add(nameLabel, BorderLayout.SOUTH);
        }

        @Override
        public Component getListCellRendererComponent(final JList<? extends CardView> list, final CardView value,
                final int index, final boolean isSelected, final boolean cellHasFocus) {
            imageLabel.setIcon(getOrComputeIcon(value, list));
            String name = value.getCurrentState().getName();
            if (name == null || name.isEmpty()) {
                name = "—";
            }
            // HTML wrapper enables centered word-wrap when the name is long.
            nameLabel.setText("<html><center>" + name + "</center></html>");
            setBackground(list.getBackground());
            nameLabel.setForeground(list.getForeground());
            cellSelected = isSelected;
            currentIndex = index;
            return this;
        }

        @Override
        protected void paintComponent(final Graphics g) {
            super.paintComponent(g);
            // Selection ring drawn before children so the inflated rectangle's interior gets covered
            // by the icon — only the outer ring stays visible.
            if (!cellSelected) return;
            final Rectangle b = imageLabel.getBounds();
            if (b.width <= 0 || b.height <= 0) return;
            final Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                final int n = Math.max(2, Math.round(b.width * CardPanel.SELECTED_BORDER_SIZE));
                final int corner = Math.max(4, Math.round(b.width * CardPanel.ROUNDED_CORNER_SIZE));
                g2.setColor(Color.green);
                g2.fillRoundRect(b.x - n, b.y - n,
                        b.width + 2 * n, b.height + 2 * n,
                        corner + n, corner + n);
            } finally {
                g2.dispose();
            }
        }

        @Override
        protected void paintChildren(final Graphics g) {
            // Order badge drawn after children so it sits on top of the imageLabel's icon.
            super.paintChildren(g);
            if (!showOrder || currentIndex < 0) return;
            final Rectangle b = imageLabel.getBounds();
            if (b.width <= 0 || b.height <= 0) return;
            final Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                final int bx = b.x + 6;
                final int by = b.y + 6;
                g2.setColor(new Color(20, 20, 20, 220));
                g2.fillOval(bx, by, BADGE_DIAMETER, BADGE_DIAMETER);
                g2.setColor(new Color(220, 220, 220));
                g2.drawOval(bx, by, BADGE_DIAMETER, BADGE_DIAMETER);
                g2.setColor(Color.WHITE);
                g2.setFont(g2.getFont().deriveFont(java.awt.Font.BOLD));
                final String text = String.valueOf(currentIndex + 1);
                final java.awt.FontMetrics fm = g2.getFontMetrics();
                final int textW = fm.stringWidth(text);
                final int textH = fm.getAscent();
                g2.drawString(text, bx + (BADGE_DIAMETER - textW) / 2, by + (BADGE_DIAMETER + textH) / 2 - 2);
            } finally {
                g2.dispose();
            }
        }
    }

    // Mirrors CardImageGrid's HighQualityScaledIcon — draws to the destination Graphics2D so HiDPI
    // scaling stays in one step.
    private static final class HighQualityScaledIcon implements Icon {
        private final Image source;
        private final int displayW;
        private final int displayH;

        HighQualityScaledIcon(final Image source, final int displayW, final int displayH) {
            this.source = source;
            this.displayW = displayW;
            this.displayH = displayH;
        }

        @Override public int getIconWidth()  { return displayW; }
        @Override public int getIconHeight() { return displayH; }

        @Override
        public void paintIcon(final Component c, final Graphics g, final int x, final int y) {
            final Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2.drawImage(source, x, y, displayW, displayH, null);
            g2.dispose();
        }
    }

    private static final class HorizontalTransferable implements Transferable {
        private final DnDPayload payload;
        HorizontalTransferable(final DnDPayload payload) { this.payload = payload; }

        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[] { DND_FLAVOR };
        }

        @Override
        public boolean isDataFlavorSupported(final DataFlavor flavor) {
            return DND_FLAVOR != null && DND_FLAVOR.equals(flavor);
        }

        @Override
        public Object getTransferData(final DataFlavor flavor) throws UnsupportedFlavorException {
            if (!isDataFlavorSupported(flavor)) {
                throw new UnsupportedFlavorException(flavor);
            }
            return payload;
        }
    }

    private final class HorizontalTransferHandler extends TransferHandler {
        @Override public int getSourceActions(final JComponent c) { return MOVE; }

        @Override
        protected Transferable createTransferable(final JComponent c) {
            if (!(c instanceof FList)) return null;
            final FList<?> list = (FList<?>) c;
            final int[] indices = list.getSelectedIndices();
            if (indices.length == 0) return null;
            return new HorizontalTransferable(new DnDPayload(list, indices));
        }

        @Override
        public boolean canImport(final TransferSupport ts) {
            if (DND_FLAVOR == null || !ts.isDataFlavorSupported(DND_FLAVOR)) return false;
            if (!(ts.getComponent() instanceof FList)) return false;
            try {
                final DnDPayload payload = (DnDPayload) ts.getTransferable().getTransferData(DND_FLAVOR);
                final FList<?> target = (FList<?>) ts.getComponent();
                if (payload.source == target) return true; // same-pane reorder always allowed
                if (payload.source == choicesList && target == selectedList) {
                    final boolean anySize = maxRemaining < 0;
                    if (!anySize) {
                        final int newSrcSize = choicesModel.getSize() - payload.indices.length;
                        if (newSrcSize < minRemaining) return false;
                    }
                }
                return true;
            } catch (final Exception e) {
                return false;
            }
        }

        @Override
        public boolean importData(final TransferSupport ts) {
            if (!canImport(ts)) return false;
            try {
                final DnDPayload payload = (DnDPayload) ts.getTransferable().getTransferData(DND_FLAVOR);
                @SuppressWarnings("unchecked")
                final FList<CardView> targetJList = (FList<CardView>) ts.getComponent();

                final DefaultListModel<CardView> srcModel =
                        (payload.source == choicesList) ? choicesModel : selectedModel;
                final DefaultListModel<CardView> dstModel =
                        (targetJList == choicesList) ? choicesModel : selectedModel;

                final int[] sortedIndices = payload.indices.clone();
                Arrays.sort(sortedIndices);

                final List<CardView> items = new ArrayList<>(sortedIndices.length);
                for (final int idx : sortedIndices) {
                    if (idx < 0 || idx >= srcModel.getSize()) return false;
                    items.add(srcModel.get(idx));
                }

                int dropIndex = ((JList.DropLocation) ts.getDropLocation()).getIndex();
                if (dropIndex < 0 || dropIndex > dstModel.getSize()) {
                    dropIndex = dstModel.getSize();
                }

                if (srcModel == dstModel) {
                    for (final int idx : sortedIndices) {
                        if (idx < dropIndex) dropIndex--;
                    }
                }

                for (int i = sortedIndices.length - 1; i >= 0; i--) {
                    srcModel.remove(sortedIndices[i]);
                }

                int insertAt = dropIndex;
                for (final CardView cv : items) {
                    dstModel.add(insertAt++, cv);
                }

                _setButtonState();
                // Select the moved items at their new position and scroll so the last lands in view.
                final int[] newSel = new int[items.size()];
                for (int i = 0; i < newSel.length; i++) newSel[i] = dropIndex + i;
                targetJList.setSelectedIndices(newSel);
                final int lastInserted = dropIndex + items.size() - 1;
                SwingUtilities.invokeLater(() -> targetJList.ensureIndexIsVisible(lastInserted));
                return true;
            } catch (final Exception e) {
                return false;
            }
        }
    }
}
