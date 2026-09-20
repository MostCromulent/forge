package forge.web;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.common.primitives.Ints;
import forge.ImageKeys;
import forge.gui.GuiBase;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.util.ImageUtil;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.CookieHeaderNames;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolConfig;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.tinylog.Logger;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import forge.sound.MusicPlaylist;
import forge.sound.SoundSystem;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Serves the page, its files and card images, and the browser's WebSocket; 127.0.0.1 only, behind a per-launch token. */
public final class WebServer implements AutoCloseable {
    public interface Endpoint {
        void connected(BrowserChannel channel);
        void disconnected(BrowserChannel channel);
        void onMessage(BrowserChannel channel, JsonObject message);
    }

    private static final String COOKIE = "forge_token";
    private final EventLoopGroup group = new NioEventLoopGroup(2, new DefaultThreadFactory("WebServer", true));
    private final String token;
    // The shared fetcher tries a path once per run and never calls back again, so a key that failed is not retried
    private final Set<String> unavailableImages = ConcurrentHashMap.newKeySet();
    private final Channel channel;

    public WebServer(final Endpoint endpoint, final String token) throws InterruptedException {
        this.token = token;
        final ServerBootstrap b = new ServerBootstrap()
                .group(group)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(final SocketChannel ch) {
                        ch.pipeline().addLast(
                                new HttpServerCodec(),
                                new HttpObjectAggregator(1 << 20),
                                new AccessGate(),
                                new WebSocketServerProtocolHandler(WebSocketServerProtocolConfig.newBuilder()
                                        .websocketPath("/ws").checkStartsWith(true).maxFramePayloadLength(1 << 22).build()),
                                new StaticFiles(),
                                new BrowserSocket(endpoint));
                    }
                });
        channel = b.bind(InetAddress.getLoopbackAddress(), 0).sync().channel();
    }

    public int port() {
        return ((InetSocketAddress) channel.localAddress()).getPort();
    }

    public String url() {
        return origin() + "/?token=" + token;
    }

    String origin() {
        return "http://127.0.0.1:" + port();
    }

    @Override
    public void close() {
        channel.close().syncUninterruptibly();
        group.shutdownGracefully();
    }

    private static File cardImage(final String imageKey) {
        String key = imageKey;
        final boolean backFace = key.endsWith(ImageKeys.BACKFACE_POSTFIX);
        if (backFace) {
            key = key.substring(0, key.length() - ImageKeys.BACKFACE_POSTFIX.length());
        }
        if (key.startsWith(ImageKeys.CARD_PREFIX)) {
            final PaperCard card = ImageUtil.getPaperCardFromImageKey(key);
            if (card == null) {
                return null;
            }
            key = backFace ? card.getCardAltImageKey() : card.getCardImageKey();
        }
        final File file = ImageKeys.getImageFile(key);
        return file != null && file.isFile() ? file : null;
    }

    // A missing image is downloaded as on desktop, and the request answered when it lands
    private void serveImage(final ChannelHandlerContext ctx, final String key) throws IOException {
        final File file = cardImage(key);
        if (file != null) {
            respondImage(ctx, file);
            return;
        }
        if (unavailableImages.contains(key) || !FModel.getPreferences().getPrefBoolean(FPref.UI_ENABLE_ONLINE_IMAGE_FETCHER)) {
            respond(ctx, HttpResponseStatus.NOT_FOUND, new byte[0], "text/plain", false);
            return;
        }
        final AtomicBoolean answered = new AtomicBoolean();
        GuiBase.getInterface().invokeInEdtLater(() -> GuiBase.getInterface().getImageFetcher().fetchImage(key, () -> {
            final File fetched = cardImage(key);
            if (answered.compareAndSet(false, true)) {
                try {
                    if (fetched != null) {
                        respondImage(ctx, fetched);
                    } else {
                        respond(ctx, HttpResponseStatus.NOT_FOUND, new byte[0], "text/plain", false);
                    }
                } catch (final IOException e) {
                    respond(ctx, HttpResponseStatus.NOT_FOUND, new byte[0], "text/plain", false);
                }
            }
        }));
        ctx.executor().schedule(() -> {
            if (answered.compareAndSet(false, true)) {
                unavailableImages.add(key);
                respond(ctx, HttpResponseStatus.NOT_FOUND, new byte[0], "text/plain", false);
            }
        }, 15, TimeUnit.SECONDS);
    }

    private void respondImage(final ChannelHandlerContext ctx, final File file) throws IOException {
        respond(ctx, HttpResponseStatus.OK, Files.readAllBytes(file.toPath()), file.getName().endsWith(".png") ? "image/png" : "image/jpeg", false);
    }

    private boolean queryToken(final QueryStringDecoder q) {
        final List<String> values = q.parameters().get("token");
        return values != null && token.equals(values.get(0));
    }

    private boolean hasToken(final FullHttpRequest req, final QueryStringDecoder q) {
        if (queryToken(q)) {
            return true;
        }
        final String header = req.headers().get(HttpHeaderNames.COOKIE);
        if (header == null) {
            return false;
        }
        for (final Cookie c : ServerCookieDecoder.STRICT.decode(header)) {
            if (COOKIE.equals(c.name()) && token.equals(c.value())) {
                return true;
            }
        }
        return false;
    }

    private void respond(final ChannelHandlerContext ctx, final HttpResponseStatus status, final byte[] body, final String type, final boolean setCookie) {
        final FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(body));
        resp.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, type)
                .setInt(HttpHeaderNames.CONTENT_LENGTH, body.length)
                .set(HttpHeaderNames.CACHE_CONTROL, "no-cache")
                .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        if (setCookie) {
            final DefaultCookie cookie = new DefaultCookie(COOKIE, token);
            cookie.setPath("/");
            cookie.setHttpOnly(true);
            cookie.setSameSite(CookieHeaderNames.SameSite.Strict);
            resp.headers().set(HttpHeaderNames.SET_COOKIE, ServerCookieEncoder.STRICT.encode(cookie));
        }
        ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
    }

    // -Dforge.web.pageDir=<the web resource folder> serves the page from disk, so an edit needs only a reload
    private static final String PAGE_DIR = System.getProperty("forge.web.pageDir");

    private static String contentType(final String resource) {
        if (resource.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (resource.endsWith(".js")) {
            return "text/javascript; charset=utf-8";
        }
        if (resource.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (resource.endsWith(".svg")) {
            return "image/svg+xml";
        }
        return "application/octet-stream";
    }

    // The player's own sound set and music, resolved the way the desktop client resolves them
    private void serveAudio(final ChannelHandlerContext ctx, final boolean sound, final String name) throws IOException {
        final File file = sound && name != null && !name.contains("/") && !name.contains("\\")
                ? SoundSystem.instance.getSoundResource(name)
                : sound ? null : musicTrack();
        if (file == null || !file.isFile()) {
            respond(ctx, HttpResponseStatus.NOT_FOUND, new byte[0], "text/plain", false);
            return;
        }
        final String type = file.getName().toLowerCase(Locale.ROOT).endsWith(".wav") ? "audio/wav" : "audio/mpeg";
        respond(ctx, HttpResponseStatus.OK, Files.readAllBytes(file.toPath()), type, false);
    }

    private static File musicTrack() {
        final String track = MusicPlaylist.MATCH.getRandomFilename();
        return track == null ? null : new File(track);
    }

    private static byte[] readResource(final String resource) throws IOException {
        if (PAGE_DIR != null) {
            final Path root = Path.of(PAGE_DIR).toAbsolutePath().normalize();
            final Path file = root.resolve(resource).normalize();
            return file.startsWith(root) && Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
        }
        try (InputStream in = WebServer.class.getResourceAsStream("/web/" + resource)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    private final class AccessGate extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
            if (msg instanceof FullHttpRequest req) {
                final QueryStringDecoder q = new QueryStringDecoder(req.uri());
                final boolean socket = "/ws".equals(q.path());
                // Any page in the same browser can reach 127.0.0.1; the token, cookie and origin keep them out
                if (!hasToken(req, q) || (socket && !origin().equals(req.headers().get(HttpHeaderNames.ORIGIN)))) {
                    req.release();
                    respond(ctx, HttpResponseStatus.FORBIDDEN, new byte[0], "text/plain", false);
                    return;
                }
            }
            ctx.fireChannelRead(msg);
        }
    }

    private final class StaticFiles extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(final ChannelHandlerContext ctx, final FullHttpRequest req) throws IOException {
            final QueryStringDecoder q = new QueryStringDecoder(req.uri());
            final String path = q.path();
            if ("/avatar".equals(path) || "/sleeve".equals(path)) {
                final List<String> index = q.parameters().get("i");
                final Integer i = index == null ? null : Ints.tryParse(index.get(0));
                final byte[] png = i == null ? null : SkinSprites.png("/avatar".equals(path), i);
                if (png == null) {
                    respond(ctx, HttpResponseStatus.NOT_FOUND, new byte[0], "text/plain", false);
                } else {
                    respond(ctx, HttpResponseStatus.OK, png, "image/png", false);
                }
                return;
            }
            if ("/playmat".equals(path)) {
                final List<String> id = q.parameters().get("id");
                final byte[] image = id == null ? null : Playmats.image(id.get(0));
                if (image == null) {
                    respond(ctx, HttpResponseStatus.NOT_FOUND, new byte[0], "text/plain", false);
                } else {
                    respond(ctx, HttpResponseStatus.OK, image, "image/jpeg", false);
                }
                return;
            }
            if ("/mana".equals(path)) {
                final List<String> symbol = q.parameters().get("s");
                final byte[] png = symbol == null ? null : SkinSprites.manaPng(symbol.get(0));
                if (png == null) {
                    respond(ctx, HttpResponseStatus.NOT_FOUND, new byte[0], "text/plain", false);
                } else {
                    respond(ctx, HttpResponseStatus.OK, png, "image/png", false);
                }
                return;
            }
            if ("/sound".equals(path) || "/music".equals(path)) {
                final List<String> name = q.parameters().get("name");
                serveAudio(ctx, "/sound".equals(path), name == null ? null : name.get(0));
                return;
            }
            if ("/img".equals(path)) {
                final List<String> key = q.parameters().get("key");
                if (key == null) {
                    respond(ctx, HttpResponseStatus.NOT_FOUND, new byte[0], "text/plain", false);
                } else {
                    serveImage(ctx, key.get(0));
                }
                return;
            }
            final String resource = "/".equals(path) ? "index.html" : path.substring(1);
            final byte[] body = resource.contains("..") ? null : readResource(resource);
            final String type = contentType(resource);
            if (body == null) {
                respond(ctx, HttpResponseStatus.NOT_FOUND, new byte[0], "text/plain", false);
                return;
            }
            respond(ctx, HttpResponseStatus.OK, body, type, queryToken(q));
        }
    }

    private static final class BrowserSocket extends SimpleChannelInboundHandler<WebSocketFrame> {
        private final Endpoint endpoint;
        private BrowserChannel browser;

        BrowserSocket(final Endpoint endpoint) {
            this.endpoint = endpoint;
        }

        @Override
        public void userEventTriggered(final ChannelHandlerContext ctx, final Object evt) {
            if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
                final Channel ch = ctx.channel();
                browser = message -> ch.writeAndFlush(new TextWebSocketFrame(JsonCodec.GSON.toJson(message)));
                endpoint.connected(browser);
            } else {
                ctx.fireUserEventTriggered(evt);
            }
        }

        @Override
        protected void channelRead0(final ChannelHandlerContext ctx, final WebSocketFrame frame) {
            if (frame instanceof TextWebSocketFrame text && browser != null) {
                try {
                    endpoint.onMessage(browser, JsonParser.parseString(text.text()).getAsJsonObject());
                } catch (final RuntimeException e) {
                    Logger.warn(e, "Bad browser message");
                }
            }
        }

        @Override
        public void channelInactive(final ChannelHandlerContext ctx) {
            if (browser != null) {
                endpoint.disconnected(browser);
            }
            ctx.fireChannelInactive();
        }
    }
}
