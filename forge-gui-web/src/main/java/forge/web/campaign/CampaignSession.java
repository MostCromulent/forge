package forge.web.campaign;

import forge.gamemodes.match.PreparedMatch;
import forge.gamemodes.match.PreparedMatch.Looks;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.web.Campaigns;
import forge.web.WebGuiBase;
import forge.web.WebSession;
import forge.web.WebSession.InCampaign;
import forge.web.WebSession.Menu;
import forge.web.WebSession.Playing;
import forge.web.WebSession.Stage;
import forge.web.deck.DeckWorkspace;
import forge.web.match.NetplaySeat;
import forge.web.protocol.BrowserChannel;
import forge.web.protocol.CampaignProtocol.*;
import forge.web.protocol.Commands;
import forge.web.protocol.Commands.Handler;
import forge.web.protocol.FromBrowser.Bare;
import forge.web.protocol.FromBrowser.CatalogueQuery;
import forge.web.protocol.FromBrowser.Plain;
import forge.web.protocol.ToBrowser.Notice;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;

/** One session's campaign modes. It holds the commands their pages share, the thread they run on, and what they show as the browser moves between pages. */
public final class CampaignSession implements Campaigns {
    private final Campaigns.Host session;
    /** The modes by the name the browser opens them by. */
    private final Map<String, Campaign> modes;
    /** One thread for the work of every mode that has none of its own, because the campaign model is Forge's own and has no locking. */
    private final Executor work = WebGuiBase.worker("WebCampaign");

    public CampaignSession(final Campaigns.Host session, final Map<String, Campaign> modes) {
        this.session = session;
        this.modes = modes;
    }

    private final Campaign.Host host = new Campaign.Host() {
        @Override
        public Record error(final String text) {
            return WebSession.error(text);
        }

        @Override
        public DeckWorkspace decks() {
            return session.decks();
        }

        @Override
        public void opened(final String save) {
            final Stage from = session.stage();
            if (from instanceof InCampaign c && c.save() == null && session.move(from, new InCampaign(c.mode(), save))) {
                show();
            }
        }

        @Override
        public void startMatch(final String mode, final String save, final Supplier<PreparedMatch> prepare, final Runnable failed) {
            if (!(session.stage() instanceof InCampaign back) || !back.equals(new InCampaign(mode, save))) {
                failed.run();
                return;
            }
            // A game's result belongs to the campaign that started it, wherever the browser is when the result is recorded
            final Campaign campaign = modes.get(mode);
            session.startMatch(back, gui -> {
                final PreparedMatch prepared = prepare.get();
                if (prepared == null) {
                    throw new IllegalStateException("A match is already being played");
                }
                // The game names its winner by this name. The board finds the seat by the session's player name
                prepared.human().getPlayer().setName(session.playerName());
                // A mode may play as Forge's one shared human player. Its avatar and sleeve are whatever the last match set
                final Looks looks = prepared.looks() != null ? prepared.looks()
                        : new Looks(session.avatarIndex(), NetplaySeat.storedIndex(FPref.UI_SLEEVES, 0));
                prepared.human().getPlayer().setAvatarIndex(looks.avatar());
                prepared.human().getPlayer().setSleeveIndex(looks.sleeve());
                if (campaign.oneGame()) {
                    gui.oneGameOnly();
                }
                final NetplaySeat local = session.local();
                local.openAlone(session.playerName(), gui).startPrepared(prepared, local.webSeat());
            }, failed, game -> run(() -> {
                final Record result = campaign.gameOver(game);
                if (result != null) {
                    session.tell(result);
                    campaign.resultSent();
                }
            }));
        }

        @Override
        public Campaigns.Host session() {
            return session;
        }

        @Override
        public void run(final Runnable job) {
            CampaignSession.this.run(job);
        }

        @Override
        public void show() {
            CampaignSession.this.show();
        }
    };

    public void run(final Runnable job) {
        work.execute(job);
    }

    /** A command that works on the open save of a campaign. */
    private interface OpenHandler<T> {
        void run(CampaignSession campaigns, BrowserChannel channel, Campaign campaign, T command);
    }

    /** Runs on the campaign's thread, and only while one of its saves is open. */
    private static <T extends Record> Handler<CampaignSession, T> inOpen(final OpenHandler<T> handler) {
        return (s, ch, command) -> s.run(() -> {
            if (s.session.stage() instanceof InCampaign c && c.save() != null) {
                handler.run(s, ch, s.modes.get(c.mode()), command);
            }
        });
    }

