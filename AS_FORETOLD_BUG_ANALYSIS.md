# As Foretold "Free Cast" Library/Command Zone Steal Bug

> **Note:** This analysis is based on **static code review and adversarial review only**. No runtime testing or unit tests were performed. The proposed fixes should be validated against the existing test suite and exercised in-game before being relied upon.

## Bug Summary

When AI opponents control effects like [As Foretold](https://scryfall.com/card/2x2/38/as-foretold), they can illegally cast cards directly from the player's library and command zone, including the player's commander.

The bug is not specific to As Foretold. It affects any passive effect that grants an alternative casting cost without granting zone permissions, including: As Foretold, Fires of Invention, Aluren, Primal Prayers, Dracogenesis, World War Hulk, Eye of Ojer Taq, and Weftwalking.

The bug only manifests for AI players. Human players are not affected.

## Background: How `MayPlay` Works

Forge's card API uses the `MayPlay` static ability parameter to express "you may cast this spell" effects. For effects that grant an *alternative cost* without granting *zone access*, the API uses `MayPlayDontGrantZonePermissions$ True`. As Foretold's definition is:

```
S:Mode$ Continuous | MayPlay$ True | MayPlayAltManaCost$ 0 | MayPlayLimit$ 1
  | MayPlayDontGrantZonePermissions$ True
  | Affected$ Card.nonLand+cmcLEX
  | AffectedZone$ Hand,Graveyard,Library,Exile,Command
```

When this resolves, `StaticAbilityContinuous` calls `setMayPlay(...)` on every card matching `Affected$` in any of the listed zones. Because there's no ownership filter, this gets applied to **every player's** matching cards — including opponents' library tops and commanders. The `MayPlayDontGrantZonePermissions` flag is the engine's enforcement hook to prevent that mark from being exploited as new zone access.

## Root Cause

The bug arises from a divergence between the human-player and AI-player code paths.

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

Cards marked by As Foretold in opponents' zones never appear as playable in the UI, so the human player never gets the option.

### Why AI Players Are Affected

The AI uses `ComputerUtilAbility.getAvailableCards()` to build a list of candidate cards, then validates each via `Spell.canPlay()` → `canPlayFromHost()`. Two bugs combine here:

#### Bug A: `getAvailableCards` Hands Opponents' Cards to the AI

`forge-ai/src/main/java/forge/ai/ComputerUtilAbility.java:71-84`:

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

The method indiscriminately includes every player's library top and the entire game-wide command zone, without checking whether the AI has any legitimate reason to cast those cards.

#### Bug B: LKI Copy Loses `mayPlay` Data, Bypassing Zone Permission Check

In `Spell.canPlayFromHost()` at lines 97-101:

```java
if (!Spell.performanceMode && !card.getController().equals(activator)) {
    card = CardCopyService.getLKICopy(card);
    card.setController(activator, 0);
}
```

When the AI evaluates a card it doesn't control, it creates an LKI (Last Known Information) copy and assigns itself as controller. But `CardCopyService.getLKICopy()` does not copy the `mayPlay` map — that map is transient runtime state populated by static abilities, not intrinsic card data.

Then in `SpellAbilityRestriction.checkZoneRestrictions()` at lines 235-238:

```java
if (sa.isSpell()) {
    final CardPlayOption o = c.mayPlay(sa.getMayPlay());
    if (o == null || sa.isCastFromPlayEffect()) {
        return this.getZone() == null || (cardZone != null && cardZone.is(this.getZone()));
    } else if (o.getPlayer() == activator) {
        // ... proper MayPlayDontGrantZonePermissions enforcement here ...
```

The lookup `c.mayPlay(sa.getMayPlay())` returns **null** because `c` is the LKI copy with no mayPlay data. The fallback at line 238 was written assuming "null means this isn't a MayPlay spell at all" — but here it actually means "the data was lost in copying." Because `getMayPlaySpellOptions` sets `zone = null` for all MayPlay spell abilities (`GameActionUtil.java:373`), `this.getZone() == null` is always true, and the method returns `true` — bypassing the proper enforcement block at lines 245-260 entirely.

## The Fix

### Fix 1: Restore Zone Permission Enforcement (primary)

**File:** `forge-game/src/main/java/forge/game/spellability/SpellAbilityRestriction.java`

When the lookup on `c` returns null but the SA has a `getMayPlay()` reference, fall back to the original (non-LKI) host card so the real `grantsZonePermissions` check at lines 245-260 can run:

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

This addresses the core engine bug.

### Fix 2: AI Candidate Filtering (defense-in-depth)

**File:** `forge-ai/src/main/java/forge/ai/ComputerUtilAbility.java`

Only include opponents' library tops when the AI has a `CardPlayOption` that `grantsZonePermissions()` for that card. Restrict command zone to `player.getCardsIn(ZoneType.Command)`:

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

A naïve "just use `player.getCardsIn(ZoneType.Library)`" fix would break legitimate cards like **Xanathar, Guild Kingpin** and **Windriddle Palaces**, which do grant zone permissions to play from opponents' libraries. The filter must check `grantsZonePermissions()` rather than blindly excluding opponents' libraries.

`game.getCardsIn(ZoneType.Exile)` remains game-wide — Gonti, Thief of Sanity, Praetor's Grasp, etc. legitimately let you cast opponents' exiled cards. Fix 1 ensures `MayPlayDontGrantZonePermissions` is enforced in the exile case too.

## Known Remaining Issues (Pre-existing, Not Regressions)

**Edge case — two stacked effects:** If the AI has both As Foretold (no zone perms) AND Xanathar (zone perms) for the same opponent's library card, the secondary check at line 246 (`c.mayPlay(activator)`) still queries the LKI copy and gets an empty list, so `hasOtherGrantor` stays false. The As Foretold free-cast can't piggyback on Xanathar's zone permission. The base case (either card alone) works correctly.

**`checkActivatorRestrictions` has the same LKI lookup gap:** Line 356 calls `c.mayPlay(sa.getMayPlay())` on the LKI copy and gets null, falling through to a generic `isValid("You", controller, ...)` that passes because the LKI's controller was set to the activator. Not exploitable on its own — the zone check (now fixed) catches it — but technical debt worth a future cleanup.

## Scope of This Analysis

This is a static code review and adversarial review. No runtime testing, unit testing, or in-game verification was performed. The static analysis covered:

- All 24 cards in `cardsfolder/` using `MayPlayDontGrantZonePermissions`, to confirm the affected pattern and identify cards that would and wouldn't be impacted.
- All callers of `getAvailableCards` (3 calls across 2 AI files, plus 4 calls in `GameSimulationTest`).
- The flow from `getAvailableCards` → `getAllPossibleAbilities` → `getMayPlaySpellOptions` → `Spell.canPlayFromHost` → `checkZoneRestrictions`.
- Whether `sa.getHostCard()` is stable through `canPlayFromHost()` (it is — the LKI copy is only assigned to a local variable, never back to the SA).
- An adversarial pass that surfaced the Xanathar/Windriddle Palaces regression risk in an earlier draft of Fix 2.

The fixes should be validated against the existing test suite and exercised in-game before being merged.
