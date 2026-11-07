package forge.app;

import forge.adventure.IAdventureBattleHost;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;

/** Starts the web GUI's server beside adventure, so each battle is played in the browser. The server stops when adventure exits. */
final class WebBattleServer {
    private WebBattleServer() {}

    /** Whether the server process started. */
    static boolean start() {
        final File jar = webJar();
        if (!jar.isFile()) {
            System.err.println("Adventure: no web GUI jar at " + jar + ". Build forge-gui-web, or set -Dforge.web.jar.");
            return false;
        }
        final String dir = IAdventureBattleHost.newLaunchDir();
        IAdventureBattleHost.setIpcDir(dir);
        final ProcessBuilder builder = new ProcessBuilder(
                Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx4g", "-Dio.netty.tryReflectionSetAccessible=true", "-Dfile.encoding=UTF-8",
                "-jar", jar.getAbsolutePath());
        builder.directory(jar.getParentFile());
        builder.inheritIO();
        builder.environment().put(IAdventureBattleHost.IPC_DIR_ENV, dir);
        try {
            final Process server = builder.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                server.destroy();
                IAdventureBattleHost.purgeIpcDir();
            }, "WebBattleServerStop"));
            return true;
        } catch (final IOException e) {
            System.err.println("Adventure: could not start the web GUI: " + e.getMessage());
            return false;
        }
    }

    /** The jar built by forge-gui-web in the same checkout as this module's jar, unless forge.web.jar names another. */
    private static File webJar() {
        final String configured = System.getProperty("forge.web.jar");
        if (configured != null) {
            return new File(configured);
        }
        try {
            final File own = new File(WebBattleServer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            // own is forge-gui-mobile-dev/target/<jar> or forge-gui-mobile-dev/target/classes
            File checkout = own.getParentFile();
            while (checkout != null && !new File(checkout, "forge-gui-web").isDirectory()) {
                checkout = checkout.getParentFile();
            }
            if (checkout != null) {
                return new File(checkout, "forge-gui-web/target/forge-gui-web.jar");
            }
        } catch (final Exception ignored) {
            // Falls through to the working directory
        }
        return new File("../forge-gui-web/target/forge-gui-web.jar");
    }
}
