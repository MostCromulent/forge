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

import forge.game.card.CardView;
import forge.toolbox.MouseTriggerEvent;
import forge.view.arcane.FloatingCardWindow;

/** Default interaction for zone-backed floating windows: routes clicks to the game controller. */
final class BrowseInteraction implements FloatingCardWindow.Interaction {
    private final CMatchUI matchUI;

    BrowseInteraction(final CMatchUI matchUI) {
        this.matchUI = matchUI;
    }

    // Legality is checked by the game controller, not the widget — every panel stays clickable.
    @Override public boolean canClick(final FloatingCardWindow w, final CardView c) { return true; }
    @Override public void onLeftClick(final FloatingCardWindow w, final CardView c, final MouseEvent evt) {
        matchUI.getGameController().selectCard(c, null, new MouseTriggerEvent(evt));
    }
    @Override public void onRightClick(final FloatingCardWindow w, final CardView c, final MouseEvent evt) {
        matchUI.getGameController().selectCard(c, null, new MouseTriggerEvent(evt));
    }
    @Override public boolean showsSelectionPrompt() { return true; }
    @Override public boolean supportsHotkeys() { return true; }
}
