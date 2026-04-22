package forge.perf.tokenheavy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class HypothesisLog {
    public static void appendJsonl(Path logPath, String jsonLine) throws IOException {
        Files.createDirectories(logPath.getParent());
        Files.writeString(logPath, jsonLine + System.lineSeparator(),
            StandardCharsets.UTF_8,
            StandardOpenOption.APPEND, StandardOpenOption.CREATE);
    }

    public static Path branchNotesPath(String branch) {
        return Path.of(".claude", "notes", branch);
    }

    private HypothesisLog() {}
}
