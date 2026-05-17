package forge.installer;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class ForgePriorInstallDetector {

    private static final String[] LAUNCHER_NAMES = {
        "forge.exe", "forge.cmd", "forge.sh", "forge.command",
        "forge-adventure.exe", "forge-adventure.cmd", "forge-adventure.sh", "forge-adventure.command",
        "adventure-editor.exe", "adventure-editor.cmd", "adventure-editor.sh", "adventure-editor.command"
    };

    private static final String PROFILE_FILE = "forge.profile.properties";

    private static final String[] PROFILE_PATH_KEYS = {
        "userDir", "cacheDir", "cardPicsDir", "decksDir", "decksConstructedDir"
    };

    private ForgePriorInstallDetector() {}

    public static boolean detect(final File dir) {
        if (dir == null || !dir.isDirectory()) {
            return false;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir.toPath(),
                "forge-gui-desktop-*-jar-with-dependencies.jar")) {
            if (stream.iterator().hasNext()) {
                return true;
            }
        } catch (Exception ignore) {
            // fall through
        }
        for (final String name : LAUNCHER_NAMES) {
            if (new File(dir, name).isFile()) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasInternalUserData(final File installDir) {
        if (installDir == null || !installDir.isDirectory()) {
            return false;
        }
        final File props = new File(installDir, PROFILE_FILE);
        if (!props.isFile()) {
            return false;
        }
        final Properties p = new Properties();
        try (InputStream in = new FileInputStream(props)) {
            p.load(in);
        } catch (Exception e) {
            return false;
        }
        final String installCanonical;
        try {
            installCanonical = installDir.getCanonicalPath();
        } catch (Exception e) {
            return false;
        }
        for (final String key : PROFILE_PATH_KEYS) {
            final String value = p.getProperty(key, "").trim();
            if (value.isEmpty()) {
                continue;
            }
            final File resolved = new File(value).isAbsolute() ? new File(value) : new File(installDir, value);
            try {
                final String resolvedCanonical = resolved.getCanonicalPath();
                if (resolvedCanonical.equals(installCanonical)
                        || resolvedCanonical.startsWith(installCanonical + File.separator)) {
                    return true;
                }
            } catch (Exception ignore) {
                // skip this key
            }
        }
        return false;
    }
}
