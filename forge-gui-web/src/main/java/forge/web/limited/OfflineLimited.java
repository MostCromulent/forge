package forge.web.limited;

import forge.deck.Deck;
import forge.deck.DeckGroup;
import forge.deck.DeckSection;
import forge.game.GameType;
import forge.game.GameView;
import forge.gamemodes.limited.BoosterDraft;
import forge.gamemodes.limited.GauntletMini;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.quest.QuestEventDraft;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.util.IHasForgeLog;
import forge.util.Localizer;
import forge.util.storage.IStorage;
import forge.web.WebGuiBase;
import forge.web.WebSession;
import forge.web.WebSession.Event;
import forge.web.WebSession.InCampaign;
import forge.web.WebSession.Playing;
import forge.web.WebSession.Stage;
import forge.web.deck.DeckCatalog;
import forge.web.deck.DeckSession;
import forge.web.deck.DeckStore;
import forge.web.lobby.Lobby;
import forge.web.match.LocalGame;
import forge.web.match.WebGuiGame;
import forge.web.protocol.BrowserChannel;
import forge.web.protocol.Commands;
import forge.web.protocol.FromBrowser.*;
import forge.web.protocol.ToBrowser.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** One session's Limited play against the computer: its sealed pools, its draft and its gauntlet. */
public final class OfflineLimited implements IHasForgeLog {
    private final WebSession session;
    private final WebGuiBase ui;
    private final Lobby lobby;
    private final DeckSession decks;
    private final LocalGame local;
    /** Whether a sealed pool is being opened, which takes seconds and may wait on a question to the player. */
    private final AtomicBoolean openingPacks = new AtomicBoolean();
    /** A match seats at most eight players, so several opponents at once are at most seven. */
    private static final int MOST_OPPONENTS = 7;
    /** Whether this session is playing a limited gauntlet, and the result of its last game. */
    private volatile boolean gauntletRunning;
    public volatile LimitedResult lastResult;
    /** The draft against the computer, while one is being played. */
    private volatile OfflineDraft draft;

    public OfflineLimited(final WebSession session, final WebGuiBase ui, final Lobby lobby, final DeckSession decks, final LocalGame local) {
        this.session = session;
        this.ui = ui;
        this.lobby = lobby;
        this.decks = decks;
        this.local = local;
    }

    public static final Commands<OfflineLimited> COMMANDS = new Commands<OfflineLimited>()
            .on(PoolEdit.class, (l, ch, e) -> l.editPool(ch, e.name()))
            .on(PoolDelete.class, (l, ch, d) -> l.deletePool(ch, d.name()))
            .on(SealedCreate.class, OfflineLimited::createSealed)
            .on(DraftStart.class, OfflineLimited::startDraft)
            .on(DraftSave.class, OfflineLimited::saveDraft)
            .on(Plain.draftDiscard, (l, ch, c) -> l.discardDraft())
            // The gauntlet starts its rounds itself, which, like any match, happens on the host UI thread
            .on(Plain.gauntletNext, (l, ch, c) -> l.ui.invokeInEdtLater(() -> {
                final LimitedResult result = l.lastResult;
                if (l.gauntletRunning && result != null && result.nextRound()) {
                    l.lastResult = null;
                    FModel.getGauntletMini().nextRound();
                }
            }))
            .on(Plain.gauntletRestart, (l, ch, c) -> l.ui.invokeInEdtLater(() -> {
                if (l.gauntletRunning) {
                    l.lastResult = null;
                    final HostedMatch match = l.local.hostedMatch();
                    if (match != null) {
                        match.restartMatch();
                    }
                }
            }))
            // A match is started on the host UI thread, as a table's is
            .on(PoolPlay.class, (l, ch, play) -> l.ui.invokeInEdtLater(() -> l.playPool(ch, play)));

    public void onCommand(final BrowserChannel channel, final Record command) {
        COMMANDS.run(this, channel, command);
    }

    /** The Limited pages are drawn from the pools and what the setup form offers. Reading the lists touches files, so not here. */
    public void sendLimited(final BrowserChannel channel) {
        ui.runBackgroundTask("Limited", () -> {
            channel.send(OfflineEvents.pools());
            channel.send(OfflineEvents.options());
        });
    }

    private void editPool(final BrowserChannel channel, final String name) {
        final Stage now = session.stage();
        final DeckGroup group = now instanceof Event e ? e.storage().get(name) : null;
        if (group == null) {
            channel.send(WebSession.error(Localizer.getInstance().getMessage("lblWebSessionNoPool", name)));
            return;
        }
        final Event e = (Event) now;
        decks.openPool(group.getHumanDeck(), e.storage(), poolType(e, group.getHumanDeck()), channel);
    }

