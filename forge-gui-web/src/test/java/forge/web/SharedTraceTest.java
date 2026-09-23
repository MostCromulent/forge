package forge.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * BrowserModel builds the recorded game's table from its recorded messages. src/test/ts/model.test.ts holds
 * model.ts to the same trace, so the two copies of the apply-and-prune rules cannot drift apart unnoticed.
 */
public class SharedTraceTest {
    static JsonObject trace() throws IOException {
        try (InputStream in = SharedTraceTest.class.getResourceAsStream("/" + TraceRecordingTest.TRACE)) {
            Assert.assertNotNull(in, "no trace at " + TraceRecordingTest.TRACE);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    @Test
    public void browserModelBuildsTheRecordedTable() throws IOException {
        final JsonObject trace = trace();
        final BrowserModel model = new BrowserModel();
        for (final JsonElement message : trace.getAsJsonArray("messages")) {
            model.applyStateMessage(message.getAsJsonObject());
        }
        final JsonObject expected = trace.getAsJsonObject("expected").getAsJsonObject("objects");
        final Map<Integer, JsonObject> built = model.objectsCopy();
        Assert.assertEquals(built.size(), expected.size(), "objects in the table");
        for (final Map.Entry<String, JsonElement> e : expected.entrySet()) {
            Assert.assertEquals(built.get(Integer.parseInt(e.getKey())), e.getValue(), "object " + e.getKey());
        }
    }
}
