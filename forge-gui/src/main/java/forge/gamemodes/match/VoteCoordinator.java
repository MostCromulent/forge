package forge.gamemodes.match;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.VoteChoice;
import forge.game.VoteKind;
import forge.game.VoteSession;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.gui.interfaces.IGuiGame;
import forge.player.PlayerControllerHuman;

/**
 * Orchestrates draw-offer votes end-to-end (build, collect, resolve, apply) and provides the
 * shared tally {@link #broadcast} used by every vote kind. Next-game votes are driven by
 * {@link HostedMatch} but reuse {@link #broadcast}, {@link VoteSession}, and {@link NextGameVoteResolver}.
 */
public final class VoteCoordinator {
    private VoteCoordinator() {}

    /** A player activated Offer Draw. Ignored if a viable offer is already in flight. */
    public static synchronized void offerDraw(final Game game, final Player offerer) {
        if (game.isGameOver() || offerer == null) {
            return;
        }
        final VoteSession existing = game.getActiveVote();
        if (existing != null) {
            final boolean offererPresent = game.getPlayers().contains(existing.getInitiator());
            final boolean anyResponderPresent = existing.getVotes().keySet().stream().anyMatch(game.getPlayers()::contains);
            if (offererPresent && anyResponderPresent) {
                return; // a viable offer is already in flight
            }
            game.setActiveVote(null); // stale (offerer/responders left) — supersede it
        }
        final List<Player> responders = new ArrayList<>(game.getPlayers());
        responders.remove(offerer);
        if (responders.isEmpty()) {
            return;
        }
        final VoteSession offer = new VoteSession(VoteKind.DRAW_OFFER, offerer, responders);
        game.setActiveVote(offer);

        for (final Player p : responders) {
            if (p.getController() instanceof PlayerControllerAi ai) {
                offer.record(p, ai.acceptsDrawOffer() ? VoteChoice.ACCEPT : VoteChoice.DECLINE);
            }
        }

        broadcast(game, offer, null);
        resolveDraw(game, offer);
    }

    /** A human responder voted on the in-flight draw offer. */
    public static synchronized void respondDraw(final Game game, final Player responder, final VoteChoice choice) {
        final VoteSession offer = game.getActiveVote();
        if (offer == null || offer.getKind() != VoteKind.DRAW_OFFER || game.isGameOver()) {
            return; // stale / already settled
        }
        offer.record(responder, choice);
        broadcast(game, offer, null);
        resolveDraw(game, offer);
    }

    private static void resolveDraw(final Game game, final VoteSession offer) {
        final VoteOutcome outcome = new DrawVoteResolver().resolve(offer);
        if (outcome == null) {
            return;
        }
        if (game.isGameOver()) {
            game.setActiveVote(null);
            return;
        }
        broadcast(game, offer, outcome);
        game.setActiveVote(null);
        if (outcome != VoteOutcome.ACCEPTED) {
            return;
        }
        // Atomic: only now do we touch outcomes. Apply to every alive player, then end directly.
        for (final Player p : game.getPlayers()) {
            p.intentionalDraw();
        }
        game.setGameOver(GameEndReason.Draw);
        releaseInputQueues(game);
    }

    /** Build a {@link VoteTally} for {@code session} and push it to every human GUI. Shared by all vote kinds. */
    public static void broadcast(final Game game, final VoteSession session, final VoteOutcome outcome) {
        // Draw offers run mid-game: drop any responder who has left since the offer was made.
        // Next-game votes run after the game is over, so their voter set is fixed — leave it intact.
        if (session.getKind() == VoteKind.DRAW_OFFER) {
            session.getVotes().keySet().removeIf(p -> !game.getPlayers().contains(p));
        }

        final List<VoteTally.Entry> entries = new ArrayList<>();
        for (final Map.Entry<Player, VoteChoice> e : session.getVotes().entrySet()) {
            entries.add(new VoteTally.Entry(e.getKey().getView(), e.getValue()));
        }
        final PlayerView initiatorView = session.getInitiator() == null ? null : session.getInitiator().getView();
        final VoteTally tally = new VoteTally(session.getKind(), initiatorView, entries, outcome);
        for (final Player p : game.getRegisteredPlayers()) {
            if (p.getController() instanceof PlayerControllerHuman pch) {
                final IGuiGame gui = pch.getGui();
                if (gui != null) {
                    gui.updateVoteTally(tally);
                }
            }
        }
    }

    /** Mirror concede: remote-client controllers have no event handler, so release their input queues. */
    private static void releaseInputQueues(final Game game) {
        if (!game.isGameOver()) {
            return;
        }
        for (final Player p : game.getRegisteredPlayers()) {
            if (p.getController() instanceof PlayerControllerHuman pch) {
                pch.getInputQueue().onGameOver(true);
            }
        }
    }
}
