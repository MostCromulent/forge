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
    /** How long the process waits after the last browser goes. The status window is the way to end it sooner. */
    private static final long IDLE_MILLIS = 15_000;

    private WebMain() {}

    public static void main(final String[] args) throws Exception {
        final WebGuiBase ui = new WebGuiBase();
        GuiBase.setInterface(ui);
        FModel.initialize(null, prefs -> null);
        final CountDownLatch quit = new CountDownLatch(1);
        final WebSessions sessions = new WebSessions(ui, IDLE_MILLIS, quit::countDown);
        StatusWindow window = null;
        try (WebServer server = new WebServer(sessions, newToken())) {
            sessions.setServer(server);
            System.out.println("Forge web UI: " + server.url());
            // The browser is the whole interface, so without this there is nothing to show the game is running
            window = StatusWindow.open(server.url(), ui, quit::countDown);
            if (!Boolean.getBoolean("forge.web.noBrowser")) {
                openBrowser(server.url(), ui);
            }
            quit.await();
        } finally {
            if (window != null) {
                window.close();
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
