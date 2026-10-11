package forge.web;

import forge.card.DraftOptions;
import forge.deck.Deck;
import forge.deck.DeckProxy;
import forge.game.GameType;
import forge.gamemodes.limited.BoosterDraft;
import forge.gamemodes.limited.LimitedPoolType;
import forge.gamemodes.limited.SealedCardPoolGenerator;
import forge.gamemodes.match.GameLobby;
import forge.gamemodes.match.GameLobby.GameLobbyData;
import forge.gamemodes.match.LobbySlot;
import forge.gamemodes.net.EventFormat;
import forge.gamemodes.net.EventPhase;
import forge.gamemodes.net.NetworkEvent;
import forge.gamemodes.net.NetworkEventView;
import forge.gamemodes.net.server.ServerGameLobby;
import forge.model.FModel;
import forge.util.Localizer;
import forge.web.FromBrowser.*;
import forge.web.ToBrowser.*;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** The Limited half of a table: the draft and sealed events hosted through the lobby. */
final class LobbyEvents {
    private final Lobby owner;
    private final LocalGame local;

    LobbyEvents(final Lobby owner, final LocalGame local) {
        this.owner = owner;
        this.local = local;
    }

    /** Whether the table's event has begun: its packs are out, or a past event's decks were chosen for the match. */
    boolean eventStarted() {
        final GameLobby lobby = owner.view();
        final GameLobbyData data = lobby == null ? null : lobby.getData();
        if (data == null) {
            return false;
        }
        final NetworkEventView event = data.getEventView();
        return data.getActiveEventId() != null || (event != null && event.getPhase() != EventPhase.LOBBY_GATHER);
    }

    /** Whether every seat's finder lists only the table's event's decks, as desktop's conformance switch sets. */
    void setEventDecksOnly(final boolean on) {
        final ServerGameLobby lobby = owner.host();
        if (lobby != null && lobby.getData().getActiveEventId() != null) {
            lobby.selectEventForMatch(lobby.getData().getActiveEventId(), on);
        }
    }

    /** The Limited part of the table, read from the lobby data so a guest reads the same; null at a Constructed table. */
    LimitedTable limitedTable(final GameLobby lobby) {
        if (!Lobby.limited(lobby)) {
            return null;
        }
        final GameLobbyData data = lobby.getData();
        final NetworkEventView event = data.getEventView();
        final boolean draft = data.getLimitedType() != GameType.Sealed;
        // An event's product is blank until the event is set up
        String product = event == null || event.getProductDescription() == null || event.getProductDescription().isEmpty()
                ? null : event.getProductDescription();
        // A past event played again has no product of its own, so its pools' tag names it
        if (product == null && data.getActiveEventId() != null) {
            product = playedAgain(data.getActiveEventId());
        }
        return new LimitedTable(draft ? "draft" : "sealed", product,
                event == null ? 0 : event.getPodSize(),
                event == null || event.getDoublePick() == null ? null : event.getDoublePick().name(),
                event == null ? 0 : event.getPickTimerSeconds(), event == null ? null : event.getPhase().name(),
                data.getActiveEventId(), data.isActiveConformance(), eventStarted(),
                local.isHost() && data.getActiveEventId() == null && !eventStarted() ? pastEvents() : List.of());
    }

    /** The events whose pools the host keeps, newest first, as desktop's past events list orders them. */
    private static List<PastEvent> pastEvents() {
        final Map<String, PastEvent> events = new java.util.LinkedHashMap<>();
        synchronized (DeckCatalog.DECKS) {
            for (final Deck d : FModel.getDecks().getNetworkEventDecks()) {
                final String id = DeckProxy.getEventTag(d, "eventId");
                if (id != null) {
                    events.putIfAbsent(id, pastEvent(id, d));
                }
            }
        }
        // The date is "yyyy-MM-dd HH:mm", so reverse order of the text is newest first
        return events.values().stream().sorted(java.util.Comparator.comparing(PastEvent::date).reversed()).toList();
    }

    /** The product of a past event, read from its pools; a pool saved without one still names an event rather than none. */
    private static String playedAgain(final String eventId) {
        synchronized (DeckCatalog.DECKS) {
            for (final Deck d : FModel.getDecks().getNetworkEventDecks()) {
                if (eventId.equals(DeckProxy.getEventTag(d, "eventId"))) {
                    final String product = DeckProxy.getEventTag(d, "eventProduct");
                    return product == null || product.isBlank() ? Localizer.getInstance().getMessage("lblWebEventAnEarlierEvent") : product;
                }
            }
        }
        return Localizer.getInstance().getMessage("lblWebEventAnEarlierEvent");
    }

