package forge.perf;

import forge.LobbyPlayer;
import forge.game.Game;
import forge.game.player.IGameEntitiesFactory;
import forge.game.player.Player;
import forge.game.player.PlayerController;

/**
 * A lobby player that creates an InstrumentedPlayerController — plays normally
 * as AI but optionally runs updateHasAvailableActions() each priority pass and
 * times the cost.
 */
public class LobbyPlayerInstrumented extends LobbyPlayer implements IGameEntitiesFactory {

    private final boolean evaluate;
    private InstrumentedPlayerController lastController;

    public LobbyPlayerInstrumented(String name, boolean evaluate) {
        super(name);
        this.evaluate = evaluate;
    }

    @Override
    public Player createIngamePlayer(Game game, int id) {
        Player p = new Player(getName(), game, id);
        lastController = new InstrumentedPlayerController(game, p, this, evaluate);
        p.setFirstController(lastController);
        return p;
    }

    @Override
    public PlayerController createMindSlaveController(Player master, Player slave) {
        return new InstrumentedPlayerController(slave.getGame(), slave, this, evaluate);
    }

    @Override
    public void hear(LobbyPlayer player, String message) { }

    public InstrumentedPlayerController getLastController() { return lastController; }
}
