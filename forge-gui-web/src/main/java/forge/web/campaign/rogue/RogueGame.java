package forge.web.campaign.rogue;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import forge.StaticData;
import forge.deck.CardPool;
import forge.game.GameType;
import forge.game.GameView;
import forge.gamemodes.rogue.RogueMetaProgress;
import forge.gamemodes.rogue.RogueRun;
import forge.gamemodes.rogue.effect.CustomRunModification;
import forge.gamemodes.rogue.flow.RogueDecisions;
import forge.gamemodes.rogue.flow.RogueFlow;
import forge.gamemodes.rogue.flow.RogueStart;
import forge.localinstance.achievements.RogueCommanderAchievements;
import forge.localinstance.skin.ISkinImage;
import forge.util.IHasForgeLog;
import forge.util.Localizer;
import forge.web.Campaigns;
import forge.web.WebGuiBase;
import forge.web.WebGuiBase.CardImage;
import forge.web.WebSession.InCampaign;
import forge.web.WebSession.Menu;
import forge.web.WebSession.Stage;
import forge.web.campaign.Campaign;
import forge.web.campaign.rogue.RoguePages.Snapshot;
import forge.web.campaign.rogue.WebRogueDecisions.Open;
import forge.web.deck.DeckEditor;
import forge.web.deck.DeckWorkspace;
import forge.web.protocol.BrowserChannel;
import forge.web.protocol.CampaignProtocol.CampaignResult;
import forge.web.protocol.Commands;
import forge.web.protocol.FromBrowser.Bare;
import forge.web.protocol.FromBrowser.CatalogueQuery;
import forge.web.protocol.FromBrowser.Plain;
import forge.web.protocol.RogueProtocol.*;
import forge.web.protocol.ToBrowser.DeckDetails;
import forge.web.protocol.ToBrowser.DeckDetailsMessage;
import org.apache.commons.lang3.EnumUtils;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Rogue Commander. There is one per server, because the run and the progress are one per profile, and the host's session attaches to it.
 * The flow runs on a thread of its own, one job at a time: a command that arrives while a job runs is refused, not queued.
 * The pages are numbered snapshots that the flow thread publishes, so reading them never touches the model.
 */
public final class RogueGame implements Campaign, IHasForgeLog {
    public static final String MODE = "rogue";
    /** The save name of the open run. The hub has none. */
    public static final String RUN = "run";

    private final Executor worker = WebGuiBase.worker("WebRogue");
    private final AtomicBoolean busy = new AtomicBoolean(true);
    private final RogueFlow flow;
    /** Guards the latest snapshot and the sending of pages, so pages reach the browser in the order they were numbered. */
    private final Object pages = new Object();
    private Snapshot latest = new Snapshot(0, null, null, -1, List.of());
    /** Whether the browser's going stopped the timed run's clock, so its return starts it again. Guarded by pages. */
    private boolean paused;
    private volatile Host host;
    /** The question the flow thread waits on. It is set and sent while holding pages, so it always follows the pages it was asked over. */
    private volatile Open question;
    /** What was won or unlocked and has not reached the host's browser on Rogue's pages yet. Guarded by pages. */
    private final List<RogueAnnounce> announced = new ArrayList<>();
    /** The pages last sent to the host's browser by type, so a publish leaves out those it already holds. Guarded by pages. */
    private final Map<Class<?>, Record> sent = new HashMap<>();

    private static final class Shared {
        static final RogueGame GAME = new RogueGame(WebRogueDecisions::new);
    }

    public static RogueGame shared() {
        return Shared.GAME;
    }

    /** The open run, or null. Only the flow thread calls this. */
    RogueRun run() {
        return flow.run();
    }

    /** decisions is given the game, so tests can answer the flow's questions from a script. */
    public RogueGame(final Function<RogueGame, RogueDecisions> decisions) {
        flow = new RogueFlow(decisions.apply(this));
        worker.execute(job(this::loadSaves));
    }

    private void loadSaves() {
        RogueMetaProgress.reload();
        flow.setRun(null);
        flow.load();
    }

    /**
     * Runs step as a job on the flow thread. It is refused while another job runs, when snapshot is not the latest,
     * or when node is not negative and is not the current node. The run's deck editor is closed first, because the step may change the deck.
     */
    public boolean submit(final long snapshot, final int node, final Runnable step) {
        if (!busy.compareAndSet(false, true)) {
            return false;
        }
        synchronized (pages) {
            if (snapshot != latest.number() || node >= 0 && node != latest.node()) {
                busy.set(false);
                return false;
            }
        }
        worker.execute(job(() -> {
            closeEditor();
            step.run();
        }));
        return true;
    }

