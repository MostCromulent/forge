package forge.net;

import forge.game.Game;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.gamemodes.match.HostedMatch;
import forge.util.IHasForgeLog;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Test-only side channel that lets a remote client ask the host's AI what it would
 * play, without any game state crossing the channel: the request is a player view id,
 * the reply is a card id plus the chosen ability's description. The client then acts
 * on the answer over the real game protocol, so the wire path stays fully exercised.
 *
 * <p>The query runs the real {@link forge.ai.PlayerControllerAi} against the host's
 * {@link Game} inside {@link Player#runWithController}, the same temporary-controller
 * mechanism production uses for dev-mode "ask AI" and InputPayMana's Auto button. The
 * player stays human-controlled throughout.
 *
 * <p>Protocol: one line {@code ADVICE <playerViewId>} per request; reply is one line,
 * either {@code CARD <cardId>|<abilityDescription>} or {@code PASS}.
 */
public final class AdviceServer implements AutoCloseable, IHasForgeLog {

    private final ServerSocket serverSocket;
    private volatile boolean closed;

    public AdviceServer(final int port) throws IOException {
        serverSocket = new ServerSocket(port);
        final Thread acceptor = new Thread(this::acceptLoop, "AdviceServer-Accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public int getPort() {
        return serverSocket.getLocalPort();
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                final Socket socket = serverSocket.accept();
                final Thread handler = new Thread(() -> handle(socket),
                        "AdviceServer-" + socket.getPort());
                handler.setDaemon(true);
                handler.start();
            } catch (IOException e) {
                if (!closed) {
                    netLog.warn("AdviceServer accept failed: {}", e.getMessage());
                }
                return;
            }
        }
    }

    private void handle(Socket socket) {
        try (socket;
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8)) {
            String line;
            while ((line = in.readLine()) != null) {
                out.println(respond(line));
            }
        } catch (IOException e) {
            // client went away; nothing to do
        }
    }

    /**
     * Serialized across connections. Only one input is ever outstanding game-wide (the
     * game loop parks on each in turn), so only one client can legitimately be asking,
     * but a stale watchdog on a client could overlap a fresh request.
     */
    private synchronized String respond(String request) {
        if (!request.startsWith("ADVICE ")) {
            return "PASS";
        }
        final int playerId;
        try {
            playerId = Integer.parseInt(request.substring("ADVICE ".length()).trim());
        } catch (NumberFormatException e) {
            return "PASS";
        }
        final HostedMatch match = HeadlessGuiDesktop.getLastMatch();
        final Game game = match == null ? null : match.getGame();
        if (game == null || game.isGameOver()) {
            return "PASS";
        }
        Player target = null;
        for (final Player p : game.getPlayers()) {
            if (p.getView().getId() == playerId) {
                target = p;
                break;
            }
        }
        if (target == null) {
            return "PASS";
        }
        final Player player = target;
        final CompletableFuture<String> result = new CompletableFuture<>();
        // On the game pool, as production runs AI work while an input has the game
        // loop parked (InputPayMana.onOk). The asking client is the one being waited
        // on, so the engine stays quiet until it acts on the answer.
        game.getAction().invoke(() -> {
            try {
                final forge.ai.PlayerControllerAi ai = new forge.ai.PlayerControllerAi(
                        game, player, player.getOriginalLobbyPlayer());
                player.runWithController(() -> {
                    final List<SpellAbility> sas = ai.chooseSpellAbilityToPlay();
                    final SpellAbility chosen = sas == null || sas.isEmpty() ? null : sas.get(0);
                    if (chosen == null || chosen.getHostCard() == null) {
                        result.complete("PASS");
                    } else {
                        result.complete("CARD " + chosen.getHostCard().getId() + "|"
                                + chosen.toUnsuppressedString().replace('\n', ' ').replace('\r', ' '));
                    }
                }, ai);
            } catch (Exception e) {
                netLog.warn("AdviceServer query failed: {}", e.getMessage());
                result.complete("PASS");
            }
        });
        try {
            final String reply = result.get(15, TimeUnit.SECONDS);
            netLog.info("[Advice] player {} -> {}", playerId, reply);
            return reply;
        } catch (Exception e) {
            netLog.warn("AdviceServer query timed out or failed: {}", e.getMessage());
            return "PASS";
        }
    }

    @Override
    public void close() {
        closed = true;
        try {
            serverSocket.close();
        } catch (IOException e) {
            // shutting down anyway
        }
    }
}
