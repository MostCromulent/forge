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

import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JScrollBar;
import javax.swing.WindowConstants;

import forge.game.card.CardView;
import forge.screens.match.CMatchUI;
import forge.toolbox.FButton;
import forge.toolbox.FSkin;
import forge.util.Localizer;
import forge.view.arcane.util.CardPanelMouseAdapter;

// Modal dialog for arranging the scryed cards into the top or bottom of the library.
// Only the movable (scryed) cards are shown, split into a "top" and "bottom" section
// separated by a divider; the untouched library cards aren't drawn but are reinserted
// between the two sections in the returned list so manipulateCardList's top/bottom decode
// is unchanged.
public class ListCardArea extends FloatingCardArea {

    private static final int HEADER_HEIGHT = 20;
    private static final int DIVIDER_GAP = 14;
    private static final int CARD_GAP = 6;
    private static final int MIN_SECTION_HEIGHT = 32; // keep empty sections an easy drop target

    private static ListCardArea storedArea;
    private FButton doneButton;

    private List<CardView> allCards = new ArrayList<>();
    private List<CardView> moveableCards = new ArrayList<>();
    private final List<CardView> topSection = new ArrayList<>();
    private final List<CardView> bottomSection = new ArrayList<>();

    private int topCount;
    private int topHeaderY, dividerY, bottomHeaderY;
    private Font headerFont;
    private Font separatorFont;

    private ListCardArea(final CMatchUI matchUI) {
        super(matchUI);
        window.add(getScrollPane(), "grow, push, wrap, gap 6 6 6 6");
        window.setModal(true);
        getScrollPane().setViewportView(this);
        doneButton = new FButton(Localizer.getInstance().getMessage("lblDone"));
        doneButton.addActionListener(e -> window.setVisible(false));
        window.add(doneButton, "align center, gapbottom 8, w 140, h 24");
        // Arranging the scry is mandatory — only Done dismisses it; the X, Esc, and titlebar
        // double-click must not close it.
        window.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setOpaque(false);
        setDragEnabled(true);
    }

    public static ListCardArea show(final CMatchUI matchUI, final String title0, final Iterable<CardView> cardList0, final Iterable<CardView> moveableCards0, final boolean toTop0, final boolean toBottom0, final boolean toAnywhere0) {
        if (storedArea == null) {
            storedArea = new ListCardArea(matchUI);
        }
        storedArea.init(title0, cardList0, moveableCards0);
        storedArea.showWindow();
        return storedArea;
    }

    private void init(final String title0, final Iterable<CardView> cardList0, final Iterable<CardView> moveableCards0) {
        title = title0;
        allCards = new ArrayList<>();
        for (final CardView c : cardList0) {
            allCards.add(c);
        }
        moveableCards = new ArrayList<>();
        for (final CardView c : moveableCards0) {
            if (allCards.contains(c)) {
                moveableCards.add(c);
            }
        }
        topSection.clear();
        bottomSection.clear();
        topSection.addAll(moveableCards); // all start on top, in library order
    }

    // Reconstruct the full library order: top cards, the untouched cards in their
    // original order, then the bottom cards reversed so the lowest one in the window
    // becomes the very bottom of the library once moveToBottomOfLibrary is applied.
    @Override
    public List<CardView> getCards() {
        final List<CardView> result = new ArrayList<>(topSection);
        for (final CardView c : allCards) {
            if (!moveableCards.contains(c)) {
                result.add(c);
            }
        }
        for (int i = bottomSection.size() - 1; i >= 0; i--) {
            result.add(bottomSection.get(i));
        }
        return result;
    }

    @Override
    protected void showWindow() {
        onShow();
        getWindow().setFocusableWindowState(true);
        getWindow().setVisible(true);
    }

