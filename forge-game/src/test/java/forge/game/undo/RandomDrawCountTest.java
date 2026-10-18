package forge.game.undo;

import forge.util.MyRandom;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.concurrent.atomic.AtomicLong;

/** Undo bars itself when its game draws randomness, so each game must count only its own draws. */
public class RandomDrawCountTest {
    private static Thread game(final AtomicLong count, final Runnable play) {
        final Thread t = new Thread(() -> {
            MyRandom.countDrawsIn(count);
            play.run();
        });
        t.start();
        return t;
    }

    // Fails if one game's draws count in another game's, or if draws on a helper thread its game started are not counted
    @Test
    public void eachGameCountsItsOwnDrawsAndItsHelpersDraws() throws InterruptedException {
        final AtomicLong quiet = new AtomicLong();
        final AtomicLong busy = new AtomicLong();
        final Thread a = game(quiet, () -> { });
        final Thread b = game(busy, () -> {
            for (int i = 0; i < 10; i++) {
                MyRandom.getRandom().nextInt();
            }
            final Thread helper = new Thread(() -> MyRandom.getRandom().nextBoolean());
            helper.start();
            try {
                helper.join();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        a.join();
        b.join();
        Assert.assertEquals(quiet.get(), 0, "another game's draws were counted");
        Assert.assertEquals(busy.get(), 11, "the game's own draws or its helper thread's were not all counted");
    }
}
