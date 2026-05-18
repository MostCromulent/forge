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
package forge.screens.match;

import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

import forge.game.card.CardView;
import forge.util.Localizer;
import forge.view.arcane.FloatingCardWindow;

/**
 * Modal reorder window with a Done button. Drag or click to move cards
 * within {@code cardList}; legality is gated by toTop / toBottom / toAnywhere.
 * Callers read the final order via {@link #getDestList()} after the window closes.
 */
public final class ManipulationWindow extends FloatingCardWindow {
    private final String title;
    private final List<CardView> cardList;
    private final List<CardView> moveableCards;
    private final boolean toTop, toBottom, toAnywhere;

    public ManipulationWindow(final CMatchUI matchUI, final String title,
                              final List<CardView> cards, final List<CardView> moveable,
                              final boolean toTop, final boolean toBottom, final boolean toAnywhere) {
        super(matchUI, true, Localizer.getInstance().getMessage("lblDone"));
        this.title = title;
        this.cardList = new ArrayList<>(cards);
        this.moveableCards = new ArrayList<>();
        for (final CardView c : moveable) if (cardList.contains(c)) moveableCards.add(c);
        this.toTop = toTop;
        this.toBottom = toBottom;
        this.toAnywhere = toAnywhere;
    }

    public List<CardView> getDestList() { return new ArrayList<>(cardList); }

    @Override protected Iterable<CardView> getCards() { return cardList; }
    @Override protected String getTitle(final int shown, final int total, final boolean sortedByName) { return title; }

    @Override
    protected boolean isCardClickable(final CardView c) {
        return moveableCards.contains(c) && (toTop || toBottom);
    }

    @Override
    protected boolean isCardDraggable(final CardView c) {
        return moveableCards.contains(c);
    }

    @Override
    protected void onCardDragged(final CardView c, final int newIndex) {
        if (!moveableCards.contains(c)) return;
        if (!validIndex(c, newIndex)) return;
        cardList.remove(c);
        cardList.add(newIndex, c);
    }

    @Override
    protected void handleLeftClick(final CardView c, final MouseEvent e) {
        if (!toTop && !toBottom) return;
        cardList.remove(c);
        final int position = toTop ? 0 : firstNonMoveableFromEnd();
        cardList.add(position, c);
        refresh();
    }

    @Override
    protected void handleRightClick(final CardView c, final MouseEvent e) {
        if (!toTop && !toBottom) return;
        cardList.remove(c);
        final int position = toBottom ? cardList.size() : firstNonMoveableFromStart();
        cardList.add(position, c);
        refresh();
    }

    private boolean validIndex(final CardView card, final int newIndex) {
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

    private int firstNonMoveableFromEnd() {
        int position;
        for (position = cardList.size();
             position > 0 && moveableCards.contains(cardList.get(position - 1));
             position--) {}
        return position;
    }

    private int firstNonMoveableFromStart() {
        int position;
        for (position = 0;
             position < cardList.size() && moveableCards.contains(cardList.get(position));
             position++) {}
        return position;
    }
}
