package forge.card;

import java.util.ArrayList;
import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Lives in this package so it can set the two fields directly, which is what the engine does
 * when a card gains every creature type.
 */
public class CardTypeViewCopyTest {

    /**
     * Copying a type through its view constructor keeps every creature type, and the
     * exclusions from it.
     *
     * <p>That constructor is what stores a card's type onto its view, and the copy is
     * unconditional, so dropping these showed a changeling without them and had the view
     * answer that it has no creature type - in local play as much as over a network. The two
     * fields survive {@code addAll} only by being assigned after it, since tidying subtypes
     * clears them.
     */
    @Test
    public void copyingAViewKeepsAllCreatureTypesAndItsExclusions() {
        final CardType original = new CardType(false);
        original.add("Creature");
        original.allCreatureTypes = true;
        original.excludedCreatureSubtypes.add("Wall");

        final CardType copy = new CardType((CardTypeView) original);

        Assert.assertTrue(copy.hasAllCreatureTypes(), "the copy stopped having every creature type");
        Assert.assertEquals(exclusions(copy), List.of("Wall"), "the copy lost what was excluded");
        // The copy is what a later change is compared against, so an unequal one reports a
        // change on every store.
        Assert.assertEquals(copy, original, "the copy does not equal what it copied");
    }

    private static List<String> exclusions(final CardTypeView type) {
        final List<String> names = new ArrayList<>();
        type.getExcludedCreatureSubTypes().forEach(names::add);
        return names;
    }
}
