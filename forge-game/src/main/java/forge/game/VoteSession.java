package forge.game;

import java.util.LinkedHashMap;
import java.util.Map;

import forge.game.player.Player;

/**
 * Transient state of an in-flight multiplayer vote: its {@link VoteKind}, an optional initiator
 * (who accepts/abstains implicitly), and each voter's {@link VoteChoice}. Pure data — no GUI or
 * network references. Resolution rules live in forge-gui's VoteResolver implementations.
 */
public class VoteSession {
    private final VoteKind kind;
    private final Player initiator;
    private final Map<Player, VoteChoice> votes = new LinkedHashMap<>();

    public VoteSession(final VoteKind kind, final Player initiator, final Iterable<Player> voters) {
        this.kind = kind;
        this.initiator = initiator;
        for (final Player p : voters) {
            votes.put(p, VoteChoice.PENDING);
        }
    }

    public VoteKind getKind() { return kind; }
    public Player getInitiator() { return initiator; }
    public Map<Player, VoteChoice> getVotes() { return votes; }

    public void record(final Player voter, final VoteChoice choice) {
        if (votes.containsKey(voter)) {
            votes.put(voter, choice);
        }
    }

    public boolean isPending(final Player voter) {
        return votes.get(voter) == VoteChoice.PENDING;
    }

    public boolean allVoted() {
        if (votes.isEmpty()) {
            return false;
        }
        for (final VoteChoice v : votes.values()) {
            if (v == VoteChoice.PENDING) {
                return false;
            }
        }
        return true;
    }
}
