package forge.perf.tokenheavy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

public final class HypothesisLog {
    public static void appendJsonl(Path logPath, String jsonLine) throws IOException {
        Files.createDirectories(logPath.getParent());
        Files.writeString(logPath, jsonLine + System.lineSeparator(),
            StandardCharsets.UTF_8,
            StandardOpenOption.APPEND, StandardOpenOption.CREATE);
    }

    public static Path branchNotesPath(String branch) {
        return repoRoot().resolve(".claude").resolve("notes").resolve(branch);
    }

    // Surefire's cwd is the module dir, so naked relative paths would scatter
    // logs under each module. Walk up to the repo root (marker: .git) so a
    // single hypotheses.jsonl aggregates runs regardless of module invocation.
    public static Path repoRoot() {
        Path cur = Paths.get("").toAbsolutePath();
        while (cur != null) {
            if (Files.exists(cur.resolve(".git"))) return cur;
            cur = cur.getParent();
        }
        return Paths.get("").toAbsolutePath();
    }

    private HypothesisLog() {}
}
