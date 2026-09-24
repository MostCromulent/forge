package forge.web;

import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
        // Git hands a Windows checkout CRLF, and the generator writes LF, which is not the drift this is looking for
        Assert.assertEquals(sameLineEndings(committed), sameLineEndings(generated),
                ProtocolTypes.FILE + " is out of date with the protocol records."
                + " Regenerate it: mvn -pl forge-gui-web test -Dtest=ProtocolTypesTest -Dforge.web.writeProtocol=true");
    }

    /**
     * Fails if a record carries a protocol annotation but is missing from the list the generator reads. The lists
     * set the order the types are written in, so they are kept by hand; without this, a record left out of one is
     * simply absent from the generated file, and the browser loses the type with nothing to say it has.
     */
    @Test
    public void everyAnnotatedRecordIsInAList() {
        Assert.assertEquals(annotated(ToBrowser.class, Wire.Message.class), names(ToBrowser.MESSAGES),
                "records marked @Message but missing from ToBrowser.MESSAGES, or the other way about");
        Assert.assertEquals(annotated(ToBrowser.class, Wire.Request.class), names(ToBrowser.REQUESTS),
                "records marked @Request but missing from ToBrowser.REQUESTS, or the other way about");
        Assert.assertEquals(annotated(ToBrowser.class, Wire.Event.class), names(ToBrowser.EVENTS),
                "records marked @Event but missing from ToBrowser.EVENTS, or the other way about");
        Assert.assertEquals(annotated(FromBrowser.class, Wire.Command.class), names(FromBrowser.COMMANDS),
                "records marked @Command but missing from FromBrowser.COMMANDS, or the other way about");
    }

    private static Set<String> annotated(final Class<?> holder, final Class<? extends Annotation> mark) {
        return Stream.of(holder.getDeclaredClasses())
                .filter(c -> c.isAnnotationPresent(mark))
                .map(Class::getSimpleName)
                .collect(Collectors.toSet());
    }

    private static Set<String> names(final List<Class<? extends Record>> listed) {
        return listed.stream().map(Class::getSimpleName).collect(Collectors.toSet());
    }

    private static String sameLineEndings(final String text) {
        return text.replace("\r\n", "\n");
    }

    private static Path tsDir() {
        final Path module = Path.of("src/main/ts");
        return Files.isDirectory(module) ? module : Path.of("forge-gui-web/src/main/ts");
    }
}
