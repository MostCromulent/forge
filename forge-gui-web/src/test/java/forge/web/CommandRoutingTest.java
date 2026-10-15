package forge.web;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** The server routes the browser's commands by name, in switches and equals tests spread over the module, which the compiler does not check against FromBrowser. */
public class CommandRoutingTest {
    private static final Pattern CASE = Pattern.compile("case\\s+([^:>]+?)\\s*->");
    private static final Pattern NAME = Pattern.compile("\"([A-Za-z]+)\"|\\b([a-z][A-Za-z]+)\\b");
    /** A command some handlers test for by name rather than switch on. */
    private static final Pattern EQUALS = Pattern.compile("\"([A-Za-z]+)\"\\.equals\\(");

    /** Every command's name: its @Command value, or each constant of the enum its t component names it by. */
    private static Set<String> commandNames() {
        final Set<String> names = new HashSet<>();
        for (final Class<? extends Record> c : FromBrowser.COMMANDS) {
            final String named = c.getAnnotation(Wire.Command.class).value();
            if (!named.isEmpty()) {
                names.add(named);
                continue;
            }
            final RecordComponent t = c.getRecordComponents()[0];
            Assert.assertTrue(t.getName().equals("t") && t.getType().isEnum(), c.getSimpleName() + " has neither a name nor a t enum");
            for (final Object constant : t.getType().getEnumConstants()) {
                names.add(((Enum<?>) constant).name());
            }
        }
        return names;
    }

    private static Set<String> routed() throws IOException {
        final Set<String> labels = new HashSet<>();
        try (Stream<Path> sources = Files.list(Path.of("src/main/java/forge/web"))) {
            for (final Path source : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
                final String code = Files.readString(source);
                final Matcher tested = EQUALS.matcher(code);
                while (tested.find()) {
                    labels.add(tested.group(1));
                }
                final Matcher cases = CASE.matcher(code);
                while (cases.find()) {
                    final Matcher name = NAME.matcher(cases.group(1));
                    while (name.find()) {
                        labels.add(name.group(1) != null ? name.group(1) : name.group(2));
                    }
                }
            }
        }
        return labels;
    }

    // Fails if a command the browser can send is handled nowhere, so it would be dropped without a word
    @Test
    public void everyCommandIsRouted() throws IOException {
        final Set<String> routed = routed();
        final List<String> missing = new ArrayList<>(commandNames());
        missing.removeIf(routed::contains);
        missing.sort(null);
        Assert.assertTrue(missing.isEmpty(), "commands nothing handles: " + missing);
    }
}
