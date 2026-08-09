package forge.trackable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

import com.google.common.collect.Maps;
import com.google.common.collect.ImmutableMultiset;
import com.google.common.collect.Multiset;

import forge.card.CardType;
import forge.card.CardTypeView;
import forge.card.ColorSet;
import forge.card.mana.ManaCost;
import forge.game.GameEntityView;
import forge.game.card.CardView;
import forge.game.card.CardView.CardStateView;
import forge.game.card.CounterType;
import forge.game.combat.CombatView;
import forge.game.keyword.KeywordCollectionView;
import forge.game.player.PlayerView;
import forge.game.spellability.StackItemView;
import forge.item.IPaperCard;

public class TrackableTypes {
    public static class TrackableType<T> {
        private T defaultValue;
        private final UnaryOperator<T> copier;

        private TrackableType(T defaultValue) {
            this(defaultValue, null);
        }

        private TrackableType(T defaultValue, UnaryOperator<T> copier) {
            this.defaultValue = defaultValue;
            this.copier = copier;
        }

        /**
         * A value that will not change underneath whoever reads the property next.
         *
         * <p>Some of these are handed to a view still owned by the engine - counters are
         * stored as the engine's own {@code Multiset}, which it keeps mutating - so a reader
         * on another thread iterates something being written. Copying as it is stored puts
         * that cost on the thread that owns the value, where nothing is racing it, rather
         * than on every reader.
         *
         * <p>Only the types holding a plain collection copy. A type whose value is a
         * {@code TrackableObject} or {@code TrackableCollection} must not: those are the
         * shared graph, and copying one would sever it. So must a type whose collection the
         * view deliberately mutates in place after storing it, which is why
         * {@link #GenericMapType} passes through.
         */
        @SuppressWarnings("unchecked")
        final Object detach(final Object value) {
            return copier == null || value == null ? value : copier.apply((T) value);
        }

        /** Whether a stored value is a private copy. Every type has to answer this on purpose. */
        final boolean copiesOnStore() {
            return copier != null;
        }

        protected void updateObjLookup(Tracker tracker, T newObj) {
        }
        protected void copyChangedProps(TrackableObject from, TrackableObject to, TrackableProperty prop) {
            to.set(prop, from.get(prop));
        }

        protected T getDefaultValue() {
            return defaultValue;
        }
    }

    public static class TrackableObjectType<T extends TrackableObject> extends TrackableType<T> {
        private TrackableObjectType() {
            super(null);
        }

        public T lookup(T from) {
            if (from == null) { return null; }
            T to = from.getTracker().getObj(this, from.getId());
            if (to == null) {
                from.getTracker().putObj(this, from.getId(), from);
                return from;
            }
            return to;
        }

        @Override
        protected void updateObjLookup(Tracker tracker, T newObj) {
            if (tracker == null) { return; }
            if (newObj != null && !tracker.hasObj(this, newObj.getId())) {
                tracker.putObj(this, newObj.getId(), newObj);
                newObj.updateObjLookup();
            }
        }

        @Override
        protected void copyChangedProps(TrackableObject from, TrackableObject to, TrackableProperty prop) {
            T newObj = from.get(prop);
            if (newObj != null) {
                T existingObj = newObj.getTracker().getObj(this, newObj.getId());
                if (existingObj != null) { //if object exists already, update its changed properties
                    existingObj.copyChangedProps(newObj);
                    newObj = existingObj;
                }
                else { //if object is new, cache in object lookup
                    newObj.getTracker().putObj(this, newObj.getId(), newObj);
                }
            }
            to.set(prop, newObj);
        }
    }

    private static class TrackableCollectionType<T extends TrackableObject> extends TrackableType<TrackableCollection<T>> {
        private final TrackableObjectType<T> itemType;

        private TrackableCollectionType(TrackableObjectType<T> itemType0) {
            super(null);
            itemType = itemType0;
        }

        @Override
        protected void updateObjLookup(Tracker tracker, TrackableCollection<T> newCollection) {
            if (newCollection != null) {
                for (T newObj : newCollection) {
                    if (newObj != null) {
                        itemType.updateObjLookup(tracker, newObj);
                    }
                }
            }
        }

        @Override
        protected void copyChangedProps(TrackableObject from, TrackableObject to, TrackableProperty prop) {
            TrackableCollection<T> newCollection = from.get(prop);
            if (newCollection != null) {
                // Snapshot via toArray: the loop below clears and rebuilds the collection,
                // so direct iteration would throw ConcurrentModificationException
                @SuppressWarnings("unchecked")
                T[] items = (T[]) newCollection.toArray(new TrackableObject[0]);
                newCollection.clear();
                for (T newObj : items) {
                    if (newObj != null) {
                        T existingObj = from.getTracker().getObj(itemType, newObj.getId());
                        if (existingObj != null) {
                            // Skip CardView collections — cross-zone refs like Commander hold stale copies
                            if (prop.getType() != TrackableTypes.CardViewCollectionType &&
                                    prop.getType() != TrackableTypes.StackItemViewListType) {
                                existingObj.copyChangedProps(newObj);
                            }
                            newCollection.add(existingObj);
                        } else {
                            from.getTracker().putObj(itemType, newObj.getId(), newObj);
                            newCollection.add(newObj);
                        }
                    }
                }
            }
            to.set(prop, newCollection);
        }
    }

