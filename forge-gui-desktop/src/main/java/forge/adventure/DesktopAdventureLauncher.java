package forge.adventure;

import forge.screens.home.adventure.VSubmenuAdventure;

import javax.swing.SwingUtilities;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Launches Adventure Mode from the Desktop GUI.
 *
 * Adventure Mode runs as a separate process (forge-gui-mobile-dev) to avoid
 * class conflicts between desktop Swing and mobile LibGDX toolbox classes.
 *
 * When a battle starts in Adventure, the battle is hosted by this desktop
 * process using the native Swing CMatchUI through IPC (inter-process communication).
 */
public class DesktopAdventureLauncher {
    private static Process adventureProcess;
    private static volatile boolean isRunning = false;
    private static Path argFile = null;
    private static String ipcDir = null;

    // Only needed for the no-manifest classpath fallback below: the fat jar carries Add-Opens in its
    // manifest. This list mirrors mandatory.java.args in forge-gui-mobile-dev/pom.xml.
    private static final String[] ADD_OPENS = {
        "java.desktop/java.beans", "java.desktop/javax.swing.border",
        "java.desktop/javax.swing.event", "java.desktop/sun.swing",
        "java.desktop/java.awt.image", "java.desktop/java.awt.color",
        "java.desktop/sun.awt.image", "java.desktop/javax.swing",
        "java.desktop/java.awt", "java.base/java.util",
        "java.base/java.lang", "java.base/java.lang.reflect",
        "java.base/java.text", "java.desktop/java.awt.font",
        "java.base/jdk.internal.misc", "java.base/sun.nio.ch",
        "java.base/java.nio", "java.base/java.math",
        "java.base/java.util.concurrent", "java.base/java.net"
    };

