package forge.perf.tokenheavy;

import forge.ai.GameState;
import forge.item.IPaperCard;
import forge.model.FModel;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Concrete GameState for loading perf testbed .txt fixtures.
 * Copies the Puzzle class's getPaperCard wiring since we need the same
 * card-lookup capability without pulling in puzzle-specific metadata.
 */
public class PerfFixtureState extends GameState {
    @Override
    public IPaperCard getPaperCard(String cardName, String setCode, int artID) {
        return FModel.getMagicDb().getCommonCards().getCard(cardName, setCode, artID);
    }

    public static PerfFixtureState fromResource(String resourcePath) throws Exception {
        try (InputStream in = PerfFixtureState.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) throw new IOException("Fixture not found on classpath: " + resourcePath);
            PerfFixtureState s = new PerfFixtureState();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in))) {
                s.parse(br.lines().filter(l -> !l.isBlank()));
            }
            return s;
        }
    }

    public static PerfFixtureState fromPath(Path p) throws Exception {
        PerfFixtureState s = new PerfFixtureState();
        try (BufferedReader br = Files.newBufferedReader(p)) {
            s.parse(br.lines().filter(l -> !l.isBlank()));
        }
        return s;
    }
}
