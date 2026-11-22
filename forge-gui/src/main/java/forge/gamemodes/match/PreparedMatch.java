package forge.gamemodes.match;

import forge.game.GameRules;
import forge.game.GameType;
import forge.game.player.RegisteredPlayer;

import java.util.List;
import java.util.Set;

/** A match a game mode has built, which a client only has to start. looks is null when the human plays with their own. */
public record PreparedMatch(GameRules rules, Set<GameType> variants, List<RegisteredPlayer> players, RegisteredPlayer human, Looks looks) {
    /** The avatar and sleeve the human has in this match. */
    public record Looks(int avatar, int sleeve) {
    }
}
