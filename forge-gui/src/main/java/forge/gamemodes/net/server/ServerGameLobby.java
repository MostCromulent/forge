package forge.gamemodes.net.server;

import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.gamemodes.limited.BoosterDraft;
import forge.gamemodes.match.GameLobby;
import forge.gamemodes.match.LobbySlot;
import forge.gamemodes.match.LobbySlotType;
import forge.gamemodes.net.draft.BoosterDraftHost;
import forge.gamemodes.net.draft.EventFormat;
import forge.gamemodes.net.draft.EventParticipant;
import forge.gamemodes.net.draft.NetworkEvent;
import forge.gamemodes.net.event.DraftPickEvent;
import forge.gamemodes.net.event.EventCreatedEvent;
import forge.gamemodes.net.event.ReceiveEventPoolEvent;
import forge.gui.interfaces.IGuiGame;
import org.apache.commons.lang3.StringUtils;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

public final class ServerGameLobby extends GameLobby {
    private BoosterDraftHost draftHost;

    public ServerGameLobby() {
        super(true);
        addSlot(new LobbySlot(LobbySlotType.LOCAL, localName(), localAvatarIndices()[0], localSleeveIndices()[0],0, true, false, Collections.emptySet()));
        addSlot(new LobbySlot(LobbySlotType.OPEN, null, -1, -1, 1, false, false, Collections.emptySet()));
    }

    /**
     * Connect a player to the first available open slot.
     * This method is synchronized to prevent race conditions when multiple
     * clients connect simultaneously (which could assign the same slot to
     * multiple clients).
     *
     * @param name the player's name
     * @param avatarIndex the avatar index
     * @param sleeveIndex the sleeve index
     * @return the assigned slot index, or -1 if no slots available
     */
    public synchronized int connectPlayer(final String name, final int avatarIndex, final int sleeveIndex) {
        final int nSlots = getNumberOfSlots();
        for (int index = 0; index < nSlots; index++) {
            final LobbySlot slot = getSlot(index);
            if (slot.getType() == LobbySlotType.OPEN) {
                connectPlayer(name, avatarIndex, sleeveIndex, slot);
                return index;
            }
        }
        return -1;
    }
    private void connectPlayer(final String name, final int avatarIndex, final int sleeveIndex, final LobbySlot slot) {
        slot.setType(LobbySlotType.REMOTE);
        slot.setName(name);
        slot.setAvatarIndex(avatarIndex);
        slot.setSleeveIndex(sleeveIndex);
        updateView(false);
    }
    public void disconnectPlayer(final int index) {
        final LobbySlot slot = getSlot(index);
        slot.setType(LobbySlotType.OPEN);
        slot.setName(StringUtils.EMPTY);
        slot.setIsReady(false);
        updateView(false);
    }

    @Override
    public boolean hasControl() {
        return true;
    }

    @Override
    public boolean mayEdit(final int index) {
        final LobbySlotType type = getSlot(index).getType();
        return type != LobbySlotType.REMOTE && type != LobbySlotType.OPEN;
    }

    @Override
    public boolean mayControl(final int index) {
        return getSlot(index).getType() != LobbySlotType.REMOTE;
    }

    @Override
    public boolean mayRemove(final int index) {
        return index >= 2;
    }

    @Override
    protected IGuiGame getGui(final int index) {
        return FServerManager.getInstance().getGui(index);
    }

    @Override
    protected void onGameStarted() {
    }

    public synchronized void createEvent(EventFormat format) {
        NetworkEvent event = new NetworkEvent(format);
        setCurrentEvent(event);
        FServerManager.getInstance().broadcast(new EventCreatedEvent(event.toView()));
        updateView(true);
    }

    public synchronized void configureEvent(List<String> boosterConfig, int pickTimerSeconds,
            String productDescription, boolean deckConformance) {
        NetworkEvent event = getCurrentEvent();
        if (event == null) return;
        event.setBoosterConfiguration(boosterConfig);
        event.setPickTimerSeconds(pickTimerSeconds);
        event.setProductDescription(productDescription);
        event.setDeckConformance(deckConformance);
        updateView(true);
    }

