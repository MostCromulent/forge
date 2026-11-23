package forge.gamemodes.rogue;

import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.converters.Converter;
import com.thoughtworks.xstream.converters.MarshallingContext;
import com.thoughtworks.xstream.converters.UnmarshallingContext;
import com.thoughtworks.xstream.io.HierarchicalStreamReader;
import com.thoughtworks.xstream.io.HierarchicalStreamWriter;
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
import java.util.ArrayList;
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
    public void aRunCutShortStaysInPlace() throws IOException {
        cutShort(RogueFixture.install("/rogue/run-master.xml", RUN));
        Assert.assertTrue(RogueIO.loadAllRuns().isEmpty());
        Assert.assertTrue(Files.exists(dir.resolve(RUN)));
        Assert.assertEquals(filesEndingWith(".broken"), List.of());
    }

    @Test
    public void aFileCutShortIsSetAside() throws IOException {
        Files.createFile(dir.resolve(PROGRESS));
        Assert.assertEquals(RogueMetaProgress.getInstance().getTotalEchoes(), 0);
        Assert.assertFalse(Files.exists(dir.resolve(PROGRESS)));
        RogueFixture.clean();

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

        Files.delete(dir.resolve(PROGRESS));
        RogueFixture.install("/rogue/meta-master.xml", PROGRESS);
        Assert.assertEquals(RogueMetaProgress.getInstance().getTotalEchoes(), 25);
    }

    @Test
    public void aFailedSaveKeepsTheOldFile() throws IOException {
        final Path target = Files.write(dir.resolve(RUN), nonsense());
        final XStream xStream = RogueIO.getSerializer(false);
        xStream.registerConverter(new ThrowingConverter());
        final List<Object> value = new ArrayList<>(List.of("written", new Unwritable()));
        Assert.expectThrows(IllegalStateException.class, () -> RogueIO.savePacked(xStream, value, target.toFile()));
        Assert.assertEquals(Files.readAllBytes(target), nonsense());
        Assert.assertEquals(filesEndingWith(""), List.of(target));
    }

    @Test
    public void theFixtureRoundTrips() throws IOException {
        final Path runFile = RogueFixture.install("/rogue/run-master.xml", RUN);
        final Path progressFile = RogueFixture.install("/rogue/meta-master.xml", PROGRESS);
        final RogueRun before = RogueIO.loadMostRecentContinuableRun().orElseThrow(AssertionError::new);
        Assert.assertEquals(before.getCurrentCommanderName(), "Aragorn, the Uniter");
        Assert.assertEquals(before.getCurrentNodeIndex(), 11);
        Assert.assertEquals(before.getCarryCards().stream().map(RogueRun.CarryCard::cardName).toList(),
                List.of("Sol Ring", "Llanowar Elves", "Lightning Bolt"));
        final RogueMetaProgress progress = RogueMetaProgress.getInstance();
        Files.delete(runFile);
        Files.delete(progressFile);
        RogueIO.saveRun(before);
        progress.save();

        final RogueRun after = RogueIO.loadRun(runFile.toFile());
        Assert.assertEquals(xml(after.getPath()), xml(before.getPath()));
        Assert.assertEquals(xml(after.getCurrentDeck()), xml(before.getCurrentDeck()));
        Assert.assertEquals(xml(after.getCarryCards()), xml(before.getCarryCards()));
        RogueMetaProgress.reload();
        Assert.assertNotSame(RogueMetaProgress.getInstance(), progress);
        Assert.assertEquals(RogueMetaProgress.getInstance().getTotalEchoes(), 25);
    }

    private static final class Unwritable {
    }

    private static final class ThrowingConverter implements Converter {
        @Override
        public boolean canConvert(@SuppressWarnings("rawtypes") final Class type) {
            return type == Unwritable.class;
        }

        @Override
        public void marshal(final Object source, final HierarchicalStreamWriter writer, final MarshallingContext context) {
            throw new IllegalStateException();
        }

        @Override
        public Object unmarshal(final HierarchicalStreamReader reader, final UnmarshallingContext context) {
            throw new UnsupportedOperationException();
        }
    }

    private static String xml(final Object value) {
        return RogueIO.getSerializer(false).toXML(value);
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
