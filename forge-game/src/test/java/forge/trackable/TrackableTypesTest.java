package forge.trackable;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.trackable.TrackableTypes.TrackableType;

public class TrackableTypesTest {

    /**
     * A type holding a plain collection stores a private copy of it.
     *
     * <p>These are handed values the engine goes on mutating, so a type that shares one leaves
     * every reader on another thread looking at something being written. Missing one is the
     * easy mistake: a manual sweep for them found six of the eight that were actually there.
     */
    private static final Set<String> COPIES_ON_STORE = Set.of(
            "CardTypeViewType",
            "CounterMapType",
            "IntegerMapType",
            "IntegerSetType",
            "ManaMapType",
            "StringListType",
            "StringMapType",
            "StringSetType");

    /**
     * A type that deliberately hands out what it was given.
     *
     * <p>Three reasons, and each entry here is one of them. The value is immutable already, so
     * a copy would be waste. Or it is a {@code TrackableObject} or {@code TrackableCollection},
     * which are the shared graph itself and copying one would sever it. Or the view mutates the
     * stored collection in place on purpose, which is why {@code GenericMapType} — the combat
     * bands — is here rather than above.
     */
    private static final Set<String> SHARES_BY_DESIGN = Set.of(
            "BooleanType",
            "CardStateViewType",
            "CardViewCollectionType",
            "CardViewType",
            "ColorSetType",
            "CombatViewType",
            "FloatType",
            "GameEntityViewType",
            "GenericMapType",
            "IPaperCardType",
            "IntegerType",
            "KeywordCollectionViewType",
            "ManaCostType",
            "PlayerViewCollectionType",
            "PlayerViewType",
            "StackItemViewListType",
            "StackItemViewType",
            "StringType");

    /**
     * Every trackable type has decided, on purpose, whether it copies what it stores.
     *
     * <p>Adding a type is where this goes wrong: a new one holding a collection and no copier
     * shares the engine's, silently, and nothing else would say so. This fails until the new
     * type is named in one of the two lists above, so the decision has to be made rather than
     * defaulted into.
     */
    @Test
    public void everyTypeDecidesWhetherItCopiesOnStore() throws Exception {
        final Set<String> unclassified = new TreeSet<>();
        final Set<String> wrong = new TreeSet<>();

        for (final Field field : TrackableTypes.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())
                    || !TrackableType.class.isAssignableFrom(field.getType())) {
                continue;
            }
            final String name = field.getName();
            final boolean shouldCopy = COPIES_ON_STORE.contains(name);
            if (!shouldCopy && !SHARES_BY_DESIGN.contains(name)) {
                unclassified.add(name);
                continue;
            }
            field.setAccessible(true);
            final TrackableType<?> type = (TrackableType<?>) field.get(null);
            if (type.copiesOnStore() != shouldCopy) {
                wrong.add(name + " copies=" + type.copiesOnStore() + " expected=" + shouldCopy);
            }
        }

        Assert.assertEquals(unclassified, Set.of(),
                "a trackable type is in neither list — decide whether it must copy what it stores, "
                        + "and say so in " + TrackableTypesTest.class.getSimpleName());
        Assert.assertEquals(wrong, Set.of(), "a trackable type does not copy the way it is listed to");
    }

    /** The lists describe real types, so a renamed or deleted one is caught rather than ignored. */
    @Test
    public void everyListedTypeStillExists() throws Exception {
        final Set<String> declared = new TreeSet<>();
        for (final Field field : TrackableTypes.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())
                    && TrackableType.class.isAssignableFrom(field.getType())) {
                declared.add(field.getName());
            }
        }
        for (final Set<String> listed : List.of(COPIES_ON_STORE, SHARES_BY_DESIGN)) {
            for (final String name : listed) {
                Assert.assertTrue(declared.contains(name), name + " is listed but no longer exists");
            }
        }
    }
}
