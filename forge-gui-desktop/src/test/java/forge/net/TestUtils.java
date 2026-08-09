package forge.net;

import forge.StaticData;
import forge.util.IHasForgeLog;
import forge.gamemodes.net.NetworkChecksumUtil;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgeNetPreferences;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Bootstrap and shared utilities for network test infrastructure.
 * {@link #ensureFModelInitialized()} is the entry point — all test classes call it
 * to set up HeadlessGuiDesktop, load card data, and configure test preferences.
 */
public final class TestUtils {

    private TestUtils() {} // Utility class

    /**
     * Format bytes in human-readable form (B, KB, MB, GB).
     */
    public static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        } else if (bytes < 1024L * 1024L * 1024L) {
            return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
        } else {
            return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
    }

    /**
     * Ensure FModel is initialized with HeadlessGuiDesktop for testing.
     * Thread-safe. Always ensures HeadlessGuiDesktop is active, even if another
     * test class set a different GuiBase interface (e.g. GuiDesktop).
     */
    public static synchronized void ensureFModelInitialized() {
        if (!(GuiBase.getInterface() instanceof HeadlessGuiDesktop)) {
            GuiBase.setInterface(new HeadlessGuiDesktop());
        }
        if (StaticData.instance() == null) {
            FModel.initialize(null, preferences -> {
                preferences.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
                preferences.setPref(FPref.UI_LANGUAGE, "en-US");
                preferences.setPref(FPref.ENFORCE_DECK_LEGALITY, false);
                FModel.getNetPreferences().setPref(ForgeNetPreferences.FNetPref.UPnP, "NEVER");
                return null;
            });
        }
        // Always ensure runtime test preferences regardless of initialization order —
        // another test class may have initialized FModel before us
        FModel.getPreferences().setPref(FPref.ENFORCE_DECK_LEGALITY, false);
        FModel.getNetPreferences().setPref(ForgeNetPreferences.FNetPref.NET_BANDWIDTH_LOGGING, true);
        FModel.getNetPreferences().setPref(ForgeNetPreferences.FNetPref.UPnP, "NEVER");

        // Use -Dforge.checksum.mode=production to test with production (sampled) checksum
        boolean useStable = !"production".equalsIgnoreCase(System.getProperty("forge.checksum.mode"));
        NetworkChecksumUtil.setStableChecksum(useStable);

        IHasForgeLog.netLog.info("[TestConfig] checksum={}, remote={}",
                useStable ? "stable" : "sampled",
                useAiForRemotePlayers() ? "ai" : "human");
    }

    /**
     * The configuration a spawned JVM needs in order to be set up like this one.
     *
     * <p>A batch spawns a JVM per game, and each of those spawns one per remote client, so a
     * property has two hops to survive. Anything that reaches only the first hop puts a client
     * in a different configuration from the server it is talking to — a client computing a
     * different kind of checksum disagrees with its server on every packet it checks, which
     * reads as thousands of desyncs and is nothing of the sort. One list, used by both, so the
     * two cannot drift apart again.
     */
    public static List<String> childJvmProperties() {
        final List<String> args = new ArrayList<>();
        for (final String name : List.of(
                "forge.checksum.mode",
                "forge.snapshot.crosscheck",
                "forge.snapshot.skewPasses",
                "test.useAiForRemote")) {
            final String value = System.getProperty(name);
            if (value != null) {
                args.add("-D" + name + "=" + value);
            }
        }
        return args;
    }

    /**
     * Whether a batch replaces its remote players with server-side AI once the game starts.
     *
     * <p>Off by default, so remote players stay human and the server has to ask them
     * questions over the wire. The AI swap is faster and was the default for a long time, but
     * a converted player is decided inside the server, so nothing is ever sent to a client:
     * no prompt, no card in a protocol argument, and none of the display or input paths. It
     * has hidden real defects that way. {@code -Dtest.useAiForRemote=true} opts back into it.
     */
    public static boolean useAiForRemotePlayers() {
        return "true".equalsIgnoreCase(System.getProperty("test.useAiForRemote"));
    }
}
