package forge.perf.tokenheavy;

import forge.LobbyPlayer;
import forge.game.Game;
import forge.game.player.IGameEntitiesFactory;
import forge.game.player.Player;
import forge.game.player.PlayerController;

import java.util.List;

public class LobbyPlayerInstrumented extends LobbyPlayer implements IGameEntitiesFactory {
    private final InstrumentedController.Mode mode;
    private final List<InstrumentedController.VariantSlot> slots;
    private InstrumentedController lastController;

    public LobbyPlayerInstrumented(String name,
                                   InstrumentedController.Mode mode,
                                   List<InstrumentedController.VariantSlot> slots) {
        super(name);
        this.mode = mode;
        this.slots = slots;
    }

    @Override
    public Player createIngamePlayer(Game game, int id) {
        Player p = new Player(getName(), game, id);
        lastController = new InstrumentedController(game, p, this, mode, slots);
        p.setFirstController(lastController);
        return p;
    }

    @Override
    public PlayerController createMindSlaveController(Player master, Player slave) {
        return new InstrumentedController(slave.getGame(), slave, this, mode, slots);
    }

    @Override
    public void hear(LobbyPlayer player, String message) { }

    public InstrumentedController getLastController() { return lastController; }
}
