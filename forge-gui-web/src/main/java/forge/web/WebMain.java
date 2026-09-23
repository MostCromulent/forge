package forge.web;

import forge.gui.GuiBase;
import forge.model.FModel;
import org.tinylog.Logger;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;

public final class WebMain {
    /** How long the process waits after the last browser goes. The console is the way to end it sooner. */
    private static final long IDLE_MILLIS = 15_000;

    private WebMain() {}

    public static void main(final String[] args) throws Exception {
        final WebGuiBase ui = new WebGuiBase();
        GuiBase.setInterface(ui);
        final CountDownLatch quit = new CountDownLatch(1);
        // Opened before the cards are read, because reading them takes long enough to look like a failure
        final ServerConsole console = ServerConsole.open(quit::countDown);
        FModel.initialize(console, prefs -> null);
        final WebSessions sessions = new WebSessions(ui, IDLE_MILLIS, quit::countDown);
        // The console's own link is the host's; the addresses it lists are for other players, so they carry the guest token
        final String guestToken = newToken();
        try (WebServer server = new WebServer(sessions, newToken(), guestToken)) {
            sessions.setServer(server);
            System.out.println("Forge web UI: " + server.url());
            if (console != null) {
                console.serving(server.url(), server.port(), guestToken, ui);
                sessions.visibleElsewhere();
            }
            if (!Boolean.getBoolean("forge.web.noBrowser")) {
                openBrowser(server.url(), ui);
            }
            quit.await();
        } finally {
            if (console != null) {
                console.close();
            }
        }
        // Engine and netplay threads are not all daemons
        System.exit(0);
    }

    private static String newToken() {
        final byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    static void openBrowser(final String url, final WebGuiBase ui) {
        for (final String exe : appModeBrowsers()) {
            if (new File(exe).isFile()) {
                try {
                    // --app gives a window without tabs or an address bar
                    new ProcessBuilder(exe, "--app=" + url).start();
                    return;
                } catch (final IOException e) {
                    Logger.warn(e, "Could not start {}", exe);
                }
            }
        }
        try {
            ui.browseToUrl(url);
        } catch (final IOException | URISyntaxException e) {
            Logger.warn(e, "Open {} in a browser", url);
        }
    }

    private static List<String> appModeBrowsers() {
        final List<String> candidates = new ArrayList<>();
        final String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            for (final String base : new String[]{System.getenv("ProgramFiles(x86)"), System.getenv("ProgramFiles"), System.getenv("LOCALAPPDATA")}) {
                if (base != null) {
                    candidates.add(base + "\\Microsoft\\Edge\\Application\\msedge.exe");
                    candidates.add(base + "\\Google\\Chrome\\Application\\chrome.exe");
                }
            }
        } else if (os.contains("mac")) {
            candidates.add("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
            candidates.add("/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge");
            candidates.add("/Applications/Chromium.app/Contents/MacOS/Chromium");
        } else {
            for (final String dir : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
                for (final String name : new String[]{"chromium", "chromium-browser", "google-chrome", "microsoft-edge"}) {
                    candidates.add(dir + File.separator + name);
                }
            }
        }
        return candidates;
    }
}
