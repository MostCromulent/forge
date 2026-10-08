package forge.web;

import forge.game.card.CardView;
import forge.game.event.GameEvent;
import forge.game.event.GameEventBlockersDeclared;
import forge.game.event.GameEventPlayerDamaged;
import forge.game.event.GameEventTurnPhase;
import forge.game.player.PlayerView;
import forge.gui.control.GameEventForwarder;
import forge.gui.interfaces.IGuiGame;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

public class EventBatchTest {
    // The phase is left unnamed because naming one reads the language files
    private static GameEvent phase() {
        return new GameEventTurnPhase((PlayerView) null, null, "");
    }

    // Fails if a batch holds events of two phases, so a client draws blocks already dealt their damage, or a turn's end with the next turn's start
    @Test
    @SuppressWarnings("unchecked")
    public void aBatchHoldsOnePhase() {
        final List<List<GameEvent>> batches = new ArrayList<>();
        final IGuiGame gui = (IGuiGame) Proxy.newProxyInstance(IGuiGame.class.getClassLoader(), new Class<?>[] { IGuiGame.class }, (proxy, method, args) -> {
            if ("handleGameEvents".equals(method.getName())) {
                batches.add(List.copyOf((List<GameEvent>) args[0]));
            }
            return null;
        });
        final GameEventForwarder forwarder = new GameEventForwarder(gui);
        final GameEvent blocking = phase();
        final GameEvent block = new GameEventBlockersDeclared((PlayerView) null, null);
        final GameEvent damaging = phase();
        final GameEvent damage = new GameEventPlayerDamaged(null, (CardView) null, 2, true, false);
        final GameEvent cleanup = phase();
        final GameEvent untap = phase();
        for (final GameEvent ev : List.of(blocking, block, damaging, damage, cleanup, untap)) {
            forwarder.receiveGameEvent(ev);
        }
        Assert.assertEquals(batches.size(), 3, "a phase's events go out when the next phase begins");
        Assert.assertSame(batches.get(0).get(1), block);
        Assert.assertEquals(batches.get(0).size(), 2, "the blocks left with the damage that follows them");
        Assert.assertSame(batches.get(1).get(0), damaging);
        Assert.assertSame(batches.get(1).get(1), damage);
        Assert.assertSame(batches.get(2).get(0), cleanup);
        Assert.assertEquals(batches.get(2).size(), 1, "the turn's end left with the next turn's start");
        Assert.assertTrue(forwarder.hasPendingEvents(), "the new turn waits for its own batch");
    }
}
