package forge.gamemodes.net.event;

import forge.gamemodes.net.NetworkEventView;
import forge.gamemodes.net.server.RemoteClient;

public final class EventCreatedEvent implements NetEvent {
    private static final long serialVersionUID = 1L;
    private final NetworkEventView view;

    public EventCreatedEvent(NetworkEventView view) {
        this.view = view;
    }

    public NetworkEventView getView() { return view; }

    @Override
    public void updateForClient(RemoteClient client) { }
}