    /**
     * Populate event participants from current lobby slots.
     * Each non-OPEN slot becomes a participant: LOCAL and REMOTE are HUMAN, AI is AI.
     */
    public synchronized void populateParticipants() {
        NetworkEvent event = getCurrentEvent();
        if (event == null) return;
        event.getParticipants().clear();
        int seatIndex = 0;
        for (int i = 0; i < getNumberOfSlots(); i++) {
            LobbySlot slot = getSlot(i);
            if (slot.getType() == LobbySlotType.OPEN) {
                continue;
            }
            EventParticipant.Type pType = (slot.getType() == LobbySlotType.AI)
                    ? EventParticipant.Type.AI : EventParticipant.Type.HUMAN;
            event.addParticipant(new EventParticipant(slot.getName(), pType, seatIndex));
            System.err.println("[ServerLobby] Participant slot=" + i + " seat=" + seatIndex + " name=" + slot.getName() + " type=" + pType + " slotType=" + slot.getType());
            seatIndex++;
        }
        System.err.println("[ServerLobby] Total participants: " + event.getParticipants().size());
    }

    /**
     * Fill remaining seats up to targetSize with AI participants.
     * AI seats are for draft pick selection only — they are not match opponents.
     */
    public synchronized void fillRemainingWithAI(int targetSize) {
        NetworkEvent event = getCurrentEvent();
        if (event == null) return;
        int currentSize = event.getParticipants().size();
        for (int i = currentSize; i < targetSize; i++) {
            String aiName = "Seat " + (i + 1);
            event.addParticipant(new EventParticipant(aiName, EventParticipant.Type.AI, i));
            System.err.println("[ServerLobby] Auto-fill AI seat=" + i + " name=" + aiName);
        }
    }

    /**
     * Start a network draft using the given BoosterDraft instance.
     * The draft must already have been created and initialized via
     * {@link BoosterDraft#createDraft}.
     *
     * @param draft the initialized BoosterDraft
     */
    public synchronized void startDraft(BoosterDraft draft) {
        NetworkEvent event = getCurrentEvent();
        if (event == null) {
            System.err.println("[ServerGameLobby] Cannot start draft: no event configured");
            return;
        }
        draftHost = new BoosterDraftHost(draft, event);
        draftHost.start();
    }

    /**
     * Generate sealed pools and send one to each human participant.
     * Each pool is 6 boosters opened into a CardPool, wrapped in a Deck.
     */
    public synchronized void generateAndDistributeSealedPools() {
        NetworkEvent event = getCurrentEvent();
        if (event == null) {
            System.err.println("[ServerGameLobby] Cannot generate sealed pools: no event configured");
            return;
        }
        if (event.getFormat() != EventFormat.SEALED) {
            System.err.println("[ServerGameLobby] Event is not sealed format");
            return;
        }

        forge.gamemodes.limited.SealedCardPoolGenerator gen = event.getSealedGenerator();
        if (gen == null || gen.isEmpty()) {
            System.err.println("[ServerGameLobby] No sealed generator configured — run Configure first");
            return;
        }

        String eventId = event.getEventId();
        FServerManager server = FServerManager.getInstance();

        for (EventParticipant participant : event.getParticipants()) {
            if (participant.isAI()) {
                continue;
            }

            CardPool pool = gen.getCardPool(false);
            if (pool == null) {
                System.err.println("[ServerGameLobby] Failed to generate pool for " + participant.getName());
                continue;
            }

            String poolName = participant.getName() + "-" + eventId.substring(0, Math.min(8, eventId.length()));
            Deck deck = new Deck(poolName);
            deck.getOrCreate(DeckSection.Sideboard).addAll(pool);
            deck.getTags().add("eventId:" + eventId);
            deck.getTags().add("eventFormat:" + event.getFormat().name());
            deck.getTags().add("eventProduct:" + event.getProductDescription());
            deck.getTags().add("eventDate:" + LocalDate.now().toString());

            RemoteClient client = server.getClientByName(participant.getName());
            if (client != null) {
                client.send(new ReceiveEventPoolEvent(eventId, deck));
            } else {
                forge.interfaces.ILobbyListener listener = server.getLobbyListener();
                if (listener != null) {
                    listener.receiveEventPool(eventId, deck);
                }
            }
            System.err.println("[ServerGameLobby] Sent sealed pool to " + participant.getName()
                    + " (" + pool.countAll() + " cards)");
        }
    }

    /**
     * Route an incoming draft pick from a client to the draft host.
     */
    public synchronized void handleDraftPick(DraftPickEvent pickEvent) {
        if (draftHost == null) {
            System.err.println("[ServerGameLobby] No draft in progress");
            return;
        }
        draftHost.handlePick(pickEvent.getSeatIndex(), pickEvent.getCard());
    }

    public BoosterDraftHost getDraftHost() {
        return draftHost;
    }
}