    /** A past event as one of its pools' tags describe it. */
    private static PastEvent pastEvent(final String id, final Deck pool) {
        final boolean draft = EventFormat.BOOSTER_DRAFT.name().equals(DeckProxy.getEventTag(pool, "eventFormat"));
        return new PastEvent(id, draft ? "draft" : "sealed", Objects.toString(DeckProxy.getEventTag(pool, "eventProduct"), ""),
                Objects.toString(DeckProxy.getEventTag(pool, "eventDate"), ""));
    }

    /** Plays an event's decks at this table, where type is Draft or Sealed, and answers why not, or null. */
    String playEvent(final String eventId, final GameType type, final boolean decksOnly) {
        final ServerGameLobby lobby = owner.host();
        if (lobby == null || eventId == null) {
            return null;
        }
        if (Lobby.drafting(lobby)) {
            return Localizer.getInstance().getMessage("lblWebLobbyDraftStillOn");
        }
        lobby.clearCurrentEvent();
        lobby.setLimitedType(type);
        lobby.setLimitedMode(true);
        lobby.selectEventForMatch(eventId, decksOnly);
        return null;
    }

    /** Hosts a past event again, its kind read from its pools as desktop reads it. */
    String hostAgain(final String eventId) {
        Deck pool = null;
        synchronized (DeckCatalog.DECKS) {
            for (final Deck d : FModel.getDecks().getNetworkEventDecks()) {
                if (eventId != null && eventId.equals(DeckProxy.getEventTag(d, "eventId"))) {
                    pool = d;
                }
            }
        }
        if (pool == null) {
            return Localizer.getInstance().getMessage("lblWebLobbyNoEventDecks");
        }
        final boolean sealed = EventFormat.SEALED.name().equals(DeckProxy.getEventTag(pool, "eventFormat"));
        return playEvent(eventId, sealed ? GameType.Sealed : GameType.Draft, true);
    }

    /** Starts a new event of the same kind and keeps the old one's pools so it can be played again, answering why not, or null. */
    String newEvent() {
        final ServerGameLobby lobby = owner.host();
        if (lobby == null || !Lobby.limited(lobby)) {
            return null;
        }
        if (Lobby.drafting(lobby)) {
            return Localizer.getInstance().getMessage("lblWebLobbyDraftStillOn");
        }
        final String kind = lobby.getData().getLimitedType() == GameType.Sealed ? "sealed" : "draft";
        lobby.clearCurrentEvent();
        lobby.selectEventForMatch(null, false);
        for (int i = 0; i < lobby.getNumberOfSlots(); i++) {
            lobby.getSlot(i).setBenched(false);
        }
        return setLimited(kind);
    }

    /** Deletes a past event's pools from the host's event decks. The event the table is playing stays. Answers why not, or null. */
    String forgetEvent(final String eventId) {
        final ServerGameLobby lobby = owner.host();
        if (lobby == null || eventId == null) {
            return null;
        }
        if (eventId.equals(lobby.getData().getActiveEventId())) {
            return Localizer.getInstance().getMessage("lblWebLobbyEventInPlay");
        }
        synchronized (DeckCatalog.DECKS) {
            final var stored = FModel.getDecks().getNetworkEventDecks();
            for (final Deck d : stored.stream().toList()) {
                if (eventId.equals(DeckProxy.getEventTag(d, "eventId"))) {
                    stored.delete(d.getName());
                }
            }
        }
        return null;
    }

    /** Switches the table to a "draft" or "sealed" event, or to Constructed when kind is null, and answers why not, or null. */
    String setLimited(final String kind) {
        final ServerGameLobby lobby = owner.host();
        if (lobby == null) {
            return null;
        }
        if (Lobby.drafting(lobby)) {
            return Localizer.getInstance().getMessage("lblWebLobbyDraftStillOn");
        }
        if (kind == null) {
            if (lobby.getNumberOfSlots() > Lobby.MAX_SEATS) {
                return Localizer.getInstance().getMessage("lblWebLobbyConstructedSeats", Lobby.MAX_SEATS);
            }
            lobby.clearCurrentEvent();
            lobby.selectEventForMatch(null, false);
            // Sitting out is an event's matter; a Constructed table neither shows nor clears it
            for (int i = 0; i < lobby.getNumberOfSlots(); i++) {
                lobby.getSlot(i).setBenched(false);
            }
            lobby.setLimitedMode(false);
            return null;
        }
        if (eventStarted()) {
            return Localizer.getInstance().getMessage("lblWebLobbyEventStartedSwitch");
        }
        final boolean sealed = "sealed".equals(kind);
        // An event is played without a format or casual variant, as desktop's Limited mode hides them
        owner.setFormat(GameType.Constructed.name());
        for (final GameType v : Lobby.VARIANTS) {
            lobby.removeVariant(v);
        }
        lobby.setLimitedType(sealed ? GameType.Sealed : GameType.Draft);
        lobby.createEvent(sealed ? EventFormat.SEALED : EventFormat.BOOSTER_DRAFT);
        lobby.setLimitedMode(true);
        return null;
    }