    /**
     * Launches Adventure Mode as a separate process.
     * Also starts the battle host monitor to handle battles with desktop UI.
     *
     * @return true if launch was successful, false otherwise
     */
    public static boolean launch() {
        if (isRunning) {
            return false;
        }

        try {
            DesktopAdventureMode.activate();

            // Private per-launch IPC directory, shared with the spawned process via the environment.
            ipcDir = IAdventureBattleHost.newLaunchDir();
            IAdventureBattleHost.setIpcDir(ipcDir);

            DesktopAdventureBattleHost.startMonitoring();
            DesktopAdventureBattleHost.setOnBattleStarting(() -> {
                final java.awt.Frame frame = forge.Singletons.getView().getFrame();
                if (frame != null) {
                    if (frame.getState() == java.awt.Frame.ICONIFIED) {
                        frame.setState(java.awt.Frame.NORMAL);
                    }
                    frame.toFront();
                    frame.requestFocus();
                }
            });

            File fatJar = findFatJar();
            List<String> command = buildLaunchCommand(fatJar);

            if (command == null) {
                System.err.println("Failed to build Adventure launch command");
                cleanup();
                return false;
            }

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.inheritIO();
            pb.directory(resolveWorkingDir(fatJar));

            pb.environment().put("FORGE_DESKTOP_ADVENTURE", "true");
            pb.environment().put(IAdventureBattleHost.IPC_DIR_ENV, ipcDir);

            adventureProcess = pb.start();
            isRunning = true;

            new Thread(() -> {
                try {
                    int exitCode = adventureProcess.waitFor();
                    if (exitCode != 0) {
                        System.err.println("Adventure Mode exited with code: " + exitCode);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    cleanup();
                }
            }, "Adventure-Monitor").start();

            return true;

        } catch (IOException e) {
            System.err.println("Failed to launch Adventure Mode: " + e.getMessage());
            e.printStackTrace();
            cleanup();
            return false;
        }
    }

    /**
     * Builds the command line to launch the Adventure Mode process.
     * Prefers using the fat jar (jar-with-dependencies) for reliability.
     * Falls back to classpath approach if fat jar is not found.
     */
    private static List<String> buildLaunchCommand(File fatJar) {
        List<String> command = new ArrayList<>();

        String javaHome = System.getProperty("java.home");
        String javaBin = javaHome + File.separator + "bin" + File.separator + "java";
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            javaBin += ".exe";
        }
        command.add(javaBin);

        // -Xmx and -D flags can't live in the jar manifest, so they're always passed here.
        command.add("-Xmx4g");
        command.add("-DFORGE_DESKTOP_ADVENTURE=true");
        command.add("-Dio.netty.tryReflectionSetAccessible=true");
        command.add("-Dfile.encoding=UTF-8");

        if (fatJar != null) {
            // The fat jar's manifest supplies Add-Opens and Main-Class, so no --add-opens needed here.
            command.add("-jar");
            command.add(fatJar.getAbsolutePath());
            return command;
        }

        // No fat jar (running from a dev build): launch via classpath. Without a manifest the JDK 17+
        // --add-opens must be supplied explicitly.
        for (String opens : ADD_OPENS) {
            command.add("--add-opens");
            command.add(opens + "=ALL-UNNAMED");
        }

        String classpath = buildClasspathFromMavenFile();
        if (classpath == null) {
            System.err.println("Adventure launch: no fat jar found and no forge-gui-mobile-dev/target/mobile-dev-classpath.txt. "
                + "Build forge-gui-mobile-dev first (mvn -pl forge-gui-mobile-dev -am install).");
            return null;
        }

        // Argument file avoids Windows command line length limits
        try {
            argFile = Files.createTempFile("forge-adventure-args", ".txt");
            Files.writeString(argFile, "-cp\n" + classpath + "\nforge.app.Main\n");
            argFile.toFile().deleteOnExit();
            command.add("@" + argFile.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("Failed to create argument file, falling back to direct args: " + e.getMessage());
            command.add("-cp");
            command.add(classpath);
            command.add("forge.app.Main");
        }

        return command;
    }

    /**
     * Finds the forge-gui-mobile-dev fat jar (jar-with-dependencies), which bundles LibGDX and the rest of
     * Adventure's dependencies. Looks in both layouts: a source checkout (forge-gui-mobile-dev/target) and an
     * installer build, where the installer drops the jar in the application directory next to the desktop jar.
     *
     * @return the newest matching fat jar, or null if none is found
     */
    private static File findFatJar() {
        List<File> candidates = new ArrayList<>();

        File projectRoot = findProjectRoot(System.getProperty("user.dir"));
        if (projectRoot != null) {
            candidates.add(new File(projectRoot, "forge-gui-mobile-dev" + File.separator + "target"));
        }
        File appDir = applicationDir();
        if (appDir != null) {
            candidates.add(appDir);
        }
        candidates.add(new File(System.getProperty("user.dir")));

        File newest = null;
        for (File dir : candidates) {
            File jar = newestFatJarIn(dir);
            if (jar != null && (newest == null || jar.lastModified() > newest.lastModified())) {
                newest = jar;
            }
        }
        return newest;
    }

    private static File newestFatJarIn(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return null;
        }
        File[] jars = dir.listFiles((d, name) ->
            name.startsWith("forge-gui-mobile-dev-") &&
            name.endsWith("-jar-with-dependencies.jar"));
        if (jars == null || jars.length == 0) {
            return null;
        }
        File newest = jars[0];
        for (File jar : jars) {
            if (jar.lastModified() > newest.lastModified()) {
                newest = jar;
            }
        }
        return newest;
    }

