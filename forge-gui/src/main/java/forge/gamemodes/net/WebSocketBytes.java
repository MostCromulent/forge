package forge.gamemodes.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.ContinuationWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;

import java.nio.channels.ClosedChannelException;

/**
 * Carries the netplay byte stream inside a WebSocket, so a client can reach a host through a web server's port.
 * The handlers after this one are told the channel is open only once the WebSocket handshake is done, because
 * a client logs in the moment it is told that.
 */
public final class WebSocketBytes extends ChannelDuplexHandler {
    private ChannelPromise opened;

    /** Done when the WebSocket is open, and failed when the other side refuses it or the connection ends first. */
    public ChannelFuture opened() {
        return opened;
    }

    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) {
        opened = ctx.newPromise();
    }

    @Override
    public void channelActive(final ChannelHandlerContext ctx) {
    }

    @Override
    public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
        opened.tryFailure(new ClosedChannelException());
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) throws Exception {
        opened.tryFailure(cause);
        super.exceptionCaught(ctx, cause);
    }

    @Override
    public void userEventTriggered(final ChannelHandlerContext ctx, final Object evt) throws Exception {
        if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete
                || evt == WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE) {
            opened.trySuccess();
            ctx.fireChannelActive();
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
        if (msg instanceof BinaryWebSocketFrame || msg instanceof ContinuationWebSocketFrame) {
            ctx.fireChannelRead(((WebSocketFrame) msg).content());
        } else if (msg instanceof WebSocketFrame frame) {
            // Netplay is bytes only, and nothing further on would free a frame of text
            frame.release();
            ctx.close();
        } else {
            ctx.fireChannelRead(msg);
        }
    }

    @Override
    public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise) {
        ctx.write(msg instanceof ByteBuf bytes ? new BinaryWebSocketFrame(bytes) : msg, promise);
    }
}
