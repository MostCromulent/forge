package forge.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

public class PendingRequestsTest {
    private List<JsonObject> sent;
    private PendingRequests requests;

    @BeforeMethod
    public void freshRequests() {
        sent = new CopyOnWriteArrayList<>();
        requests = new PendingRequests(sent::add);
    }

    private CompletableFuture<JsonElement> ask(final int defaultAnswer) {
        return CompletableFuture.supplyAsync(() -> requests.await("option", new JsonObject(), new JsonPrimitive(defaultAnswer),
                v -> v.isJsonPrimitive() && v.getAsInt() >= 0 && v.getAsInt() < 3));
    }

    private int awaitSentId(final int index) throws InterruptedException {
        for (int i = 0; i < 200 && sent.size() <= index; i++) {
            Thread.sleep(10);
        }
        return sent.get(index).get("id").getAsInt();
    }

    @Test
    public void replyCompletesTheWaitingCaller() throws Exception {
        final CompletableFuture<JsonElement> answer = ask(0);
        final int id = awaitSentId(0);
        Assert.assertEquals(sent.get(0).get("default").getAsInt(), 0);
        requests.complete(id, new JsonPrimitive(2));
        Assert.assertEquals(answer.get(2, TimeUnit.SECONDS).getAsInt(), 2);
    }

    @Test
    public void invalidReplyLeavesTheRequestOpen() throws Exception {
        final CompletableFuture<JsonElement> answer = ask(1);
        final int id = awaitSentId(0);
        requests.complete(id, new JsonPrimitive(7));
        Thread.sleep(100);
        Assert.assertFalse(answer.isDone());
        requests.complete(id, new JsonPrimitive(1));
        Assert.assertEquals(answer.get(2, TimeUnit.SECONDS).getAsInt(), 1);
    }

    @Test
    public void cancelAllAnswersWithDefaults() throws Exception {
        final CompletableFuture<JsonElement> answer = ask(2);
        awaitSentId(0);
        requests.cancelAll();
        Assert.assertEquals(answer.get(2, TimeUnit.SECONDS).getAsInt(), 2);
    }

    @Test
    public void replayResendsOpenRequestsOnly() throws Exception {
        final CompletableFuture<JsonElement> first = ask(0);
        final int firstId = awaitSentId(0);
        requests.complete(firstId, new JsonPrimitive(0));
        first.get(2, TimeUnit.SECONDS);
        final CompletableFuture<JsonElement> second = ask(0);
        final int secondId = awaitSentId(1);
        final List<JsonObject> replayed = new CopyOnWriteArrayList<>();
        requests.replay(replayed::add);
        Assert.assertEquals(replayed.size(), 1);
        Assert.assertEquals(replayed.get(0).get("id").getAsInt(), secondId);
        requests.cancelAll();
        second.get(2, TimeUnit.SECONDS);
    }
}