    private void deletePool(final BrowserChannel channel, final String name) {
        final Stage now = session.stage();
        if (!session.hosting() || !(now instanceof Event e)) {
            return;
        }
        if (name.equals(decks.openPoolName())) {
            channel.send(WebSession.error(Localizer.getInstance().getMessage("lblWebSessionClosePoolDeck", name)));
            return;
        }
        synchronized (DeckCatalog.DECKS) {
            if (e.storage().contains(name)) {
                e.storage().delete(name);
            }
        }
        if (name.equals(e.pool())) {
            session.move(now, new Event(e.kind(), null));
        }
        channel.send(OfflineEvents.pools());
    }

    /** Opens a sealed pool as desktop's sealed screen does, then its deck in the editor. Asking for a taken name comes first. */
    private void createSealed(final BrowserChannel channel, final SealedCreate create) {
        if (!session.hosting() || !(session.stage() instanceof Event e) || !"sealed".equals(e.kind())) {
            return;
        }
        final String name = create.name() == null ? "" : create.name().trim();
        final String problem = DeckStore.nameProblem(name);
        if (problem != null) {
            channel.send(WebSession.error(problem));
            return;
        }
        if (OfflineEvents.sealed().contains(name) && !create.replace()) {
            channel.send(new NameTaken(name));
            return;
        }
        // A second request while the first is opening would pass the name check above and replace its pool
        if (!openingPacks.compareAndSet(false, true)) {
            channel.send(WebSession.error(Localizer.getInstance().getMessage("lblWebSessionOpeningPacks")));
            return;
        }
        // Opening can wait on a question to the player, so results go to whichever browser is attached when it is done
        ui.runBackgroundTask("Sealed", () -> {
            try {
                final DeckGroup group;
                try {
                    group = OfflineEvents.create(create, name);
                } catch (final IllegalArgumentException ex) {
                    session.tell(WebSession.error(ex.getMessage()));
                    return;
                }
                if (group == null) {
                    return;
                }
                OfflineEvents.store(OfflineEvents.sealed(), group);
                final Stage now = session.stage();
                if (now instanceof Event) {
                    session.move(now, new Event("sealed", name));
                }
                session.tell(OfflineEvents.pools());
                decks.openPool(group.getHumanDeck(), OfflineEvents.sealed(), GameType.Sealed, session.browser());
            } finally {
                openingPacks.set(false);
            }
        });
    }

    /** Starts an offline booster draft against the computer, as desktop's Draft screen does. One runs at a time. */
    private void startDraft(final BrowserChannel channel, final DraftStart start) {
        synchronized (session) {
            final Stage now = session.stage();
            if (!session.hosting() || !(now instanceof Event e) || !"draft".equals(e.kind())) {
                return;
            }
            if (draft != null) {
                channel.send(WebSession.error(Localizer.getInstance().getMessage("lblWebSessionDraftRunning")));
                return;
            }
            final Supplier<BoosterDraft> make;
            try {
                make = OfflineEvents.draft(start);
            } catch (final IllegalArgumentException ex) {
                channel.send(WebSession.error(ex.getMessage()));
                return;
            }
            beginDraft(make, false);
            session.move(now, new Event("draft", null));
        }
    }

    /** Starts a draft against the computer unless one is running. dealt is true for packs already opened, as a quest tournament's are. */
    public boolean beginDraft(final Supplier<BoosterDraft> make, final boolean dealt) {
        synchronized (session) {
            if (draft != null) {
                return false;
            }
            draft = new OfflineDraft(make, dealt, session.playerName(), session::tell, problem -> {
                endDraft();
                session.tell(WebSession.error(problem));
            });
            return true;
        }
    }

    /** The draft against the computer being played, while there is one. */
    public OfflineDraft draft() {
        return draft;
    }

    /** Stops the draft without saving it, as desktop's "quit without saving" does. */
    public void endDraft() {
        final OfflineDraft ended;
        synchronized (session) {
            ended = draft;
            draft = null;
        }
        if (ended == null) {
            return;
        }
        ended.close();
        final Stage now = session.stage();
        if (now instanceof Event e) {
            session.move(now, new Event(e.kind(), e.pool()));
        } else {
            session.tell(session.hello());
        }
    }

    /** Whether the draft running is a quest tournament's, which the quest's page holds instead of the Limited page. */
    private boolean tournamentDrafting() {
        return draft != null && session.stage() instanceof InCampaign c && "quest".equals(c.mode());
    }

