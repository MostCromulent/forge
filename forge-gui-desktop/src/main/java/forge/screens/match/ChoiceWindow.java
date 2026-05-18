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
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

import forge.game.card.CardView;
import forge.view.arcane.FloatingCardWindow;

/**
 * Modal pick-one-card window for zoneless cards. Click commits via {@code onPick}.
 * Mandatory choices ({@code !isOptional}) disable the title-bar X.
 */
public final class ChoiceWindow extends FloatingCardWindow {
    private final String title;
    private final List<CardView> cards;
    private final boolean isOptional;
    private final Consumer<CardView> onPick;

    public ChoiceWindow(final CMatchUI matchUI, final String title, final Iterable<CardView> cards,
                        final boolean isOptional, final Consumer<CardView> onPick) {
        super(matchUI, true, null);
        this.title = title;
        this.cards = new ArrayList<>();
        for (final CardView c : cards) this.cards.add(c);
        this.isOptional = isOptional;
        this.onPick = onPick;
    }

    @Override protected Iterable<CardView> getCards() { return cards; }

    @Override
    protected String getTitle(final int shown, final int total, final boolean sortedByName) {
        return title + " (" + shown + ")";
    }

    @Override protected boolean isCardClickable(final CardView c) { return true; }
    @Override protected boolean isCardSelectable(final CardView c) { return true; }
    @Override protected boolean showsSelectionPrompt() { return true; }
    @Override protected boolean supportsHotkeys() { return true; }
    @Override protected boolean supportsSortToggle() { return true; }
    @Override protected boolean allowsCancel() { return isOptional; }

    @Override
    protected Comparator<CardView> sortedComparator() {
        return Comparator.comparing(CardView::getName);
    }

    @Override
    protected void handleLeftClick(final CardView c, final MouseEvent e) {
        onPick.accept(c);
        hideWindow();
    }

    @Override
    protected void handleRightClick(final CardView c, final MouseEvent e) {
        onPick.accept(c);
        hideWindow();
    }
}
