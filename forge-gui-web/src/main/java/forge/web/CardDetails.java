package forge.web;

import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.card.CardView.CardStateView;
import forge.game.player.PlayerView;
import forge.gamemodes.net.DeltaPacket;
import forge.gui.card.CardDetailUtil;
import forge.web.ToBrowser.CardFace;
import forge.web.ToBrowser.Detail;
import forge.web.ToBrowser.PlayerDetail;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** What the zoom panel shows for a card or a player: the text the desktop client composes (CardDetailUtil). */
final class CardDetails {
    private CardDetails() {
    }

    static PlayerDetail player(final PlayerView player) {
        final List<String> lines = new ArrayList<>();
        final String[] parts = player.getDetails().split("\n");
        // The commander damage lines follow the commanders' cast counts in the engine's list; the browser draws that
        // damage itself, above these lines, so it is left out of them
        final List<String> commander = player.getPlayerCommanderInfo();
        final int casts = player.getCommanders() == null ? 0 : 1 + player.getCommanders().size();
        final Set<String> damage = new HashSet<>();
        for (int i = casts; i < commander.size(); i++) {
            damage.add(commander.get(i).trim());
        }
        // The first line is the player's name, which travels separately
        for (int i = 1; i < parts.length; i++) {
            if (!parts[i].isBlank() && !damage.contains(parts[i].trim())) {
                lines.add(parts[i]);
            }
        }
        return new PlayerDetail(DeltaPacket.makeDeltaKey(DeltaPacket.TYPE_PLAYER_VIEW, player.getId()), player.getName(), lines);
    }

    /**
     * A card's faces, as far as this viewer may see them. mayFlip hides an opponent's face-down card but shows the
     * owner theirs, as on desktop.
     */
    static Detail card(final CardView card, final GameView game, final boolean mayView, final boolean mayFlip) {
        final List<CardFace> faces = new ArrayList<>();
        if (mayView) {
            faces.add(face(card.getCurrentState(), game));
            if (card.isSplitCard() && card.hasLeftSplitState() && card.hasRightSplitState()) {
                faces.add(face(card.getLeftSplitState(), game));
                faces.add(face(card.getRightSplitState(), game));
            } else if (mayFlip) {
                faces.add(face(card.getAlternateState(), game));
            }
        }
        return new Detail(DeltaPacket.makeDeltaKey(DeltaPacket.TYPE_CARD_VIEW, card.getId()), faces);
    }

    private static CardFace face(final CardStateView state, final GameView game) {
        final String pt = state.isCreature() ? state.getPower() + "/" + state.getToughness()
                : state.isPlaneswalker() ? state.getLoyalty()
                : state.isBattle() ? state.getDefense() : null;
        return new CardFace(state.getName(), JsonCodec.manaCost(state.getManaCost()),
                state.getType() == null ? "" : state.getType().toString(), pt,
                CardDetailUtil.composeCardText(state, game, true).trim(), state.getImageKey());
    }
}