    /** Stops the draft unsaved; a tournament's is left as desktop leaves it, with its fee given back. */
    private void discardDraft() {
        final boolean tournament = tournamentDrafting();
        endDraft();
        if (tournament) {
            session.onCampaign(() -> {
                session.quest.cancelDraft();
                session.sendCampaign();
            });
        }
    }

    /** Saves a finished draft as desktop does, asking before replacing one of the same name, then opens its deck. */
    private void saveDraft(final BrowserChannel channel, final DraftSave save) {
        final OfflineDraft draft = this.draft;
        if (!session.hosting() || draft == null || draft.latest() == null || !draft.latest().done()) {
            return;
        }
        if (tournamentDrafting()) {
            // A tournament's draft is kept under the one name Quest gives it, so the browser's name is not used
            draft.save(QuestEventDraft.DECK_NAME).whenCompleteAsync((group, ex) -> {
                if (ex != null) {
                    session.tell(WebSession.error(Localizer.getInstance().getMessage("lblWebSessionDraftSaveFailed", String.valueOf(ex.getMessage()))));
                    return;
                }
                endDraft();
                session.onCampaign(() -> {
                    final IStorage<DeckGroup> pools = session.quest.drafted(group);
                    session.sendCampaign();
                    decks.openPool(group.getHumanDeck(), pools, GameType.QuestDraft, session.browser());
                });
            });
            return;
        }
        final String name = save.name() == null ? "" : save.name().trim();
        final String problem = DeckStore.nameProblem(name);
        if (problem != null) {
            channel.send(WebSession.error(problem));
            return;
        }
        final IStorage<DeckGroup> drafts = OfflineEvents.storage("draft");
        if (drafts.contains(name) && !save.replace()) {
            channel.send(new NameTaken(name));
            return;
        }
        // Off the draft's own thread, which closing the draft interrupts
        draft.save(name).whenCompleteAsync((group, ex) -> {
            if (ex != null) {
                session.tell(WebSession.error(Localizer.getInstance().getMessage("lblWebSessionDraftSaveFailed", String.valueOf(ex.getMessage()))));
                return;
            }
            OfflineEvents.store(drafts, group);
            endDraft();
            final Stage now = session.stage();
            if (now instanceof Event) {
                session.move(now, new Event("draft", name));
            }
            session.tell(OfflineEvents.pools());
            decks.openPool(group.getHumanDeck(), drafts, poolType(GameType.Draft, group.getHumanDeck()), draft.freeCommander(), session.browser());
        });
    }

    /** A drafted pool is a Commander Draft when its deck carries desktop's tag or a commander, as desktop tells one. */
    private static GameType poolType(final GameType type, final Deck human) {
        return type == GameType.Draft && (human.getTags().contains(OfflineDraft.COMMANDER_DRAFT) || human.has(DeckSection.Commander))
                ? GameType.CommanderDraft : type;
    }

    private static GameType poolType(final Event event, final Deck human) {
        return poolType(event.type(), human);
    }

    /** Plays a pool's deck against one of its opponents, several at once, or every one in turn as a gauntlet, and returns to the pool after. */
    private void playPool(final BrowserChannel channel, final PoolPlay play) {
        final Stage from = session.stage();
        final DeckGroup group = from instanceof Event e ? e.storage().get(play.name()) : null;
        if (!session.hosting() || group == null) {
            return;
        }
        final Event event = (Event) from;
        final List<Deck> ai = group.getAiDecks();
        final Deck human = group.getHumanDeck();
        final GameType limitedType = poolType(event, human);
        if (FModel.getPreferences().getPrefBoolean(FPref.ENFORCE_DECK_LEGALITY)) {
            final String illegal = limitedType.getDeckFormat().getDeckConformanceProblem(human);
            if (illegal != null) {
                channel.send(WebSession.error(Localizer.getInstance().getMessage("lblWebSessionYourDeck", illegal)));
                return;
            }
        }
        if (play.games() == 1 || play.games() == 3 || play.games() == 5) {
            FModel.getPreferences().setPref(FPref.UI_MATCHES_PER_GAME, String.valueOf(play.games()));
            FModel.getPreferences().save();
        }
        final Event back = new Event(event.kind(), play.name());
        switch (play.mode() == null ? "one" : play.mode()) {
            case "gauntlet" -> {
                final GauntletMini gauntlet = FModel.getGauntletMini();
                if (event.type() == GameType.Draft) {
                    gauntlet.resetGauntletDraft();
                }
                gauntletRunning = true;
                lastResult = null;
                gauntlet.setRoundStarter((type, variants, players, me) -> playLimited(back, type, List.of(mySeat(players.get(0).getDeck()),
                        opponentSeat(gauntlet.getCurrentRound(), 1, players.get(1).getDeck()))));
                try {
                    gauntlet.launch(ai.size(), human, limitedType);
                } catch (final RuntimeException ex) {
                    netLog.error(ex, "Could not start the gauntlet");
                    channel.send(WebSession.error(Localizer.getInstance().getMessage("lblWebSessionGauntletFailed", String.valueOf(ex.getMessage()))));
                }
                // A first round that did not start leaves the pool showing, and no gauntlet to carry on
                if (!(session.stage() instanceof Playing)) {
                    stopGauntlet();
                }
            }
            case "several" -> {
                final int count = Math.min(Math.min(play.count(), MOST_OPPONENTS), ai.size());
                if (event.type() != GameType.Draft || count < 2) {
                    channel.send(WebSession.error(Localizer.getInstance().getMessage("lblWebSessionSeveralNeedsDraft")));
                    return;
                }
                // Chosen at random, as desktop's draft screen chooses them
                final List<Integer> indices = new ArrayList<>();
                for (int i = 0; i < ai.size(); i++) {
                    indices.add(i);
                }
                Collections.shuffle(indices);
                final List<LocalGame.Seat> seats = new ArrayList<>(List.of(mySeat(human)));
                for (int k = 0; k < count; k++) {
                    seats.add(opponentSeat(indices.get(k) + 1, k + 1, ai.get(indices.get(k))));
                }
                playLimited(back, limitedType, seats);
            }
            default -> {
                if (play.opponent() < 0 || play.opponent() >= ai.size()) {
                    channel.send(WebSession.error(Localizer.getInstance().getMessage("lblWebSessionNoSuchOpponent")));
                    return;
                }
                playLimited(back, limitedType, List.of(mySeat(human), opponentSeat(play.opponent() + 1, 1, ai.get(play.opponent()))));
            }
        }
    }

