package forge.web;

import com.google.gson.JsonObject;
import forge.ImageKeys;
import forge.localinstance.properties.ForgeConstants;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.File;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public class WebServerTest {
    private final HttpClient http = HttpClient.newHttpClient();
    private final List<JsonObject> received = new CopyOnWriteArrayList<>();
    private volatile BrowserChannel connected;
    private volatile boolean connectedMayHost;
    private WebServer server;

    private String origin() {
        return "http://127.0.0.1:" + server.port();
    }

    @BeforeClass
    public void setUp() throws Exception {
        WebTestSupport.initModel();
        server = new WebServer(new WebServer.Endpoint() {
            @Override public void connected(final BrowserChannel channel, final String clientId, final boolean mayHost) {
                connected = channel;
                connectedMayHost = mayHost;
            }
            @Override public void disconnected(final BrowserChannel channel) { }
            @Override public void onMessage(final BrowserChannel channel, final JsonObject message) { received.add(message); }
        }, "secret", "guest-secret", 0);
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

    /** Fails if a guest's page keeps the host's token, which would let it take the host's seat. */
    @Test
    public void aGuestLinkKeepsTheGuestToken() throws Exception {
        final HttpResponse<String> r = get("/?token=guest-secret", null);
        Assert.assertEquals(r.statusCode(), 200);
        Assert.assertTrue(r.headers().firstValue("set-cookie").orElse("").contains("forge_token=guest-secret"));
        Assert.assertTrue(server.inviteUrl("10.0.0.2").endsWith("token=guest-secret"), "the invite link carries the host's token");
        Assert.assertTrue(server.url().endsWith("token=secret"));
    }

    /** Fails if the endpoint cannot tell a browser on the host's link from one on a guest's. */
    @Test
    public void theSocketSaysWhichLinkOpenedIt() throws Exception {
        final WebSocket guest = openSocket(origin(), "guest-secret", new CopyOnWriteArrayList<>());
        awaitConnected();
        Assert.assertFalse(connectedMayHost, "a guest's link was let host");
        guest.sendClose(WebSocket.NORMAL_CLOSURE, "").get(5, TimeUnit.SECONDS);

        final WebSocket host = openSocket(origin(), "secret", new CopyOnWriteArrayList<>());
        awaitConnected();
        Assert.assertTrue(connectedMayHost, "the host's link was not let host");
        host.sendClose(WebSocket.NORMAL_CLOSURE, "").get(5, TimeUnit.SECONDS);
    }

    private void awaitConnected() throws InterruptedException {
        for (int i = 0; i < 250 && connected == null; i++) {
            Thread.sleep(20);
        }
        Assert.assertNotNull(connected, "the socket never reached the endpoint");
    }

    @Test
    public void imageKeysThatClimbOutAreRefused() {
        for (final String key : new String[] {"i:../x", "i:a/../../x", "t:..\\x", "../x.jpg", "c:..|set", "b:..", "i:.."}) {
            Assert.assertFalse(WebServer.safeImageKey(key), key);
        }
        for (final String key : new String[] {"c:Lightning Bolt|M10", "t:w_1_1_soldier", "i:mana/w", "c:Who..What|UNH"}) {
            Assert.assertTrue(WebServer.safeImageKey(key), key);
        }
    }

    @Test
    public void missingImageAndPathTraversalAre404() throws Exception {
        Assert.assertEquals(get("/img?key=c:NoSuchCard%7CXXX&token=secret", null).statusCode(), 404);
        Assert.assertEquals(get("/..%2Fpom.xml?token=secret", null).statusCode(), 404);
    }

    // An image key is joined onto a folder, and some are also tried with no extension, so ".." in one reached any file
    @Test
    public void anImageKeyCannotReachOutsideTheImageFolders() throws Exception {
        // A real installation has the icon folder, and a path through it only resolves if it exists
        final File icons = new File(ForgeConstants.CACHE_ICON_PICS_DIR);
        final boolean madeIcons = icons.mkdirs();
        final File outside = new File(icons.getParentFile(), "not-an-image-" + System.nanoTime());
        Files.writeString(outside.toPath(), "private");
        try {
            final String key = URLEncoder.encode(ImageKeys.ICON_PREFIX + "../" + outside.getName(), StandardCharsets.UTF_8);
            final HttpResponse<String> r = get("/img?key=" + key + "&token=secret", null);
            Assert.assertEquals(r.statusCode(), 404, "an image key read a file outside the image folders");
        } finally {
            Files.deleteIfExists(outside.toPath());
            if (madeIcons) {
                Files.deleteIfExists(icons.toPath());
            }
        }
    }

    private WebSocket openSocket(final String origin, final List<String> texts) throws Exception {
        return openSocket(origin, "secret", texts);
    }

    private WebSocket openSocket(final String origin, final String token, final List<String> texts) throws Exception {
        connected = null;
        return http.newWebSocketBuilder()
                .header("Origin", origin)
                .buildAsync(URI.create("ws://127.0.0.1:" + server.port() + "/ws?token=" + token), new WebSocket.Listener() {
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
