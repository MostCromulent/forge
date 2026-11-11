package forge.web.adventure;

import forge.adventure.data.ItemData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.util.Config;
import forge.adventure.world.WorldSave;
import forge.card.CardEdition;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.model.FModel;
import forge.util.storage.IStorage;
import forge.web.deck.DeckEditor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;

/** The player's deck slots and card collection, as the web deck editor needs them. The editor calls in from the campaign thread, so each call moves to Forge's GUI thread. */
final class AdventureDecks {
    private AdventureDecks() {}

    /**
     * One deck slot as storage. The editor saves a copy of the deck it edits, so a save copies every section back into the slot.
     * The selected deck may be a separate object from its slot after a load, so it is selected again.
     */
    static IStorage<Deck> storage(final int slot) {
        return new IStorage<>() {
            @Override
            public String getFullPath() {
                return "";
            }

            @Override
            public String getName() {
                return "";
            }

            @Override
            public Deck get(final String name) {
                return AdventureRuntime.onUi(() -> {
                    final Deck deck = AdventurePlayer.current().getDeck(slot);
                    return deck.getName().equals(name) ? deck : null;
                });
            }

            @Override
            public Deck find(final Predicate<Deck> condition) {
                final Deck deck = AdventurePlayer.current().getDeck(slot);
                return condition.test(deck) ? deck : null;
            }

            /** Every slot's name, so the editor never gives two slots one name. */
            @Override
            public Collection<String> getItemNames() {
                return AdventureRuntime.onUi(() -> {
                    final List<String> names = new ArrayList<>();
                    final AdventurePlayer player = AdventurePlayer.current();
                    for (int i = 0; i < player.getDeckCount(); i++) {
                        if (i != slot) {
                            names.add(player.getDeck(i).getName());
                        }
                    }
                    return names;
                });
            }

            @Override
            public boolean contains(final String name) {
                return get(name) != null;
            }

            @Override
            public int size() {
                return 1;
            }

            @Override
            public void add(final Deck item) {
                AdventureRuntime.onUi(() -> {
                    copyInto(item);
                    return null;
                });
            }

            private void copyInto(final Deck item) {
                final AdventurePlayer player = AdventurePlayer.current();
                final Deck target = player.getDeck(slot);
                target.setName(item.getName());
                for (final DeckSection section : DeckSection.values()) {
                    final CardPool source = item.get(section);
                    if (source != null) {
                        final CardPool into = target.getOrCreate(section);
                        into.clear();
                        into.addAll(source);
                    } else if (target.has(section)) {
                        target.get(section).clear();
                    }
                }
                if (slot == player.getSelectedDeckIndex()) {
                    player.setSelectedDeckSlot(slot, false);
                }
            }

            @Override
            public void add(final String name, final Deck item) {
                add(item);
            }

            @Override
            public void delete(final String deckName) {
            }

            @Override
            public IStorage<IStorage<Deck>> getFolders() {
                return null;
            }

            @Override
            public IStorage<Deck> tryGetFolder(final String path) {
                return null;
            }

            @Override
            public IStorage<Deck> getFolderOrCreate(final String path) {
                return this;
            }

            @Override
            public Stream<Deck> stream() {
                return Stream.of(AdventureRuntime.onUi(() -> AdventurePlayer.current().getDeck(slot)));
            }

            @Override
            public Iterator<Deck> iterator() {
                return stream().iterator();
            }
        };
    }

    /** What a deck is built from: the cards the player owns, without those set to be sold, as the mobile deck editor offers them. */
    static DeckEditor.Collection collection(final int slot) {
        return new DeckEditor.Collection(AdventureRuntime.onUi(() -> AdventurePlayer.current().getName()), () -> AdventureRuntime.onUi(() -> {
            final CardPool owned = new CardPool();
            owned.addAll(AdventurePlayer.current().getCards());
            owned.removeAll(AdventurePlayer.current().autoSellCards);
            return owned;
        }), card -> AdventureRuntime.onUi(() -> AdventurePlayer.current().newCards.contains(card)), () -> AdventureRuntime.onUi(() -> {
            AdventurePlayer.current().newCards.clear();
            return null;
        }), deck -> AdventureRuntime.onUi(AdventureDecks::basicLandSets), () -> AdventureRuntime.onUi(() -> WorldSave.getCurrentSave().autoSave()), false);
    }

    // Copied from AdventureDeckEditor's getBasicLandSets
    private static List<CardEdition> basicLandSets() {
        final List<CardEdition> unlocked = new ArrayList<>();
        final Map<String, CardEdition> byName = new HashMap<>();
        for (final CardEdition e : FModel.getMagicDb().getEditions()) {
            byName.put(e.getName().toLowerCase(), e);
            byName.put(e.getName().replace(":", "").toLowerCase(), e);
            byName.put(e.getName().replace("'", "").toLowerCase(), e);
        }
        byName.put("thrones of eldraine", FModel.getMagicDb().getEditions().get("ELD"));
        byName.put("magic 2021", FModel.getMagicDb().getEditions().get("M21"));
        byName.put("sixth edition", FModel.getMagicDb().getEditions().get("6ED"));
        final String prefix = "landscape sketchbook - ";
        for (final ItemData item : AdventurePlayer.current().getItems()) {
            if (item == null || !item.name.toLowerCase().startsWith(prefix)) {
                continue;
            }
            final CardEdition edition = byName.get(item.name.substring(prefix.length()).trim().toLowerCase());
            if (edition != null && edition.hasBasicLands()) {
                unlocked.add(edition);
            }
        }
        final CardEdition defaultEdition = FModel.getMagicDb().getEditions().get(Config.instance().getConfigData().defaultBasicLandSet);
        if (!unlocked.contains(defaultEdition)) {
            unlocked.add(defaultEdition);
        }
        return unlocked;
    }
}
