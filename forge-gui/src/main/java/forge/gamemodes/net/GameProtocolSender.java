package forge.gamemodes.net;

import forge.gamemodes.net.event.GuiGameEvent;

import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

public final class GameProtocolSender {

    private final IRemote remote;
    private final AtomicInteger sendTimeouts = new AtomicInteger(0);

    public GameProtocolSender(final IRemote remote) {
        this.remote = remote;
    }

    public void send(final ProtocolMethod method, final Object... args) {
        method.checkArgs(args);
        remote.send(new GuiGameEvent(method, args));
    }

    @SuppressWarnings("unchecked")
    public <T> T sendAndWait(final ProtocolMethod method, final Object... args) {
        method.checkArgs(args);
        try {
            final Object returned = remote.sendAndWait(new GuiGameEvent(method, args));
            method.checkReturnValue(returned);
            return (T) returned;
        } catch (final TimeoutException e) {
            sendTimeouts.incrementAndGet();
            System.err.printf("Protocol timeout on %s (total timeouts: %d)%n",
                    method.name(), sendTimeouts.get());
            e.printStackTrace();
        }
        return null;
    }

    public int getSendTimeoutCount() {
        return sendTimeouts.get();
    }
}
