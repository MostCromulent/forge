package forge.adventure;

import forge.gui.GuiBase;

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
public class AdventureDesktopLauncher {
    private static Process adventureProcess;
    private static volatile boolean isRunning = false;
    private static Path argFile = null;

    /**
     * Launches Adventure Mode as a separate process.
     * Also starts the battle host monitor to handle battles with desktop UI.
     *
     * @return true if launch was successful, false otherwise
     */
    public static boolean launch() {
        if (isRunning) {
            System.out.println("Adventure Mode is already running");
            return false;
        }

        try {
            // Set the flag to indicate desktop adventure mode is active
            GuiBase.setDesktopAdventureMode(true);

            // Start the battle monitor to handle battles with desktop UI
            DesktopAdventureBattleHost.startMonitoring();

            // Build the command to launch Adventure
            List<String> command = buildLaunchCommand();

            if (command == null) {
                System.err.println("Failed to build Adventure launch command");
                cleanup();
                return false;
            }

            System.out.println("Launching Adventure Mode with command: " + String.join(" ", command));

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.inheritIO();  // Redirect IO to parent process for debugging
            pb.directory(new File(System.getProperty("user.dir")));

            // Set environment variable to indicate desktop adventure mode
            pb.environment().put("FORGE_DESKTOP_ADVENTURE", "true");

            adventureProcess = pb.start();
            isRunning = true;

            // Monitor for process exit
            new Thread(() -> {
                try {
                    int exitCode = adventureProcess.waitFor();
                    System.out.println("Adventure Mode exited with code: " + exitCode);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    cleanup();
                }
            }, "Adventure-Monitor").start();

            System.out.println("Adventure Mode launched successfully");
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
    private static List<String> buildLaunchCommand() {
        List<String> command = new ArrayList<>();

        // Find Java executable
        String javaHome = System.getProperty("java.home");
        String javaBin = javaHome + File.separator + "bin" + File.separator + "java";
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            javaBin += ".exe";
        }
        command.add(javaBin);

        // JVM arguments
        command.add("-Xmx4g");
        command.add("-DFORGE_DESKTOP_ADVENTURE=true");

        // Try to find the fat jar first (most reliable approach)
        File fatJar = findFatJar();
        if (fatJar != null) {
            System.out.println("Using fat jar: " + fatJar.getAbsolutePath());
            command.add("-jar");
            command.add(fatJar.getAbsolutePath());
        } else {
            // Fall back to classpath approach
            System.out.println("Fat jar not found, using classpath approach");
            String classpath = buildFullClasspath();

            // Use argument file to avoid Windows command line limits
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
        }

        return command;
    }

    /**
     * Finds the forge-gui-mobile-dev fat jar (jar-with-dependencies).
     * This jar bundles all dependencies including LibGDX, making it the most reliable
     * way to launch Adventure Mode.
     *
     * @return the fat jar File, or null if not found
     */
    private static File findFatJar() {
        String workingDir = System.getProperty("user.dir");
        File projectRoot = findProjectRoot(workingDir);

        if (projectRoot == null) {
            return null;
        }

        File targetDir = new File(projectRoot, "forge-gui-mobile-dev" + File.separator + "target");
        if (!targetDir.isDirectory()) {
            return null;
        }

        // Look for *-jar-with-dependencies.jar
        File[] jars = targetDir.listFiles((dir, name) ->
            name.startsWith("forge-gui-mobile-dev-") &&
            name.endsWith("-jar-with-dependencies.jar"));

        if (jars != null && jars.length > 0) {
            // Return the most recently modified one
            File newest = jars[0];
            for (File jar : jars) {
                if (jar.lastModified() > newest.lastModified()) {
                    newest = jar;
                }
            }
            return newest;
        }

        return null;
    }

    /**
     * Builds the full classpath including mobile modules and their dependencies.
     * This is necessary because the desktop process classpath doesn't include
     * forge-gui-mobile and forge-gui-mobile-dev modules needed for Adventure mode.
     */
    private static String buildFullClasspath() {
        StringBuilder cp = new StringBuilder();
        String pathSep = File.pathSeparator;

        // Start with current classpath
        cp.append(System.getProperty("java.class.path"));

        // Find project root (go up from working dir or detect from classpath)
        String workingDir = System.getProperty("user.dir");
        File projectRoot = findProjectRoot(workingDir);

        if (projectRoot != null) {
            // Add mobile module target directories
            addIfExists(cp, pathSep, projectRoot, "forge-gui-mobile/target/classes");
            addIfExists(cp, pathSep, projectRoot, "forge-gui-mobile-dev/target/classes");

            // Add mobile resources
            addIfExists(cp, pathSep, projectRoot, "forge-gui-mobile-dev/src/main/resources");
        }

        // Add Maven dependencies required by mobile modules
        addMavenDependencies(cp, pathSep);

        return cp.toString();
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
     * Adds Maven dependencies required by mobile modules (LibGDX, LWJGL, etc.)
     */
    private static void addMavenDependencies(StringBuilder cp, String pathSep) {
        String m2Repo = System.getProperty("user.home") + File.separator + ".m2" + File.separator + "repository";

        // LibGDX core dependencies
        addMavenJar(cp, pathSep, m2Repo, "com/badlogicgames/gdx/gdx/1.13.5/gdx-1.13.5.jar");
        addMavenJar(cp, pathSep, m2Repo, "com/badlogicgames/gdx/gdx-backend-lwjgl3/1.13.5/gdx-backend-lwjgl3-1.13.5.jar");
        addMavenJar(cp, pathSep, m2Repo, "com/badlogicgames/gdx/gdx-platform/1.13.5/gdx-platform-1.13.5-natives-desktop.jar");
        addMavenJar(cp, pathSep, m2Repo, "com/badlogicgames/gdx/gdx-freetype/1.13.5/gdx-freetype-1.13.5.jar");
        addMavenJar(cp, pathSep, m2Repo, "com/badlogicgames/gdx/gdx-freetype-platform/1.13.5/gdx-freetype-platform-1.13.5-natives-desktop.jar");
        addMavenJar(cp, pathSep, m2Repo, "com/badlogicgames/gdx/gdx-box2d/1.13.5/gdx-box2d-1.13.5.jar");
        addMavenJar(cp, pathSep, m2Repo, "com/badlogicgames/gdx/gdx-box2d-platform/1.13.5/gdx-box2d-platform-1.13.5-natives-desktop.jar");

        // Controllers
        addMavenJar(cp, pathSep, m2Repo, "com/badlogicgames/gdx-controllers/gdx-controllers-core/2.2.4/gdx-controllers-core-2.2.4.jar");
        addMavenJar(cp, pathSep, m2Repo, "com/badlogicgames/gdx-controllers/gdx-controllers-desktop/2.2.4/gdx-controllers-desktop-2.2.4.jar");

        // LWJGL3 native support
        addMavenJar(cp, pathSep, m2Repo, "org/lwjgl/lwjgl/3.3.5/lwjgl-3.3.5.jar");
        addMavenJar(cp, pathSep, m2Repo, "org/lwjgl/lwjgl-glfw/3.3.5/lwjgl-glfw-3.3.5.jar");
        addMavenJar(cp, pathSep, m2Repo, "org/lwjgl/lwjgl-opengl/3.3.5/lwjgl-opengl-3.3.5.jar");
        addMavenJar(cp, pathSep, m2Repo, "org/lwjgl/lwjgl-openal/3.3.5/lwjgl-openal-3.3.5.jar");
        addMavenJar(cp, pathSep, m2Repo, "org/lwjgl/lwjgl-stb/3.3.5/lwjgl-stb-3.3.5.jar");

        // OSHI for hardware info
        addMavenJar(cp, pathSep, m2Repo, "com/github/oshi/oshi-core/6.9.0/oshi-core-6.9.0.jar");

        // Commons CLI
        addMavenJar(cp, pathSep, m2Repo, "commons-cli/commons-cli/1.9.0/commons-cli-1.9.0.jar");
    }

    /**
     * Adds a Maven jar to the classpath if it exists.
     */
    private static void addMavenJar(StringBuilder cp, String pathSep, String m2Repo, String jarPath) {
        File jar = new File(m2Repo, jarPath);
        if (jar.exists()) {
            cp.append(pathSep).append(jar.getAbsolutePath());
        }
    }

    /**
     * Cleanup when Adventure mode closes.
     */
    private static void cleanup() {
        isRunning = false;
        adventureProcess = null;
        GuiBase.setDesktopAdventureMode(false);
        DesktopAdventureBattleHost.stopMonitoring();
        IAdventureBattleHost.cleanupIpcFiles();
        // Clean up argument file
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
