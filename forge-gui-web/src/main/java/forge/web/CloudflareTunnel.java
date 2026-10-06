package forge.web;

import org.tinylog.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs Cloudflare's cloudflared, which calls out to Cloudflare and has it pass players' browsers back down that call,
 * so players on the internet reach this machine without the router letting anyone in.
 */
final class CloudflareTunnel {
    /** Where the program is offered for each system, since Forge does not fetch it itself. */
    static final String DOWNLOADS = "https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/downloads/";

    /** MISSING is waiting for the program to be installed, which is looked for again every few seconds. */
    enum State { OFF, MISSING, CONNECTING, CONNECTED, FAILED }

    private static final Pattern ADDRESS = Pattern.compile("https://[a-z0-9-]+\\.trycloudflare\\.com");
    private static final long LOOK_AGAIN_MILLIS = 3000;
    /** Cloudflare names the address at once but takes up to a minute to start answering at it. */
    private static final long ANSWER_WITHIN_MILLIS = 120_000;

    private final int port;
    private final Consumer<State> listener;
    private final Thread closeWithForge = new Thread(this::end, "ForgeTunnelEnd");
    private volatile File chosen;
    private volatile Process process;
    private volatile String address;
    private volatile boolean closed;

    /** chosen is where the host said the program is, or null to look in the usual places. */
    CloudflareTunnel(final int port, final File chosen, final Consumer<State> listener) {
        this.port = port;
        this.chosen = chosen;
        this.listener = listener;
    }

    /** The program where the host said it is, on the path, or where its installers put it; null when it is in none of them. */
    static File find(final File chosen) {
        if (chosen != null && chosen.isFile()) {
            return chosen;
        }
        final boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        final String name = windows ? "cloudflared.exe" : "cloudflared";
        final List<String> folders = new ArrayList<>(List.of(System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)));
        if (windows) {
            for (final String programs : new String[] {"ProgramFiles(x86)", "ProgramFiles"}) {
                if (System.getenv(programs) != null) {
                    folders.add(System.getenv(programs) + File.separator + "cloudflared");
                }
            }
        } else {
            folders.addAll(List.of("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin"));
        }
        for (final String folder : folders) {
            final File program = new File(folder, name);
            if (!folder.isEmpty() && program.isFile()) {
                return program;
            }
        }
        return null;
    }

    /** Starts on a thread of its own, since finding the program, starting it and waiting for Cloudflare all take their time. */
    void open() {
        Runtime.getRuntime().addShutdownHook(closeWithForge);
        final Thread t = new Thread(this::run, "ForgeTunnel");
        t.setDaemon(true);
        t.start();
    }

    /** The host has pointed at the program, so the wait for it to be installed looks there next. */
    void lookIn(final File program) {
        chosen = program;
    }

    /** The address players open, without a token, or null until Cloudflare is answering at it. */
    String address() {
        return address;
    }

    void close() {
        closed = true;
        end();
        try {
            Runtime.getRuntime().removeShutdownHook(closeWithForge);
        } catch (final IllegalStateException e) {
            // Forge is already closing, and the hook does this itself
        }
    }

    private void end() {
        final Process running = process;
        if (running != null) {
            running.descendants().forEach(ProcessHandle::destroy);
            running.destroy();
        }
    }

    private void tell(final State state) {
        if (!closed) {
            listener.accept(state);
        }
    }

    private void run() {
        try {
            File program = find(chosen);
            if (program == null) {
                tell(State.MISSING);
                while (program == null && !closed) {
                    Thread.sleep(LOOK_AGAIN_MILLIS);
                    program = find(chosen);
                }
            }
            if (closed) {
                return;
            }
            tell(State.CONNECTING);
            // 127.0.0.1 and not localhost, which the program may take for an IPv6 address the server is not bound to
            process = new ProcessBuilder(program.getPath(), "tunnel", "--no-autoupdate", "--url", "http://127.0.0.1:" + port)
                    .redirectErrorStream(true).start();
            final String named = readAddress(process);
            if (named != null && answers(named)) {
                address = named;
                Logger.info("Players on the internet can join through Cloudflare at {}.", named);
                tell(State.CONNECTED);
                process.waitFor();
                address = null;
            }
            if (!closed) {
                Logger.warn("The connection through Cloudflare has ended.");
                tell(State.FAILED);
            }
        } catch (final IOException e) {
            Logger.warn(e, "Could not connect through Cloudflare");
            tell(State.FAILED);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            end();
        }
    }

    /** The program writes the address it was given among its start-up lines; null if it stops without one. */
    private static String readAddress(final Process running) throws IOException {
        final BufferedReader lines = new BufferedReader(new InputStreamReader(running.getInputStream(), StandardCharsets.UTF_8));
        String found = null;
        for (String line = lines.readLine(); line != null && found == null; line = lines.readLine()) {
            final Matcher m = ADDRESS.matcher(line);
            if (m.find()) {
                found = m.group();
            }
        }
        if (found != null) {
            // The program stops if nothing reads what it goes on to write
            final Thread drain = new Thread(() -> {
                try {
                    while (lines.readLine() != null) {
                        // Nothing here is for the host
                    }
                } catch (final IOException e) {
                    // The program has gone
                }
            }, "ForgeTunnelOutput");
            drain.setDaemon(true);
            drain.start();
        }
        return found;
    }

    /** Waits until a request to the address comes back from this server, whose refusal for want of a token is answer enough. */
    private boolean answers(final String named) throws InterruptedException {
        final long giveUp = System.currentTimeMillis() + ANSWER_WITHIN_MILLIS;
        while (!closed && process.isAlive() && System.currentTimeMillis() < giveUp) {
            try {
                final HttpURLConnection c = (HttpURLConnection) new URL(named + "/").openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(5000);
                final int status = c.getResponseCode();
                c.disconnect();
                if (status < 500) {
                    return true;
                }
            } catch (final IOException e) {
                // The name does not resolve yet
            }
            Thread.sleep(1500);
        }
        return false;
    }
}