    private LocalGame.Seat mySeat(final Deck deck) {
        return new LocalGame.Seat(session.playerName(), false, session.avatarIndex(), LocalGame.storedIndex(FPref.UI_SLEEVES, 0), deck);
    }

    /** An opponent named by its deck's place in the pool, as desktop numbers them, with the looks of seat. */
    private static LocalGame.Seat opponentSeat(final int number, final int seat, final Deck deck) {
        return new LocalGame.Seat(Localizer.getInstance().getMessage("lblWebSessionOpponent", number), true, LocalGame.storedIndex(FPref.UI_AVATARS, seat),
                LocalGame.storedIndex(FPref.UI_SLEEVES, seat), deck);
    }

    /** Starts a pool's match, from the Limited pages or from the match before it in a gauntlet. Null when it could not start. */
    private HostedMatch playLimited(final Event back, final GameType type, final List<LocalGame.Seat> seats) {
        final Stage from = session.stage();
        final Playing playing = new Playing(new WebGuiGame(session.settings), false, false, back);
        if (!session.move(from, playing)) {
            playing.gui().close();
            return null;
        }
        session.attach(playing.gui());
        if (gauntletRunning) {
            playing.gui().onGameOver(() -> recordGauntletGame(playing));
        }
        try {
            local.startLimitedMatch(seats, type, playing.gui());
            return local.hostedMatch();
        } catch (final RuntimeException ex) {
            netLog.error(ex, "Could not start the match");
            local.endMatch();
            session.move(playing, back);
            session.tell(WebSession.error(Localizer.getInstance().getMessage("lblWebSessionMatchFailed", String.valueOf(ex.getMessage()))));
            return null;
        }
    }

    /** A gauntlet game ended: the win or loss is recorded after every game, as desktop's limited result does. */
    private void recordGauntletGame(final Playing playing) {
        final GameView game = playing.gui().getGameView();
        if (!gauntletRunning || game == null) {
            return;
        }
        final GauntletMini gauntlet = FModel.getGauntletMini();
        final boolean won = session.playerName().equals(game.getWinningPlayerName());
        if (won) {
            gauntlet.addWin();
        } else {
            gauntlet.addLoss();
        }
        // The winner of a match's last game is the match's winner
        final boolean wonMatch = game.isMatchOver() && won;
        lastResult = new LimitedResult(gauntlet.getCurrentRound(), gauntlet.getRounds(), gauntlet.getWins(), gauntlet.getLosses(),
                game.isMatchOver(), wonMatch && gauntlet.getCurrentRound() < gauntlet.getRounds());
        session.tell(lastResult);
    }

    /** Leaving a gauntlet ends it, as desktop's Quit does. */
    public void stopGauntlet() {
        if (!gauntletRunning) {
            return;
        }
        gauntletRunning = false;
        lastResult = null;
        FModel.getGauntletMini().resetCurrentRound();
        FModel.getGauntletMini().setRoundStarter(null);
    }
}