    /** Closes the run's deck editor, which drops its undo. Only the flow thread calls this. */
    private void closeEditor() {
        final Host h = host;
        if (h != null) {
            h.decks().closeCollectionDeck(h.session().browser());
        }
    }

    /** Opens the run's deck in the editor, with the run's rules. Each change writes the deck back to the run and saves it, and a failed save is told. */
    private void openEditor(final BrowserChannel channel) {
        final RogueRun run = flow.run();
        final Host h = host;
        if (run == null || h == null) {
            return;
        }
        final RunDeckRules rules = new RunDeckRules(run);
        final DeckEditor.Collection collection = new DeckEditor.Collection(Localizer.getInstance().getMessage("lblRogueCommander"), CardPool::new,
                c -> false, () -> { }, deck -> run.basicLands().stream().map(c -> StaticData.instance().getEditions().get(c.getEdition()))
                .distinct().collect(Collectors.toList()), () -> { }, true, rules);
        h.decks().openCollectionDeck(run.getCurrentDeck(), new DeckEditor.Run(deck -> {
            final String failed = rules.save(deck);
            if (failed != null) {
                tell(failed);
            }
        }), GameType.RogueCommander, collection, true, channel);
    }

    /**
     * Runs a deck editor command as a job, refused while another job runs. Closing is never refused: during a job it only closes,
     * and otherwise leaving the editor evaluates the run's achievements, as desktop does.
     */
    private void deckCommand(final BrowserChannel channel, final Record command, final Host h) {
        final boolean closing = command instanceof Bare b && b.t() == Plain.editorClose;
        if (!busy.compareAndSet(false, true)) {
            if (closing) {
                DeckWorkspace.COMMANDS.run(h.decks(), channel, command);
            }
            return;
        }
        worker.execute(job(() -> {
            DeckWorkspace.COMMANDS.run(h.decks(), channel, command);
            if (closing && flow.run() != null) {
                RogueCommanderAchievements.getInstance().evaluateRunAchievements(flow.run());
            }
        }));
    }

    /** The pages are published before the next command is accepted, so that command is checked against the state this step left. */
    private Runnable job(final Runnable step) {
        return () -> {
            try {
                step.run();
            } catch (final RuntimeException | Error e) {
                netLog.error(e, "A Rogue Commander step failed");
                tell(Localizer.getInstance().getMessage("lblWebRogueStepFailed", String.valueOf(e.getMessage())));
                reloadRun();
            } finally {
                try {
                    publish();
                } finally {
                    busy.set(false);
                }
            }
        };
    }

    /** The run in memory may be part-way through a step, so it is dropped for the one last saved. */
    private void reloadRun() {
        flow.setRun(null);
        try {
            flow.load();
        } catch (final RuntimeException e) {
            netLog.error(e, "Could not load the last Rogue Commander save");
        }
    }

    private void tell(final String error) {
        final Host h = host;
        if (h != null) {
            h.session().tell(h.error(error));
        }
    }

    /** Builds the pages from the model and sends them. Only the flow thread calls this. */
    private void publish() {
        final RogueMetaProgress progress;
        try {
            progress = RogueMetaProgress.getInstance();
        } catch (final UncheckedIOException e) {
            netLog.error(e, "Could not read Rogue Commander's progress");
            return;
        }
        synchronized (pages) {
            latest = RoguePages.build(flow, progress, latest.number() + 1);
            send();
        }
    }

    /**
     * Sends the latest pages if the host is at Rogue. The run's time counts while the host's browser is on the run's pages,
     * and during a new run's start-of-run questions, as on desktop. Call it holding pages.
     */
    private void send() {
        final Host h = host;
        if (h == null || !(h.session().stage() instanceof InCampaign c) || !MODE.equals(c.mode())) {
            return;
        }
        for (final Record page : latest.pages()) {
            if (!page.equals(sent.put(page.getClass(), page))) {
                h.session().tell(page);
            }
        }
        sendAnnounced(h);
        final Open asked = question;
        if (asked != null) {
            h.session().tell(asked.message());
        }
        final RogueRun timed = latest.timed();
        if (h.session().browser() == null) {
            pause();
        } else if (paused) {
            paused = false;
            if (timed != null) {
                timed.getRunTimer().start();
            }
        } else if (RUN.equals(c.save()) && latest.run() != null) {
            latest.run().getRunTimer().start();
        }
    }