    @Override
    protected void onShow() {
        // Deliberately not calling super.onShow(): it installs a titlebar double-click handler that
        // closes the window, which must not dismiss this mandatory dialog. Load the saved location here.
        if (!hasBeenShown) {
            loadLocation();
            addCardPanelMouseListener(new CardPanelMouseAdapter() {
                @Override
                public void mouseDragEnd(final CardPanel dragPanel, final MouseEvent evt) {
                    dropCard(dragPanel.getCard(), evt.getX(), evt.getY());
                }
            });
            addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(final KeyEvent e) {
                    if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                        doneButton.doClick();
                    }
                }
            });
        }
    }

    @Override
    protected void doRefresh() {
        final List<CardPanel> panels = new ArrayList<>();
        for (final CardView c : topSection) {
            panels.add(panelFor(c));
        }
        for (final CardView c : bottomSection) {
            panels.add(panelFor(c));
        }
        topCount = topSection.size();
        setCardPanels(panels);
        getWindow().setTitle(title);
    }

    private CardPanel panelFor(final CardView c) {
        CardPanel panel = getCardPanel(c.getId());
        if (panel == null) {
            panel = new CardPanel(getMatchUI(), c);
            panel.setDisplayEnabled(true);
        } else {
            panel.setCard(c);
        }
        return panel;
    }

    @Override
    public void doLayout() {
        final List<CardPanel> panels = getCardPanels();
        if (panels.isEmpty()) {
            return;
        }
        final Rectangle rect = getScrollPane().getVisibleRect();
        final Insets insets = getScrollPane().getInsets();
        int availW = rect.width - insets.left - insets.right;
        final JScrollBar vsb = getScrollPane().getVerticalScrollBar();
        if (vsb != null && vsb.isVisible()) {
            availW -= vsb.getWidth();
        }
        availW = Math.max(availW, getCardWidthMin() + 2 * CardArea.GUTTER_X);
        final int availH = rect.height - insets.top - insets.bottom;

        final int botCount = panels.size() - topCount;
        final int overhead = 2 * HEADER_HEIGHT + 2 * DIVIDER_GAP + 2 * CardArea.GUTTER_Y;

        // Shrink the cards until both sections plus the headers and divider fit without scrolling
        int cardWidth = Math.min(getCardWidthMax(), availW - 2 * CardArea.GUTTER_X);
        int cardHeight;
        int cols;
        while (cardWidth > getCardWidthMin()) {
            cardHeight = Math.round(cardWidth * CardPanel.ASPECT_RATIO);
            cols = columnsFor(cardWidth, availW);
            final int contentH = overhead
                    + sectionHeight(rowsFor(topCount, cols), cardHeight)
                    + sectionHeight(rowsFor(botCount, cols), cardHeight);
            if (contentH <= availH) {
                break;
            }
            cardWidth -= 4;
        }
        cardWidth = Math.max(cardWidth, getCardWidthMin());
        cardHeight = Math.round(cardWidth * CardPanel.ASPECT_RATIO);
        cols = columnsFor(cardWidth, availW);

        int y = CardArea.GUTTER_Y;
        topHeaderY = y;
        y += HEADER_HEIGHT;
        y = layoutSection(panels, 0, topCount, y, cardWidth, cardHeight, cols, availW);
        y += DIVIDER_GAP;
        dividerY = y;
        y += DIVIDER_GAP;
        bottomHeaderY = y;
        y += HEADER_HEIGHT;
        y = layoutSection(panels, topCount, panels.size(), y, cardWidth, cardHeight, cols, availW);

        final int prefH = Math.max(y + CardArea.GUTTER_Y, availH);
        if (getPreferredSize().width != availW || getPreferredSize().height != prefH) {
            setPreferredSize(new Dimension(availW, prefH));
            revalidate();
        }
    }

    private int columnsFor(final int cardWidth, final int availW) {
        return Math.max(1, (availW - 2 * CardArea.GUTTER_X + CARD_GAP) / (cardWidth + CARD_GAP));
    }

    private static int rowsFor(final int count, final int cols) {
        return count == 0 ? 0 : (int) Math.ceil(count / (double) cols);
    }

    private static int sectionHeight(final int rows, final int cardHeight) {
        return rows == 0 ? MIN_SECTION_HEIGHT : rows * cardHeight + (rows - 1) * CARD_GAP;
    }

    private int layoutSection(final List<CardPanel> panels, final int from, final int to, int y,
            final int cardWidth, final int cardHeight, final int cols, final int availW) {
        final int count = to - from;
        if (count == 0) {
            return y + MIN_SECTION_HEIGHT;
        }
        int idx = 0;
        while (idx < count) {
            final int rowCards = Math.min(cols, count - idx);
            final int rowWidth = rowCards * cardWidth + (rowCards - 1) * CARD_GAP;
            int x = Math.max(CardArea.GUTTER_X, (availW - rowWidth) / 2);
            for (int i = 0; i < rowCards; i++) {
                panels.get(from + idx + i).setCardBounds(x, y, cardWidth, cardHeight);
                x += cardWidth + CARD_GAP;
            }
            idx += rowCards;
            y += cardHeight + CARD_GAP;
        }
        return y - CARD_GAP;
    }

    @Override
    protected void paintComponent(final Graphics g) {
        super.paintComponent(g);
        final Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        if (headerFont == null) {
            headerFont = getFont().deriveFont(Font.BOLD, 13f);
        }
        g2.setFont(headerFont);
        final FontMetrics fm = g2.getFontMetrics();
        final Localizer localizer = Localizer.getInstance();
        FSkin.setGraphicsColor(g2, FSkin.getColor(FSkin.Colors.CLR_TEXT));
        g2.drawString(localizer.getMessage("lblTopOfLibrary"), CardArea.GUTTER_X, topHeaderY + fm.getAscent());
        g2.drawString(localizer.getMessage("lblBottomOfLibrary"), CardArea.GUTTER_X, bottomHeaderY + fm.getAscent());
        paintDivider(g2, localizer);
    }

    // Centered count flanked by rule lines, all in the separator color: ──── N other cards in library ────
    private void paintDivider(final Graphics2D g2, final Localizer localizer) {
        if (separatorFont == null) {
            separatorFont = getFont().deriveFont(Font.PLAIN, 12f);
        }
        g2.setFont(separatorFont);
        final FontMetrics fm = g2.getFontMetrics();
        final int untouched = allCards.size() - moveableCards.size();
        final String text = localizer.getMessage("lblNOtherCardsInLibrary", String.valueOf(untouched));
        final int textW = fm.stringWidth(text);
        final int textX = (getWidth() - textW) / 2;
        final int pad = 8;
        FSkin.setGraphicsColor(g2, FSkin.getColor(FSkin.Colors.CLR_BORDERS));
        g2.drawLine(CardArea.GUTTER_X, dividerY, textX - pad, dividerY);
        g2.drawLine(textX + textW + pad, dividerY, getWidth() - CardArea.GUTTER_X, dividerY);
        g2.drawString(text, textX, dividerY + (fm.getAscent() - fm.getDescent()) / 2);
    }

    @Override
    protected boolean cardPanelDraggable(final CardPanel panel) {
        return moveableCards.contains(panel.getCard());
    }

    private void dropCard(final CardView card, final int dropX, final int dropY) {
        if (!moveableCards.contains(card)) {
            return;
        }
        final List<CardView> target = dropY < dividerY ? topSection : bottomSection;
        topSection.remove(card);
        bottomSection.remove(card);
        target.add(insertIndex(target, dropX, dropY), card);
        refresh();
    }

    private int insertIndex(final List<CardView> section, final int dropX, final int dropY) {
        int idx = 0;
        for (final CardView c : section) {
            final CardPanel panel = getCardPanel(c.getId());
            if (panel == null) {
                continue;
            }
            final int cx = panel.getCardX() + panel.getCardWidth() / 2;
            final int cy = panel.getCardY() + panel.getCardHeight() / 2;
            final int halfH = panel.getCardHeight() / 2;
            final boolean earlierRow = cy < dropY - halfH;
            final boolean sameRowToLeft = Math.abs(cy - dropY) <= halfH && cx < dropX;
            if (earlierRow || sameRowToLeft) {
                idx++;
            }
        }
        return idx;
    }

    @Override
    public final void mouseLeftClicked(final CardPanel panel, final MouseEvent evt) {
        toggleSection(panel.getCard());
    }

    @Override
    public final void mouseRightClicked(final CardPanel panel, final MouseEvent evt) {
        toggleSection(panel.getCard());
    }

    private void toggleSection(final CardView card) {
        if (!moveableCards.contains(card)) {
            return;
        }
        final boolean inTop = topSection.contains(card);
        topSection.remove(card);
        bottomSection.remove(card);
        (inTop ? bottomSection : topSection).add(card);
        refresh();
    }
}
