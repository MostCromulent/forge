package forge.web;

import com.google.gson.JsonObject;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public class WebServerTest {
    private final HttpClient http = HttpClient.newHttpClient();
    private final List<JsonObject> received = new CopyOnWriteArrayList<>();
    private volatile BrowserChannel connected;
    private WebServer server;

    private String origin() {
        return "http://127.0.0.1:" + server.port();
    }

    @BeforeClass
    public void setUp() throws Exception {
        WebTestSupport.initModel();
        server = new WebServer(new WebServer.Endpoint() {
            @Override public void connected(final BrowserChannel channel, final String clientId) {
                connected = channel;
            }
            @Override public void disconnected(final BrowserChannel channel) { }
            @Override public void onMessage(final BrowserChannel channel, final JsonObject message) { received.add(message); }
        }, "secret", 0);
    }

    @AfterClass
    public void tearDown() {
        server.close();
    }

    private HttpResponse<String> get(final String pathAndQuery, final String cookie) throws Exception {
        final HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(origin() + pathAndQuery));
        if (cookie != null) {
            b.header("Cookie", cookie);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    public void requestWithoutTokenIsForbidden() throws Exception {
        Assert.assertEquals(get("/", null).statusCode(), 403);
    }

    @Test
    public void tokenServesThePageAndSetsTheCookie() throws Exception {
        final HttpResponse<String> r = get("/?token=secret", null);
        Assert.assertEquals(r.statusCode(), 200);
        Assert.assertTrue(r.body().contains("<title>"));
        Assert.assertTrue(r.headers().firstValue("set-cookie").orElse("").contains("forge_token=secret"));
    }

    @Test
    public void cookieAuthorisesLaterRequests() throws Exception {
        Assert.assertEquals(get("/index.html", "forge_token=secret").statusCode(), 200);
    }

    @Test
    public void missingImageAndPathTraversalAre404() throws Exception {
        Assert.assertEquals(get("/img?key=c:NoSuchCard%7CXXX&token=secret", null).statusCode(), 404);
        Assert.assertEquals(get("/..%2Fpom.xml?token=secret", null).statusCode(), 404);
    }

    private WebSocket openSocket(final String origin, final List<String> texts) throws Exception {
        return http.newWebSocketBuilder()
                .header("Origin", origin)
                .buildAsync(URI.create("ws://127.0.0.1:" + server.port() + "/ws?token=secret"), new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onText(final WebSocket ws, final CharSequence data, final boolean last) {
                        texts.add(data.toString());
                        ws.request(1);
                        return null;
                    }
                }).get(5, TimeUnit.SECONDS);
    }

    @Test
    public void socketCarriesMessagesBothWays() throws Exception {
        final List<String> texts = new CopyOnWriteArrayList<>();
        final WebSocket ws = openSocket(origin(), texts);
        ws.sendText("{\"t\":\"ping\"}", true).get(5, TimeUnit.SECONDS);
        for (int i = 0; i < 100 && received.isEmpty(); i++) {
            Thread.sleep(20);
        }
        Assert.assertEquals(received.get(0).get("t").getAsString(), "ping");
        connected.send(JsonCodec.message("pong"));
        for (int i = 0; i < 100 && texts.isEmpty(); i++) {
            Thread.sleep(20);
        }
        Assert.assertTrue(texts.get(0).contains("pong"));
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "").get(5, TimeUnit.SECONDS);
    }

    @Test(expectedExceptions = ExecutionException.class)
    public void socketFromAnotherOriginIsRefused() throws Exception {
        openSocket("http://evil.example", new CopyOnWriteArrayList<>());
    }
}