    /** Answers why not, or null, and must run off the socket thread because building the product can wait on a web site. */
    String setUpEvent(final EventSetup s) {
        final ServerGameLobby lobby = owner.host();
        if (lobby == null || !Lobby.limited(lobby)) {
            return Localizer.getInstance().getMessage("lblWebLobbyChooseLimited");
        }
        if (eventStarted()) {
            return Localizer.getInstance().getMessage("lblWebLobbyEventStartedFixed");
        }
        try {
            if (lobby.getData().getLimitedType() == GameType.Sealed) {
                final SealedCardPoolGenerator gen = OfflineEvents.generator(new SealedCreate(s.product(), s.block(), s.combo(),
                        s.edition(), s.template(), s.cubeId(), s.packs(), "", false));
                lobby.createEvent(EventFormat.SEALED);
                return lobby.configureEvent(LimitedPoolType.valueOf(s.product()), gen, 0, 0) ? null : Localizer.getInstance().getMessage("lblWebLobbySealedSetUpFailed");
            }
            final BoosterDraft draft = OfflineEvents.draft(new DraftStart(s.product(), s.block(), s.combo(), s.cube(), s.theme(),
                    s.cubeId())).get();
            if (draft == null) {
                return Localizer.getInstance().getMessage("lblWebLobbyDraftSetUpFailed");
            }
            // As desktop's pod choice: never fewer than the seats at the table, and never more than a pod
            final int wanted = s.podSize() > 0 ? s.podSize() : draft.getPodSize();
            draft.setPodSize(Math.min(Lobby.MAX_POD, Math.max(Math.max(2, lobby.getNumberOfSlots()), wanted)));
            if (s.pickRule() != null) {
                draft.setDoublePick(DraftOptions.DoublePick.valueOf(s.pickRule()));
            }
            lobby.createEvent(EventFormat.BOOSTER_DRAFT);
            return lobby.configureEvent(LimitedPoolType.valueOf(s.product()), draft, Math.max(0, s.timer()), Math.max(0, s.grace()))
                    ? null : Localizer.getInstance().getMessage("lblWebLobbyDraftSetUpFailed");
        } catch (final RuntimeException e) {
            return e.getMessage() == null ? Localizer.getInstance().getMessage("lblWebLobbyEventSetUpFailed") : e.getMessage();
        }
    }

    /** Deals the packs or pools once every seat is ready, as desktop's Start Event does. Answers why not, or null. */
    String startEvent() {
        final ServerGameLobby lobby = owner.host();
        final NetworkEvent event = lobby == null ? null : lobby.getCurrentEvent();
        if (event == null || (event.getDraft() == null && event.getSealedGenerator() == null)) {
            return Localizer.getInstance().getMessage("lblWebLobbySetUpFirst");
        }
        if (eventStarted()) {
            return Localizer.getInstance().getMessage("lblWebLobbyEventStarted");
        }
        final LobbySlot unready = lobby.findFirstUnreadySlot();
        if (unready != null) {
            return Localizer.getInstance().getMessage("lblWebLobbyNotReady", String.valueOf(unready.getName()));
        }
        if (event.getFormat() == EventFormat.SEALED) {
            lobby.startSealedEvent();
            return null;
        }
        return lobby.startDraftEvent() == null ? Localizer.getInstance().getMessage("lblWebLobbyDraftStartFailed") : null;
    }

    /** Whether the host's setup form is open: a Limited table whose event has not begun. */
    boolean settingUpEvent() {
        final GameLobby lobby = owner.view();
        return lobby != null && Lobby.limited(lobby) && !eventStarted();
    }

    /** Sits a seat out of the next match, or brings it back. The host's to do, and not while the pod drafts. */
    void benchSeat(final int index, final boolean benched) {
        final ServerGameLobby lobby = owner.host();
        if (lobby != null && Lobby.limited(lobby) && !Lobby.drafting(lobby) && index >= 0 && index < lobby.getNumberOfSlots()) {
            lobby.getSlot(index).setBenched(benched);
            local.pushLobby();
        }
    }
}
