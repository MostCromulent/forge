package forge.web.match;

import com.google.common.eventbus.Subscribe;
import forge.game.Game;
import forge.game.event.GameEvent;
import forge.game.event.GameEventTurnBegan;
import forge.gamemodes.match.HostedMatch;
import forge.util.IHasForgeLog;

import javax.management.NotificationEmitter;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryNotificationInfo;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.ThreadMXBean;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Ends one table's game when it would hold up the others. Every table plays in this process, so a game whose thread runs
 * without its turn advancing slows every table, and a game that fills the heap would end them all. Such a game ends as a draw.
 *
 * <p>A game ends when its thread has died or is deadlocked, or when it uses more CPU in one turn than
 * {@link #TURN_CPU_SECONDS} allows, 120 seconds by default. A thread that waits for a player uses none, so a slow player
 * never counts. When the old generation is still above its limit after a full collection, the game with the most cards ends.
 * The limit is {@link #HEAP_LIMIT_MB} megabytes if set, or 70% of the largest heap.
 *
 * <p>A loop that never checks whether its game is over cannot be stopped in this process, and neither can a deadlocked
 * thread. Their players are released, but their threads and memory stay until the server restarts.
 */
public final class TableWatchdog implements IHasForgeLog {
    static final String TURN_CPU_SECONDS = "forge.web.turnCpuSeconds";
    static final String HEAP_LIMIT_MB = "forge.web.heapLimitMb";
    private static final long SAMPLE_MILLIS = 1000;
    /**
     * The least time between two forced collections. G1 reports the threshold after young collections too, so without it every
     * report would force another full collection. It also gives a game ended for the heap time to be freed before another ends.
     */
    private static final long HEAP_COOLDOWN_MILLIS = 30_000;

    private static final Set<LocalGame> tables = ConcurrentHashMap.newKeySet();
    private static final Map<Game, Turn> turns = Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    private static ScheduledExecutorService sampler;
    private static MemoryPoolMXBean oldGen;
    private static volatile long lastHeapCheck;

    /**
     * The game thread and its CPU time when the current turn began, or when it was first seen. Game events fire on the game
     * thread. Its pool reuses threads across games, so CPU from before the game was seen is not counted.
     */
    private static final class Turn {
        volatile Thread thread;
        volatile long cpuAtStart;
        volatile int cards;
        volatile boolean ended;

        @Subscribe
        public void fired(final GameEvent e) {
            if (thread == null || e instanceof GameEventTurnBegan) {
                cpuAtStart = threads.getCurrentThreadCpuTime();
                thread = Thread.currentThread();
            }
        }
    }

    private TableWatchdog() { }

    static synchronized void watch(final LocalGame table) {
        tables.add(table);
        if (sampler == null) {
            sampler = Executors.newSingleThreadScheduledExecutor(r -> {
                final Thread t = new Thread(r, "TableWatchdog");
                t.setDaemon(true);
                return t;
            });
            sampler.scheduleWithFixedDelay(TableWatchdog::sample, SAMPLE_MILLIS, SAMPLE_MILLIS, TimeUnit.MILLISECONDS);
            listenForFullHeap();
        }
    }

    static void forget(final LocalGame table) {
        tables.remove(table);
    }

    /** Called as soon as a game starts. A loop can start before the sampler's next look, but not before the game's first event. */
    public static void started(final Game game) {
        if (game != null) {
            turnOf(game);
        }
    }

    private static Turn turnOf(final Game game) {
        return turns.computeIfAbsent(game, g -> {
            final Turn t = new Turn();
            g.subscribeToEvents(t);
            return t;
        });
    }

    private static void sample() {
        if (tables.isEmpty()) {
            return;
        }
        try {
            applyHeapLimit();
            final long budget = TimeUnit.SECONDS.toNanos(Long.getLong(TURN_CPU_SECONDS, 120));
            final Set<Long> deadlocked = new HashSet<>();
            final long[] ids = threads.findDeadlockedThreads();
            if (ids != null) {
                for (final long id : ids) {
                    deadlocked.add(id);
                }
            }
            for (final LocalGame table : tables) {
                final Game game = runningGame(table);
                final Turn turn = game == null ? null : turnOf(game);
                final Thread thread = turn == null || turn.ended ? null : turn.thread;
                if (thread == null) {
                    continue;
                }
                final long cpu = threads.getThreadCpuTime(thread.getId());
                if (!thread.isAlive()) {
                    end(table, turn, game, true, "its thread died");
                } else if (deadlocked.contains(thread.getId())) {
                    end(table, turn, game, true, "its thread is deadlocked");
                } else if (cpu >= 0 && cpu - turn.cpuAtStart > budget) {
                    end(table, turn, game, false, "its thread used " + TimeUnit.NANOSECONDS.toSeconds(cpu - turn.cpuAtStart)
                            + " s of CPU in turn " + game.getPhaseHandler().getTurn() + " without moving on");
                }
            }
        } catch (final RuntimeException e) {
            // The sampler must keep running, or no table would be watched again
            netLog.error(e, "Table watchdog sample failed");
        }
    }

    /** Ends the game once. The table ends it off this thread, because a deadlocked game can hold the lock that ending it takes. */
    private static void end(final LocalGame table, final Turn turn, final Game game, final boolean threadStopped, final String why) {
        turn.ended = true;
        netLog.warn("Ending game {}: {}", game.getId(), why);
        table.abandonGame(game, threadStopped);
    }

    private static Game runningGame(final LocalGame table) {
        final HostedMatch match = table.hostedMatch();
        final Game game = match == null ? null : match.getGame();
        return game == null || game.isGameOver() ? null : game;
    }

    /** The old generation's collection threshold fires after a collection that leaves it above the limit. */
    private static void listenForFullHeap() {
        for (final MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP && pool.isCollectionUsageThresholdSupported()
                    && (pool.getName().contains("Old") || pool.getName().contains("Tenured"))) {
                oldGen = pool;
            }
        }
        if (oldGen == null) {
            netLog.warn("No old generation pool reports its use after a collection, so a full heap is not watched");
            return;
        }
        applyHeapLimit();
        ((NotificationEmitter) ManagementFactory.getMemoryMXBean()).addNotificationListener((n, handback) -> {
            if (MemoryNotificationInfo.MEMORY_COLLECTION_THRESHOLD_EXCEEDED.equals(n.getType())) {
                sampler.execute(TableWatchdog::heapFull);
            }
        }, null, null);
    }

    private static void applyHeapLimit() {
        final MemoryPoolMXBean pool = oldGen;
        if (pool == null) {
            return;
        }
        final long mb = Long.getLong(HEAP_LIMIT_MB, 0);
        final long max = pool.getUsage().getMax();
        final long limit = mb > 0 ? mb << 20 : max > 0 ? max / 10 * 7 : 0;
        if (limit > 0 && pool.getCollectionUsageThreshold() != limit) {
            pool.setCollectionUsageThreshold(limit);
        }
    }

    private static void heapFull() {
        if (System.currentTimeMillis() - lastHeapCheck < HEAP_COOLDOWN_MILLIS) {
            return;
        }
        lastHeapCheck = System.currentTimeMillis();
        // G1 counts garbage that concurrent marking has not yet reclaimed, so only a full collection shows what is live
        System.gc();
        final long limit = oldGen.getCollectionUsageThreshold();
        if (oldGen.getUsage().getUsed() < limit) {
            return;
        }
        LocalGame largest = null;
        Turn largestTurn = null;
        Game largestGame = null;
        for (final LocalGame table : tables) {
            final Game game = runningGame(table);
            if (game == null) {
                continue;
            }
            final Turn turn = turnOf(game);
            try {
                turn.cards = game.getCardsInGame().size();
            } catch (final RuntimeException e) {
                // The game thread changes the zones while they are counted, so the last count stands
            }
            if (!turn.ended && (largestTurn == null || turn.cards > largestTurn.cards)) {
                largest = table;
                largestTurn = turn;
                largestGame = game;
            }
        }
        if (largest != null) {
            end(largest, largestTurn, largestGame, false, "the heap is above " + (limit >> 20) + " MB after a full collection, and it has the most cards ("
                    + largestTurn.cards + ")");
        }
    }
}