    /** Runs on the campaign's thread while a mode is open, with or without a save. A mode's preferences belong to the process, not to a save. */
    private static <T extends Record> Handler<CampaignSession, T> inMode(final OpenHandler<T> handler) {
        return (s, ch, command) -> s.run(() -> {
            if (s.session.stage() instanceof InCampaign c) {
                handler.run(s, ch, s.modes.get(c.mode()), command);
            }
        });
    }

    /** Sends a mode's answer. A mode without that page has none. */
    private static void send(final BrowserChannel channel, final Record answer) {
        if (answer != null) {
            channel.send(answer);
        }
    }

    /** The commands every mode's pages share. Each mode's own are in its table. */
    public static final Commands<CampaignSession> COMMANDS = new Commands<CampaignSession>()
            // A campaign is Forge's own, with one per process, so only the host may open it
            .on(CampaignOpen.class, (s, ch, o) -> {
                final Campaign campaign = s.modes.get(o.mode());
                if (s.session.hosting() && campaign != null) {
                    s.run(() -> s.open(o.mode(), o.resume() ? campaign.current() : null));
                }
            })
            .on(CampaignLoad.class, (s, ch, l) -> s.run(() -> {
                if (s.session.stage() instanceof InCampaign c) {
                    s.open(c.mode(), l.name());
                }
            }))
            .on(CampaignPlain.campaignLeave, (s, ch, l) -> s.run(() -> {
                final Stage now = s.session.stage();
                if (now instanceof InCampaign c) {
                    if (c.save() != null) {
                        s.open(c.mode(), null);
                    } else if (s.modes.get(c.mode()).mayLeave(null)) {
                        s.session.move(now, new Menu());
                    }
                }
            }))
            .on(CampaignRename.class, (s, ch, r) -> s.run(() -> s.shelf(ch, campaign -> campaign.rename(r.name(), r.to()))))
            .on(CampaignDelete.class, (s, ch, d) -> s.run(() -> s.shelf(ch, campaign -> {
                campaign.delete(d.name());
                return null;
            })))
            .on(CampaignPlain.rewardClaim, inOpen((s, ch, campaign, c) -> campaign.claim(s.host)))
            .on(CampaignStatsQuery.class, inOpen((s, ch, campaign, q) -> send(ch, campaign.stats(q.scope()))))
            .on(CampaignPlain.campaignPrefs, inMode((s, ch, campaign, c) -> send(ch, campaign.prefs(null))))
            .on(CampaignPref.class, inMode((s, ch, campaign, p) -> send(ch, campaign.prefs(campaign.setPref(p.key(), p.value())))))
            .on(CampaignPlain.campaignPrefsReset, inMode((s, ch, campaign, c) -> {
                campaign.resetPrefs();
                send(ch, campaign.prefs(null));
            }))
            .on(CampaignPlain.trading, inOpen((s, ch, campaign, c) -> send(ch, campaign.trading())))
            .on(Trade.class, inOpen((s, ch, campaign, trade) -> {
                final String problem = campaign.trade(trade.source(), trade.picks());
                if (problem != null) {
                    ch.send(new Notice(problem, null, false));
                }
                // The traded cards may have been on any of the campaign's pages
                campaign.page().forEach(m -> ch.send(m));
                send(ch, campaign.reward());
                send(ch, campaign.trading());
            }));

    @Override
    public boolean handles(final Record command) {
        return COMMANDS.handles(command) || modes.values().stream().anyMatch(m -> m.handles(command));
    }

    @Override
    public void run(final BrowserChannel channel, final Record command) {
        if (COMMANDS.run(this, channel, command)) {
            return;
        }
        for (final Campaign campaign : modes.values()) {
            if (campaign.anywhere(command)) {
                run(() -> campaign.handle(channel, command, null, host));
                return;
            }
        }
        // Every other command from a campaign's pages belongs to the open mode
        if (ranItself(channel, command)) {
            return;
        }
        run(() -> {
            if (session.stage() instanceof InCampaign c) {
                modes.get(c.mode()).handle(channel, command, c.save(), host);
            }
        });
    }

    /** Passes a command to the open mode at once when the mode has a thread of its own. Returns whether it was passed. */
    private boolean ranItself(final BrowserChannel channel, final Record command) {
        if (!(session.stage() instanceof InCampaign c) || !modes.get(c.mode()).runsItself()) {
            return false;
        }
        modes.get(c.mode()).handle(channel, command, c.save(), host);
        return true;
    }

