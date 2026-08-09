package forge;

import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.util.collect.FCollection;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class FCollectionTest {
    /**
     * Just a quick test for FCollection.
     */
    /*@Test
    void testBadIteratorLogic() {
        List<Card> cards = new ArrayList<>();
        for (int i = 1; i < 5; i++)
            cards.add(new Card(i, null));
        CardCollection cc = new CardCollection(cards);
        Iterator<Card> it = cc.iterator();
        it.next();
        it.remove();
        assertEquals(cc.size(), 3);
    }

    /*@Test
    void testBadIteratorLogicTwo() {
        List<Card> cards = new ArrayList<>();
        for (int i = 1; i <= 10; i++)
            cards.add(new Card(i, null));
        CardCollection cc = new CardCollection(cards);
        int i = 0;
        for (Card c : cc) {
            if (i != 3)
                cc.remove(c);  // throws error if the CardCollection not threadsafe
            i++;
        }
        assertEquals(cc.size(), 1);
    }*/// Commented out since we use synchronized collection and it doesn't support modification while iteration

    @Test
    void testCompletableFuture() {
        List<Card> cards = new ArrayList<>();
        for (int i = 1; i < 5; i++)
            cards.add(new Card(i, null));
        CardCollection cc = new CardCollection(cards);
        List<CompletableFuture<Integer>> futures = new ArrayList<>();
        for (Card c : cc.threadSafeIterable()) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                if (c.getId() % 2 > 0)
                    cc.remove(c);
                return 0;
            }));
        }
        CompletableFuture<?>[] futuresArray = futures.toArray(new CompletableFuture<?>[0]);
        CompletableFuture.allOf(futuresArray).join();
        futures.clear();
        assertEquals(cc.size(), 2);
    }

    /**
     * A thread-safe iteration never hands out a null element.
     *
     * <p>The copy it is built on reads the backing array and the size separately, so a
     * concurrent write leaves nulls in the tail. That is worse than the concurrent
     * modification it was added to prevent: no exception is thrown and the caller is handed
     * an element that was never in the collection. Before the tail was dropped this saw
     * millions of them.
     */
    @Test
    void threadSafeIterationYieldsNoNulls() throws Exception {
        final FCollection<String> collection = new FCollection<>();
        for (int i = 0; i < 40; i++) {
            collection.add("e" + i);
        }

        final AtomicBoolean stop = new AtomicBoolean(false);
        final AtomicLong reads = new AtomicLong();
        final AtomicLong writes = new AtomicLong();
        final AtomicLong nulls = new AtomicLong();
        final AtomicLong thrown = new AtomicLong();

        final Thread writer = new Thread(() -> {
            int n = 0;
            while (!stop.get()) {
                final String e = "x" + (n++ & 0xFF);
                collection.add(e);
                collection.remove(e);
                writes.incrementAndGet();
            }
        });
        final Thread reader = new Thread(() -> {
            while (!stop.get()) {
                try {
                    for (final String s : collection.threadSafeIterable()) {
                        if (s == null) {
                            nulls.incrementAndGet();
                        }
                    }
                    reads.incrementAndGet();
                } catch (final Throwable t) {
                    thrown.incrementAndGet();
                }
            }
        });

        writer.start();
        reader.start();
        Thread.sleep(2000);
        stop.set(true);
        writer.join(5000);
        reader.join(5000);

        assertEquals(nulls.get(), 0L, "thread-safe iteration produced null elements");
        assertEquals(thrown.get(), 0L, "thread-safe iteration threw");
        assertTrue(reads.get() > 0 && writes.get() > 0, "neither thread ran, so nothing was tested");
    }
}