    /** Sends what was announced while the host's browser is on Rogue's pages. Call it holding pages. */
    private void sendAnnounced(final Host h) {
        if (h.session().browser() != null && h.session().stage() instanceof InCampaign c && MODE.equals(c.mode())) {
            announced.forEach(h.session()::tell);
            announced.clear();
        }
    }

    /** Only announcements made while a job runs are kept, so another mode's trophies are not shown on Rogue's pages. Called on any thread. */
    void announce(final RogueAnnounce announcement) {
        if (!busy.get()) {
            return;
        }
        synchronized (pages) {
            announced.add(announcement);
            final Host h = host;
            if (h != null) {
                sendAnnounced(h);
            }
        }
    }

    private void announce(final ISkinImage image, final String message, final String title) {
        announce(new RogueAnnounce("unlock", title, message, image instanceof CardImage c ? c.card().getImageKey(false) : null));
    }

    /** Call it holding pages. */
    private void pause() {
        final RogueRun timed = latest.timed();
        if (timed != null && timed.getRunTimer().isRunning()) {
            timed.getRunTimer().stop();
            paused = true;
        }
    }

    /** Waits on the flow thread, with no timeout, for an answer the question's check accepts. */
    JsonElement ask(final Open asked) {
        synchronized (pages) {
            question = asked;
        }
        publish();
        try {
            return asked.answer().join();
        } finally {
            synchronized (pages) {
                question = null;
            }
        }
    }

    /** A refused answer leaves the question open, and the browser is sent it again. */
    private void answer(final BrowserChannel channel, final int id, final JsonElement value) {
        final Open asked = question;
        if (asked == null) {
            return;
        }
        final JsonElement answer = value == null ? JsonNull.INSTANCE : value;
        if (asked.message().id() != id || !asked.valid().test(answer)) {
            channel.send(asked.message());
            return;
        }
        asked.answer().complete(answer);
    }

    void runStarted() {
        publish();
        final Host h = host;
        if (h != null) {
            h.opened(RUN);
        }
    }

    void runEnded() {
        publish();
        final Host h = host;
        final Stage now = h == null ? null : h.session().stage();
        if (now instanceof InCampaign c && MODE.equals(c.mode()) && RUN.equals(c.save()) && h.session().move(now, new InCampaign(MODE, null))) {
            h.show();
        }
    }

    private void stopTimer() {
        synchronized (pages) {
            paused = false;
            if (latest.timed() != null) {
                latest.timed().getRunTimer().stop();
            }
        }
    }

    @Override
    public boolean runsItself() {
        return true;
    }

    @Override
    public boolean oneGame() {
        return true;
    }

    @Override
    public void becameHost(final Host h) {
        stopTimer();
        host = h;
        h.session().ui().setAnnouncer(this::announce);
    }

    @Override
    public void disconnected() {
        synchronized (pages) {
            pause();
        }
    }

    @Override
    public String current() {
        synchronized (pages) {
            return latest.run() != null ? RUN : null;
        }
    }

    @Override
    public boolean mayLeave(final String save) {
        return !busy.get() || question != null;
    }

    /**
     * Opening reads the saves again only when no job runs, because a job's run in memory is newer than its save.
     * During a question, Back from the run goes to the play menu, and opening the pages with no run open goes on to the run.
     */
    @Override
    public void opening(final String save, final Runnable then) {
        stopTimer();
        final Host h = host;
        if (question != null && save == null && h != null) {
            final Campaigns.Host session = h.session();
            final Stage now = session.stage();
            if (now instanceof InCampaign c && c.save() != null) {
                session.move(now, new Menu());
                return;
            }
            then.run();
            if (current() != null) {
                h.opened(RUN);
            }
            return;
        }
        if (!busy.compareAndSet(false, true)) {
            then.run();
            return;
        }
        worker.execute(job(() -> {
            closeEditor();
            try {
                loadSaves();
            } catch (final UncheckedIOException e) {
                netLog.error(e, "Could not read Rogue Commander's progress");
                tell(Localizer.getInstance().getMessage("lblRogueProgressUnreadable"));
                return;
            }
            publish();
            then.run();
            if (save == null) {
                flow.betweenRuns();
            }
        }));
    }

    @Override
    public String open(final String save) {
        return RUN.equals(save) ? current() : null;
    }

