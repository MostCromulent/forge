package forge.web;

import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The browser's protocol types are generated from the Java records, and committed so the TypeScript build needs no
 * Java. This fails when they have drifted apart. To regenerate, run this test with -Dforge.web.writeProtocol=true.
 */
public class ProtocolTypesTest {
    /** Some of the engine's enums read their display names as they load. */
    @BeforeClass
    public void initModel() {
        WebTestSupport.initModel();
    }

    @Test
    public void generatedTypesMatchTheRecords() throws IOException {
        final Path file = tsDir().resolve(ProtocolTypes.FILE);
        final String generated = ProtocolTypes.generate();
        if (Boolean.getBoolean("forge.web.writeProtocol")) {
            Files.writeString(file, generated, StandardCharsets.UTF_8);
        }
        final String committed = Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        Assert.assertEquals(committed, generated, ProtocolTypes.FILE + " is out of date with the protocol records."
                + " Regenerate it: mvn -pl forge-gui-web test -Dtest=ProtocolTypesTest -Dforge.web.writeProtocol=true");
    }

    private static Path tsDir() {
        final Path module = Path.of("src/main/ts");
        return Files.isDirectory(module) ? module : Path.of("forge-gui-web/src/main/ts");
    }
}
