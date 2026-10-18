package forge.net.embedded;

import com.google.common.eventbus.Subscribe;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.event.GameEventTurnBegan;
import forge.game.player.RegisteredPlayer;
import forge.game.replacement.ReplacementEffect;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbility;
import forge.game.trigger.Trigger;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.net.TestUtils;
import forge.player.GamePlayerUtil;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Plays AI games on parallel threads in one JVM and checks that they share no state. */
public class ConcurrentGamesStressTest {
    private static final String[][] DECKS = {
            {"Red", "Mountain:16", "Lightning Bolt:4", "Shock:4", "Mogg Fanatic:4", "Goblin Piker:4", "Hill Giant:4", "Lava Axe:4"},
            {"Green", "Forest:16", "Llanowar Elves:4", "Grizzly Bears:4", "Giant Growth:4", "Prey Upon:4", "Craw Wurm:4", "Elvish Visionary:4"},
            {"Black", "Swamp:16", "Doom Blade:4", "Gravedigger:4", "Vampire Nighthawk:4", "Hypnotic Specter:4", "Duress:4", "Sign in Blood:4"},
            {"White", "Plains:16", "Savannah Lions:4", "Pacifism:4", "Serra Angel:4", "Raise the Alarm:4", "Swords to Plowshares:4", "Ajani's Pridemate:4"},
    };
    private static final int GAMES = 12;
    private static final int THREADS = 6;
    private static final AtomicInteger THREAD_SEQ = new AtomicInteger();

    private static Deck deck(final int index) {
        final String[] spec = DECKS[index];
        final Deck d = new Deck(spec[0]);
        for (int i = 1; i < spec.length; i++) {
            final String[] p = spec[i].split(":");
            d.getMain().add(p[0], Integer.parseInt(p[1]));
        }
        return d;
    }

    /**
     * The objects of a game at one moment, by kind and id. A card that changes zones is copied under its id, so ids are
     * compared within one moment: two live objects under one id mean an id counter lost an update.
     */
    private static final class Ids {
        final Map<String, Map<Integer, Set<Object>>> byKind = new HashMap<>();

        void add(final String kind, final int id, final Object o) {
            byKind.computeIfAbsent(kind, k -> new HashMap<>()).computeIfAbsent(id, k -> Collections.newSetFromMap(new IdentityHashMap<>())).add(o);
        }

        void record(final Game g) {
            for (final Card c : g.getCardsInGame()) {
                add("card", c.getId(), c);
                for (final SpellAbility sa : c.getAllSpellAbilities()) add("ability", sa.getId(), sa);
                for (final Trigger t : c.getTriggers()) add("trigger", t.getId(), t);
                for (final StaticAbility st : c.getStaticAbilities()) add("static", st.getId(), st);
                for (final ReplacementEffect re : c.getReplacementEffects()) add("replacement", re.getId(), re);
            }
        }

        List<String> shared() {
            final List<String> out = new ArrayList<>();
            byKind.forEach((kind, m) -> m.forEach((id, objects) -> {
                if (objects.size() > 1) out.add(kind + " " + id + " x" + objects.size());
            }));
            return out;
        }
    }

    private static void check(final int index, final Game game, final Queue<String> problems) {
        final Ids ids = new Ids();
        ids.record(game);
        for (final String s : ids.shared()) {
            problems.add("game " + index + " turn " + game.getPhaseHandler().getTurn() + " gave one id to two objects: " + s);
        }
    }

    private static Game play(final int index, final Queue<String> problems) {
        final int a = index % DECKS.length, b = (index + 1) % DECKS.length;
        final GameRules rules = new GameRules(GameType.Constructed);
        rules.setGamesPerMatch(1);
        final Match match = new Match(rules, List.of(
                new RegisteredPlayer(deck(a)).setPlayer(GamePlayerUtil.createAiPlayer("AI" + index + "a", 0)),
                new RegisteredPlayer(deck(b)).setPlayer(GamePlayerUtil.createAiPlayer("AI" + index + "b", 1))), "Concurrent" + index);
        final Game game = match.createGame();
        game.setNoGUIUser();
        game.subscribeToEvents(new Object() {
            @Subscribe
            public void turn(final GameEventTurnBegan e) {
                check(index, game, problems);
            }
        });
        try {
            match.startGame(game);
        } catch (final RuntimeException e) {
            problems.add("game " + index + " threw " + e);
        }
        check(index, game, problems);
        if (game.getOutcome() == null) {
            problems.add("game " + index + " ended without an outcome");
        }
        return game;
    }

    // Fails if games on parallel threads share an id, lose their outcome, or throw
    @Test(timeOut = 1_800_000)
    public void gamesOnParallelThreadsShareNoState() throws Exception {
        if (!"true".equalsIgnoreCase(System.getProperty("run.stress.tests"))) {
            throw new SkipException("Stress tests skipped. Use -Drun.stress.tests=true to run.");
        }
        TestUtils.ensureFModelInitialized();
        FModel.getPreferences().setPref(FPref.UI_RECORD_REPLAYS, false);
        FModel.getPreferences().setPref(FPref.MATCH_AI_TIMEOUT, "5");
        final Queue<String> problems = new ConcurrentLinkedQueue<>();
        final Queue<Throwable> uncaught = new ConcurrentLinkedQueue<>();
        final ExecutorService pool = Executors.newFixedThreadPool(THREADS, r -> {
            // A name starting with "Game" makes ThreadUtil.isGameThread() true, as on HostedMatch's pool
            final Thread t = new Thread(r, "Game-concurrent-" + THREAD_SEQ.incrementAndGet());
            t.setDaemon(true);
            t.setUncaughtExceptionHandler((th, e) -> uncaught.add(e));
            return t;
        });
        final List<Future<Game>> games = new ArrayList<>();
        for (int i = 0; i < GAMES; i++) {
            final int index = i;
            games.add(pool.submit(() -> play(index, problems)));
        }
        final Set<Integer> gameIds = new java.util.HashSet<>();
        for (final Future<Game> f : games) {
            Assert.assertTrue(gameIds.add(f.get(600, TimeUnit.SECONDS).getId()), "two games got one game id");
        }
        pool.shutdownNow();
        System.out.println("[CONC] " + GAMES + " games on " + THREADS + " threads, problems " + problems + ", uncaught " + uncaught);
        Assert.assertTrue(problems.isEmpty(), String.valueOf(problems));
        Assert.assertTrue(uncaught.isEmpty(), String.valueOf(uncaught));
    }
}
