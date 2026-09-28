package forge.web;

import forge.StaticData;
import forge.deck.Deck;
import forge.model.FModel;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** The events a test stored or started, whose pools go into the player's network event decks and come out after it. */
final class EventDecks {
    private final List<String> events = new CopyOnWriteArrayList<>();

    /** Stores a 40-Forest deck as a pool of a made-up sealed event, and answers the event's id. */
    String stored(final String name) {
        final String id = UUID.randomUUID().toString();
        final Deck deck = new Deck(name + " " + id.substring(0, 8));
        deck.getMain().add(StaticData.instance().getCommonCards().getCard("Forest"), 40);
        deck.getTags().add("eventId:" + id);
        deck.getTags().add("eventFormat:SEALED");
        deck.getTags().add("eventDate:2026-09-26 10:00");
        FModel.getDecks().getNetworkEventDecks().add(deck);
        events.add(id);
        return id;
    }

    /** Takes an event a test started, so its decks are removed with the rest. */
    void add(final String eventId) {
        events.add(eventId);
    }

    /** Removes every deck of the events taken. */
    void clear() {
        final var stored = FModel.getDecks().getNetworkEventDecks();
        for (final Deck d : stored.stream().toList()) {
            if (events.contains(eventIdOf(d))) {
                stored.delete(d.getName());
            }
        }
        events.clear();
    }

    static String eventIdOf(final Deck deck) {
        return deck.getTags().stream().filter(t -> t.startsWith("eventId:")).map(t -> t.substring("eventId:".length()))
                .findFirst().orElse(null);
    }
}
