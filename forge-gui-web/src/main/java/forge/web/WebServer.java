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
import io.netty.channel.ChannelFuture;
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
import io.netty.handler.codec.http.HttpUtil;
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
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketServerCompressionHandler;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.DefaultThreadFactory;
import forge.sound.MusicPlaylist;
import forge.sound.SoundSystem;
import org.tinylog.Logger;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/**
 * Serves the page, its files and card images, and the browser's WebSocket. It listens on every interface so a
 * player elsewhere can open a link, and the per-launch tokens in the links are what keep everyone else out.
 *
 * <p>There are two: the host's, in the link Forge opens itself, and the guests', in the links the host sends. Either
 * lets a browser in; only the host's lets it take the host's seat, which can set the table and stop the server.</p>
 */
public final class WebServer implements AutoCloseable {
    public interface Endpoint {
        /** A browser arrived. The id tells one browser from another across a reload; mayHost says it came in on the
         *  host's link. */
        void connected(BrowserChannel channel, String clientId, boolean mayHost);
        void disconnected(BrowserChannel channel);
        void onMessage(BrowserChannel channel, JsonObject message);
    }

    private static final String COOKIE = "forge_token";
    /** Which link a request came in on, kept on a socket's channel from the request that opened it. */
    private enum Access { NONE, GUEST, HOST }
    private static final AttributeKey<Access> ACCESS = AttributeKey.valueOf("forge.access");
    private static final AttributeKey<Boolean> KEEP_ALIVE = AttributeKey.valueOf("forge.keepAlive");
    /** For what never changes at its address, a card picture of one printing or a hashed script chunk: kept for good. */
    private static final String KEEP_FOREVER = "public, max-age=31536000, immutable";
    /**
     * For an avatar or sleeve, which changes only with the skin. Asked for again, it would queue behind card pictures
     * that are still downloading, which can hold every connection the browser allows to one server.
     */
    private static final String KEEP_AN_HOUR = "private, max-age=3600";
    private static final Pattern BYTE_RANGE = Pattern.compile("bytes=(\\d*)-(\\d*)");
    /** Keys that failed are remembered so they are not fetched again; past this many, the list starts over. */
    private static final int MOST_UNAVAILABLE_IMAGES = 10_000;
    /**
     * Forge's own netplay port. Anyone who has hosted a game from the desktop client has already opened it,
     * and this server never binds it for netplay, because every seat here reaches the game over loopback.
     * Hosting from the desktop client at the same time needs {@code -Dforge.web.port}.
     */
    private static final int DEFAULT_PORT = 36743;
    /** How long a download is waited on before the browser is told there is no image. */
    private static final int FETCH_TIMEOUT_SECONDS = 15;
    private final EventLoopGroup group = new NioEventLoopGroup(2, new DefaultThreadFactory("WebServer", true));
    private final ServerTraffic traffic = new ServerTraffic();
    private final String hostToken;
    private final String guestToken;
    // The shared fetcher tries a path once per run and never calls back again, so a key that failed is not retried
    private final Set<String> unavailableImages = ConcurrentHashMap.newKeySet();
    private final Channel channel;

    public WebServer(final Endpoint endpoint, final String hostToken, final String guestToken) throws InterruptedException {
        this(endpoint, hostToken, guestToken, Integer.getInteger("forge.web.port", DEFAULT_PORT));
    }

