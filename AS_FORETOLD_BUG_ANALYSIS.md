# As Foretold "Free Cast" Library/Command Zone Steal Bug

## Bug Summary

When AI opponents control effects like [As Foretold](https://scryfall.com/card/2x2/38/as-foretold) — which let you cast spells for free once per turn — they can illegally cast cards directly from the player's library and command zone, including the player's commander.

The bug is not specific to As Foretold. It affects **any passive effect that grants an alternative casting cost without granting zone permissions**. Confirmed affected cards include:

- As Foretold
- Fires of Invention
- Aluren
- Primal Prayers
- Dracogenesis
- World War Hulk
- Eye of Ojer Taq
- Weftwalking

The bug only manifests for AI players. Human players are not affected (see [Why Human Players Aren't Affected](#why-human-players-arent-affected) below).

## Background: How `MayPlay` Works

Forge's card API expresses "you may cast this spell" effects via the `MayPlay` static ability parameter. Cards like Future Sight (`Affected$ Card.TopLibrary+YouCtrl`) use it to grant zone access — letting you cast from your library top.

For effects that grant an *alternative cost* without granting *zone access*, the API uses `MayPlayDontGrantZonePermissions$ True`. As Foretold's definition is:

```
S:Mode$ Continuous | MayPlay$ True | MayPlayAltManaCost$ 0 | MayPlayLimit$ 1
  | MayPlayDontGrantZonePermissions$ True
  | Affected$ Card.nonLand+cmcLEX
  | AffectedZone$ Hand,Graveyard,Library,Exile,Command
```

The intent: mark every matching card across every zone as eligible for the {0} alternative cost, but **don't** unlock new zones — you still need some other reason to be casting from that zone (e.g. it's in your hand, or another effect like Xanathar grants library access).

When the static ability resolves, `StaticAbilityContinuous` calls `setMayPlay(...)` on every card matching `Affected$` in any of the listed `AffectedZone$` zones. Because there's no ownership filter (no `YouCtrl` or `YouOwn`), the `setMayPlay` call gets applied to **every player's** matching cards — including opponents' library tops and commanders.

The `MayPlayDontGrantZonePermissions` flag is supposed to prevent exploitation of this. The engine's job is to enforce it correctly.

## Root Cause

The bug arises from a divergence between the human-player and AI-player code paths for evaluating spell castability.

### Why Human Players Aren't Affected

Human players use the view system (`Card.updateMayPlay()`), which only surfaces a card as playable in the UI if at least one of its `CardPlayOption`s has `grantsZonePermissions() == true`:

```java
public final void updateMayPlay() {
    PlayerCollection result = new PlayerCollection();
    for (CardPlayOption o : mayPlay.values()) {
        if (o.grantsZonePermissions())
            result.add(o.getPlayer());
    }
    getView().setMayPlayPlayers(result);
}
```

Cards marked by As Foretold in opponents' zones never appear as playable in the UI, so the human player never even gets the option to click on them.

### Why AI Players Are Affected

The AI uses `ComputerUtilAbility.getAvailableCards()` to build a list of candidate cards, then validates each by calling `Spell.canPlay()` (which calls `canPlayFromHost()`). Two bugs combine in this path:

#### Bug A: `getAvailableCards` Hands Opponents' Cards to the AI

`forge-ai/src/main/java/forge/ai/ComputerUtilAbility.java:71-84` (pre-fix):

```java
public static CardCollection getAvailableCards(final Game game, final Player player) {
    CardCollection all = new CardCollection(player.getCardsIn(ZoneType.Hand));
    all.addAll(player.getCardsIn(ZoneType.Graveyard));
    for (Player p : game.getPlayers()) {                        // BUG: all players
        if (!p.getCardsIn(ZoneType.Library).isEmpty()) {
            all.add(p.getCardsIn(ZoneType.Library).get(0));     // BUG: every library top
        }
    }
    all.addAll(game.getCardsIn(ZoneType.Command));              // BUG: all command zones
    all.addAll(game.getCardsIn(ZoneType.Exile));
    all.addAll(game.getCardsIn(ZoneType.Battlefield));
    return all;
}
```

The method indiscriminately includes every player's library top and the entire game-wide command zone, without checking whether the AI has any legitimate reason to cast those cards. This feeds the downstream validator candidates it should never consider.

#### Bug B: LKI Copy Loses `mayPlay` Data, Bypassing Zone Permission Check

LKI (Last Known Information) copies are how Forge takes a frozen snapshot of a card's state for evaluation purposes without mutating the actual game state. In `Spell.canPlayFromHost()` at lines 97-101:

```java
if (!Spell.performanceMode && !card.getController().equals(activator)) {
    card = CardCopyService.getLKICopy(card);
    card.setController(activator, 0);
}
```

When the AI tries to evaluate a card it doesn't control (e.g. an opponent's library card), it creates an LKI copy and temporarily assigns itself as controller — many checks like `isValid("You", controller, ...)` need this to evaluate "you" correctly.

The problem: `CardCopyService.getLKICopy()` copies most card data (name, types, keywords, counters, zone, owner, etc.) but **does not copy the `mayPlay` map**. That map is transient runtime state populated by static abilities, not intrinsic card data.

Then in `SpellAbilityRestriction.checkZoneRestrictions()` at lines 235-238:

```java
if (sa.isSpell()) {
    final CardPlayOption o = c.mayPlay(sa.getMayPlay());
    if (o == null || sa.isCastFromPlayEffect()) {
        return this.getZone() == null || (cardZone != null && cardZone.is(this.getZone()));
    } else if (o.getPlayer() == activator) {
        // ... proper MayPlayDontGrantZonePermissions enforcement here ...
```

The lookup `c.mayPlay(sa.getMayPlay())` returns **null** because `c` is the LKI copy with no mayPlay data. The fallback path at line 238 was written assuming "null means this isn't a MayPlay spell at all" — but in this case it actually means "the data was lost in copying."

Because `getMayPlaySpellOptions` sets `zone = null` for all MayPlay spell abilities (`GameActionUtil.java:373`), `this.getZone() == null` is always true, and the method returns `true` — bypassing the proper `MayPlayDontGrantZonePermissions` enforcement block at lines 245-260 entirely. The cast is allowed.

## The Fix

### Fix 1: Restore Zone Permission Enforcement in `checkZoneRestrictions`

**File:** `forge-game/src/main/java/forge/game/spellability/SpellAbilityRestriction.java`

When the `CardPlayOption` lookup on `c` returns null but the spell ability has a `getMayPlay()` reference, fall back to looking up the option on `sa.getHostCard()` (the original, non-LKI card). This restores access to the `mayPlay` data so the real `grantsZonePermissions` check at lines 245-260 can actually run.

```java
if (sa.isSpell()) {
    CardPlayOption o = c.mayPlay(sa.getMayPlay());
    // If c is an LKI copy, it may not have mayPlay data.
    // Look up from the SA's original host card instead.
    if (o == null && sa.getMayPlay() != null && !c.equals(sa.getHostCard())) {
        o = sa.getHostCard().mayPlay(sa.getMayPlay());
    }
    if (o == null || sa.isCastFromPlayEffect()) {
        return this.getZone() == null || (cardZone != null && cardZone.is(this.getZone()));
    } else if (o.getPlayer() == activator) {
```

This is the primary fix. It addresses the core engine bug.

### Fix 2: AI Candidate Filtering in `getAvailableCards`

**File:** `forge-ai/src/main/java/forge/ai/ComputerUtilAbility.java`

Restrict `getAvailableCards` to only include opponents' library tops when the AI has a `CardPlayOption` that `grantsZonePermissions()` for that card. Restrict command zone to `player.getCardsIn(ZoneType.Command)` since no MTG effect grants casting from opponents' command zones.

```java
public static CardCollection getAvailableCards(final Game game, final Player player) {
    CardCollection all = new CardCollection(player.getCardsIn(ZoneType.Hand));
    all.addAll(player.getCardsIn(ZoneType.Graveyard));
    if (!player.getCardsIn(ZoneType.Library).isEmpty()) {
        all.add(player.getCardsIn(ZoneType.Library).get(0));
    }
    // Only consider opponents' library tops if the player has a zone-granting
    // mayPlay for them (e.g. Xanathar, Windriddle Palaces). Effects like
    // As Foretold that don't grant zone permissions must not let the AI
    // cast from opponents' libraries.
    for (Player p : game.getPlayers()) {
        if (p != player && !p.getCardsIn(ZoneType.Library).isEmpty()) {
            Card top = p.getCardsIn(ZoneType.Library).get(0);
            for (CardPlayOption o : top.mayPlay(player)) {
                if (o.grantsZonePermissions()) {
                    all.add(top);
                    break;
                }
            }
        }
    }
    all.addAll(player.getCardsIn(ZoneType.Command));
    all.addAll(game.getCardsIn(ZoneType.Exile));
    all.addAll(game.getCardsIn(ZoneType.Battlefield));
    return all;
}
```

This is defense-in-depth. With Fix 1 in place, Fix 2 isn't strictly necessary for correctness, but it avoids feeding the validator obviously-illegal candidates and protects against future regressions in the validation path.

#### Why Not Just Restrict to `player.getCardsIn(ZoneType.Library)`?

A naïve fix would simply scope library access to the AI's own library:

```java
if (!player.getCardsIn(ZoneType.Library).isEmpty()) {
    all.add(player.getCardsIn(ZoneType.Library).get(0));
}
```

This breaks legitimate cards like:

- **Xanathar, Guild Kingpin** — "you may play the top card of that player's library"
- **Windriddle Palaces** — "you may play lands and cast spells from the top of any player's library"

Both grant zone permissions and legitimately allow casting from opponents' library tops. The filter must check `grantsZonePermissions()` rather than blindly excluding opponents' libraries.

Note that `game.getCardsIn(ZoneType.Exile)` remains game-wide — many effects (Gonti, Thief of Sanity, Praetor's Grasp) legitimately let you cast opponents' exiled cards, and Fix 1 ensures `MayPlayDontGrantZonePermissions` is properly enforced in the exile case too.

## Known Remaining Issues (Pre-existing, Not Regressions)

### Edge Case: Two Stacked Effects

If the AI has both As Foretold (no zone perms) AND Xanathar (zone perms) for the same opponent's library card, the secondary check in `checkZoneRestrictions` at line 246 (`c.mayPlay(activator)`) still queries the LKI copy and gets an empty list. So `hasOtherGrantor` stays false, and the As Foretold free-cast can't piggyback on Xanathar's zone permission.

This is a pre-existing issue independent of our fix, and it's a very rare scenario requiring two specific cards in play simultaneously. The base case (Xanathar alone, or As Foretold alone) works correctly.

### `checkActivatorRestrictions` Has the Same LKI Lookup Gap

`SpellAbilityRestriction.checkActivatorRestrictions()` at line 356 also calls `c.mayPlay(sa.getMayPlay())` on the LKI copy and gets null, falling through to a generic `isValid("You", controller, ...)` check that passes because the LKI copy's controller was set to the activator. This isn't exploitable on its own — the zone check (now fixed) catches it — but it's technical debt worth addressing in a future cleanup.

## Verification

The fixes were verified by:

1. **Code analysis** of all 24 cards using `MayPlayDontGrantZonePermissions` to confirm the pattern is correctly handled
2. **Adversarial review** to identify regression risks — surfaced the Xanathar/Windriddle issue, which led to refining Fix 2
3. **Cross-checking** all callers of `getAvailableCards` (3 calls across 2 AI files, plus 4 calls in `GameSimulationTest`) to confirm uniform impact
4. **Verification** that `sa.getHostCard()` reliably returns the original card throughout `canPlayFromHost()` (it's never overwritten with the LKI copy — the LKI is only assigned to a local variable)
