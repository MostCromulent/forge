package forge.web.protocol;

import forge.web.protocol.FromBrowser.Bare;
import forge.web.protocol.FromBrowser.Plain;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** One owner's handlers for the browser's commands, found by the name each command is sent under. */
public final class Commands<O> {
    /** Runs one command for its owner. channel is the browser that sent it. */
    @FunctionalInterface
    public interface Handler<O, T extends Record> {
        void run(O owner, BrowserChannel channel, T command);
    }

    private final Map<String, Handler<O, Record>> handlers = new HashMap<>();

    /** Handles every command the record reads: its one name, or each constant of its t component. */
    public <T extends Record> Commands<O> on(final Class<T> type, final Handler<O, T> handler) {
        for (final String name : Wire.commandNames(type)) {
            add(name, handler);
        }
        return this;
    }

    /** Handles one of the commands that are only their name. */
    public Commands<O> on(final Plain name, final Handler<O, Bare> handler) {
        add(name.name(), handler);
        return this;
    }

    @SuppressWarnings("unchecked")
    private void add(final String name, final Handler<O, ? extends Record> handler) {
        if (handlers.put(name, (Handler<O, Record>) handler) != null) {
            throw new IllegalStateException(name + " is handled twice");
        }
    }

    public boolean handles(final Record command) {
        return handlers.containsKey(Wire.commandName(command));
    }

    /** Runs the command's handler. Answers false when this owner has none. */
    public boolean run(final O owner, final BrowserChannel channel, final Record command) {
        final Handler<O, Record> handler = handlers.get(Wire.commandName(command));
        if (handler == null) {
            return false;
        }
        handler.run(owner, channel, command);
        return true;
    }

    public Set<String> names() {
        return handlers.keySet();
    }
}
