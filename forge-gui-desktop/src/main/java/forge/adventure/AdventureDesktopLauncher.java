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
            return false;
        }

        try {
            DesktopAdventureMode.activate();

            // Start the battle monitor to handle battles with desktop UI
            DesktopAdventureBattleHost.startMonitoring();

            // Build the command to launch Adventure
            List<String> command = buildLaunchCommand();

            if (command == null) {
                System.err.println("Failed to build Adventure launch command");
                cleanup();
                return false;
            }

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.inheritIO();  // Redirect IO to parent process for debugging

            // Set working directory to a module subdir so ../forge-gui/ resolves to the resources
            File workDir = new File(System.getProperty("user.dir"));
            File projectRoot = findProjectRoot(workDir.getAbsolutePath());
            if (projectRoot != null) {
                workDir = new File(projectRoot, "forge-gui-mobile-dev");
            }
            pb.directory(workDir);

            // Set environment variable to indicate desktop adventure mode
            pb.environment().put("FORGE_DESKTOP_ADVENTURE", "true");

            adventureProcess = pb.start();
            isRunning = true;

            // Monitor for process exit
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
    private static List<String> buildLaunchCommand() {
        List<String> command = new ArrayList<>();

        // Find Java executable
        String javaHome = System.getProperty("java.home");
        String javaBin = javaHome + File.separator + "bin" + File.separator + "java";
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            javaBin += ".exe";
        }
        command.add(javaBin);

        // JVM arguments — must match mandatory.java.args from forge-gui-mobile-dev pom.xml
        command.add("-Xmx4g");
        command.add("-DFORGE_DESKTOP_ADVENTURE=true");
        command.add("-Dio.netty.tryReflectionSetAccessible=true");
        command.add("-Dfile.encoding=UTF-8");
        // --add-opens required for LWJGL, LibGDX, XStream on JDK 17+
        String[] addOpens = {
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
        for (String opens : addOpens) {
            command.add("--add-opens");
            command.add(opens + "=ALL-UNNAMED");
        }

        // Try to find the fat jar first (most reliable approach)
        File fatJar = findFatJar();
        if (fatJar != null) {
            command.add("-jar");
            command.add(fatJar.getAbsolutePath());
        } else {
            // Fall back to classpath approach
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
     * Prefers the Maven-generated classpath file (created by dependency:build-classpath
     * during forge-gui-mobile-dev build) for accurate transitive dependency resolution.
     */
    private static String buildFullClasspath() {
        String workingDir = System.getProperty("user.dir");
        File projectRoot = findProjectRoot(workingDir);

        if (projectRoot != null) {
            // Try Maven-generated classpath (complete — includes all forge modules + dependencies)
            File cpFile = new File(projectRoot, "forge-gui-mobile-dev/target/mobile-dev-classpath.txt");
            if (cpFile.isFile()) {
                try {
                    String mavenCp = Files.readString(cpFile.toPath()).trim();
                    if (!mavenCp.isEmpty()) {
                        StringBuilder cp = new StringBuilder();
                        // Add mobile-dev classes (the module itself isn't in its own dependency list)
                        addIfExists(cp, "", projectRoot, "forge-gui-mobile-dev/target/classes");
                        cp.append(File.pathSeparator).append(mavenCp);
                        return cp.toString();
                    }
                } catch (IOException e) {
                    System.err.println("Failed to read classpath file: " + e.getMessage());
                }
            }
        }

        // Fallback: desktop classpath + manual deps (less reliable)
        StringBuilder cp = new StringBuilder(System.getProperty("java.class.path"));
        String pathSep = File.pathSeparator;

        if (projectRoot != null) {
            addIfExists(cp, pathSep, projectRoot, "forge-gui-mobile/target/classes");
            addIfExists(cp, pathSep, projectRoot, "forge-gui-mobile-dev/target/classes");
            addIfExists(cp, pathSep, projectRoot, "forge-gui-mobile-dev/src/main/resources");
        }

        System.out.println("WARNING: Maven classpath file not found, using manual dependency resolution");
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
        addMavenArtifact(cp, pathSep, m2Repo, "com/badlogicgames/gdx", "gdx", null);
        addMavenArtifact(cp, pathSep, m2Repo, "com/badlogicgames/gdx", "gdx-backend-lwjgl3", null);
        addMavenArtifact(cp, pathSep, m2Repo, "com/badlogicgames/gdx", "gdx-platform", "natives-desktop");
        addMavenArtifact(cp, pathSep, m2Repo, "com/badlogicgames/gdx", "gdx-freetype", null);
        addMavenArtifact(cp, pathSep, m2Repo, "com/badlogicgames/gdx", "gdx-freetype-platform", "natives-desktop");
        addMavenArtifact(cp, pathSep, m2Repo, "com/badlogicgames/gdx", "gdx-box2d", null);
        addMavenArtifact(cp, pathSep, m2Repo, "com/badlogicgames/gdx", "gdx-box2d-platform", "natives-desktop");

        // Controllers
        addMavenArtifact(cp, pathSep, m2Repo, "com/badlogicgames/gdx-controllers", "gdx-controllers-core", null);
        addMavenArtifact(cp, pathSep, m2Repo, "com/badlogicgames/gdx-controllers", "gdx-controllers-desktop", null);

        // LWJGL3 jars and platform natives
        String[] lwjglModules = {"lwjgl", "lwjgl-glfw", "lwjgl-opengl", "lwjgl-openal", "lwjgl-stb", "lwjgl-jemalloc"};
        String nativesClassifier = getNativesClassifier();
        for (String module : lwjglModules) {
            addMavenArtifact(cp, pathSep, m2Repo, "org/lwjgl", module, null);
            if (nativesClassifier != null) {
                addMavenArtifact(cp, pathSep, m2Repo, "org/lwjgl", module, nativesClassifier);
            }
        }

        // OSHI for hardware info (requires JNA)
        addMavenArtifact(cp, pathSep, m2Repo, "com/github/oshi", "oshi-core", null);
        addMavenArtifact(cp, pathSep, m2Repo, "net/java/dev/jna", "jna", null);
        addMavenArtifact(cp, pathSep, m2Repo, "net/java/dev/jna", "jna-platform", null);

        // Commons CLI
        addMavenArtifact(cp, pathSep, m2Repo, "commons-cli", "commons-cli", null);

        // SLF4J (logging facade used by various dependencies)
        addMavenArtifact(cp, pathSep, m2Repo, "org/slf4j", "slf4j-api", null);
    }

    /**
     * Finds and adds a Maven artifact jar to the classpath by discovering whatever version is installed.
     * This avoids hardcoding version strings that can drift from transitive dependency versions.
     *
     * @param classifier optional classifier (e.g. "natives-desktop"), or null for the main jar
     */
    private static void addMavenArtifact(StringBuilder cp, String pathSep, String m2Repo,
                                          String groupPath, String artifactId, String classifier) {
        File artifactDir = new File(m2Repo, groupPath + File.separator + artifactId);
        if (!artifactDir.isDirectory()) return;

        // Find the newest version directory
        File[] versionDirs = artifactDir.listFiles(File::isDirectory);
        if (versionDirs == null || versionDirs.length == 0) return;

        File versionDir = versionDirs[0];
        for (File d : versionDirs) {
            if (d.lastModified() > versionDir.lastModified()) {
                versionDir = d;
            }
        }

        String version = versionDir.getName();
        String jarName = classifier != null
                ? artifactId + "-" + version + "-" + classifier + ".jar"
                : artifactId + "-" + version + ".jar";
        File jar = new File(versionDir, jarName);
        if (jar.exists()) {
            cp.append(pathSep).append(jar.getAbsolutePath());
        }
    }

    /**
     * Returns the LWJGL natives classifier for the current OS (e.g. "natives-windows").
     */
    private static String getNativesClassifier() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) return "natives-windows";
        if (os.contains("mac")) return "natives-macos";
        if (os.contains("linux")) return "natives-linux";
        return null;
    }

    /**
     * Cleanup when Adventure mode closes.
     */
    private static void cleanup() {
        isRunning = false;
        adventureProcess = null;
        DesktopAdventureMode.deactivate();
        DesktopAdventureBattleHost.stopMonitoring();
        IAdventureBattleHost.cleanupIpcFiles();
        // Re-enable the Start Adventure button on the EDT
        SwingUtilities.invokeLater(() ->
            VSubmenuAdventure.SINGLETON_INSTANCE.getBtnStart().setEnabled(true));
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