    /**
     * The pages are sent here instead of returned, so a reload's pages cannot reach the browser after a newer snapshot.
     * All of them are sent, because the browser may hold none.
     */
    @Override
    public List<Record> home() {
        synchronized (pages) {
            sent.clear();
            send();
        }
        return List.of();
    }

    @Override
    public List<Record> page() {
        return home();
    }

    @Override
    public CampaignResult gameOver(final GameView hostGame) {
        return null;
    }

    @Override
    public void left(final String choice) {
    }

    /** Null when a name is not a modification. */
    private static Set<CustomRunModification> modifications(final List<String> names) {
        final Set<CustomRunModification> modifications = EnumSet.noneOf(CustomRunModification.class);
        for (final String name : names) {
            final CustomRunModification modification = EnumUtils.getEnum(CustomRunModification.class, name);
            if (modification == null) {
                return null;
            }
            modifications.add(modification);
        }
        return modifications;
    }

    private static RogueStart start(final RogueBegin begin) {
        final Set<CustomRunModification> modifications = modifications(begin.modifications());
        if (modifications == null) {
            return null;
        }
        return new RogueStart(RoguePages.deck(begin.deck()), begin.custom(), modifications, begin.descension());
    }

    /** Runs on the flow thread, because it reads the progress. Null for an unknown modification. */
    private static RogueChoice choice(final RogueChoose asked) {
        final Set<CustomRunModification> asOn = modifications(asked.modifications());
        final CustomRunModification toggle = asked.toggle() == null ? null : EnumUtils.getEnum(CustomRunModification.class, asked.toggle());
        if (asOn == null || asked.toggle() != null && toggle == null) {
            return null;
        }
        final Set<CustomRunModification> on = toggle == null ? asOn : RogueStart.toggle(asOn, toggle, !asOn.contains(toggle));
        final RogueStart start = new RogueStart(RoguePages.deck(asked.deck()), true, on, asked.descension());
        return new RogueChoice(asked.deck(), on.stream().map(Enum::name).toList(), asked.descension(),
                on.stream().filter(m -> RogueStart.isForced(on, m)).map(Enum::name).toList(), start.canBegin(RogueMetaProgress.getInstance()));
    }

    public static final Commands<RogueGame> COMMANDS = new Commands<RogueGame>()
            .on(RogueSelect.class, (g, ch, s) -> g.submit(s.snapshot(), -1, () -> g.flow.select(s.node())))
            .on(RogueEnter.class, (g, ch, e) -> g.submit(e.snapshot(), e.node(), g.flow::enter))
            .on(RogueReroll.class, (g, ch, r) -> g.submit(r.snapshot(), r.node(), g.flow::rerollPlane))
            .on(RogueAbandon.class, (g, ch, a) -> g.submit(a.snapshot(), -1, g.flow::abandon))
            .on(RogueEditDeck.class, (g, ch, d) -> g.submit(d.snapshot(), -1, () -> g.openEditor(ch)))
            .on(RogueAnswer.class, (g, ch, a) -> g.answer(ch, a.id(), a.value()))
            .on(RogueStartDeck.class, (g, ch, d) -> {
                final DeckDetails details = RoguePages.startDeck(d.deck());
                if (details != null) {
                    ch.send(new DeckDetailsMessage(details));
                }
            })
            .on(RogueChoose.class, (g, ch, c) -> g.worker.execute(() -> {
                final RogueChoice choice = choice(c);
                if (choice != null) {
                    ch.send(choice);
                }
            }))
            .on(RogueBegin.class, (g, ch, b) -> g.submit(b.snapshot(), -1, () -> {
                final RogueStart start = start(b);
                if (start != null) {
                    g.flow.begin(start);
                }
            }));

    @Override
    public boolean handles(final Record command) {
        return COMMANDS.handles(command);
    }

    /** Runs on the web server's thread. Each command becomes a job, so it never waits. */
    @Override
    public void handle(final BrowserChannel channel, final Record command, final String save, final Host h) {
        if (command instanceof CatalogueQuery q && WebRogueDecisions.COMMANDERS.equals(q.source())) {
            final Open asked = question;
            if (asked != null && asked.offer() != null) {
                channel.send(asked.offer().apply(q));
            }
            return;
        }
        if (!COMMANDS.run(this, channel, command) && DeckWorkspace.COMMANDS.handles(command)) {
            deckCommand(channel, command, h);
        }
    }
}
