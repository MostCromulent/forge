package forge.gamemodes.rogue;

import forge.web.RogueFixture;
import forge.web.WebTestSupport;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

@Test(singleThreaded = true)
public class RogueSavesTest {
    private static final String RUN = "Aragorn_1.dat";
    private static final String PROGRESS = "meta_progress.dat";
    private Path dir;

    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
        // Only now does ForgeConstants point at the test profile
        dir = Path.of(RogueIO.ROGUE_SAVE_DIR);
    }

    @BeforeMethod
    public void clean() {
        RogueFixture.clean();
    }

    @Test
    public void aRunThatUsedToBeDeletedIsSetAside() throws IOException {
        final byte[] bytes = nonsense();
        Files.write(dir.resolve(RUN), bytes);
        Assert.assertTrue(RogueIO.loadAllRuns().isEmpty());
        Assert.assertFalse(Files.exists(dir.resolve(RUN)));
        final List<Path> broken = filesEndingWith(".broken");
        Assert.assertEquals(broken.size(), 1);
        Assert.assertEquals(Files.readAllBytes(broken.get(0)), bytes);
    }

    @Test
    public void twoBrokenRunsWithOneNameAreBothKept() throws IOException {
        for (int i = 0; i < 2; i++) {
            Files.write(dir.resolve(RUN), nonsense());
            Assert.assertTrue(RogueIO.loadAllRuns().isEmpty());
        }
        Assert.assertEquals(filesEndingWith(".broken").size(), 2);
    }

    @Test
    public void aRunCutShortStaysInPlace() throws IOException {
        cutShort(RogueFixture.install("/rogue/run-master.xml", RUN));
        Assert.assertTrue(RogueIO.loadAllRuns().isEmpty());
        Assert.assertTrue(Files.exists(dir.resolve(RUN)));
        Assert.assertEquals(filesEndingWith(".broken"), List.of());
    }

    @Test
    public void anEmptyFileIsSetAsideAndProgressStartsEmpty() throws IOException {
        Files.createFile(dir.resolve(PROGRESS));
        Assert.assertEquals(RogueMetaProgress.getInstance().getTotalEchoes(), 0);
        Assert.assertEquals(filesEndingWith(".broken").size(), 1);
        Assert.assertFalse(Files.exists(dir.resolve(PROGRESS)));
    }

    @Test
    public void aFileCutShortIsSetAside() throws IOException {
        final Path file = RogueFixture.install("/rogue/meta-master.xml", PROGRESS);
        cutShort(file);
        final byte[] bytes = Files.readAllBytes(file);
        Assert.assertEquals(RogueMetaProgress.getInstance().getTotalEchoes(), 0);
        final List<Path> broken = filesEndingWith(".broken");
        Assert.assertEquals(broken.size(), 1);
        Assert.assertEquals(Files.readAllBytes(broken.get(0)), bytes);
    }

    @Test
    public void anUnreadableFileIsNotTouched() throws IOException {
        Files.createDirectory(dir.resolve(PROGRESS));
        Assert.expectThrows(UncheckedIOException.class, RogueMetaProgress::getInstance);
        Assert.assertEquals(filesEndingWith(""), List.of(dir.resolve(PROGRESS)));
        Assert.assertTrue(Files.isDirectory(dir.resolve(PROGRESS)));
    }

    @Test
    public void theNextOpenLoadsItOnceReadable() throws IOException {
        Files.createDirectory(dir.resolve(PROGRESS));
        Assert.expectThrows(UncheckedIOException.class, RogueMetaProgress::getInstance);
        Files.delete(dir.resolve(PROGRESS));
        RogueFixture.install("/rogue/meta-master.xml", PROGRESS);
        Assert.assertEquals(RogueMetaProgress.getInstance().getTotalEchoes(), 25);
    }

    @Test
    public void reloadKeepsTheDevUnlock() {
        RogueMetaProgress.getInstance().setDevUnlockAll(true);
        RogueMetaProgress.reload();
        Assert.assertTrue(RogueMetaProgress.getInstance().isDevUnlockAll());
    }

    private static byte[] nonsense() throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (OutputStream zip = new GZIPOutputStream(bytes)) {
            zip.write("<nonsense/>".getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }

    private static void cutShort(final Path file) throws IOException {
        final byte[] bytes = Files.readAllBytes(file);
        Files.write(file, Arrays.copyOf(bytes, bytes.length / 2));
    }

    private List<Path> filesEndingWith(final String suffix) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(f -> f.toString().endsWith(suffix)).toList();
        }
    }
}
