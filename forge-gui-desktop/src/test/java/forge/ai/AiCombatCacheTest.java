package forge.ai;

import java.util.List;
import java.util.Map;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

/**
 * Tests for AiCombatCache creature equivalence grouping, cache correctness,
 * and the verify/exhaustive verification modes.
 *
 * Tests are numbered to match the spec scenarios in
 * .claude/superpowers/specs/2026-04-13-ai-combat-cache-design.md
 */
public class AiCombatCacheTest extends AITest {

    // --- Scenario 1: Identical vanilla creatures are grouped ---
    @Test
    public void testIdenticalVanillaCreaturesGrouped() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(1);
        Player p2 = game.getPlayers().get(0);

        // Add 5 identical Grizzly Bears to p1
        List<Card> bears = addCards("Grizzly Bears", 5, p1);
        for (Card c : bears) c.setSickness(false);

        // Add a blocker for p2
        addCard("Hill Giant", p2);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);
        game.getAction().checkStateEffects(true);

        // Build cache from battlefield
        CardCollectionView battlefield = game.getCardsIn(ZoneType.Battlefield);
        AiCombatCache cache = new AiCombatCache(battlefield, p2, AiCacheMode.ENABLED);

        // All 5 bears should map to the same representative
        Card rep = cache.getRepresentative(bears.get(0));
        for (Card bear : bears) {
            AssertJUnit.assertSame("All bears should share one representative",
                    rep, cache.getRepresentative(bear));
        }

        // Should be 2 groups: bears + Hill Giant
        Map<Card, List<Card>> groups = cache.getCreatureGroups();
        AssertJUnit.assertEquals("Should have 2 creature groups", 2, groups.size());

        // Bears group should have 5 members
        AssertJUnit.assertEquals("Bears group should have 5 members", 5, groups.get(rep).size());
    }

    // --- Scenario 3: Mixed board doesn't false-group different creatures ---
    @Test
    public void testMixedBoardNoFalseGrouping() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(1);

        // 3 identical bears + 2 unique creatures
        List<Card> bears = addCards("Grizzly Bears", 3, p1);
        Card giant = addCard("Hill Giant", p1);
        Card elf = addCard("Elvish Mystic", p1);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);
        game.getAction().checkStateEffects(true);

        CardCollectionView battlefield = game.getCardsIn(ZoneType.Battlefield);
        AiCombatCache cache = new AiCombatCache(battlefield, null, AiCacheMode.ENABLED);

        // Bears should be grouped together
        Card bearRep = cache.getRepresentative(bears.get(0));
        for (Card bear : bears) {
            AssertJUnit.assertSame("Bears should share representative", bearRep, cache.getRepresentative(bear));
        }

        // Giant and elf should be their own representatives
        AssertJUnit.assertSame("Giant is own representative", giant, cache.getRepresentative(giant));
        AssertJUnit.assertSame("Elf is own representative", elf, cache.getRepresentative(elf));

        // 3 groups total
        Map<Card, List<Card>> groups = cache.getCreatureGroups();
        AssertJUnit.assertEquals("Should have 3 creature groups", 3, groups.size());
    }

    // --- Scenario 4: Creatures with different damage are NOT grouped ---
    @Test
    public void testDifferentDamageNotGrouped() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(1);

        List<Card> bears = addCards("Grizzly Bears", 3, p1);
        // Mark damage on one bear
        bears.get(1).setDamage(1);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);
        game.getAction().checkStateEffects(true);

        CardCollectionView battlefield = game.getCardsIn(ZoneType.Battlefield);
        AiCombatCache cache = new AiCombatCache(battlefield, null, AiCacheMode.ENABLED);

        // Damaged bear should NOT share representative with undamaged bears
        Card undamagedRep = cache.getRepresentative(bears.get(0));
        Card damagedRep = cache.getRepresentative(bears.get(1));
        AssertJUnit.assertNotSame("Damaged bear should have different representative",
                undamagedRep, damagedRep);
    }

    // --- Scenario 5: Equipped creatures excluded from grouping ---
    @Test
    public void testEquippedCreatureNotGrouped() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(1);

        List<Card> bears = addCards("Grizzly Bears", 3, p1);
        // Equip one bear
        Card sword = addCard("Short Sword", p1);
        sword.attachToEntity(bears.get(0), null);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);
        game.getAction().checkStateEffects(true);

        CardCollectionView battlefield = game.getCardsIn(ZoneType.Battlefield);
        AiCombatCache cache = new AiCombatCache(battlefield, null, AiCacheMode.ENABLED);

        // Equipped bear should be its own representative
        Card equippedRep = cache.getRepresentative(bears.get(0));
        Card unequippedRep = cache.getRepresentative(bears.get(1));
        AssertJUnit.assertNotSame("Equipped bear should have different representative",
                equippedRep, unequippedRep);

        // The other 2 bears should be grouped
        AssertJUnit.assertSame("Unequipped bears should share representative",
                unequippedRep, cache.getRepresentative(bears.get(2)));
    }

    // --- Scenario 9: Non-token identical creatures are grouped ---
    @Test
    public void testNonTokenIdenticalCreaturesGrouped() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(1);

        // Two copies of the same non-token creature
        Card bear1 = addCard("Grizzly Bears", p1);
        Card bear2 = addCard("Grizzly Bears", p1);
        bear1.setSickness(false);
        bear2.setSickness(false);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);
        game.getAction().checkStateEffects(true);

        CardCollectionView battlefield = game.getCardsIn(ZoneType.Battlefield);
        AiCombatCache cache = new AiCombatCache(battlefield, null, AiCacheMode.ENABLED);

        AssertJUnit.assertSame("Non-token identical creatures should be grouped",
                cache.getRepresentative(bear1), cache.getRepresentative(bear2));
    }

    // --- Scenario 10: No duplicates — each creature is its own group ---
    @Test
    public void testNoDuplicatesEachIsOwnGroup() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(1);

        Card bear = addCard("Grizzly Bears", p1);
        Card giant = addCard("Hill Giant", p1);
        Card elf = addCard("Elvish Mystic", p1);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);
        game.getAction().checkStateEffects(true);

        CardCollectionView battlefield = game.getCardsIn(ZoneType.Battlefield);
        AiCombatCache cache = new AiCombatCache(battlefield, null, AiCacheMode.ENABLED);

        Map<Card, List<Card>> groups = cache.getCreatureGroups();
        AssertJUnit.assertEquals("Each creature should be its own group", 3, groups.size());
        for (List<Card> group : groups.values()) {
            AssertJUnit.assertEquals("Each group should have 1 member", 1, group.size());
        }
    }

    // --- canBlock keyword cache test ---
    @Test
    public void testCanBlockKeywordCacheConsistency() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(1);
        Player p2 = game.getPlayers().get(0);

        // 5 identical bears as potential blockers
        List<Card> bears = addCards("Grizzly Bears", 5, p2);
        // A flying attacker that bears can't block
        Card flyer = addCard("Wind Drake", p1);
        // A ground attacker that bears can block
        Card ground = addCard("Hill Giant", p1);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);
        game.getAction().checkStateEffects(true);

        CardCollectionView battlefield = game.getCardsIn(ZoneType.Battlefield);
        AiCombatCache cache = new AiCombatCache(battlefield, p2, AiCacheMode.ENABLED);

        // First bear vs flyer — should be cached
        Boolean result1 = cache.canBlockKeyword(flyer, bears.get(0));
        AssertJUnit.assertNotNull("Cache should return a result", result1);
        AssertJUnit.assertFalse("Bears can't block flyers", result1);

        // Second bear vs same flyer — should hit cache (same representative)
        Boolean result2 = cache.canBlockKeyword(flyer, bears.get(1));
        AssertJUnit.assertFalse("Cached result should be same", result2);

        // Bear vs ground — should be cacheable, different result
        Boolean result3 = cache.canBlockKeyword(ground, bears.get(0));
        AssertJUnit.assertTrue("Bears can block ground creatures", result3);

        // Another bear vs same ground — cache hit
        Boolean result4 = cache.canBlockKeyword(ground, bears.get(2));
        AssertJUnit.assertTrue("Cached result should be same", result4);
    }

    // --- evaluateCreature cache test ---
    @Test
    public void testEvaluateCreatureCacheConsistency() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(1);

        List<Card> bears = addCards("Grizzly Bears", 5, p1);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);
        game.getAction().checkStateEffects(true);

        CardCollectionView battlefield = game.getCardsIn(ZoneType.Battlefield);
        AiCombatCache cache = new AiCombatCache(battlefield, null, AiCacheMode.ENABLED);

        // Evaluate first bear — computed
        Integer eval1 = cache.evaluateCreature(bears.get(0));
        AssertJUnit.assertNotNull("Should return evaluation", eval1);

        // Evaluate second bear — should be cache hit with same value
        Integer eval2 = cache.evaluateCreature(bears.get(1));
        AssertJUnit.assertEquals("Identical creatures should have same evaluation", eval1, eval2);

        // Verify against uncached evaluation
        int directEval = ComputerUtilCard.evaluateCreature(bears.get(2));
        AssertJUnit.assertEquals("Cached eval should match direct eval", directEval, eval1.intValue());
    }

    // --- Tapped vs untapped creatures are NOT grouped ---
    @Test
    public void testTappedUntappedNotGrouped() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(1);

        Card bear1 = addCard("Grizzly Bears", p1);
        Card bear2 = addCard("Grizzly Bears", p1);
        bear1.setSickness(false);
        bear2.setSickness(false);
        bear2.tap(true, null, null);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);
        game.getAction().checkStateEffects(true);

        CardCollectionView battlefield = game.getCardsIn(ZoneType.Battlefield);
        AiCombatCache cache = new AiCombatCache(battlefield, null, AiCacheMode.ENABLED);

        AssertJUnit.assertNotSame("Tapped and untapped bears should not be grouped",
                cache.getRepresentative(bear1), cache.getRepresentative(bear2));
    }

    // --- Summoning-sick vs non-sick creatures are NOT grouped ---
    @Test
    public void testSummoningSicknessNotGrouped() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(1);

        Card bear1 = addCard("Grizzly Bears", p1);
        Card bear2 = addCard("Grizzly Bears", p1);
        bear1.setSickness(false);
        // bear2 keeps summoning sickness (default)

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);
        game.getAction().checkStateEffects(true);

        CardCollectionView battlefield = game.getCardsIn(ZoneType.Battlefield);
        AiCombatCache cache = new AiCombatCache(battlefield, null, AiCacheMode.ENABLED);

        AssertJUnit.assertNotSame("Sick and non-sick bears should not be grouped",
                cache.getRepresentative(bear1), cache.getRepresentative(bear2));
    }

    // --- Disabled mode returns null from cache lookups ---
    @Test
    public void testDisabledModeReturnsNull() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(1);

        List<Card> bears = addCards("Grizzly Bears", 3, p1);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);
        game.getAction().checkStateEffects(true);

        CardCollectionView battlefield = game.getCardsIn(ZoneType.Battlefield);
        AiCombatCache cache = new AiCombatCache(battlefield, null, AiCacheMode.DISABLED);

        // In disabled mode, cache lookups return null
        AssertJUnit.assertNull("Disabled mode should return null",
                cache.canBlockKeyword(bears.get(0), bears.get(1)));
        AssertJUnit.assertNull("Disabled mode should return null",
                cache.evaluateCreature(bears.get(0)));
    }
}
