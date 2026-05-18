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

import java.util.Comparator;

import forge.game.card.CardView;
import forge.game.player.PlayerView;
import forge.game.zone.ZoneType;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.localinstance.skin.FSkinProp;
import forge.toolbox.special.PlayerDetailsPanel;
import forge.util.Localizer;
import forge.util.collect.FCollectionView;
import forge.view.arcane.CardPanel;
import forge.view.arcane.FloatingCardWindow;

/** Live source backing a floating window with a player's zone cards. */
final class LiveZoneSource implements FloatingCardWindow.Source {
    private final CMatchUI matchUI;
    private final ZoneType zone;
    private PlayerView player;
    private FPref locPref;

    LiveZoneSource(final CMatchUI matchUI, final PlayerView player, final ZoneType zone) {
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
    public String getTitle(final int shown, final int total, final boolean sortedByName) {
        final Localizer loc = Localizer.getInstance();
        final String sortHint = loc.getMessage(sortedByName ? "lblRightClickToUnSort" : "lblRightClickToSort");
        if (shown == total) {
            return loc.getMessage("lblPlayerZoneNCardSortStatus",
                    player.getName(), zone.getTranslatedName(), String.valueOf(shown), sortHint);
        }
        return loc.getMessage("lblPlayerZoneNOfMCardSortStatus",
                player.getName(), zone.getTranslatedName(),
                String.valueOf(shown), String.valueOf(total), sortHint);
    }

    @Override public FPref getLocPref() { return locPref; }
    @Override public boolean supportsSortToggle() { return true; }

    @Override
    public void decoratePanel(final CardPanel panel, final CardView card) {
        if (zone != ZoneType.Flashback) return;
        final ZoneType cardZone = card.getZone();
        if (cardZone != null) {
            panel.setZoneBanner(cardZone.getTranslatedName().toUpperCase(), cardZone);
        }
    }

    @Override
    public Comparator<CardView> defaultComparator() {
        if (zone == ZoneType.Flashback) return CMatchUI.ZONE_ORDER_COMPARATOR;
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
    public FSkinProp getIconSkinProp() {
        return PlayerDetailsPanel.iconFromZone(zone);
    }
}