    public static final TrackableType<Boolean> BooleanType = new TrackableType<Boolean>(false);
    public static final TrackableType<Integer> IntegerType = new TrackableType<Integer>(0);
    public static final TrackableType<Float> FloatType = new TrackableType<Float>(0f);
    public static final TrackableType<String> StringType = new TrackableType<String>("");

    //make this quicker than having to define a new class for every single enum
    private static Map<Class<? extends Enum<?>>, TrackableType<?>> enumTypes = Maps.newHashMap();

    @SuppressWarnings("unchecked")
    public static <E extends Enum<E>> TrackableType<E> EnumType(final Class<E> enumType) {
        return (TrackableType<E>)enumTypes.computeIfAbsent(enumType, (t) -> new TrackableType<E>(null));
    }

    public static final TrackableObjectType<CardView> CardViewType = new TrackableObjectType<CardView>();

    public static final TrackableType<IPaperCard> IPaperCardType = new TrackableType<IPaperCard>(null);

    public static final TrackableCollectionType<CardView> CardViewCollectionType = new TrackableCollectionType<CardView>(CardViewType);
    public static final TrackableObjectType<CardStateView> CardStateViewType = new TrackableObjectType<CardStateView>() {
        @Override
        protected void copyChangedProps(TrackableObject from, TrackableObject to, TrackableProperty prop) {
            // CardStateViews share their parent CardView's ID, so multiple states
            // (CurrentState, AlternateState) have the same (type, id) key. The base
            // implementation uses tracker.getObj(type, id) which returns the wrong
            // state. Instead, look up the existing state directly via the property.
            CardStateView newCsv = from.get(prop);
            CardStateView existingCsv = to.get(prop);
            if (newCsv != null && existingCsv != null) {
                existingCsv.copyChangedProps(newCsv);
                to.set(prop, existingCsv);
            } else {
                to.set(prop, newCsv);
            }
        }
    };
    public static final TrackableType<CardTypeView> CardTypeViewType =
            new TrackableType<>(CardType.EMPTY, CardType::new);
    public static final TrackableObjectType<PlayerView> PlayerViewType = new TrackableObjectType<>();
    public static final TrackableCollectionType<PlayerView> PlayerViewCollectionType = new TrackableCollectionType<>(PlayerViewType);
    public static final TrackableObjectType<GameEntityView> GameEntityViewType = new TrackableObjectType<>();
    public static final TrackableObjectType<StackItemView> StackItemViewType = new TrackableObjectType<>();
    public static final TrackableCollectionType<StackItemView> StackItemViewListType = new TrackableCollectionType<>(StackItemViewType);
    public static final TrackableObjectType<CombatView> CombatViewType = new TrackableObjectType<>();
    public static final TrackableType<ManaCost> ManaCostType = new TrackableType<>(ManaCost.NO_COST);
    public static final TrackableType<ColorSet> ColorSetType = new TrackableType<>(ColorSet.C);
    public static final TrackableType<List<String>> StringListType =
            new TrackableType<>(null, TrackableTypes::copyList);
    public static final TrackableType<Set<String>> StringSetType =
            new TrackableType<>(null, TrackableTypes::copySet);
    public static final TrackableType<Map<String, String>> StringMapType =
            new TrackableType<>(null, TrackableTypes::copyMap);

    public static final TrackableType<Set<Integer>> IntegerSetType =
            new TrackableType<>(null, TrackableTypes::copySet);
    public static final TrackableType<Map<Integer, Integer>> IntegerMapType =
            new TrackableType<>(null, TrackableTypes::copyMap);
    public static final TrackableType<Map<Byte, Integer>> ManaMapType =
            new TrackableType<>(null, TrackableTypes::copyMap);
    public static final TrackableType<Multiset<CounterType>> CounterMapType =
            new TrackableType<>(null, ImmutableMultiset::copyOf);
    public static final TrackableType<Map<Object, Object>> GenericMapType = new TrackableType<>(null);
    public static final TrackableType<KeywordCollectionView> KeywordCollectionViewType = new TrackableType<>(KeywordCollectionView.EMPTY);
    // Not List.copyOf and friends: those reject a null element, and these run on the engine
    // thread as it stores, where a stray null must not become an exception.
    private static <E> List<E> copyList(final List<E> value) {
        return Collections.unmodifiableList(new ArrayList<>(value));
    }

    private static <E> Set<E> copySet(final Set<E> value) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(value));
    }

    private static <K, V> Map<K, V> copyMap(final Map<K, V> value) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }
}