    @Override
    public boolean deckCommand(final BrowserChannel channel, final Record command) {
        if (ranItself(channel, command)) {
            return true;
        }
        if (command instanceof CatalogueQuery q && q.source() != null) {
            // A campaign's own cards, which do not need an open deck
            inOpen((s, ch, campaign, c) -> send(ch, campaign.cards(q))).run(this, channel, command);
            return true;
        }
        if (!(session.stage() instanceof InCampaign)) {
            return false;
        }
        // A campaign's collection editor reads and saves the campaign's cards. Only the campaign thread may touch them
        run(() -> DeckWorkspace.COMMANDS.run(session.decks(), channel, command));
        // A deck may have changed size or become playable, and the campaign's pages show both
        if (command instanceof Bare b && b.t() == Plain.editorClose) {
            inOpen((s, ch, campaign, c) -> campaign.page().forEach(m -> ch.send(m))).run(this, channel, command);
        }
        return true;
    }

    @Override
    public void reconnected(final BrowserChannel channel, final Stage now) {
        if (now instanceof Playing p && p.back() instanceof InCampaign c) {
            send(channel, modes.get(c.mode()).result());
        } else if (now instanceof InCampaign c && modes.get(c.mode()).runsItself()) {
            show();
        } else if (now instanceof InCampaign) {
            run(this::show);
        }
    }

    @Override
    public void disconnected(final Stage now) {
        if ((now instanceof Playing p ? p.back() : now) instanceof InCampaign c) {
            modes.get(c.mode()).disconnected();
        }
    }

    @Override
    public Map<String, String> resume() {
        final Map<String, String> saves = new LinkedHashMap<>();
        modes.forEach((mode, campaign) -> {
            final String save = campaign.current();
            if (save != null) {
                saves.put(mode, save);
            }
        });
        return saves;
    }

    @Override
    public void becameHost() {
        modes.values().forEach(m -> m.becameHost(host));
    }

    @Override
    public void leavingMatch(final InCampaign back) {
        modes.get(back.mode()).leavingMatch();
    }

    @Override
    public void left(final Playing from, final InCampaign back, final String choice) {
        // Queued after the last game's result, so the campaign records the game before it is told the match was left
        final Campaign campaign = modes.get(back.mode());
        run(() -> {
            if (session.stage() == from) {
                campaign.left(choice);
                if (session.move(from, back) && session.browser() != null) {
                    show();
                }
            }
        });
    }

    /** Sends what the campaign stage draws: the pages shown with no save open, or the open save's pages and any reward not yet shown. */
    @Override
    public void show() {
        if (!(session.stage() instanceof InCampaign c)) {
            return;
        }
        final Campaign campaign = modes.get(c.mode());
        if (c.save() == null) {
            campaign.home().forEach(session::tell);
        } else {
            campaign.page().forEach(session::tell);
            final Reward pending = campaign.reward();
            if (pending != null) {
                session.tell(pending);
            }
        }
    }

    /** Opens the pages a campaign shows with no save open, or one save. Reading a save is slow, so this runs as campaign work. */
    private void open(final String mode, final String save) {
        final Stage from = session.stage();
        final Campaign campaign = modes.get(mode);
        if (!session.hosting() || campaign == null || !(from instanceof Menu || from instanceof InCampaign)) {
            return;
        }
        if (from instanceof InCampaign c && !modes.get(c.mode()).mayLeave(c.save())) {
            return;
        }
        campaign.opening(save, () -> {
            // The mode may have kept this until the player had gone elsewhere
            if (session.stage() != from) {
                return;
            }
            // A deck from the campaign being left can no longer be built
            session.decks().closeCollectionDeck(session.browser());
            if (session.move(from, new InCampaign(mode, save == null ? null : campaign.open(save)))) {
                show();
            }
        });
    }

    /** Applies a change from the menu of a saved game in the saves list. change returns why it could not be made, or null. */
    private void shelf(final BrowserChannel channel, final Function<Campaign, String> change) {
        if (!session.hosting() || !(session.stage() instanceof InCampaign c) || c.save() != null) {
            return;
        }
        final Campaign campaign = modes.get(c.mode());
        final String problem = change.apply(campaign);
        campaign.home().forEach(m -> channel.send(m));
        // The start page's Resume button names the save played last
        channel.send(session.hello());
        // Sent last, because the browser clears any error on each hello
        if (problem != null) {
            channel.send(WebSession.error(problem));
        }
    }
}
