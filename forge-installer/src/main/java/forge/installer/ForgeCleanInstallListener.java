package forge.installer;

import com.izforge.izpack.api.data.InstallData;
import com.izforge.izpack.api.data.Pack;
import com.izforge.izpack.api.exception.IzPackException;
import com.izforge.izpack.event.AbstractProgressInstallerListener;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

public class ForgeCleanInstallListener extends AbstractProgressInstallerListener {

    public static final String WIPE_VARIABLE = "forge.wipe.previous.install";

    private static final String KEEP_FILE = "forge.profile.properties";

    public ForgeCleanInstallListener(final InstallData installData) {
        super(installData);
    }

    @Override
    public void beforePacks(final List<Pack> packs) {
        final InstallData data = getInstallData();
        if (!Boolean.parseBoolean(data.getVariable(WIPE_VARIABLE))) {
            return;
        }
        final File installDir = new File(data.getInstallPath());
        if (!ForgePriorInstallDetector.detect(installDir)) {
            return;
        }
        try {
            wipeExcept(installDir.toPath(), KEEP_FILE);
        } catch (IOException e) {
            throw new IzPackException("Failed to clean previous Forge install: " + e.getMessage(), e);
        }
    }

    private static void wipeExcept(final Path root, final String keepFile) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                if (file.getParent().equals(root) && file.getFileName().toString().equals(keepFile)) {
                    return FileVisitResult.CONTINUE;
                }
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(final Path dir, final IOException exc) throws IOException {
                if (dir.equals(root)) {
                    return FileVisitResult.CONTINUE;
                }
                try {
                    Files.delete(dir);
                } catch (DirectoryNotEmptyException ignore) {
                    // keepFile resides here — leave the directory in place
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