    /** A port of 0 takes whichever one is free, which is what a test wants. */
    WebServer(final Endpoint endpoint, final String hostToken, final String guestToken, final int port)
            throws InterruptedException {
        this.hostToken = hostToken;
        this.guestToken = guestToken;
        final ServerBootstrap b = new ServerBootstrap()
                .group(group)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(final SocketChannel ch) {
                        ch.pipeline().addLast(
                                traffic,
                                new HttpServerCodec(),
                                new HttpObjectAggregator(1 << 20),
                                new AccessGate(),
                                // State and deck lists are JSON that repeats its field names, so they compress well,
                                // which matters to a guest over the internet. Every browser asks for it by itself.
                                new WebSocketServerCompressionHandler(),
                                new WebSocketServerProtocolHandler(WebSocketServerProtocolConfig.newBuilder()
                                        .websocketPath("/ws").checkStartsWith(true).maxFramePayloadLength(1 << 22)
                                        .allowExtensions(true).build()),
                                new StaticFiles(),
                                new BrowserSocket(endpoint));
                    }
                });
        channel = b.bind(port).sync().channel();
    }

    /** Every byte in and out of the port since it was bound, card images included. */
    ServerTraffic traffic() {
        return traffic;
    }

    public int port() {
        return ((InetSocketAddress) channel.localAddress()).getPort();
    }

    /** The host's link, for this machine's own browser. */
    public String url() {
        return "http://127.0.0.1:" + port() + "/?token=" + hostToken;
    }

    /** A guest's link, at whichever address they can reach this machine by. */
    public String inviteUrl(final String address) {
        return "http://" + address + ":" + port() + "/?token=" + guestToken;
    }

    @Override
    public void close() {
        channel.close().syncUninterruptibly();
        group.shutdownGracefully();
    }

    private static final Pattern PARENT = Pattern.compile("(^|[/\\\\:|])\\.\\.([/\\\\|]|$)");

    /**
     * Whether an image key stays inside Forge's image folders. Forge joins a key onto a folder as it is, and tries
     * some with no extension at all, so a ".." in one reached any file on the machine; and Forge deletes a folder it
     * finds where an image should be. A key like that is refused before Forge looks.
     */
    static boolean safeImageKey(final String key) {
        return !PARENT.matcher(key).find();
    }

    private static boolean isImage(final File file) {
        final String name = file.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png");
    }

    private static File cardImage(final String imageKey) {
        if (!safeImageKey(imageKey)) {
            return null;
        }
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
        return file != null && file.isFile() && isImage(file) ? file : null;
    }

    /** As {@link #serveImage}: never wait on the download here, or the thread that serves every other
     *  request waits with it. */
    private void serveSleeveArt(final ChannelHandlerContext ctx, final String key) throws IOException {
        final File cached = SleeveArtCache.file(key);
        if (cached != null) {
            respondImage(ctx, cached);
            return;
        }
        final AtomicBoolean answered = new AtomicBoolean();
        GuiBase.getInterface().invokeInEdtLater(() -> GuiBase.getInterface().getImageFetcher().fetchSleeveArt(key, () -> {
            if (answered.compareAndSet(false, true)) {
                final File fetched = SleeveArtCache.file(key);
                try {
                    if (fetched != null) {
                        respondImage(ctx, fetched);
                        return;
                    }
                } catch (final IOException e) {
                    Logger.warn("Could not send sleeve art {}: {}", key, e.getMessage());
                }
                notFound(ctx);
            }
        }));
        ctx.executor().schedule(() -> {
            if (answered.compareAndSet(false, true)) {
                notFound(ctx);
            }
        }, FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    // A missing image is downloaded as on desktop, and the request answered when it lands
    /** small asks for the card at the size the board draws it, which is shrunk here rather than in the browser. */
    private void serveImage(final ChannelHandlerContext ctx, final String key, final boolean small) throws IOException {
        final File file = cardImage(key);
        if (file != null) {
            respondCardImage(ctx, file, small);
            return;
        }
        if (!safeImageKey(key) || unavailableImages.contains(key)
                || !FModel.getPreferences().getPrefBoolean(FPref.UI_ENABLE_ONLINE_IMAGE_FETCHER)) {
            notFound(ctx);
            return;
        }
        final AtomicBoolean answered = new AtomicBoolean();
        GuiBase.getInterface().invokeInEdtLater(() -> GuiBase.getInterface().getImageFetcher().fetchImage(key, () -> {
            final File fetched = cardImage(key);
            if (answered.compareAndSet(false, true)) {
                try {
                    if (fetched != null) {
                        respondCardImage(ctx, fetched, small);
                    } else {
                        notFound(ctx);
                    }
                } catch (final IOException e) {
                    notFound(ctx);
                }
            }
        }));
        ctx.executor().schedule(() -> {
            if (answered.compareAndSet(false, true)) {
                if (unavailableImages.size() >= MOST_UNAVAILABLE_IMAGES) {
                    unavailableImages.clear();
                }
                unavailableImages.add(key);
                notFound(ctx);
            }
        }, FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private void respondCardImage(final ChannelHandlerContext ctx, final File file, final boolean small) throws IOException {
        if (!small) {
            respondImage(ctx, file);
            return;
        }
        CardThumbnails.of(file).whenComplete((bytes, failed) -> {
            if (bytes != null) {
                respond(ctx, HttpResponseStatus.OK, bytes, imageType(file), null, KEEP_FOREVER);
                return;
            }
            Logger.warn("Could not shrink {}: {}", file, failed.getMessage());
            try {
                respondImage(ctx, file);
            } catch (final IOException e) {
                notFound(ctx);
            }
        });
    }

    private static String imageType(final File file) {
        return file.getName().endsWith(".png") ? "image/png" : "image/jpeg";
    }

    private void respondImage(final ChannelHandlerContext ctx, final File file) throws IOException {
        respond(ctx, HttpResponseStatus.OK, Files.readAllBytes(file.toPath()), imageType(file), null, KEEP_FOREVER);
    }

    /** True when the socket's page came from this server, whichever address the browser reached it by. */
    private static boolean sameOrigin(final FullHttpRequest req) {
        final String origin = req.headers().get(HttpHeaderNames.ORIGIN);
        final String host = req.headers().get(HttpHeaderNames.HOST);
        if (origin == null || host == null) {
            return false;
        }
        final int slashes = origin.indexOf("//");
        return slashes >= 0 && host.equals(origin.substring(slashes + 2));
    }

    /** The link a token belongs to. Compared in constant time, so the time a guess takes says nothing of the token. */
    private Access accessOf(final String presented) {
        if (presented == null) {
            return Access.NONE;
        }
        final byte[] bytes = presented.getBytes(StandardCharsets.UTF_8);
        if (MessageDigest.isEqual(bytes, hostToken.getBytes(StandardCharsets.UTF_8))) {
            return Access.HOST;
        }
        if (MessageDigest.isEqual(bytes, guestToken.getBytes(StandardCharsets.UTF_8))) {
            return Access.GUEST;
        }
        return Access.NONE;
    }

    /** The token in the link, which a page load then keeps as a cookie. */
    private static String queryToken(final QueryStringDecoder q) {
        final List<String> values = q.parameters().get("token");
        return values == null ? null : values.get(0);
    }

    /** Which link a request came in on: the token in it, or the one its page load kept. */
    private Access access(final FullHttpRequest req, final QueryStringDecoder q) {
        final Access fromQuery = accessOf(queryToken(q));
        if (fromQuery != Access.NONE) {
            return fromQuery;
        }
        final String header = req.headers().get(HttpHeaderNames.COOKIE);
        if (header == null) {
            return Access.NONE;
        }
        for (final Cookie c : ServerCookieDecoder.STRICT.decode(header)) {
            if (COOKIE.equals(c.name())) {
                final Access fromCookie = accessOf(c.value());
                if (fromCookie != Access.NONE) {
                    return fromCookie;
                }
            }
        }
        return Access.NONE;
    }

    private void respond(final ChannelHandlerContext ctx, final HttpResponseStatus status, final byte[] body, final String type) {
        respond(ctx, status, body, type, null, "no-cache");
    }

    private void notFound(final ChannelHandlerContext ctx) {
        respond(ctx, HttpResponseStatus.NOT_FOUND, new byte[0], "text/plain");
    }

    /** Sends a picture drawn from the skin, kept for an hour as the avatars are, or says there was nothing. */
    private void respondOrNotFound(final ChannelHandlerContext ctx, final byte[] body, final String type) {
        if (body == null) {
            notFound(ctx);
        } else {
            respond(ctx, HttpResponseStatus.OK, body, type, null, KEEP_AN_HOUR);
        }
    }

    /** Answers a request; a cookie, when given, is the token the page was loaded with. */
    private void respond(final ChannelHandlerContext ctx, final HttpResponseStatus status, final byte[] body, final String type,
            final String cookieToken) {
        respond(ctx, status, body, type, cookieToken, "no-cache");
    }

    /**
     * A board can hold forty pictures, so each one closing its connection costs forty handshakes and each one
     * saying "do not keep this" costs the whole board again on the next load. Both are answered here: the
     * connection is held open when the browser asked for that, and what may be kept says for how long.
     */
    private void respond(final ChannelHandlerContext ctx, final HttpResponseStatus status, final byte[] body, final String type,
            final String cookieToken, final String cacheControl) {
        respond(ctx, status, body, type, cookieToken, cacheControl, null);
    }

    private void respond(final ChannelHandlerContext ctx, final HttpResponseStatus status, final byte[] body, final String type,
            final String cookieToken, final String cacheControl, final String etag) {
        final boolean keepAlive = Boolean.TRUE.equals(ctx.channel().attr(KEEP_ALIVE).get());
        final FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(body));
        resp.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, type)
                .set(HttpHeaderNames.CACHE_CONTROL, cacheControl)
                .set(HttpHeaderNames.CONNECTION, keepAlive ? HttpHeaderValues.KEEP_ALIVE : HttpHeaderValues.CLOSE);
        // A 304 has no body, and a length on it would claim the body the browser already holds is empty
        if (status != HttpResponseStatus.NOT_MODIFIED) {
            resp.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.length);
        }
        if (etag != null) {
            resp.headers().set(HttpHeaderNames.ETAG, etag);
        }
        if (cookieToken != null) {
            final DefaultCookie cookie = new DefaultCookie(COOKIE, cookieToken);
            cookie.setPath("/");
            cookie.setHttpOnly(true);
            cookie.setSameSite(CookieHeaderNames.SameSite.Strict);
            resp.headers().set(HttpHeaderNames.SET_COOKIE, ServerCookieEncoder.STRICT.encode(cookie));
        }
        send(ctx, resp, keepAlive);
    }

    /** Sends the browser on to another address, asked for afresh every time. */
    private void redirect(final ChannelHandlerContext ctx, final String location) {
        final boolean keepAlive = Boolean.TRUE.equals(ctx.channel().attr(KEEP_ALIVE).get());
        final FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.FOUND);
        resp.headers()
                .set(HttpHeaderNames.LOCATION, location)
                .setInt(HttpHeaderNames.CONTENT_LENGTH, 0)
                .set(HttpHeaderNames.CACHE_CONTROL, "no-store")
                .set(HttpHeaderNames.CONNECTION, keepAlive ? HttpHeaderValues.KEEP_ALIVE : HttpHeaderValues.CLOSE);
        send(ctx, resp, keepAlive);
    }

    private static void send(final ChannelHandlerContext ctx, final FullHttpResponse resp, final boolean keepAlive) {
        final ChannelFuture sent = ctx.writeAndFlush(resp);
        if (!keepAlive) {
            sent.addListener(ChannelFutureListener.CLOSE);
        }
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

    /**
     * The player's own sound set and music, resolved the way the desktop client resolves them. A playlist is answered
     * with a redirect to one of its tracks by name, so the shuffle stays here while each track is downloaded only once.
     */
    private void serveAudio(final ChannelHandlerContext ctx, final boolean sound, final String name, final String track,
            final String range) throws IOException {
        if (sound) {
            // Kept for an hour and no longer, since the name stays the same when the host picks another sound set
            respondAudio(ctx, isFileName(name) ? SoundSystem.instance.getSoundResource(name) : null, range, KEEP_AN_HOUR);
            return;
        }
        final String list = "menu".equals(name) ? "menu" : "match";
        final MusicPlaylist playlist = "menu".equals(list) ? MusicPlaylist.MENUS : MusicPlaylist.MATCH;
        if (track == null) {
            final String chosen = playlist.getRandomFilename();
            if (chosen == null) {
                notFound(ctx);
            } else {
                redirect(ctx, "/music?name=" + list + "&track="
                        + URLEncoder.encode(new File(chosen).getName(), StandardCharsets.UTF_8));
            }
            return;
        }
        final File dir = SoundSystem.findMusicDirectory(playlist);
        respondAudio(ctx, dir != null && isFileName(track) ? new File(dir, track) : null, range, KEEP_FOREVER);
    }

    private static boolean isFileName(final String name) {
        return name != null && !name.isEmpty() && !name.contains("/") && !name.contains("\\") && !name.contains("..");
    }

    /**
     * A browser asks for audio in byte ranges. Answered only in whole files, it cannot seek or reuse its cache, so it
     * downloads a track or sound again each time it plays it.
     */
    private void respondAudio(final ChannelHandlerContext ctx, final File file, final String range, final String cacheControl)
            throws IOException {
        if (file == null || !file.isFile()) {
            notFound(ctx);
            return;
        }
        final byte[] body = Files.readAllBytes(file.toPath());
        int from = 0;
        int to = body.length - 1;
        // Several ranges in one request, or a header this does not read, are answered with the whole file
        final Matcher m = range == null ? null : BYTE_RANGE.matcher(range.trim());
        final boolean partial = m != null && m.matches() && !(m.group(1).isEmpty() && m.group(2).isEmpty());
        if (partial) {
            final Integer first = m.group(1).isEmpty() ? null : Ints.tryParse(m.group(1));
            final Integer last = m.group(2).isEmpty() ? null : Ints.tryParse(m.group(2));
            if (m.group(1).isEmpty()) {
                from = last == null ? body.length : Math.max(0, body.length - last);
            } else {
                from = first == null ? body.length : first;
                if (last != null) {
                    to = Math.min(to, last);
                }
            }
        }
        final boolean keepAlive = Boolean.TRUE.equals(ctx.channel().attr(KEEP_ALIVE).get());
        final FullHttpResponse resp;
        if (partial && from > to) {
            resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
            resp.headers().set(HttpHeaderNames.CONTENT_RANGE, "bytes */" + body.length);
        } else {
            resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                    partial ? HttpResponseStatus.PARTIAL_CONTENT : HttpResponseStatus.OK,
                    Unpooled.wrappedBuffer(body, from, to - from + 1));
            if (partial) {
                resp.headers().set(HttpHeaderNames.CONTENT_RANGE, "bytes " + from + "-" + to + "/" + body.length);
            }
        }
        resp.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, file.getName().toLowerCase(Locale.ROOT).endsWith(".wav") ? "audio/wav" : "audio/mpeg")
                .set(HttpHeaderNames.ACCEPT_RANGES, HttpHeaderValues.BYTES)
                .set(HttpHeaderNames.CACHE_CONTROL, cacheControl)
                .setInt(HttpHeaderNames.CONTENT_LENGTH, resp.content().readableBytes())
                .set(HttpHeaderNames.CONNECTION, keepAlive ? HttpHeaderValues.KEEP_ALIVE : HttpHeaderValues.CLOSE);
        send(ctx, resp, keepAlive);
    }

    private static String etag(final byte[] body) {
        final CRC32 crc = new CRC32();
        crc.update(body);
        return "\"" + Long.toHexString(crc.getValue()) + "-" + Integer.toHexString(body.length) + "\"";
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
                final Access access = access(req, q);
                // The token keeps other pages out; the origin check keeps them from opening a socket with a stolen cookie
                if (access == Access.NONE || (socket && !sameOrigin(req))) {
                    req.release();
                    respond(ctx, HttpResponseStatus.FORBIDDEN, new byte[0], "text/plain");
                    return;
                }
                ctx.channel().attr(ACCESS).set(access);
                ctx.channel().attr(KEEP_ALIVE).set(HttpUtil.isKeepAlive(req));
            }
            ctx.fireChannelRead(msg);
        }
    }

    private final class StaticFiles extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(final ChannelHandlerContext ctx, final FullHttpRequest req) throws IOException {
            final QueryStringDecoder q = new QueryStringDecoder(req.uri());
            final String path = q.path();
            ServerTraffic.carrying(ctx.channel(), "/img".equals(path) || "/sleeveart".equals(path) ? ServerTraffic.Kind.CARD_ART
                    : "/sound".equals(path) || "/music".equals(path) ? ServerTraffic.Kind.AUDIO : ServerTraffic.Kind.PAGE);
            if ("/avatar".equals(path) || "/sleeve".equals(path)) {
                final List<String> index = q.parameters().get("i");
                final Integer i = index == null ? null : Ints.tryParse(index.get(0));
                final byte[] png = i == null ? null : SkinSprites.png("/avatar".equals(path), i);
                if (png == null) {
                    notFound(ctx);
                } else {
                    respond(ctx, HttpResponseStatus.OK, png, "image/png", null, KEEP_AN_HOUR);
                }
                return;
            }
            if ("/sleeveart".equals(path)) {
                final List<String> key = q.parameters().get("key");
                if (key == null) {
                    notFound(ctx);
                } else {
                    serveSleeveArt(ctx, key.get(0));
                }
                return;
            }
            if ("/mana".equals(path)) {
                final List<String> symbol = q.parameters().get("s");
                final byte[] png = symbol == null ? null : SkinSprites.manaPng(symbol.get(0));
                respondOrNotFound(ctx, png, "image/png");
                return;
            }
            if ("/ability".equals(path)) {
                final List<String> icon = q.parameters().get("k");
                final byte[] png = icon == null ? null : SkinSprites.abilityPng(icon.get(0));
                respondOrNotFound(ctx, png, "image/png");
                return;
            }
            if ("/sound".equals(path) || "/music".equals(path)) {
                final List<String> name = q.parameters().get("name");
                final List<String> track = q.parameters().get("track");
                serveAudio(ctx, "/sound".equals(path), name == null ? null : name.get(0), track == null ? null : track.get(0),
                        req.headers().get(HttpHeaderNames.RANGE));
                return;
            }
            if ("/img".equals(path)) {
                final List<String> key = q.parameters().get("key");
                final List<String> width = q.parameters().get("w");
                if (key == null) {
                    notFound(ctx);
                } else {
                    serveImage(ctx, key.get(0), width != null && String.valueOf(CardThumbnails.WIDTH).equals(width.get(0)));
                }
                return;
            }
            final String resource = "/".equals(path) ? "index.html" : path.substring(1);
            final byte[] body = resource.contains("..") ? null : readResource(resource);
            final String type = contentType(resource);
            if (body == null) {
                notFound(ctx);
                return;
            }
            // The page keeps the link's token as a cookie, so its later requests come in on the same link
            final String token = queryToken(q);
            // Any other page file keeps its name from one build to the next, so the browser asks whether it changed
            final String etag = etag(body);
            final boolean unchanged = etag.equals(req.headers().get(HttpHeaderNames.IF_NONE_MATCH));
            // A chunk's name carries a hash of its contents, so a changed chunk is a new file and an old one never goes stale
            respond(ctx, unchanged ? HttpResponseStatus.NOT_MODIFIED : HttpResponseStatus.OK, unchanged ? new byte[0] : body,
                    type, accessOf(token) == Access.NONE ? null : token,
                    resource.startsWith("js/chunks/") ? KEEP_FOREVER : "no-cache", etag);
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
            if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete done) {
                ServerTraffic.carrying(ctx.channel(), ServerTraffic.Kind.GAME);
                final Channel ch = ctx.channel();
                browser = new BrowserChannel() {
                    @Override
                    public void send(final JsonObject message) {
                        ch.writeAndFlush(new TextWebSocketFrame(JsonCodec.GSON.toJson(message)));
                    }

                    @Override
                    public void close() {
                        ch.close();
                    }
                };
                final List<String> id = new QueryStringDecoder(done.requestUri()).parameters().get("client");
                endpoint.connected(browser, id == null ? "" : id.get(0), ch.attr(ACCESS).get() == Access.HOST);
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
