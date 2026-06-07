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

import forge.Singletons;
import forge.game.card.CardView;
import forge.screens.match.CMatchUI;
import forge.toolbox.FButton;
import forge.toolbox.FSkin;
import forge.util.Localizer;
import forge.view.arcane.util.CardPanelMouseAdapter;

// Modal dialog for splitting cards into two ordered piles (e.g. top/bottom of library, or library/graveyard).
// The cards are shown in a "top" and "bottom" section separated by a divider; clicking a card moves it to the
// other section and dragging places or reorders it. getTopPile()/getBottomPile() return the two ordered piles.
public class ListCardArea extends FloatingCardArea {

    private static final int HEADER_HEIGHT = 30;
    private static final int DIVIDER_GAP = 14;
    private static final int CARD_GAP = 6;

    private static ListCardArea storedArea;
    private FButton doneButton;

    private final List<CardView> topSection = new ArrayList<>();
    private final List<CardView> bottomSection = new ArrayList<>();
    private String topLabel, bottomLabel, dividerText;

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
        // Splitting the cards is mandatory — only Done dismisses it; the X, Esc, and titlebar
        // double-click must not close it.
        window.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setOpaque(false);
        setDragEnabled(true);
    }

    public static ListCardArea show(final CMatchUI matchUI, final String title0, final Iterable<CardView> cards0, final String topLabel0, final String bottomLabel0, final String dividerText0) {
        if (storedArea == null) {
            storedArea = new ListCardArea(matchUI);
        }
        storedArea.init(title0, cards0, topLabel0, bottomLabel0, dividerText0);
        storedArea.showWindow();
        return storedArea;
    }

    private void init(final String title0, final Iterable<CardView> cards0, final String topLabel0, final String bottomLabel0, final String dividerText0) {
        title = title0;
        topLabel = topLabel0;
        bottomLabel = bottomLabel0;
        dividerText = dividerText0;
        topSection.clear();
        bottomSection.clear();
        for (final CardView c : cards0) {
            topSection.add(c);
        }
    }

    public List<CardView> getTopPile() {
        return new ArrayList<>(topSection);
    }

    public List<CardView> getBottomPile() {
        return new ArrayList<>(bottomSection);
    }

    @Override
    public List<CardView> getCards() {
        final List<CardView> render = new ArrayList<>(topSection);
        render.addAll(bottomSection);
        return render;
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
            getWindow().setSize(Singletons.getView().getFrame().getWidth() / 4, Singletons.getView().getFrame().getHeight() * 2 / 3 - 50);
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
        for (final CardView c : getCards()) {
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
        // Split the window into two equal sections so the divider stays put regardless of where cards are.
        final int sectionH = (availH - 2 * CardArea.GUTTER_Y - 2 * DIVIDER_GAP) / 2;
        final int cardAreaH = Math.max(1, sectionH - HEADER_HEIGHT);

        // Shrink the cards until the busier section's rows fit within its half.
        int cardWidth = Math.min(getCardWidthMax(), availW - 2 * CardArea.GUTTER_X);
        int cols;
        while (cardWidth > getCardWidthMin()) {
            final int cardHeight = Math.round(cardWidth * CardPanel.ASPECT_RATIO);
            cols = columnsFor(cardWidth, availW);
            final int maxRows = Math.max(rowsFor(topCount, cols), rowsFor(botCount, cols));
            if (maxRows == 0 || maxRows * cardHeight + (maxRows - 1) * CARD_GAP <= cardAreaH) {
                break;
            }
            cardWidth -= 4;
        }
        cardWidth = Math.max(cardWidth, getCardWidthMin());
        final int cardHeight = Math.round(cardWidth * CardPanel.ASPECT_RATIO);
        cols = columnsFor(cardWidth, availW);

        topHeaderY = CardArea.GUTTER_Y;
        final int topEnd = layoutSection(panels, 0, topCount, topHeaderY + HEADER_HEIGHT, cardWidth, cardHeight, cols, availW);
        // Divider sits at the midpoint normally; if the top section overflows its half, push it below
        // the top cards so the sections never overlap (the window scrolls instead).
        dividerY = Math.max(CardArea.GUTTER_Y + sectionH + DIVIDER_GAP, topEnd + DIVIDER_GAP);
        bottomHeaderY = dividerY + DIVIDER_GAP;
        final int bottomEnd = layoutSection(panels, topCount, panels.size(), bottomHeaderY + HEADER_HEIGHT, cardWidth, cardHeight, cols, availW);

        final int prefH = Math.max(bottomEnd + CardArea.GUTTER_Y, availH);
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

    private int layoutSection(final List<CardPanel> panels, final int from, final int to, int y,
            final int cardWidth, final int cardHeight, final int cols, final int availW) {
        final int count = to - from;
        if (count == 0) {
            return y;
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
        FSkin.setGraphicsColor(g2, FSkin.getColor(FSkin.Colors.CLR_TEXT));
        g2.drawString(topLabel, (getWidth() - fm.stringWidth(topLabel)) / 2, topHeaderY + fm.getAscent());
        g2.drawString(bottomLabel, (getWidth() - fm.stringWidth(bottomLabel)) / 2, bottomHeaderY + fm.getAscent());
        paintDivider(g2);
    }

    // Plain rule, or a centered count flanked by rule lines, all in the separator colour.
    private void paintDivider(final Graphics2D g2) {
        FSkin.setGraphicsColor(g2, FSkin.getColor(FSkin.Colors.CLR_BORDERS));
        if (dividerText == null || dividerText.isEmpty()) {
            g2.drawLine(CardArea.GUTTER_X, dividerY, getWidth() - CardArea.GUTTER_X, dividerY);
            return;
        }
        if (separatorFont == null) {
            separatorFont = getFont().deriveFont(Font.PLAIN, 12f);
        }
        g2.setFont(separatorFont);
        final FontMetrics fm = g2.getFontMetrics();
        final int textW = fm.stringWidth(dividerText);
        final int textX = (getWidth() - textW) / 2;
        final int pad = 8;
        g2.drawLine(CardArea.GUTTER_X, dividerY, textX - pad, dividerY);
        g2.drawLine(textX + textW + pad, dividerY, getWidth() - CardArea.GUTTER_X, dividerY);
        g2.drawString(dividerText, textX, dividerY + (fm.getAscent() - fm.getDescent()) / 2);
    }

    @Override
    protected boolean cardPanelDraggable(final CardPanel panel) {
        return true;
    }

    private void dropCard(final CardView card, final int dropX, final int dropY) {
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
        final boolean inTop = topSection.contains(card);
        topSection.remove(card);
        bottomSection.remove(card);
        (inTop ? bottomSection : topSection).add(card);
        refresh();
    }
}
