package forge.game.event;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import forge.game.GameOutcome;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.LobbyPlayer;

import com.google.common.collect.Iterables;

/** winsByPlayerId counts the match's games won by each of this game's players, keyed by player id. */
public record GameEventGameOutcome(int lastTurnNumber, List<String> outcomeStrings, String winningPlayerName, String matchSummary,
        Map<Integer, Integer> winsByPlayerId) implements GameEvent {

    public GameEventGameOutcome(GameOutcome result, Collection<GameOutcome> history, Iterable<Player> players) {
        this(result.getLastTurnNumber(),
             result.getOutcomeStrings(),
             computeWinningPlayerName(result),
             computeMatchSummary(history),
             computeWins(history, players));
    }

    private static Map<Integer, Integer> computeWins(Collection<GameOutcome> history, Iterable<Player> players) {
        final Map<Integer, Integer> wins = new LinkedHashMap<>();
        for (Player p : players) {
            wins.put(p.getId(), (int) history.stream().filter(go -> go.getWinningPlayer() == p.getRegisteredPlayer()).count());
        }
        return wins;
    }

    private static String computeWinningPlayerName(GameOutcome result) {
        LobbyPlayer winner = result.getWinningLobbyPlayer();
        return winner != null ? winner.getName() : null;
    }

    private static String computeMatchSummary(Collection<GameOutcome> history) {
        final GameOutcome outcome1 = Iterables.getFirst(history, null);
        if (outcome1 == null) return "";
        final HashMap<RegisteredPlayer, String> players = outcome1.getPlayerNames();
        final Map<RegisteredPlayer, Long> winCount = history.stream().filter(go -> go.getWinningPlayer() != null).collect(Collectors.groupingBy(GameOutcome::getWinningPlayer, Collectors.counting()));

        final StringBuilder sb = new StringBuilder();
        for (Entry<RegisteredPlayer, String> entry : players.entrySet()) {
            sb.append(entry.getValue()).append(": ").append(winCount.getOrDefault(entry.getKey(), 0l)).append(" ");
        }
        return sb.toString();
    }

    @Override
    public <T> T visit(IGameEventVisitor<T> visitor) {
        return visitor.visit(this);
    }

    /* (non-Javadoc)
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return "Game Outcome: " + outcomeStrings;
    }
}