    /**
     * The directory the running desktop app lives in. In an installer build the desktop jar and the
     * mobile-dev fat jar sit side by side here; in a source checkout this is the target dir (no fat jar) and
     * findFatJar falls back to the module path. Derived from the code source so it doesn't depend on the cwd.
     */
    private static File applicationDir() {
        try {
            File codeSource = new File(DesktopAdventureLauncher.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            return codeSource.isFile() ? codeSource.getParentFile() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Working directory for the spawned Adventure process, chosen so the game resources (res/) resolve.
     * From a source target dir, run from the module so ../forge-gui/res resolves; from an installer build, run
     * from the application directory where res/ sits alongside the jar; with no jar (dev classpath launch),
     * run from the mobile-dev module.
     */
    private static File resolveWorkingDir(File fatJar) {
        if (fatJar != null) {
            File jarDir = fatJar.getParentFile();
            return "target".equals(jarDir.getName()) ? jarDir.getParentFile() : jarDir;
        }
        File projectRoot = findProjectRoot(System.getProperty("user.dir"));
        return projectRoot != null
                ? new File(projectRoot, "forge-gui-mobile-dev")
                : new File(System.getProperty("user.dir"));
    }

    /**
     * Builds the classpath from the Maven-generated dependency list (forge-gui-mobile-dev's
     * dependency:build-classpath execution). Returns null if that file is absent, so the caller can
     * fall back to the fat jar or fail loudly rather than guessing transitive dependencies.
     */
    private static String buildClasspathFromMavenFile() {
        File projectRoot = findProjectRoot(System.getProperty("user.dir"));
        if (projectRoot == null) {
            return null;
        }

        File cpFile = new File(projectRoot, "forge-gui-mobile-dev/target/mobile-dev-classpath.txt");
        if (!cpFile.isFile()) {
            return null;
        }

        try {
            String mavenCp = Files.readString(cpFile.toPath()).trim();
            if (mavenCp.isEmpty()) {
                return null;
            }
            StringBuilder cp = new StringBuilder();
            // mobile-dev's own classes aren't in its dependency list
            addIfExists(cp, "", projectRoot, "forge-gui-mobile-dev/target/classes");
            cp.append(File.pathSeparator).append(mavenCp);
            return cp.toString();
        } catch (IOException e) {
            System.err.println("Failed to read classpath file: " + e.getMessage());
            return null;
        }
    }

    /**
     * Finds the project root directory by looking for the forge module structure.
     */
    private static File findProjectRoot(String startDir) {
        File dir = new File(startDir);
        while (dir != null) {
            if (new File(dir, "forge-gui-mobile").isDirectory() &&
                new File(dir, "forge-gui-desktop").isDirectory()) {
                return dir;
            }
            dir = dir.getParentFile();
        }
        return null;
    }

    /**
     * Adds a path to the classpath if it exists.
     */
    private static void addIfExists(StringBuilder cp, String pathSep, File root, String relativePath) {
        File path = new File(root, relativePath);
        if (path.exists()) {
            cp.append(pathSep).append(path.getAbsolutePath());
        }
    }

    /**
     * Cleanup when Adventure mode closes.
     */
    private static void cleanup() {
        isRunning = false;
        adventureProcess = null;
        DesktopAdventureMode.deactivate();
        DesktopAdventureBattleHost.stopMonitoring();
        IAdventureBattleHost.purgeIpcDir();
        SwingUtilities.invokeLater(() ->
            VSubmenuAdventure.SINGLETON_INSTANCE.setStartButtonRunning(false));
        if (argFile != null) {
            try {
                Files.deleteIfExists(argFile);
            } catch (IOException ignored) {
            }
            argFile = null;
        }
    }

    /**
     * @return true if Adventure mode is currently running
     */
    public static boolean isRunning() {
        return isRunning;
    }

    /**
     * Request the Adventure process to close.
     */
    public static void requestClose() {
        if (adventureProcess != null && adventureProcess.isAlive()) {
            adventureProcess.destroy();
        }
    }

    /**
     * Force-kill the Adventure process.
     */
    public static void forceClose() {
        if (adventureProcess != null && adventureProcess.isAlive()) {
            adventureProcess.destroyForcibly();
        }
        cleanup();
    }
}
