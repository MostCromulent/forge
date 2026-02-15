# GameEvent View Refactor Plan

## Table of Contents

1. [Goal](#goal)
2. [Current State](#current-state)
   - [Engine Object Usage in Events](#engine-object-usage-in-events)
   - [Visitor Implementations](#visitor-implementations)
3. [Strategy: Convenience Constructors](#strategy-convenience-constructors)
   - [Field Type Changes](#field-type-changes)
   - [Zone Handling](#zone-handling)
   - [Mana Handling](#mana-handling)
   - [Formerly "Server-Only" Events](#formerly-server-only-events-updated-per-investigation-4-and-trt-feedback)
4. [CardView Gaps for EventVisualizer](#cardview-gaps-for-eventvisualizer)
   - [Addressing the Land Sound Gap — RESOLVED (non-issue)](#addressing-the-land-sound-gap--resolved-non-issue)
5. [Implementation Order](#implementation-order)
   - [Step 1: Foundation](#step-1-foundation)
   - [Step 2: Simple Events (~27 records)](#step-2-simple-events-27-records)
   - [Step 3: Complex Events (~20 records) — COMPLETE](#step-3-complex-events-20-records--complete)
   - [Step 4: Visitor Updates — COMPLETE](#step-4-visitor-updates--complete-done-as-part-of-steps-2-and-3)
   - [Step 5: Network Integration — COMPLETE](#step-5-network-integration--complete)
   - [Step 6: Retire Redundant Protocol Methods — COMPLETE](#step-6-retire-redundant-protocol-methods--complete)
6. [Implementation Order & Dependency](#implementation-order--dependency)
7. [Risk Analysis](#risk-analysis)
8. [Testing Strategy — COMPLETE](#testing-strategy--complete)
   - [Per-Step Gate — COMPLETE](#per-step-gate--complete)
   - [New Unit Tests — COMPLETE](#new-unit-tests--complete)
   - [Existing Tests](#existing-tests)
   - [Manual Network Testing](#manual-network-testing-after-steps-5-and-6)
   - [Test Scope in the PR](#test-scope-in-the-pr)
9. [Estimated Scope (Single PR)](#estimated-scope-single-pr)
10. [Guidelines Compliance](#guidelines-compliance)
11. [Delta Sync Integration (NetworkPlay/main)](#delta-sync-integration-networkplaymain)
    - [Architectural Difference: Inheritance Hierarchy](#architectural-difference-inheritance-hierarchy)
    - [Event Forwarding vs Delta Sync: Parallel Channels](#event-forwarding-vs-delta-sync-parallel-channels)
    - [Ordering Guarantee](#ordering-guarantee)
    - [Protocol Method Retirement — Already Aligned](#protocol-method-retirement-step-6--already-aligned)
    - [New TrackableProperty Entries](#new-trackableproperty-entries-step-1)
    - [GameEvent extends Serializable](#gameevent-extends-serializable-step-1)
    - [Subgame Lifecycle Wiring — N/A](#subgame-lifecycle-wiring--na-per-trt-feedback)
    - [Pre-Computed Event Fields and Client-Side Log Generation](#pre-computed-event-fields-and-client-side-log-generation)
    - [Summary: Integration Checklist](#summary-integration-checklist)
12. [Open Questions](#open-questions)
13. [Pre-Implementation Investigation](#pre-implementation-investigation)
    - [Investigation 1: View Class API Audit](#investigation-1-view-class-api-audit--complete)
    - [Investigation 2: GameLogFormatter Full Audit](#investigation-2-gamelogformatter-full-audit--complete)
    - [Investigation 3: Protocol Method Data Audit](#investigation-3-protocol-method-data-audit--complete)
    - [Investigation 4: Formerly-Excluded Event Data Audit](#investigation-4-formerly-excluded-event-data-audit--complete-amended-per-trt-feedback)
    - [Investigation 5: GameOutcome Serializability](#investigation-5-gameoutcome-serializability--complete)
    - [Investigation 6: Netty Serialization Round-Trip](#investigation-6-netty-serialization-round-trip--complete)

---

## Goal

Make **all** `GameEvent` subclasses network-serializable by replacing engine object references (`Card`, `Player`, `SpellAbility`, `Zone`) with their serializable view counterparts (`CardView`, `PlayerView`, `SpellAbilityView`, `ZoneType`). This enables forwarding raw game events to network clients, allowing each client to process events locally using its own `IGameEventVisitor` implementations, settings, and locale.

**Key principle (per TRT feedback):** All events are serializable, all events are forwarded. No "server-only" vs "network-safe" split — maintaining two classes of events adds complexity. As clients handle events locally, redundant per-feature protocol methods (`updateLives`, `hearSoundEffect`, etc.) are retired. The net result is **reduced** protocol complexity, not increased.

**Target:** Forge master (Card-Forge/forge). Single PR.
**Branch architecture:** `IGuiGame` → `AbstractGuiGame` → `CMatchUI` / `NetGuiGame` / `MatchController` (no `NetworkGuiGame` or delta sync).

## Current State

| Metric | Count |
|--------|-------|
| GameEvent subclasses (records) | 55 *(57 minus 2 subgame events reclassified as UiEvent)* |
| Already serializable (primitives/enums only) | 10 |
| Reference non-serializable engine objects | 45 *(47 minus 2 subgame events reclassified as UiEvent)* |
| `new GameEvent*` creation sites | 167 |
| Files containing creation sites | 63 |
| Visitor implementations | 5 |

### Engine Object Usage in Events

| Engine Type | Events Using It | View Equivalent | Status |
|-------------|-----------------|-----------------|--------|
| `Player` | ~25 | `PlayerView` | Ready |
| `Card` | ~25 | `CardView` | Ready (minor gaps) |
| `SpellAbility` | 4 | `SpellAbilityView` | Ready |
| `SpellAbilityStackInstance` | 1 | `StackItemView` | Ready |
| `GameEntity` | 3 | `GameEntityView` | Ready |
| `Zone` | 2 | `ZoneType` (enum) | Ready (downgrade to enum) |
| `Mana` | 1 | None | Needs new view or simplification |
| `Game` | ~~2~~ 0 | ~~`String` (message only)~~ | ~~Replace with minimal serializable data~~ — Both events (`SubgameStart`, `SubgameEnd`) reclassified as `UiEvent` (per TRT feedback) |
| `PlayerController` | 1 | `PlayerView` | Extract player identity |
| `Multimap<GameEntity, Card>` | 1 | `Multimap<GameEntityView, CardView>` | Needs conversion helper |
| `Map<GameEntity, Multimap<Card,Card>>` | 1 | Equivalent with views | Needs conversion helper |
| `Collection<Card>` | 4 | `Collection<CardView>` | `CardView.getCollection()` exists |
| `Collection<Player>` | 2 | `Collection<PlayerView>` | Needs helper |
| `GameOutcome` | 1 | Pre-computed primitives/strings | **Not serializable** (Investigation 5) — replace with pre-computed fields |

### Visitor Implementations

| Visitor | Module | Needs Engine Objects? | Network-Relevant? |
|---------|--------|----------------------|-------------------|
| `FControlGameEventHandler` | forge-gui | No (already converts to views) | Yes — primary client consumer |
| `FControlGamePlayback` | forge-gui | Minimal (1 SpellAbility access) | Yes — client replay |
| `EventVisualizer` | forge-gui | Yes (SVar, mana abilities) | Yes — client sound effects |
| `GameLogFormatter` | forge-game | Moderate (toString/getName) | Possible — client-side logging |
| `MatchUiEventVisitor` | forge-gui | Yes (PlayerController, Game) | Mostly host-only — subgame events reclassified as `UiEvent` (per TRT feedback). Remaining `GameEvent` handlers unchanged. |

## Strategy: Convenience Constructors

Rather than changing all 167 creation sites, use **overloaded constructors** on each event record. The record's canonical constructor stores view objects, but an additional convenience constructor accepts engine objects and calls `.getView()` internally.

```java
// BEFORE:
public record GameEventCardDamaged(Card card, Card source, int amount, DamageType type)
    implements GameEvent { ... }

// AFTER:
public record GameEventCardDamaged(CardView card, CardView source, int amount, DamageType type)
    implements GameEvent {

    /** Convenience constructor — converts engine objects to views at creation. */
    public GameEventCardDamaged(Card card, Card source, int amount, DamageType type) {
        this(CardView.get(card), CardView.get(source), amount, type);
    }

    @Override public <T> T visit(IGameEventVisitor<T> visitor) { return visitor.visit(this); }
}
```

**Benefits:**
- **Zero changes at creation sites.** All 167 `new GameEventFoo(engineObj, ...)` calls continue to compile because the convenience constructor accepts engine types.
- **Diff concentrated in event definitions** (57 files) and **visitor updates** (5 files).
- **Backward-compatible.** Existing code that creates events with engine objects keeps working. New code can create events with view objects directly (for the network path).

**Trade-off:** Records with overloaded constructors are slightly unusual in Java. The convenience constructor is clearly documented as a migration aid. Once all creation sites are optionally migrated in a future cleanup, the convenience constructors could be deprecated and removed.

### Field Type Changes

When a record field changes from an engine type to a view type, all visitors that read that field via `event.fieldName()` now get the view type. This requires updating visitor method bodies. The `IGameEventVisitor` interface itself does NOT change — its method signatures are `visit(GameEventFoo event)`, which are unchanged.

### Zone Handling

`Zone` has no view class. Replace with `ZoneType` enum (already serializable):

```java
// BEFORE:
public record GameEventCardChangeZone(Card card, Zone from, Zone to) implements GameEvent { ... }

// AFTER:
public record GameEventCardChangeZone(CardView card, ZoneType from, ZoneType to)
    implements GameEvent {

    public GameEventCardChangeZone(Card card, Zone zoneFrom, Zone zoneTo) {
        this(CardView.get(card),
             zoneFrom == null ? null : zoneFrom.getZoneType(),
             zoneTo.getZoneType());
    }
}
```

Visitors currently calling `event.from().getZoneType()` change to `event.from()` — a simplification.

### Mana Handling

`Mana` (a record containing `Card`, `AbilityManaPart`, `Player`) has no view. Only one event uses it: `GameEventManaPool(Player, EventValueChangeType, Mana)`. The only consumer (`FControlGameEventHandler`) ignores the Mana field entirely — it just uses the Player to queue a mana pool UI update.

**Option A (recommended):** Replace `Mana` with `byte color` (the only field any consumer could need):
```java
public record GameEventManaPool(PlayerView player, EventValueChangeType mode, byte manaColor)
```

**Option B:** Create a `ManaView` record. Overkill given the single consumer doesn't use it.

### Formerly "Server-Only" Events *(Updated per Investigation 4 and TRT feedback)*

#### Subgame Events → Reclassified as `UiEvent` *(per TRT feedback)*

`GameEventSubgameStart` and `GameEventSubgameEnd` are **reclassified as `UiEvent`** (not `GameEvent`). These events are host-local lifecycle signals — they wire up event subscriptions, switch GUI views, and iterate players for controller checks. None of this is relevant to a remote client. If the `EventVisualizer` listened to both `GameEvent` and `UiEvent`, forwarding these to a remote client could cause it to play sounds or react to GUI transitions that aren't part of its game flow.

**Action:** Move the two event records from `forge.game.event` to `forge.gui.events`, change them to implement `UiEvent` instead of `GameEvent`, and update their visitor interface to `IUiEventVisitor`. The existing `MatchUiEventVisitor` handlers in `HostedMatch` remain unchanged — no `SubgameEffect` extraction needed. `GameLogFormatter` and `FControlGameEventHandler` have no handlers for these events, so no changes there either.

This creates a clean dividing line: `GameEvent` = game state (serialized, forwarded to network clients). `UiEvent` = local GUI concerns (host-only, never forwarded).

#### `GameEventPlayerControl` → Serializable `GameEvent`

One event references engine-only types (`PlayerController`, `LobbyPlayer`). The engine types are replaced with minimal serializable equivalents:

- `GameEventPlayerControl(Player, LobbyPlayer, PlayerController, LobbyPlayer, PlayerController)` → `GameEventPlayerControl(PlayerView player, String oldLobbyPlayerName, String newLobbyPlayerName, boolean newControllerIsHuman)` — `GameLogFormatter` uses only `.player().getName()` and `.newLobbyPlayer().getName()` (no `PlayerController` access). `FControlGameEventHandler` uses `instanceof PlayerControllerHuman` (replaced by boolean field) and `setGameController` wiring (uses handler's own `humanController` field). `LobbyPlayer` is NOT serializable (Investigation 6) — replaced with `String` names.

## CardView Gaps for EventVisualizer

`EventVisualizer` is the most demanding consumer. `CardView.CardStateView` already exposes:
- `isCreature()`, `isArtifact()`, `isEnchantment()`, `isLand()`, `isPlaneswalker()` — all present *(confirmed Investigation 1)*
- `getName()` — present

**Missing from CardView** *(confirmed Investigation 1):*
1. `isInstant()` / `isSorcery()` — Not on `CardStateView`. **Adding in Step 1** (trivial — delegate to `getType().isInstant()` / `getType().isSorcery()`). `CardTypeView` already has both methods.
2. `hasSVar("SoundEffect")` / `getSVar("SoundEffect")` — Engine-only. Used for `ScriptedEffect` card-specific sounds. **Already excluded** from network forwarding in soundfix branch. Client-side EventVisualizer would skip ScriptedEffect logic.
3. `getManaAbilities()` → `AbilityManaPart.getOrigProduced()` — Used for land color sound selection. Not on CardView.

**Also missing from SpellAbilityView** *(discovered Investigation 1):*
4. `isSpell()` — Used by EventVisualizer to determine sound for spell resolution. **Adding in Step 1.**
5. `isTrigger()` — Used by GameLogFormatter. **Adding in Step 1.**
6. `getActivatingPlayer()` — Used by GameLogFormatter. **Adding in Step 1.**

### Addressing the Land Sound Gap — RESOLVED (non-issue)

The land sound gap was a non-issue. `CardView.CardStateView` already has `origProduceManaW()`, `origProduceManaU()`, `origProduceManaB()`, `origProduceManaR()`, `origProduceManaG()` tracked properties (backed by `TrackableProperty.OrigProduceMana*`), which are synced to clients via the normal GameView update mechanism. The `getLandSound()` implementation reads these 5 booleans to determine mono/dual/tri-color land sounds with full accuracy — no degradation, no new infrastructure needed.

## Implementation Order

All work lands in a single PR. Implementation proceeds in this order to keep the branch compilable at each step.

### Step 1: Foundation — COMPLETE

**Scope:** Add missing view methods, create conversion utilities. (Updated per Investigation 1 findings.)

1. **Add to `CardView.CardStateView`** (confirmed missing, Investigation 1):
   - `isInstant()` → `return getType().isInstant();` — follows exact pattern of 5 existing type-check delegates
   - `isSorcery()` → `return getType().isSorcery();`

2. **Add to `SpellAbilityView`** (confirmed missing, Investigation 1):
   - `isSpell()` → tracked boolean property via `TrackableProperty`. Updated in `SpellAbility.updateView()`.
   - `isTrigger()` → tracked boolean property. Needed by GameLogFormatter for "Cast"/"Triggered"/"Activated" text.
   - `getActivatingPlayer()` → tracked `PlayerView` property. Needed by GameLogFormatter for "Player cast/activated" log entries.
   - Note: `getHostCard()` already exists and returns `CardView` — no change needed.

3. **Collection conversion helpers — already present** (confirmed, Investigation 1):
   - `PlayerView.getCollection(Iterable<Player>)` ✓ exists (line 37)
   - `GameEntityView.getEntityCollection(Iterable<? extends GameEntity>)` ✓ exists (line 18)
   - No new helpers needed.

4. **`PlayerView.getLobbyPlayer()` — not needed** (confirmed, Investigation 1):
   - `PlayerView.isLobbyPlayer(LobbyPlayer)` exists and covers the only usage (EventVisualizer identity comparison).

5. **Verify `GameEntityView.toString()` matches `GameEntity.toString()`** (Investigation 2):
   - `GameEntity.toString()` returns `name`. `GameEntityView` inherits from `TrackableObject` which may not override `toString()`.
   - If `GameEntityView.toString()` does NOT return `getName()`, add: `@Override public String toString() { return getName(); }`
   - This is critical for `Lang.joinHomogenous()` and string interpolation in GameLogFormatter's combat log methods.

6. **~~Verify `GameOutcome` is serializable.~~** **RESOLVED (Investigation 5).** `GameOutcome` is NOT serializable and cannot feasibly be made so (deep `RegisteredPlayer` → `Deck` dependency). No Step 1 changes needed — handled in Step 3 via pre-computed event fields.

7. **Add `Serializable` to `GameEvent` interface** (Investigation 6):
   - Change `public interface GameEvent extends Event` → `public interface GameEvent extends Event, Serializable`
   - This makes all 57 event records serializable (records implement `Serializable` automatically when their interface does).
   - All record fields will be serializable after the refactor (view objects, enums, primitives, strings).
   - One-line change in `GameEvent.java`. Add `import java.io.Serializable;`.

**Files changed:** ~6 (`CardView.java`, `SpellAbilityView.java`, `SpellAbility.java`, `TrackableProperty.java`, possibly `GameEntityView.java`, `GameEvent.java`)
**Risk:** Very low — additive only. All additions follow existing patterns.

### Step 2: Simple Events (~27 records) — COMPLETE

**Scope:** Migrate events whose fields are only `Player`, `Card`, or primitives. These are the most numerous and mechanically straightforward.

**Events:**

| Event | Fields to Convert |
|-------|-------------------|
| `GameEventCardDamaged` | `Card` → `CardView` (x2) |
| `GameEventLandPlayed` | `Player` → `PlayerView`, `Card` → `CardView` |
| `GameEventPlayerDamaged` | `Player` → `PlayerView`, `Card` → `CardView` |
| `GameEventCardSacrificed` | `Card` → `CardView` |
| `GameEventCardTapped` | `Card` → `CardView` |
| `GameEventCardPhased` | `Card` → `CardView` |
| `GameEventCardCounters` | `Card` → `CardView` |
| `GameEventTurnBegan` | `Player` → `PlayerView` |
| `GameEventTurnPhase` | `Player` → `PlayerView` |
| `GameEventShuffle` | `Player` → `PlayerView` |
| `GameEventMulligan` | `Player` → `PlayerView` |
| `GameEventScry` | `Player` → `PlayerView` |
| `GameEventSurveil` | `Player` → `PlayerView` |
| `GameEventManaBurn` | `Player` → `PlayerView` |
| `GameEventPlayerPoisoned` | `Player` → `PlayerView` (x2) |
| `GameEventPlayerLivesChanged` | `Player` → `PlayerView` |
| `GameEventPlayerShardsChanged` | `Player` → `PlayerView` |
| `GameEventPlayerCounters` | `Player` → `PlayerView` |
| `GameEventPlayerRadiation` | `Player` → `PlayerView` (x2) |
| `GameEventSpeedChanged` | `Player` → `PlayerView` |
| `GameEventCardModeChosen` | `Player` → `PlayerView` |
| `GameEventCardForetold` | `Player` → `PlayerView` |
| `GameEventGameRestarted` | `Player` → `PlayerView` |
| `GameEventPlayerPriority` | `Player` → `PlayerView` (x2) |
| `GameEventDoorChanged` | `Player` → `PlayerView`, `Card` → `CardView` |
| `GameEventCardPlotted` | `Player` → `PlayerView`, `Card` → `CardView` |
| `GameEventSprocketUpdate` | `Card` → `CardView` |

**Pattern for each event:**
```java
// 1. Change canonical field types to views
// 2. Add convenience constructor accepting engine types
// 3. visit() method body is unchanged
```

**Files changed:** ~27 event records
**Risk:** Low — mechanical transformation.

### Step 3: Complex Events (~20 records) — COMPLETE

**Scope:** Events with `Zone`, `SpellAbility`, `Mana`, collections, and complex types.

| Event | Complexity |
|-------|-----------|
| `GameEventCardChangeZone` | `Zone` → `ZoneType` |
| `GameEventZone` | `Card` → `CardView`, `SpellAbility` → `SpellAbilityView` (ZoneType already enum) |
| `GameEventSpellAbilityCast` | `SpellAbility` → `SpellAbilityView`, `SpellAbilityStackInstance` → `StackItemView`. **New field:** `String targetDescription` — pre-computed in convenience constructor from `sa.getTargetRestrictions()` / `sa.getAllTargetChoices()` (Investigation 2). |
| `GameEventSpellResolved` | `SpellAbility` → `SpellAbilityView`. **New field:** `String stackDescription` — pre-computed in convenience constructor from `spell.getStackDescription()` (Investigation 2). |
| `GameEventSpellRemovedFromStack` | `SpellAbility` → `SpellAbilityView` |
| `GameEventManaPool` | `Player` → `PlayerView`, `Mana` → `byte` color |
| `GameEventCardAttachment` | `Card` → `CardView`, `GameEntity` → `GameEntityView` (x2) |
| `GameEventCardStatsChanged` | `Collection<Card>` → `Collection<CardView>` |
| `GameEventCardRegenerated` | `Collection<Card>` → `Collection<CardView>` |
| `GameEventAttackersDeclared` | `Multimap<GameEntity, Card>` → `Multimap<GameEntityView, CardView>` |
| `GameEventBlockersDeclared` | `Map<GameEntity, Multimap<Card, Card>>` → view equivalent |
| `GameEventCombatEnded` | `List<Card>` → `List<CardView>` (x2) |
| `GameEventCombatUpdate` | `List<Card>` → `List<CardView>` (x2) |
| `GameEventAnteCardsSelected` | `Multimap<Player, Card>` → `Multimap<PlayerView, CardView>` |
| `GameEventPlayerStatsChanged` | `Collection<Player>` → `Collection<PlayerView>` |
| `GameEventGameStarted` | `Player` → `PlayerView`, `Iterable<Player>` → `Iterable<PlayerView>` |
| ~~`GameEventSubgameStart`~~ | ~~Reclassified as `UiEvent` — removed from GameEvent scope (per TRT feedback).~~ |
| ~~`GameEventSubgameEnd`~~ | ~~Reclassified as `UiEvent` — removed from GameEvent scope (per TRT feedback).~~ |
| `GameEventPlayerControl` | `Player` → `PlayerView`, drop both `PlayerController` fields, add `boolean newControllerIsHuman`. `LobbyPlayer` → `String` names (NOT serializable — Investigation 6). |
| `GameEventGameOutcome` | `GameOutcome` → pre-computed `int lastTurnNumber`, `List<String> outcomeStrings`, `String winningPlayerName`, `String matchSummary`. `Collection<GameOutcome> history` → folded into `matchSummary`. *(Investigation 5)* |

**Conversion helpers** — static methods on each event's convenience constructor (no utility class needed):

```java
// Inside GameEventAttackersDeclared:
public GameEventAttackersDeclared(Player player, Multimap<GameEntity, Card> attackersMap) {
    this(PlayerView.get(player), convertMap(attackersMap));
}
private static Multimap<GameEntityView, CardView> convertMap(Multimap<GameEntity, Card> map) {
    Multimap<GameEntityView, CardView> result = HashMultimap.create();
    for (Map.Entry<GameEntity, Card> entry : map.entries()) {
        result.put(GameEntityView.get(entry.getKey()), CardView.get(entry.getValue()));
    }
    return result;
}
```

**Files changed:** ~19 event records *(reduced: subgame events reclassified as UiEvent, no SubgameEffect extraction needed)*
**Risk:** Medium. Collection conversions need null-safety. `Mana` simplification changes what data is available. `PlayerController` replacement needs auditing to ensure no essential data is lost.

### Step 4: Visitor Updates — COMPLETE (done as part of Steps 2 and 3)

**Scope:** Update all 5 visitors to compile and behave correctly with view-based event fields.

**FControlGameEventHandler:** *(Updated per Investigations 1, 4)*
- Remove all `.getView()` calls on event fields (now redundant).
- `visit(GameEventTurnPhase)`: `ap.getTokensInPlay()` / `ap.getCreaturesInPlay()` — these Player methods are NOT on PlayerView. PlayerView has `getBattlefield()` returning `FCollectionView<CardView>`. Simplest fix: always refresh battlefield for the turn player (minor perf cost but safe), or filter `getBattlefield()` on card type.
- `visit(GameEventPlayerControl)`: `event.player().getGame().isGameOver()` — engine-only. After refactor, event carries `PlayerView`. Use `GameView.isGameOver()` accessible from the GUI layer. `ev.newController() instanceof PlayerControllerHuman` → `ev.newControllerIsHuman()`. For `setGameController` wiring: use the handler's own `humanController` field when `newControllerIsHuman` is true.
- `visit(GameEventShuffle)`: `event.player().getZone(ZoneType.Library)` — used to get library zone for update. PlayerView has `getCards(ZoneType.Library)` and zone tracking, but the actual usage here is just triggering a zone update in the GUI, so the visitor can use `updateZone(playerView, ZoneType.Library)` directly.
- ~~`visit(GameEventSubgameEnd)`:~~ No longer a `GameEvent` — reclassified as `UiEvent` (per TRT feedback). No changes needed here.
- Net effect: mostly code simplification, with a few minor adjustments.

**EventVisualizer:** *(Updated per Investigation 1)*
- `visit(GameEventSpellResolved)`: `evt.spell().getHostCard()` now returns `CardView`. Change `source.isCreature()` etc. to `source.getCurrentState().isCreature()`. `evt.spell().isSpell()` now available via new `SpellAbilityView.isSpell()` added in Step 1.
- `visit(GameEventZone)`: `card.isLand()` → `card.getCurrentState().isLand()`. Land color sounds use `origProduceMana*()` tracked properties on `CardView.CardStateView` — full color accuracy, no degradation.
- `visit(GameEventBlockersDeclared)`: Change `Objects.equals(event.defendingPlayer().getLobbyPlayer(), player)` to `event.defendingPlayer().isLobbyPlayer(player)`. `PlayerView.isLobbyPlayer()` already exists and does the same comparison.
- `hasSpecificCardEffect()`: Accept `CardView`. `hasSVar()` not on views → always return `false` (ScriptedEffect excluded from network forwarding anyway).
- `getScriptedSoundEffectName()`: This method is only called from `SoundSystem` on the host, which subscribes directly to the `Game` EventBus. The host still fires events with engine objects via the convenience constructors, so the canonical view fields are populated from `.getView()` calls. **However**, `getScriptedSoundEffectName()` casts events to access engine-specific fields (`evSpell.spell().getHostCard()` as `Card`, `evZone.card()` as `Card`). After the refactor these return `CardView`, not `Card`. This method needs one of:
  - (a) Overload accepting `CardView` with a view-compatible implementation
  - (b) Keep it working — `CardView` has `.getName()` which is sufficient for the filename lookup. `hasSVar` check can fall through to name-based lookup. This is likely the simplest path.

**GameLogFormatter:**
*(Updated per Investigation 2 — full per-method audit complete)*
- **Critical:** Replace `card.toString()` → `card.getName()` in 8+ places (CardView.toString() ≠ Card.toString()).
- `visit(GameEventSpellResolved)`: Use new `ev.stackDescription()` field (added in Step 3) instead of `ev.spell().getStackDescription()`.
- `visit(GameEventSpellAbilityCast)`: Use `ev.si().getText()` for stack description; use new `ev.targetDescription()` field for targets.
- `visit(GameEventMulligan)`: `ev.player().getZone(ZoneType.Hand).size()` → `ev.player().getHandSize()`.
- `visit(GameEventBlockersDeclared)`: `instanceof Card c` → `instanceof CardView cv`; use `cv.getCurrentState().isBattle()`, `cv.getProtectingPlayer()`, `cv.getController()` (all confirmed present on CardView).
- `visit(GameEventAttackersDeclared)` / `visit(GameEventBlockersDeclared)`: `Lang.joinHomogenous()` calls on card collections must use names, not `toString()`.
- `visit(GameEventPlayerControl)`: `event.player()` now returns `PlayerView` — `.getName()` available, no functional change.
- 14 of 19 methods need only trivial or no changes. No fundamentally engine-only access.

**FControlGamePlayback:**
- `visit(GameEventSpellResolved)`: `evt.spell().getHostCard()` → now returns `CardView`. Minor update.

**MatchUiEventVisitor:** *(Updated per Investigation 4 and TRT feedback)*
- `visit(GameEventSubgameStart)` and `visit(GameEventSubgameEnd)`: **No changes needed.** These events are reclassified as `UiEvent` (per TRT feedback) and remain host-local. The existing `MatchUiEventVisitor` handlers stay as-is — no `SubgameEffect` extraction required.
- `visit(GameEventPlayerControl)`: No override exists in `MatchUiEventVisitor` (uses `Base` default → null). No change needed.

**Files changed:** ~5
**Risk:** Low-medium (downgraded from medium-high per Investigation 2). Main risk is consistent `toString()` → `getName()` replacement.

### Step 5: Network Integration — COMPLETE

**Scope:** Add `GameEvent` forwarding to the existing master branch protocol pipeline. **All** events are forwarded — no filtering, no two-class split.

1. **Add a generic event forwarding protocol method** to `ProtocolMethod.java`:
   ```java
   forwardGameEvent(Mode.SERVER, Void.TYPE, GameEvent.class),
   ```

2. **Add `IGuiGame.handleGameEvent(GameEvent)`** — new interface method. Default no-op in `AbstractGuiGame`.

3. **Implement in `NetGuiGame`** (server-side proxy forwards to client):
   ```java
   @Override
   public void handleGameEvent(GameEvent event) {
       send(ProtocolMethod.forwardGameEvent, event);
   }
   ```

4. **Implement in `AbstractGuiGame`** (client receives and dispatches locally):
   ```java
   @Override
   public void handleGameEvent(GameEvent event) {
       // Dispatch to local visitors — sound, GUI updates, log, lifecycle, etc.
       // Subclasses (CMatchUI, MatchController) can override for platform-specific handling.
   }
   ```

5. **Forward from `HostedMatch.MatchUiEventVisitor.receiveGameEvent()`:**
   ```java
   @Subscribe
   public void receiveGameEvent(final GameEvent evt) {
       evt.visit(this);

       // Forward ALL events to remote clients — no filtering
       for (IGuiGame gui : guis.values()) {
           if (gui instanceof NetGuiGame) {
               gui.handleGameEvent(evt);
           }
       }
   }
   ```

6. **Client-side event dispatch in `AbstractGuiGame.handleGameEvent()`:**
   The client runs its local `FControlGameEventHandler`, `EventVisualizer`, and `GameLogFormatter` on received events. Since all events are now view-based and all are forwarded, the client handles everything locally — sounds, UI updates, log generation, lifecycle events. This is the **primary synchronization path**, not a supplement to per-method protocol calls.

**Files changed:** ~6-8
**Risk:** Medium. Events must survive Netty serialization round-trip.

### Step 6: Retire Redundant Protocol Methods — COMPLETE

**Scope:** Systematically audit and remove per-feature protocol methods that are now redundant because the client processes forwarded events directly. Per TRT's feedback, the goal is to **reduce** net protocol complexity — event forwarding replaces per-method calls, it doesn't layer on top of them.

**Audit approach:** For each candidate protocol method, verify:
1. The corresponding event(s) carry all the data the protocol method sends
2. The client's local visitor handles the event correctly
3. The `NetGuiGame` implementation can become a no-op or be removed

**Finalized retirement table** *(updated per Investigation 3):*

| Protocol Method | Replacing Event(s) | Status | Notes |
|----------------|-------------------|--------|-------|
| `hearSoundEffect` | N/A | **N/A — does not exist** | Sound already event-driven via SoundSystem/EventVisualizer |
| `updateLives` | `GameEventPlayerLivesChanged` | **Remove** | Event carries old/new life. Non-event caller (`setHighlighted`) is local-only. |
| `notifyStackAddition` | `GameEventSpellAbilityCast` | **Remove** | NetGuiGame is already no-op. |
| `notifyStackRemoval` | `GameEventSpellRemovedFromStack` | **Remove** | NetGuiGame is already no-op. |
| `updateManaPool` | `GameEventManaPool` | **Remove** | Event carries player + change type + mana. |
| `updateZones` | Zone-related events (multiple) | **Remove** *(verify in testing)* | Trigger-only signal; data from GameView sync. Non-event callers use separate protocol paths. |
| `updateCards` | Card-state events (multiple) | **Remove** *(verify in testing)* | Trigger-only signal; data from GameView sync. Non-event callers use separate protocol paths. |
| `updatePlayerControl` | `GameEventPlayerControl` | **Remove** | Protocol sends zero data. Event carries full before/after. |

**Key insight (Investigation 3):** All protocol methods follow the pattern `updateGameView()` then `send(trigger)`. They are trigger signals, not data carriers. Events serve as equivalent triggers when the client runs its own `FControlGameEventHandler`. **Prerequisite:** Event forwarding must call `updateGameView()` before `send(forwardGameEvent, ...)` — same pattern.

**IGuiGame method signatures are KEPT** — only the `ProtocolMethod` entries, `NetGuiGame` send overrides, and client-side protocol dispatch entries are removed. The methods are still called locally by client-side event visitors and non-event callers.

**Files changed:** ~5-8 (ProtocolMethod.java, NetGuiGame.java, possibly AbstractGuiGame.java stubs, client-side GameProtocolHandler dispatch)
**Lines removed:** ~40-60 (net removal)
**Risk:** Medium (downgraded from Medium-high per Investigation 3). Removal is clean — the architectural analysis shows protocol methods are triggers, not data sources.

## Implementation Order & Dependency

```
Step 1 (Foundation) ──► Step 2 (Simple Events) ──► Step 3 (Complex Events)
                                                          │
                        Step 4 (Visitor Updates) ◄────────┘
                              │
                        Step 5 (Network Integration)
                              │
                        Step 6 (Remove Replaced Methods)
```

Steps 2 and 3 are independent of each other but both must complete before Step 4. Each step should leave the branch in a compilable state. Commit per step for reviewability.

## Risk Analysis

| Risk | Impact | Likelihood | Mitigation |
|------|--------|------------|------------|
| GameLogFormatter breaks with view types | Medium | Low | **AUDITED (Investigation 2).** 14/19 methods trivial. Key fix: `card.toString()` → `card.getName()` (CardView.toString() includes zone/ID). Two new event fields (`stackDescription`, `targetDescription`) bridge remaining gaps. No engine-only blockers. |
| EventVisualizer produces wrong sounds | Low | Low | Card type checks exist on CardView. Only SVar and mana abilities are missing (handled). |
| Netty serialization fails for view-based events | High | Very Low | **AUDITED (Investigation 6).** Uses Java ObjectOutputStream + LZ4. All view objects already serializable via `TrackableObject`. `GameEvent` needs `extends Serializable` (1 line). Records deserialize via canonical constructor (view-typed) — convenience constructors are safe. `LobbyPlayer` NOT serializable — replaced with `String`. `GameOutcome` NOT serializable — pre-computed fields (Investigation 5). Add serialization unit tests. |
| Collection conversion drops null entries | Medium | Medium | Use null-safe helpers. `GameEntityView.get()` / `CardView.get()` must handle null. |
| Record constructor ambiguity | Low | Very Low | Java resolves overloads by exact parameter types. Card vs CardView, Player vs PlayerView are unambiguous. |
| Performance: `.getView()` called at every event creation | Very Low | N/A | `.getView()` is a field access (O(1)). Events are created ~10-30 times per turn. Negligible. |
| Large PR is hard to review | Medium | Medium | Clear commit-per-step structure. Each commit is mechanical and independently verifiable. Event record changes are repetitive (same pattern 50 times). |
| Master has no integration test infrastructure | Medium | Medium | `testTrueNetworkTraffic` exists on master for basic protocol validation. Manual network testing required for event forwarding. |
| Removing protocol methods breaks client state | High | Low | **AUDITED (Investigation 3).** Protocol methods are trigger signals, not data carriers — actual data from GameView sync. All 7 removals are clean. Prerequisite: event forwarding calls `updateGameView()` first. `updateZones`/`updateCards` non-event callers use separate protocol paths. Verify in testing. |
| Subgame/PlayerControl events lose essential data | Medium | Low | **AUDITED (Investigation 4), AMENDED (TRT feedback).** Subgame events reclassified as `UiEvent` — no longer in GameEvent scope, no serialization or data-loss risk. `PlayerControl`: `GameLogFormatter` only uses `player.getName()` + `newLobbyPlayer.getName()` (no `PlayerController` access). `PlayerController` instanceof check → `boolean newControllerIsHuman` field. |

## Testing Strategy — COMPLETE

### Per-Step Gate — COMPLETE

Every step must pass `mvn -pl forge-gui -am compile` before proceeding. Full CI suite (`mvn -U -B clean test`) must pass after Steps 1, 4, and 6 (the steps that change non-event code or remove protocol methods).

**Result:** Full CI suite passed after all steps: 261 tests, 0 failures, 0 errors, 0 checkstyle violations. Two regressions were found and fixed: (1) `SpellAbilityView` NPE from eager `isSpell`/`isTrigger`/`activatingPlayer` updates during `WrappedAbility` construction — fixed by deferring to lazy `getView()`; (2) `GameEventManaPool` NPE from null mana in `clearPool` — fixed with null guard.

### New Unit Tests — COMPLETE

`GameEventSerializationTest` created in `forge-gui-desktop/src/test/java/forge/game/event/`. 9 tests covering serialization round-trips for view-typed event records.

**1. Serialization round-trip tests** — Verify events survive `ObjectOutputStream` → `ObjectInputStream` with correct field values.

| Test Case | What It Validates |
|-----------|-------------------|
| Simple event (e.g. `GameEventLandPlayed`) | `CardView`/`PlayerView` fields survive serialization. Deserialized fields are `.equals()` to originals. |
| Collection event (e.g. `GameEventAttackersDeclared`) | `Multimap<GameEntityView, CardView>` round-trips correctly. Entry count and contents preserved. |
| Pre-computed fields (e.g. `GameEventGameOutcome`) | `lastTurnNumber`, `outcomeStrings`, `winningPlayerName`, `matchSummary` survive round-trip. These are primitives/strings so low risk, but confirms the convenience constructor populates them correctly. |
| Null-safety (e.g. `GameEventCardChangeZone` with null `from` zone) | Null `ZoneType` fields don't cause `NullPointerException` during serialization or deserialization. |
| `GameEventPlayerControl` | `String` lobby player names (not `LobbyPlayer` objects) serialize. `boolean newControllerIsHuman` correct. |

Each test creates the event using the **convenience constructor** (engine types), serializes, deserializes, and asserts field equality. This validates the full host→client path.

**2. GameLogFormatter output regression tests** — Verify log output matches expected strings after the `toString()` → `getName()` migration.

| Test Case | What It Validates |
|-----------|-------------------|
| `GameEventCardDamaged` | Output uses card name (not `"Battlefield Lightning Bolt (123)"` format from `CardView.toString()`). |
| `GameEventLandPlayed` | Player name + land name formatted correctly. |
| `GameEventSpellAbilityCast` | `targetDescription` pre-computed field appears in log. `si.getText()` used for stack description. |
| `GameEventSpellResolved` | `stackDescription` pre-computed field appears in log. |
| `GameEventMulligan` | Hand size from `PlayerView.getHandSize()` matches expected value. |
| `GameEventAttackersDeclared` | `Lang.joinHomogenous()` produces card names, not `CardView.toString()` output. `GameEntityView.toString()` returns `getName()`. |
| `GameEventBlockersDeclared` | `instanceof CardView` path works. `isBattle()`, `getProtectingPlayer()`, `getController()` accessed correctly. |
| `GameEventPlayerControl` | Log uses `newLobbyPlayerName` string field, not `LobbyPlayer.getName()`. |

These tests construct events with known view objects (using test `CardView`/`PlayerView` instances), pass them through `GameLogFormatter.visit()`, and assert the returned string matches an expected value. This catches `toString()` divergence regressions directly.

**3. EventVisualizer smoke tests** — Verify sound effect selection logic with view-typed events.

| Test Case | What It Validates |
|-----------|-------------------|
| `GameEventSpellResolved` with creature | Returns creature-related `SoundEffectType`. `source.getCurrentState().isCreature()` works. |
| `GameEventSpellResolved` with instant/sorcery | `isInstant()`/`isSorcery()` on `CardStateView` (added in Step 1) returns correct type. |
| `GameEventZone` with land | Land sound path works. Full color-accurate sounds via `origProduceMana*()` tracked properties on `CardView.CardStateView`. |
| `GameEventBlockersDeclared` | `isLobbyPlayer()` comparison works for defending player identity check. |

### Existing Tests

- **CI suite** (`mvn -U -B clean test`): checkstyle (imports), all existing unit tests. Must pass — no regressions.
- **`testTrueNetworkTraffic`**: Validates the basic protocol pipeline still works after Step 5 (new `forwardGameEvent` method added) and Step 6 (protocol methods removed). This test starts a real server/client and exercises the Netty serialization path.

### Manual Network Testing (After Steps 5 and 6)

Host a two-player network game (host + one remote client). The following scenarios must be verified **on the client side** — the host will work regardless since it processes events locally.

**Core gameplay (Step 5 — event forwarding works):**

| Scenario | Client-Side Verification |
|----------|------------------------|
| Cast a creature spell | Client hears spell sound, sees stack animation, game log shows "Player casts Creature" |
| Creature deals combat damage | Client hears damage sound, life totals update, game log shows damage |
| Play a land | Client hears correct color-specific land sound (via `origProduceMana*()` tracked properties), land appears on battlefield |
| Tap a permanent | Client sees tap animation/state change |
| Sacrifice a permanent | Client sees card leave battlefield, game log shows sacrifice |
| Scry / Surveil | Game log shows "Player scries/surveils" |
| Mulligan | Game log shows correct hand size |

**Protocol method retirement (Step 6 — no regressions):**

| Scenario | Verifies Retirement Of |
|----------|----------------------|
| Life total changes (damage, lifegain) are visible on client | `updateLives` |
| Mana pool updates when tapping lands | `updateManaPool` |
| Cards entering/leaving zones appear correctly (cast, destroy, exile, return) | `updateZones` |
| Card state changes visible (counters, tapped/untapped, P/T changes) | `updateCards` |
| Stack additions/removals display correctly | `notifyStackAddition`, `notifyStackRemoval` |
| Mindslaver / control-change effects update client display | `updatePlayerControl` |

**Edge cases (if feasible in manual testing):**

| Scenario | What It Tests |
|----------|---------------|
| Subgame (e.g. Shahrazad or similar effect) | Subgame events are `UiEvent`s (host-only). Verify subgame still works correctly for local play; no network forwarding expected. |
| Game ends (player loses) | `GameEventGameOutcome` pre-computed fields. Client shows correct winner and match summary. |
| Multi-game match (best of 3) | `matchSummary` field in `GameEventGameOutcome` accumulates correctly across games. |

### Test Scope in the PR

| Test Type | Files | Runs In CI? |
|-----------|-------|-------------|
| Serialization round-trip | `GameEventSerializationTest.java` | Yes |
| GameLogFormatter regression | `GameEventSerializationTest.java` (or separate class) | Yes |
| EventVisualizer smoke | `GameEventSerializationTest.java` (or separate class) | Yes |
| `testTrueNetworkTraffic` | Existing | Yes |
| Manual network scenarios | N/A (documented checklist above) | No — requires two game instances |

## Estimated Scope (Single PR)

| Step | Files Changed | Lines Changed (est.) |
|------|---------------|---------------------|
| Step 1: Foundation | ~6 | ~85 *(revised: +SpellAbilityView additions, +GameEntityView.toString, +GameEvent Serializable per Investigation 6)* |
| Step 2: Simple Events | ~27 | ~350 |
| Step 3: Complex Events | ~19 | ~380 *(revised: subgame events removed from scope (UiEvent reclassification), no SubgameEffect extraction. +newControllerIsHuman field per Investigation 4, +GameEventGameOutcome pre-computed fields per Investigation 5)* |
| Step 4: Visitor Updates | ~5 | ~230 *(revised: subgame visitor updates removed. +FControlGameEventHandler PlayerControl updates per Investigation 4)* |
| Step 5: Network Integration | ~6-8 | ~100 |
| Step 6: Retire Protocol Methods | ~5-8 *(revised per Investigation 3)* | ~-50 (net removal) |
| Tests: `GameEventSerializationTest` | 1 | ~200-250 (serialization round-trips, log regression, EventVisualizer smoke) |
| **Total** | **~68-73** | **~1295-1345** |

Note: Step 6 is net negative — removing protocol method overrides and ProtocolMethod entries reduces code. Steps 3/4 were reduced by TRT's feedback to reclassify subgame events as `UiEvent` (removing SubgameEffect lifecycle extraction and subgame visitor updates). Remaining growth from investigation findings: SpellAbilityView additions, GameEvent Serializable, pre-computed event fields, LobbyPlayer → String, GameEventGameOutcome pre-computed fields. Test file adds ~200-250 lines but runs in CI and catches the highest-risk regressions (serialization failures, log format divergence).

## Implementation Notes (Deviations from Plan)

The following significant deviations from the plan occurred during implementation:

### Step 4 absorbed into Steps 2 and 3

The plan had Step 4 (Visitor Updates) as a separate phase after event conversion. In practice, changing event record field types immediately broke all visitors at compile time, so visitor fixes were done as cascading fixes during Steps 2 and 3. Step 4 was marked complete with no additional work.

### Step 5 used `handleGameEvent` instead of `forwardGameEvent`

The plan specified `forwardGameEvent` as the ProtocolMethod name. Implementation used `handleGameEvent` to match the `IGuiGame` method naming convention.

### Step 6 removed 5 protocol methods, not 7

The plan listed 7 removals including `notifyStackAddition` and `notifyStackRemoval`. These two were never `ProtocolMethod` entries — they were only `IGuiGame` interface methods with existing no-op defaults in `NetGuiGame`. Only 5 actual `ProtocolMethod` entries were removed: `updatePlayerControl`, `updateZones`, `updateCards`, `updateManaPool`, `updateLives`. Five no-op defaults were added to `AbstractGuiGame` for the `IGuiGame` methods that `NetGuiGame` previously overrode.

### SpellAbilityView tracked properties required lazy initialization

The plan specified adding `isSpell()`, `isTrigger()`, and `getActivatingPlayer()` as tracked properties on `SpellAbilityView` in Step 1, updated eagerly in the constructor. This caused an NPE during `WrappedAbility` construction: `WrappedAbility` extends `Ability`, whose `super()` call creates a `SpellAbilityView` before `WrappedAbility.sa` is assigned, so `isSpell()` (which delegates to `sa.isSpell()`) fails with NPE. Fix: these properties are deferred to lazy update in `SpellAbility.getView()`, matching the existing pattern for `updateHostCard`/`updateDescription`/`updatePromptIfOnlyPossibleAbility`.

### GameEventManaPool null mana not anticipated

The plan specified replacing `Mana` with `byte manaColor` via `mana.getColor()`. `ManaPool.clearPool()` fires the event with `null` mana (mode `Cleared`), causing an NPE. Fixed with a null guard: `mana != null ? mana.getColor() : (byte) 0`.

### CMatchUI `notifyStackAddition` significantly simplified

The plan described updating visitors to use view types. In practice, `CMatchUI.notifyStackAddition()` and its helper methods deeply accessed engine types (`SpellAbility.getRootAbility()`, `SpellAbility.getPaidList()`, `AbilityKey`, `TargetChoices`, `SpellAbilityStackInstance.getTargetRestrictions()`). Edge case code for sacrificed enchantments and triggering source SA lookup had to be removed as these require engine-level access not available on view types.

### Subgame event reclassification deferred

The plan (amended per TRT feedback) specified reclassifying `GameEventSubgameStart` and `GameEventSubgameEnd` from `GameEvent` to `UiEvent`. This was NOT done due to a module dependency issue not anticipated in the plan: `SubgameEffect` in `forge-game` creates these events, but `UiEvent` is defined in `forge-gui`. `forge-game` cannot depend on `forge-gui`. Options (A: filter in forwarding, B: `LocalGameEvent` marker interface, C: callback/hook pattern) have been documented and sent to TRT for review. The risk is limited to network play with Shahrazad-like effects (extremely rare).

### GameLogFormatter `toString()` → `getName()` resolved by `GameEntityView.toString()`

The plan specified replacing `card.toString()` with `card.getName()` in 8+ places in `GameLogFormatter`. Instead, `GameEntityView.toString()` was added in Step 1 to return `getName()`, matching `GameEntity.toString()` behavior. This made the explicit replacements unnecessary — the existing `toString()` calls produce the same output with view types.

### Serialization test scope reduced

The plan described 3 categories of tests (~200-250 lines): (1) serialization round-trips, (2) GameLogFormatter output regression, (3) EventVisualizer smoke tests. Only category 1 was implemented (9 tests, 183 lines). Categories 2 and 3 were not created because they require more complex test infrastructure (constructing full formatter/visualizer contexts). Events using `ZoneType` fields could not be tested because `ZoneType`'s static initializer requires the `Localizer`, which is unavailable in unit tests without GUI bootstrapping.

### Client-side sound effects required SoundSystem wiring in handleGameEvent

Manual network testing revealed that sound effects were not playing on the remote client. Root cause: `SoundSystem.instance` received `GameEvent`s via a Guava `@Subscribe` subscription on the game's event bus, which only exists on the host. On the client, game events arrive via the `handleGameEvent` protocol method, but `AbstractGuiGame.handleGameEvent()` was a no-op — nothing fed events to `SoundSystem`.

Fix: `AbstractGuiGame.handleGameEvent()` now calls `SoundSystem.instance.receiveEvent(event)`. To avoid double sound playback on the host (where `SoundSystem` was already subscribed to the game event bus), the direct `game.subscribeToEvents(SoundSystem.instance)` subscription was removed from `HostedMatch.startGame()` and the subgame handler. The `match.subscribeToEvents(SoundSystem.instance)` subscription is preserved because it handles `UiEvent` sounds (blocker assignment, etc.) which are host-only interactive events fired on the Match bus, not the Game bus. This also updates the plan's description of `AbstractGuiGame.handleGameEvent()` — it is no longer a no-op on master; it processes game events for sound.

## Guidelines Compliance

- **Minimal diff:** Convenience constructors mean zero changes at 167 creation sites. Diff is concentrated in event definitions (mechanical, repetitive) and visitor updates (5 files). Protocol method retirement in Step 6 is net-negative lines.
- **Search before creating:** Reuses existing `CardView.get()`, `CardView.getCollection()`, `PlayerView.get()` static helpers. Collection conversion helpers are private statics inside each event record — no new utility class.
- **Avoid over-engineering:** No new abstract framework. Records stay records. Visitor interface unchanged. `IGuiGame` gets one new method (`handleGameEvent`) in Step 5 — justified because it represents a new capability (event dispatch) not reachable through existing object graphs.
- **Prefer forwarding game events:** This IS the implementation of that guideline.
- **Trace changes across execution contexts:** Events are created on the host (forge-game), consumed by visitors on both host and client (forge-gui), and forwarded via `NetGuiGame` (forge-gui). Convenience constructors ensure host-side creation is unchanged. Client receives view-based events that are directly usable.
- **Don't expand interfaces for trivial access:** `IGameEventVisitor` is unchanged. The single new `IGuiGame.handleGameEvent()` is a genuine new capability.
- **Isolate network code:** Event forwarding dispatch stays in `NetGuiGame`/`HostedMatch`. Events themselves are module-agnostic (forge-game). Client-side handling is in `AbstractGuiGame`.
- **Fix bugs at the closest layer:** View conversion happens at the event layer (closest to the serialization problem).
- **Platform-neutral:** `handleGameEvent()` default lives in `AbstractGuiGame` (shared). Platform-specific dispatch (if any) overrides in `CMatchUI`/`MatchController`.

## Delta Sync Integration (NetworkPlay/main)

This section outlines what needs to be addressed when this refactor (targeting master) is later integrated with the delta sync protocol on `NetworkPlay/main`. The goal is to ensure the event refactor is designed in a way that doesn't create unnecessary merge conflicts or architectural incompatibilities.

### Architectural Difference: Inheritance Hierarchy

On master, the GUI hierarchy is:
```
IGuiGame → AbstractGuiGame → CMatchUI / NetGuiGame / MatchController
```

On `NetworkPlay/main`, there's an intermediate class:
```
IGuiGame → AbstractGuiGame → NetworkGuiGame → CMatchUI / NetGuiGame / MatchController
```

`NetworkGuiGame` contains delta packet deserialization, tracker state management, and full-state sync. It keeps `AbstractGuiGame` free of network dependencies.

**Impact on Step 5 (Network Integration):**

This refactor adds `handleGameEvent(GameEvent)` to `IGuiGame` with a default in `AbstractGuiGame`, and the forwarding implementation in `NetGuiGame`. On `NetworkPlay/main`:

| Item | This Refactor (master) | Delta Sync Integration |
|------|----------------------|----------------------|
| `IGuiGame.handleGameEvent()` | New method | Carries over unchanged |
| `AbstractGuiGame.handleGameEvent()` | Default no-op | Stays as no-op — no network deps in base class |
| `NetGuiGame.handleGameEvent()` | Sends `forwardGameEvent` via `GameProtocolSender` | Same — `NetGuiGame` still extends `NetworkGuiGame` which extends `AbstractGuiGame` |
| Client-side event dispatch | In `AbstractGuiGame` (receives forwarded event, runs local visitors) | **Move to `NetworkGuiGame`** — event dispatch involves tracker-aware deserialization context and should live in the network layer per guidelines |

**Action when merging:** Move the client-side `handleGameEvent()` implementation from `AbstractGuiGame` to `NetworkGuiGame`. `AbstractGuiGame` keeps the no-op default. This follows the guideline: "Network-specific functionality should be in dedicated classes (`NetworkGuiGame`) rather than added to core classes like `AbstractGuiGame`."

### Event Forwarding vs Delta Sync: Parallel Channels

Delta sync and event forwarding serve different purposes:

- **Delta sync** = continuous state synchronization (what the game *looks like* right now). Operates on `TrackableObject` property deltas — `CardView`, `PlayerView`, `GameView` properties.
- **Event forwarding** = discrete reactions (what *happened* — sounds, log entries, UI animations). Operates on `GameEvent` records carrying view-typed fields.

These are complementary, not competing. After integration:

```
Server fires GameEventPlayerLivesChanged:
  ├─ Player.setLife(10) updates TrackableObject props
  │   └─ DeltaSyncManager captures delta: {PlayerView[5]: {life: 10}}
  │       └─ DeltaPacket sent to client (state channel)
  └─ Event forwarded via handleGameEvent() (reaction channel)
      └─ Client's local FControlGameEventHandler plays sound, updates display
```

**No conflict** — delta packets carry state, forwarded events carry reactions. The client needs both.

### Ordering Guarantee

Delta packets and forwarded events must arrive in a consistent order. If a delta packet setting life to 10 arrives *after* the event saying "life changed from 20 to 10," the client's visitor might try to read stale PlayerView data.

**Current master approach (Step 5):** `NetGuiGame.handleGameEvent()` calls `updateGameView()` before `send(forwardGameEvent, event)` — same pattern all existing protocol methods use. This ensures the client has current `GameView` state before processing the event.

**Delta sync approach:** Replace the `updateGameView()` call with `DeltaSyncManager.flushPendingDelta()` (or equivalent). The delta packet and the forwarded event should be sent in the same flush, ensuring the client applies state changes before processing the reaction event.

**Action when merging:** In `NetGuiGame.handleGameEvent()`, replace `updateGameView()` with the delta sync equivalent. The delta sync manager already handles batching state changes — the event forward just needs to be sequenced after the delta flush. This may mean:
1. `DeltaSyncManager` accumulates property changes from the game event's side effects
2. `NetGuiGame.handleGameEvent()` triggers a delta flush
3. Immediately after, the event itself is sent
4. Client applies delta first (properties updated), then processes the event (visitors see current state)

### Protocol Method Retirement (Step 6) — Already Aligned

Step 6 retires 7 `ProtocolMethod` entries that are trigger signals (`updateLives`, `updateManaPool`, `updateZones`, `updateCards`, `updatePlayerControl`, `notifyStackAddition`, `notifyStackRemoval`). These are redundant because:
- On master: forwarded events serve as the same triggers, with `updateGameView()` providing data.
- On `NetworkPlay/main`: delta sync already provides the data. The trigger signals are *even more* redundant because delta packets arrive continuously. The protocol methods were already identified as technical debt on the delta sync branch.

**No conflict.** Retiring these methods on master simplifies the later merge — fewer protocol methods to maintain in both codelines.

### New `TrackableProperty` Entries (Step 1)

Step 1 adds tracked properties to `SpellAbilityView`: `isSpell`, `isTrigger`, `getActivatingPlayer`. These use `TrackableProperty` with appropriate `TrackableType`s.

**Action when merging:** Verify that the new `TrackableProperty` entries use the correct `TrackableType` for delta serialization:
- `isSpell` → `BooleanType` (or equivalent)
- `isTrigger` → `BooleanType`
- `getActivatingPlayer` → `PlayerViewType` (or the type used for view references)

If the `TrackableType` is wrong or missing, delta sync won't pick up changes to these properties, causing full-state fallbacks. This is per the guideline: "Register them in `TrackableProperty` with the correct `TrackableType` so delta tracking picks them up."

### `GameEvent extends Serializable` (Step 1)

Adding `Serializable` to the `GameEvent` interface is safe for delta sync. Delta packets don't contain `GameEvent` objects — they contain property deltas for `TrackableObject`s. Events are forwarded via `ProtocolMethod.forwardGameEvent` as `GuiGameEvent` payloads (the existing `CompatibleObjectEncoder` pipeline). No interaction with delta packet serialization.

### Subgame Lifecycle Wiring — N/A *(per TRT feedback)*

Subgame events (`GameEventSubgameStart`, `GameEventSubgameEnd`) are reclassified as `UiEvent` and remain host-local. No `SubgameEffect` extraction is performed, so there is no merge conflict with delta sync's subgame handling. The existing `MatchUiEventVisitor` handlers in `HostedMatch` are unchanged.

### Pre-Computed Event Fields and Client-Side Log Generation

Several events gain pre-computed string fields (`stackDescription`, `targetDescription`, `matchSummary`) because the data isn't available on view objects. (`dayTime` was previously listed here for `GameEventSubgameEnd`, but that event is now reclassified as `UiEvent` — see TRT feedback.) On `NetworkPlay/main`, clients run their own `GameLogFormatter` and `EventVisualizer`. These pre-computed fields are essential for correct client-side visitor operation — they carry data that only the host can compute (from engine objects).

**No conflict.** The delta sync branch wants clients to process events locally — that's the entire motivation. Pre-computed fields enable this.

### Summary: Integration Checklist

When merging this refactor into `NetworkPlay/main`:

| # | Task | Effort | Risk |
|---|------|--------|------|
| 1 | Move client-side `handleGameEvent()` from `AbstractGuiGame` to `NetworkGuiGame` | Low (~10 lines moved) | Low |
| 2 | Replace `updateGameView()` in event forwarding with delta flush | Low (~5 lines) | Medium — ordering must be tested |
| 3 | Verify new `TrackableProperty` entries have correct `TrackableType`s | Trivial (review) | Low |
| ~~4~~ | ~~Check subgame lifecycle wiring for `DeltaSyncManager` registration conflicts~~ | ~~N/A — subgame events reclassified as `UiEvent`, no SubgameEffect changes~~ | ~~N/A~~ |
| 5 | Run `NetworkPlayIntegrationTest` (quick 10-game) to validate end-to-end | Trivial (test run) | N/A |
| 6 | Verify retired protocol methods don't have delta-sync-specific callers on main branch | Low (grep + review) | Low |

**Overall integration risk: Low.** The event refactor and delta sync are complementary — events carry reactions, deltas carry state. The main merge work is moving ~10 lines to the right class in the hierarchy and adjusting the flush mechanism. The retired protocol methods are already technical debt on the delta sync branch. Subgame lifecycle wiring is no longer a concern (events reclassified as `UiEvent`).

## Open Questions

1. **~~Does `PlayerView` expose `getLobbyPlayer()`?~~** **RESOLVED (Investigation 1).** No, but `isLobbyPlayer(LobbyPlayer)` and `getLobbyPlayerName()` exist. No new method needed — see Investigation 1 results.

2. **~~Does `SpellAbilityView` expose `getHostCard()` returning `CardView`?~~** **RESOLVED (Investigation 1).** Yes. Present and working.

3. **~~Does `SpellAbilityView` expose `isSpell()`?~~** **RESOLVED (Investigation 1).** No. Must be added in Step 1. `isTrigger()` also missing and needed by GameLogFormatter — add both. See Investigation 1 results.

4. **~~GameLogFormatter depth:~~** **RESOLVED (Investigation 2).** Risk downgraded from Medium-high to Low-Medium. 14 of 19 visit methods need only trivial `toString()` → `getName()` fixes. 4 methods have direct view replacements. 1 method (`visit(GameEventSpellAbilityCast)`) needs pre-computed target description string. No fundamentally engine-only access that blocks the refactor. Two new event fields needed: `stackDescription` on `GameEventSpellResolved`, `targetDescription` on `GameEventSpellAbilityCast`. Key cross-cutting concern: `CardView.toString()` ≠ `Card.toString()` — must use `.getName()` throughout. See Investigation 2 results for full per-method breakdown.

5. **~~GameOutcome serializability:~~** **RESOLVED (Investigation 5).** `GameOutcome` does NOT implement `Serializable` and is deeply non-serializable due to `RegisteredPlayer` → `Deck` dependency chain. Making it serializable is infeasible. **Solution:** Pre-compute all visitor-needed data as serializable fields in the event record: `int lastTurnNumber`, `List<String> outcomeStrings`, `String winningPlayerName`, `String matchSummary`. Convenience constructor extracts these from `GameOutcome`/`Collection<GameOutcome>`. No changes needed to `GameOutcome` itself. See Investigation 5 for full consumer analysis.

6. **Maintainer coordination:** The guidelines state "Do not refactor existing events without coordinating with maintainers." This plan should be reviewed and approved by tool4ever/TRT before implementation begins.

7. **~~Protocol method audit depth:~~** **RESOLVED (Investigation 3).** All 8 candidate protocol methods audited. Key finding: protocol methods are **trigger signals**, not data carriers — actual UI data comes from `updateGameView()` (GameView sync). All 7 existing methods (hearSoundEffect doesn't exist) can be removed. Events serve as equivalent triggers when processed by the client's local `FControlGameEventHandler`. Prerequisite: event forwarding must call `updateGameView()` before sending. `updateZones` and `updateCards` have non-event callers but those use separate protocol paths. See Investigation 3 for full per-method audit.

8. **~~`GameEventSubgameStart/End` — what data does the client need?~~** **RESOLVED (Investigation 4).** `GameLogFormatter` has NO handlers for either event. `MatchUiEventVisitor` uses `Game` extensively for lifecycle wiring (event subscriptions, GUI view switching, player iteration). This lifecycle wiring is fundamentally host-only — it cannot be serialized. **Solution:** Move lifecycle wiring from event handlers into `SubgameEffect` directly. Events shrink to serializable-only fields: `GameEventSubgameStart(String message)`, `GameEventSubgameEnd(String message, String dayTime)`. `FControlGameEventHandler.visit(GameEventSubgameEnd)` uses `Game` for zone refresh (replace with broadcast) and daytime check (replace with new `dayTime` field). See Investigation 4 for full per-consumer audit.

9. **~~`GameEventPlayerControl` — what data does `GameLogFormatter` read?~~** **RESOLVED (Investigations 4, 6).** `GameLogFormatter` reads only `event.player().getName()` (→ `PlayerView.getName()`, identical) and `event.newLobbyPlayer().getName()` (→ `event.newLobbyPlayerName()`, `String`). `LobbyPlayer` is NOT serializable (Investigation 6) — replaced with `String` names. **No `PlayerController` access at all.** `FControlGameEventHandler` uses `PlayerController` for `instanceof PlayerControllerHuman` check and `setGameController` wiring — solved with `boolean newControllerIsHuman` field and handler's existing `humanController` reference. See Investigations 4 and 6.

## Pre-Implementation Investigation

All 6 investigations are complete. Findings have been folded into the plan sections above.

### Investigation 1: View Class API Audit — COMPLETE

**Resolves:** Open Questions 1, 2, 3

#### Results

##### PlayerView (`forge-game/.../player/PlayerView.java`)

**Q: Does it have `getLobbyPlayer()`?** No. But it has:
- `getLobbyPlayerName()` → `String` (line 61)
- `isLobbyPlayer(LobbyPlayer p)` → `boolean` (line 67)
- Static `get(Player)` → `PlayerView` (line 33)
- Static `getCollection(Iterable<Player>)` → `TrackableCollection<PlayerView>` (line 37)

**Visitor usage of `getLobbyPlayer()`:**
- `EventVisualizer.visit(GameEventBlockersDeclared)` (line 98): `Objects.equals(event.defendingPlayer().getLobbyPlayer(), player)` — compares against a `LobbyPlayer` field. **Fix:** Rewrite to `event.defendingPlayer().isLobbyPlayer(player)`. The `isLobbyPlayer()` method on `PlayerView` already does the same comparison. No new method needed.
- `EventVisualizer.visit(GameEventGameOutcome)` (line 115): `event.result().getWinningLobbyPlayer()` — this is on `GameOutcome`, not on `Player`. The `GameOutcome` object is not being refactored to use views, so this is unaffected.

**Other Player methods called by visitors (preview for Investigations 2/4):**
- `Player.getTokensInPlay()` / `getCreaturesInPlay()` — used by FControlGameEventHandler to decide whether to refresh battlefield. NOT on PlayerView. PlayerView has `getBattlefield()` returning `FCollectionView<CardView>` — visitor code can filter that or use a simpler heuristic (e.g., always refresh).
- `Player.getGame()` — used by FControlGameEventHandler for `isGameOver()` check. Engine-only. Will need alternative (e.g., pass `isGameOver` as event field, or check via `GameView`).
- `Player.getZone(ZoneType.Hand).size()` — used by GameLogFormatter for mulligan hand size. PlayerView has `getHandSize()` and `getZoneSize(ZoneType)` — direct replacement available.
- `Player.getName()` / `toString()` — both available on PlayerView.
- `Player.getController()` / `getRegisteredPlayer()` / `getView()` — used by MatchUiEventVisitor for subgame start/end. These are engine-only (Investigation 4 scope).

**Resolution:** No new method needed on `PlayerView`. Existing `isLobbyPlayer()` covers the only gap.

##### SpellAbilityView (`forge-game/.../spellability/SpellAbilityView.java`)

**Q: Does it have `getHostCard()` → `CardView`?** Yes (line 43). Returns tracked `CardView`.

**Q: Does it have `isSpell()`?** No.

**Other methods available:**
- Static `get(SpellAbility)` → `SpellAbilityView` (line 16)
- Static `getMap(Iterable<SpellAbility>)` (line 20)
- `getDescription()` → `String` (line 39)
- `canPlay()` → `boolean` (line 47)
- `promptIfOnlyPossibleAbility()` → `boolean` (line 55)
- `getCardView()` → delegates to `getHostCard()` (IHasCardView)

**Missing methods needed by visitors:**
- `isSpell()` — needed by EventVisualizer (line 129) and GameLogFormatter (line 80). **Must add in Step 1.** Simple tracked boolean property.
- `isTrigger()` — needed by GameLogFormatter (line 81). **Must add in Step 1.** Simple tracked boolean property.
- `getActivatingPlayer()` — needed by GameLogFormatter (line 79). NOT on SpellAbilityView. **Should add in Step 1** as a tracked `PlayerView` property. Alternative: GameLogFormatter could use a different event field or the log could be simplified, but this is a commonly needed accessor.
- `getTargetRestrictions()` / `getAllTargetChoices()` — needed by GameLogFormatter (lines 86-95) for target logging. NOT on SpellAbilityView. These return complex engine objects (`TargetRestrictions`, `TargetChoices`). **Investigation 2 must determine** whether the log output can be simplified to use only data already on the view (e.g., pre-formatted target string), or whether view equivalents are needed.

**Resolution:** Must add to `SpellAbilityView` in Step 1: `isSpell()`, `isTrigger()`, `getActivatingPlayer()`. Target-related methods deferred to Investigation 2 findings.

##### CardView.CardStateView (`forge-game/.../card/CardView.java`, inner class)

**Q: Are `isInstant()` / `isSorcery()` absent?** Confirmed absent from `CardStateView`.

**Present type-check methods on CardStateView** (all delegate to `getType()`):
- `isCreature()` (line 1801), `isLand()` (1804), `isPlaneswalker()` (1813), `isArtifact()` (1838), `isEnchantment()` (1841)

**`getType()`** returns `CardTypeView` (interface in forge-core), which DOES have:
- `isInstant()` (line 43), `isSorcery()` (line 44), and all other type checks

**Resolution:** Add to `CardStateView` in Step 1:
```java
public boolean isInstant() { return getType().isInstant(); }
public boolean isSorcery() { return getType().isSorcery(); }
```
Follows the exact pattern of the 5 existing type-check delegate methods. Trivial, zero risk.

##### GameEntityView (`forge-game/.../GameEntityView.java`)

Confirmed available:
- Static `get(GameEntity)` → `GameEntityView` (line 14)
- Static `getEntityCollection(Iterable<? extends GameEntity>)` → `TrackableCollection<GameEntityView>` (line 18)
- `getName()` → `String` (line 39)

No gaps. Collection conversion for combat events is supported.

#### Step 1 Updates (Concrete Additions)

Based on this investigation, Step 1 must include:

| Class | Method to Add | Complexity |
|-------|---------------|------------|
| `CardView.CardStateView` | `isInstant()` → delegates to `getType().isInstant()` | Trivial (2 lines) |
| `CardView.CardStateView` | `isSorcery()` → delegates to `getType().isSorcery()` | Trivial (2 lines) |
| `SpellAbilityView` | `isSpell()` → tracked boolean property | Low (~10 lines) |
| `SpellAbilityView` | `isTrigger()` → tracked boolean property | Low (~10 lines) |
| `SpellAbilityView` | `getActivatingPlayer()` → tracked `PlayerView` property | Low (~10 lines) |
| `GameEvent` interface | `extends Serializable` — enables network serialization of all event records | Trivial (1 line + import) |

**Not needed (previously uncertain):**
- `PlayerView.getLobbyPlayer()` — existing `isLobbyPlayer()` suffices
- `PlayerView.getCollection()` — already exists
- `GameEntityView.getEntityCollection()` — already exists

**Deferred to Investigation 2:**
- Whether `SpellAbilityView` needs target-related methods, or whether GameLogFormatter can use pre-formatted strings

### Investigation 2: GameLogFormatter Full Audit — COMPLETE

**Resolves:** Open Question 4

#### Critical Cross-Cutting Finding: `toString()` Divergence

`Card.toString()` (inherited from `GameEntity`) returns just `name`. `CardView.toString()` returns `"Battlefield Lightning Bolt (123)"` (zone + translated name + ID). This means **all** GameLogFormatter methods that call `.toString()` on a Card-typed event field will produce different log output after the refactor if called on `CardView.toString()`.

**Fix:** Use `cardView.getName()` instead of `cardView.toString()` wherever the code previously called `card.toString()`. `CardView.getName()` (from `GameEntityView`) returns the tracked name, identical to `Card.getName()` / `Card.toString()`.

`PlayerView.toString()` returns `getName()`, same as `Player.toString()` — **no divergence** for players.

This fix applies to: `visit(GameEventCardDamaged)`, `visit(GameEventLandPlayed)`, `visit(GameEventPlayerDamaged)`, `visit(GameEventPlayerPoisoned)`, `visit(GameEventPlayerRadiation)`, and any method using `Lang.joinHomogenous()` on card collections (which calls `toString()` on each element — must switch to a name-based joiner or use `getName()` explicitly).

#### Per-Method Audit

##### Category (a) — Available on views, zero or trivial changes (14 methods)

| Method | Engine Accesses | View Equivalent | Notes |
|--------|----------------|-----------------|-------|
| `visit(GameEventGameOutcome)` | `ev.result()` → GameOutcome methods | GameOutcome is NOT refactored | No changes needed |
| `visit(GameEventScry)` | `ev.player().toString()` | `PlayerView.toString()` ≡ | Identical output |
| `visit(GameEventSurveil)` | `ev.player().toString()` | `PlayerView.toString()` ≡ | Identical output |
| `visit(GameEventCardModeChosen)` | `ev.player().toString()`, primitives | `PlayerView.toString()` ≡ | Identical output |
| `visit(GameEventRandomLog)` | `ev.message()` → String | N/A | No engine objects |
| `visit(GameEventTurnPhase)` | `ev.playerTurn().getName()` | `PlayerView.getName()` ≡ | Identical output |
| `visit(GameEventTurnBegan)` | `event.turnOwner().toString()` | `PlayerView.toString()` ≡ | Identical output |
| `visit(GameEventCardDamaged)` | `event.source().toString()`, `event.card().toString()` | Use `.getName()` instead | toString() divergence fix |
| `visit(GameEventLandPlayed)` | `ev.player().toString()`, `ev.land().toString()` | Player ≡; card use `.getName()` | toString() divergence fix |
| `visit(GameEventPlayerDamaged)` | `ev.source().toString()`, `ev.target().toString()` | Card use `.getName()`; Player ≡ | toString() divergence fix |
| `visit(GameEventPlayerPoisoned)` | `ev.receiver().toString()`, `ev.source().toString()` | Player ≡; card use `.getName()` | toString() divergence fix |
| `visit(GameEventPlayerRadiation)` | `ev.receiver().toString()`, `ev.source().toString()` | Player ≡; card use `.getName()` | toString() divergence fix |
| `visit(GameEventCardForetold)` | `ev.toString()` (event record) | Record toString() auto-generated | Fields change to views; output changes slightly but acceptable |
| `visit(GameEventCardPlotted)` | `ev.toString()` (event record) | Same as above | Acceptable |
| `visit(GameEventDoorChanged)` | `ev.toString()` (event record) | Same as above | Acceptable |

##### Category (b) — Missing but has direct replacement (4 methods)

**`visit(GameEventSpellResolved)`** (line 72-74):
- `ev.spell().getStackDescription()` — NOT on `SpellAbilityView`. `SpellAbilityView.getDescription()` is populated from `sa.toUnsuppressedString()`, which is **different** from `getStackDescription()` (which applies CARDNAME substitution and card display name formatting).
- `ev.spell().getHostCard().toString()` → use `CardView.getName()`.
- **Fix:** Add a `String stackDescription` field to the refactored `GameEventSpellResolved` record, populated from `spell.getStackDescription()` in the convenience constructor. This avoids adding a complex computed property to `SpellAbilityView` and keeps the data local to the event that needs it.

**`visit(GameEventMulligan)`** (line 300-302):
- `ev.player().getZone(ZoneType.Hand).size()` — NOT on PlayerView as `getZone()`.
- **Fix:** `PlayerView.getHandSize()` or `PlayerView.getZoneSize(ZoneType.Hand)` — direct replacement. Identical result.

**`visit(GameEventBlockersDeclared)`** (line 257-297):
- `defender instanceof Card c` → must become `defender instanceof CardView cv` after refactor.
- `c.isBattle()` → `cv.getCurrentState().isBattle()` — **confirmed present** on `CardStateView`.
- `c.getProtectingPlayer().getName()` → `cv.getProtectingPlayer().getName()` — **confirmed present** on `CardView`, returns `PlayerView`.
- `c.getController().getName()` → `cv.getController().getName()` — **confirmed present** on `CardView`, returns `PlayerView`.
- `Lang.joinHomogenous(blockers)` / `att.getKey()` in string interpolation — calls `toString()` on `CardView` objects. **Fix:** Need to use `getName()` or a name-based joiner to match original log format.
- `defender.getName()` — available on `GameEntityView`. ≡.

**`visit(GameEventPlayerControl)`** (line 154-167):
- `event.player()` → Player → after refactor: PlayerView. `p.getName()` ≡.
- `event.newLobbyPlayer()` → `LobbyPlayer` — NOT serializable (Investigation 6), replaced with `String newLobbyPlayerName`. `.getName()` equivalent: direct string access.
- No issues. (Originally classified as (a), but noting here because the event record changes from `Player` to `PlayerView`, and `LobbyPlayer` to `String`.)

**`visit(GameEventAttackersDeclared)`** (line 237-254):
- `ev.attackersMap()` → `Multimap<GameEntity, Card>` → becomes `Multimap<GameEntityView, CardView>`.
- `ev.player()` — Player → PlayerView. Used in string interpolation (implicit `.toString()`). PlayerView ≡.
- `Lang.joinHomogenous(attackers)` — calls `toString()` on CardView elements. **Fix:** Need name-based joiner.
- `k` (GameEntity → GameEntityView) in string interpolation — `GameEntityView.toString()` needs checking. `GameEntityView` does NOT override `toString()` (inherits from `TrackableObject`). **This is a gap** — `GameEntity.toString()` returns `name`, but `GameEntityView` may return `TrackableObject.toString()`. Need to verify or add.

##### Category (c) — Engine-only, needs workaround (1 method)

**`visit(GameEventSpellAbilityCast)`** (line 78-103):

| Access | On View? | Fix |
|--------|----------|-----|
| `event.sa().getActivatingPlayer().getName()` | YES (Step 1 adds `getActivatingPlayer()` to SAView) | → `event.sa().getActivatingPlayer().getName()` unchanged |
| `event.sa().isSpell()` | YES (Step 1) | Unchanged |
| `event.sa().isTrigger()` | YES (Step 1) | Unchanged |
| `event.sa().getHostCard().toString()` | YES but toString divergence | → `.getHostCard().getName()` |
| `event.si().getStackDescription()` | **NO** on SpellAbilityStackInstance, but `StackItemView.getText()` stores this exact value | → `event.si().getText()` |
| `event.sa().getTargetRestrictions()` | **NO** — engine-only, used only as null check | → `event.si().getTargetCards() != null && !event.si().getTargetCards().isEmpty()` or `event.si().getTargetPlayers() != null && !event.si().getTargetPlayers().isEmpty()` |
| `event.sa().getAllTargetChoices()` → `TargetChoices.toString()` | **NO** — engine-only | → Build target string from `event.si().getTargetCards()` (CardView.getName() each) + `event.si().getTargetPlayers()` (PlayerView.getName() each). Or pre-compute target string in convenience constructor. |

**Recommended fix for target logging:** Pre-compute a `String targetDescription` in the convenience constructor:
```java
public GameEventSpellAbilityCast(SpellAbility sa, SpellAbilityStackInstance si, int stackIndex) {
    this(SpellAbilityView.get(sa), StackItemView.get(si), stackIndex,
         computeTargetDescription(sa));
}
private static String computeTargetDescription(SpellAbility sa) {
    if (sa.getTargetRestrictions() == null) return null;
    StringBuilder sb = new StringBuilder();
    for (TargetChoices ch : sa.getAllTargetChoices()) {
        if (ch != null) sb.append(ch);
    }
    return sb.toString();
}
```
This keeps the engine-type access inside the convenience constructor (which runs on the host side), producing a pre-formatted string that the view-based visitor can use directly.

#### Summary

| Category | Count | Risk |
|----------|-------|------|
| (a) Available on views | 14 methods | Zero — mechanical `.toString()` → `.getName()` fixes |
| (b) Missing but replaceable | 4 methods | Low — direct alternatives exist |
| (c) Engine-only, needs workaround | 1 method | Low-Medium — pre-computed string is clean |

**Revised risk rating: LOW-MEDIUM** (downgraded from "Medium-high"). The audit found no methods that require fundamentally engine-only data that cannot be pre-computed or accessed through existing view APIs. The main risks are:
1. `toString()` divergence — mechanical fix but must be applied consistently across all card usages.
2. `getStackDescription()` gap in `GameEventSpellResolved` — solved by adding a string field to the event record.
3. Target description in `GameEventSpellAbilityCast` — solved by pre-computing in the convenience constructor.
4. `GameEntityView.toString()` — may not match `GameEntity.toString()`. Needs verification or override.
5. `Lang.joinHomogenous()` with CardView collections — may need a name-based variant.

#### Items to Add to Step 1 (Foundation)

| Class | Addition | Reason |
|-------|----------|--------|
| `GameEntityView` | Verify or add `toString()` → `return getName()` | Must match `GameEntity.toString()` for log compatibility |

#### Items to Add to Step 3 (Complex Events)

| Event | Addition | Reason |
|-------|----------|--------|
| `GameEventSpellResolved` | New field: `String stackDescription` | `SpellAbilityView` lacks `getStackDescription()` |
| `GameEventSpellAbilityCast` | New field: `String targetDescription` | Engine-only target introspection not available on views |

#### Items for Step 4 (Visitor Updates) — GameLogFormatter Checklist

1. Replace `card.toString()` → `card.getName()` in all visit methods accessing Card-typed event fields (8+ occurrences)
2. `visit(GameEventSpellResolved)`: use new `ev.stackDescription()` field instead of `ev.spell().getStackDescription()`
3. `visit(GameEventSpellAbilityCast)`: use `ev.si().getText()` instead of `ev.si().getStackDescription()`; use new `ev.targetDescription()` field instead of introspecting `getTargetRestrictions()` / `getAllTargetChoices()`
4. `visit(GameEventMulligan)`: use `ev.player().getHandSize()` instead of `ev.player().getZone(ZoneType.Hand).size()`
5. `visit(GameEventBlockersDeclared)`: change `instanceof Card` to `instanceof CardView`; use `getCurrentState().isBattle()`, `getProtectingPlayer()`, `getController()` on CardView
6. `visit(GameEventAttackersDeclared)` and `visit(GameEventBlockersDeclared)`: update `Lang.joinHomogenous()` calls to use card names instead of `toString()`, or verify `GameEntityView.toString()` matches `GameEntity.toString()`
7. `visit(GameEventPlayerControl)`: `event.player()` now returns `PlayerView` — `getName()` available, no functional change

**Deferred from Investigation 1, now resolved:** `SpellAbilityView` does NOT need target-related methods (`getTargetRestrictions`, `getAllTargetChoices`). Pre-computing the target description string in the event's convenience constructor is the cleanest solution — it avoids adding complex engine-only types to the view layer.

### Investigation 3: Protocol Method Data Audit — COMPLETE

**Resolves:** Open Question 7

#### Key Architectural Insight

All candidate protocol methods follow the same pattern in `NetGuiGame`:
```java
@Override
public void updateXxx(args) {
    updateGameView();              // sync full GameView state to client
    send(ProtocolMethod.updateXxx, args);  // send trigger signal
}
```

The protocol methods don't carry the actual UI data — they are **trigger signals** that tell the client "something changed, refresh your display." The actual data comes from `updateGameView()` (which sends the full `GameView` state via the `TrackableObject` delta system). This is the critical observation: **events can replace these triggers** because the client's local `FControlGameEventHandler` processing a forwarded event produces the same UI update calls as the host-side handler would.

For this to work, the event forwarding path must call `updateGameView()` before forwarding the event:
```java
// In HostedMatch or NetGuiGame:
gui.handleGameEvent(evt);  // internally: updateGameView() then send(forwardGameEvent, evt)
```

Each player has their own `FControlGameEventHandler` instance (constructed from `PlayerControllerHuman`, referencing that player's `IGuiGame`). Currently the host runs one handler per human player — for local players it calls `CMatchUI.updateXxx()` directly, for remote players it calls `NetGuiGame.updateXxx()` which sends over the wire. With event forwarding, the remote player's handler runs on the CLIENT side instead, producing the same `CMatchUI.updateXxx()` calls locally.

#### Per-Method Audit

##### 1. `hearSoundEffect` → **DOES NOT EXIST**

Not found in `IGuiGame`, `NetGuiGame`, or `ProtocolMethod`. Sound effects are already handled via the event-driven architecture: `SoundSystem` subscribes to `GameEvent`s via `@Subscribe`, processes them through `EventVisualizer`, and plays sounds locally. No protocol method exists or is needed.

**Action:** Remove from Step 6 table (nothing to retire).

##### 2. `updateLives` → **REMOVE**

| Aspect | Details |
|--------|---------|
| **IGuiGame signature** | `void updateLives(Iterable<PlayerView> livesUpdate)` |
| **NetGuiGame sends** | `updateGameView()` then `send(ProtocolMethod.updateLives, Iterable<PlayerView>)` |
| **Data sent** | Which players changed (PlayerView references). No life values — client reads from synced GameView. |
| **Corresponding event** | `GameEventPlayerLivesChanged(Player player, int oldLives, int newLives)` |
| **Event data** | Player + old/new life values — **strictly more** than the protocol method sends. |
| **Non-event callers** | `AbstractGuiGame.setHighlighted()` — calls `updateLives` when highlighting a player during targeting. This is a local-only UI refresh (runs on the GUI instance directly, never through NetGuiGame for the local player). |

**Verdict:** Remove `ProtocolMethod.updateLives` and `NetGuiGame.updateLives()` override. Keep `IGuiGame.updateLives()` signature (still called locally by event visitor and `setHighlighted()`). Client's local `FControlGameEventHandler` processes forwarded `GameEventPlayerLivesChanged` and produces the same `updateLives()` call on its local `CMatchUI`.

##### 3. `notifyStackAddition` → **REMOVE (already no-op)**

| Aspect | Details |
|--------|---------|
| **IGuiGame signature** | `void notifyStackAddition(GameEventSpellAbilityCast event)` |
| **NetGuiGame sends** | **Nothing** — inherits empty implementation from `AbstractGuiGame` |
| **Data sent** | N/A — no protocol traffic |
| **Corresponding event** | `GameEventSpellAbilityCast(SpellAbility sa, SpellAbilityStackInstance si, int stackIndex)` |
| **Callers** | `FControlGameEventHandler` only; desktop-only (skipped on mobile) |

**Verdict:** Remove. `NetGuiGame` is already a no-op. The entire method is just an extra indirection layer that `FControlGameEventHandler` could bypass. Event forwarding makes this method completely redundant — the client's visitor processes `GameEventSpellAbilityCast` directly. Note: the IGuiGame method signature is unusual in that it accepts a `GameEvent` directly — after the refactor this becomes `GameEventSpellAbilityCast` with view-typed fields, which is exactly what the forwarded event provides.

##### 4. `notifyStackRemoval` → **REMOVE (already no-op)**

| Aspect | Details |
|--------|---------|
| **IGuiGame signature** | `void notifyStackRemoval(GameEventSpellRemovedFromStack event)` |
| **NetGuiGame sends** | **Nothing** — inherits empty implementation from `AbstractGuiGame` |
| **CMatchUI impl** | Only decrements `nextNotifiableStackIndex` counter |
| **Corresponding event** | `GameEventSpellRemovedFromStack(SpellAbility sa)` |

**Verdict:** Remove. Same reasoning as `notifyStackAddition`.

##### 5. `updateManaPool` → **REMOVE**

| Aspect | Details |
|--------|---------|
| **IGuiGame signature** | `void updateManaPool(Iterable<PlayerView> manaPoolUpdate)` |
| **NetGuiGame sends** | `updateGameView()` then `send(ProtocolMethod.updateManaPool, Iterable<PlayerView>)` |
| **Data sent** | Which players changed. No mana details — client reads from synced GameView. |
| **Corresponding event** | `GameEventManaPool(Player player, EventValueChangeType mode, Mana mana)` |
| **Event data** | Player + change type (Added/Removed/Cleared) + specific mana (color, source card, ability) — **strictly more** data. |

**Verdict:** Remove `ProtocolMethod.updateManaPool` and `NetGuiGame` override. Keep `IGuiGame` method. Client's local visitor processes forwarded `GameEventManaPool` and calls `updateManaPool()` on local `CMatchUI`.

##### 6. `updatePlayerControl` → **REMOVE**

| Aspect | Details |
|--------|---------|
| **IGuiGame signature** | `void updatePlayerControl()` |
| **NetGuiGame sends** | `updateGameView()` then `send(ProtocolMethod.updatePlayerControl)` — **no parameters at all** |
| **Data sent** | Nothing. Pure trigger signal. |
| **Corresponding event** | `GameEventPlayerControl(Player player, LobbyPlayer oldLobbyPlayer, PlayerController oldController, LobbyPlayer newLobbyPlayer, PlayerController newController)` |
| **Event data** | Full before/after control change information — **vastly more** data. |

**Verdict:** Remove `ProtocolMethod.updatePlayerControl` and `NetGuiGame` override. Keep `IGuiGame` method. Client's visitor processes `GameEventPlayerControl` and calls `updatePlayerControl()` locally.

##### 7. `updateZones` → **REMOVE (with prerequisite)**

| Aspect | Details |
|--------|---------|
| **IGuiGame signature** | `void updateZones(Iterable<PlayerZoneUpdate> zonesToUpdate)` |
| **NetGuiGame sends** | `updateGameView()` then `send(ProtocolMethod.updateZones, Iterable<PlayerZoneUpdate>)` |
| **Data sent** | `PlayerZoneUpdate` = which player + which zone types to refresh. No card data. |
| **Corresponding events** | `GameEventCardChangeZone(Card, Zone from, Zone to)`, `GameEventZone(ZoneType, Player, EventValueChangeType, Card, SpellAbility)`, `GameEventCardAttachment(...)`, `GameEventShuffle(Player)`, `GameEventSubgameEnd(Game, String)` |
| **Event data** | Individual card movements and zone mutations. |
| **Non-event callers** | `InputSelectEntitiesFromList` (line 66), `InputSelectTargets` (line 77) — these are input handlers that run server-side and call `updateZones` on `NetGuiGame` during player interaction prompts. |

**Data gap analysis:** Events carry individual deltas (one card moved), not bulk zone state. But the protocol method ALSO doesn't carry zone state — it's just a trigger signal ("refresh zone X for player Y"). The actual zone contents come from `updateGameView()`. Since event forwarding also calls `updateGameView()` first, the client has the same data. The client's local `FControlGameEventHandler` processes the events and produces the same `updateZones()` calls.

**Non-event caller issue:** `InputSelectEntitiesFromList` and `InputSelectTargets` call `updateZones` to ensure the UI is current before showing selection dialogs. These run server-side and go through `NetGuiGame`. These callers are on the INPUT path (prompt → client interaction → response), not the EVENT path, so they can't be replaced by event forwarding. **However**, these callers also call `updateGameView()` implicitly through `NetGuiGame.updateZones()`, and the client should have current zone data from prior GameView syncs. In practice, input prompt methods (like `getChoices`, `chooseSingleEntityForEffect`) already call `updateGameView()` themselves. The `updateZones` calls in input handlers may be redundant safety refreshes.

**Verdict:** Remove `ProtocolMethod.updateZones` and `NetGuiGame.updateZones()` override for the event-driven path. **Prerequisite:** Verify that input handler callers (`InputSelectEntitiesFromList`, `InputSelectTargets`) still produce correct UI. If they need explicit zone refresh on the client, they can call `updateGameView()` directly (which they already do implicitly via other protocol methods in the same code path). If testing reveals issues, keep as documented exception with TODO.

##### 8. `updateCards` → **REMOVE (with prerequisite)**

| Aspect | Details |
|--------|---------|
| **IGuiGame signature** | `void updateCards(Iterable<CardView> cards)` |
| **NetGuiGame sends** | `updateGameView()` then `send(ProtocolMethod.updateCards, Iterable<CardView>)` |
| **Data sent** | Which CardViews changed. No card state — client reads from synced GameView. |
| **Corresponding events** | `GameEventCardTapped`, `GameEventCardPhased`, `GameEventCardDamaged`, `GameEventCardCounters`, `GameEventCardStatsChanged`, `GameEventCardRegenerated`, `GameEventAttackersDeclared`, `GameEventBlockersDeclared`, `GameEventCombatEnded`, `GameEventCombatUpdate` |
| **Event data** | Each event identifies the changed card(s) with specific context (what changed). |
| **Non-event callers** | `PlayerControllerHuman.setViewAllCards()`, `InputAttack` (line 159), `AbstractGuiGame.setSelectables()`, `AbstractGuiGame.clearSelectables()` |

**Data gap analysis:** Same as `updateZones` — protocol method is a trigger signal, actual data from `updateGameView()`. Events carry specific delta info. The batching done by `FControlGameEventHandler` (collecting cards into `cardsUpdate` set, flushing periodically) would happen on the client side instead.

**Non-event caller issue:** `setSelectables`/`clearSelectables` are called from `NetGuiGame` and sent as their own protocol methods. `setViewAllCards` is a PlayerController method. `InputAttack` is server-side input. These callers use their own protocol paths (e.g., `ProtocolMethod.setSelectables` already sends `updateGameView()` + the selectables). They don't depend on `ProtocolMethod.updateCards` specifically.

**Verdict:** Remove `ProtocolMethod.updateCards` and `NetGuiGame.updateCards()` override. Keep `IGuiGame` method. Non-event callers use their own separate protocol methods and are unaffected. Same prerequisite as `updateZones` — verify during testing.

#### Summary Table (Updated Step 6)

| Protocol Method | Status | Justification |
|----------------|--------|---------------|
| `hearSoundEffect` | **N/A — does not exist** | Sound is already event-driven via SoundSystem/EventVisualizer |
| `updateLives` | **Remove** | Event carries old/new life values (more data). Non-event caller is local-only. |
| `notifyStackAddition` | **Remove** | NetGuiGame is already no-op. Event is the sole data source. |
| `notifyStackRemoval` | **Remove** | NetGuiGame is already no-op. Event is the sole data source. |
| `updateManaPool` | **Remove** | Event carries player + change type + mana color (more data). |
| `updatePlayerControl` | **Remove** | Protocol sends zero data. Event carries full before/after info. |
| `updateZones` | **Remove** *(verify in testing)* | Trigger-only signal; data from GameView sync. Events serve as same trigger on client. Non-event callers use separate protocol paths. |
| `updateCards` | **Remove** *(verify in testing)* | Trigger-only signal; data from GameView sync. Non-event callers use separate protocol paths. |

#### Revised Step 6 Estimates

- **ProtocolMethod entries to remove:** 7 (updateLives, notifyStackAddition, notifyStackRemoval, updateManaPool, updatePlayerControl, updateZones, updateCards). `hearSoundEffect` doesn't exist.
- **NetGuiGame overrides to remove:** 5 (the two notifyStack methods don't have overrides). Each is ~4 lines.
- **Files changed:** ~5-8 (ProtocolMethod.java, NetGuiGame.java, possibly AbstractGuiGame.java no-op stubs, client-side GameProtocolHandler if method dispatch entries need removal)
- **Lines removed:** ~40-60 (net removal)
- **Risk:** Medium (downgraded from Medium-high). The key architectural insight — that protocol methods are trigger signals, not data carriers — means removal is clean. The main risk is non-event callers of `updateZones`/`updateCards`, but analysis shows they use separate protocol paths.
- **Prerequisite:** Event forwarding must call `updateGameView()` before `send(forwardGameEvent, ...)`. This is the same pattern all current protocol methods use.

### Investigation 4: Formerly-Excluded Event Data Audit — COMPLETE *(AMENDED per TRT feedback)*

> **Amendment:** TRT feedback (2026-02-15) reclassifies `GameEventSubgameStart` and `GameEventSubgameEnd` as `UiEvent` instead of `GameEvent`. The investigation data below is preserved for reference, but the subgame recommendations (approach (b) — SubgameEffect extraction) are **superseded**. The events stay as-is in their current form; they simply move from `GameEvent` to `UiEvent`. Only the `GameEventPlayerControl` recommendations below remain active.

**Resolves:** Open Questions 8, 9

#### Event Record Definitions

```java
// Current:
GameEventSubgameStart(Game subgame, String message)
GameEventSubgameEnd(Game maingame, String message)
GameEventPlayerControl(Player player, LobbyPlayer oldLobbyPlayer, PlayerController oldController,
                       LobbyPlayer newLobbyPlayer, PlayerController newController)
```

#### Per-Event Audit

##### 1. `GameEventSubgameStart` — **Heavy `Game` usage, not simple to serialize**

**MatchUiEventVisitor** (`HostedMatch.java:396-431`) — The **primary** consumer. Uses `Game` extensively:
- `event.subgame().subscribeToEvents(SoundSystem.instance)` — subscribes SoundSystem to the subgame's EventBus
- `event.subgame().subscribeToEvents(visitor)` — subscribes the MatchUiEventVisitor to the subgame's EventBus
- `event.subgame().getView()` → `GameView` — used to switch the GUI to the subgame's view
- `event.subgame().getPlayers()` — iterates all players to:
  - Check `p.getController() instanceof PlayerControllerHuman`
  - Get `guis.get(p.getRegisteredPlayer())` — looks up the GUI for each player
  - Call `gui.setGameView(gameView)`, `gui.setOriginalGameController(p.getView(), humanController)`, `gui.openView()`
- `event.subgame().getPlayers()` (second loop) — calls `p.updateOpponentsForView()` for each player

**GameLogFormatter** — No handler. Uses the `Base` default (returns null). The `message` field is NOT consumed by GameLogFormatter.

**FControlGameEventHandler** — No handler. Uses the `Base` default (returns null).

**Analysis:** This event is fundamentally a **host-side lifecycle event**. It wires up event subscriptions and switches GUI views for a brand-new `Game` instance. A network client cannot subscribe to a remote `Game`'s EventBus or access its `Player` objects. The `message` string is the only client-relevant data.

**Recommendation:** Keep `GameEventSubgameStart` as a **host-only lifecycle event** that is NOT forwarded to clients. Instead, forward a lightweight signal (the `message` string only) via a separate mechanism or a stripped-down event. The client needs to know "a subgame started" for display purposes, but all the heavy lifting (event subscription, view switching) must happen on the host. For network play, the host's `MatchUiEventVisitor` handles this locally, and `NetGuiGame` can forward the GameView switch + message via existing `setGameView`/`message` protocol methods.

**Revised event after refactor:**
```java
// Canonical constructor stores only serializable data:
public record GameEventSubgameStart(String message) implements GameEvent {
    // Convenience constructor for host-side creation:
    public GameEventSubgameStart(Game subgame, String message) {
        this(message);
        // NOTE: The Game reference is NOT stored — host-side lifecycle wiring
        // (event subscription, GUI switching) stays in MatchUiEventVisitor,
        // which accesses the Game directly from SubgameEffect's call context.
    }
}
```

**Problem with the convenience constructor approach:** The convenience constructor discards `Game`, but `MatchUiEventVisitor` needs it. **Two options:**
- **(a) Don't refactor this event's MatchUiEventVisitor handler.** Keep it receiving the event with `Game` reference on the host side (convenience constructor still creates the event with engine types on the host). The serialized event only carries `message`. MatchUiEventVisitor runs host-side only, so it can cast or use a side-channel. BUT records don't allow "extra" non-canonical data — the convenience constructor delegates to `this(message)` and `Game` is lost.
- **(b) Split the lifecycle wiring out of the event handler.** Move the subscription/view-switching logic from `MatchUiEventVisitor.visit(GameEventSubgameStart)` into `SubgameEffect` directly (before/after `fireEvent`). The event becomes purely informational (`message` only). MatchUiEventVisitor's handler becomes a no-op or handles client-side display only. **This is the cleanest approach.**

**Recommended approach: (b).** The `SubgameEffect` already has the `Game subgame` reference in scope. Move the event subscription and GUI switching into `SubgameEffect.resolveSubgame()` directly, coordinating through `HostedMatch` (which is accessible via `Game.getMatch()`). The event shrinks to `GameEventSubgameStart(String message)` — purely informational, fully serializable, safe to forward.

##### 2. `GameEventSubgameEnd` — **Moderate `Game` usage, partially serializable**

**MatchUiEventVisitor** (`HostedMatch.java:434-458`) — Uses `Game`:
- `event.maingame().getView()` → `GameView` — switch GUI back to main game view
- `event.maingame().getPlayers()` — iterates players to:
  - Check `p.getController() instanceof PlayerControllerHuman`
  - Look up `guis.get(p.getRegisteredPlayer())`
  - Switch GUI: `gui.setGameView(gameView)`, `gui.setOriginalGameController()`, `gui.openView()`, `gui.updatePhase(true)`
  - Show message: `gui.message(event.message())`

**FControlGameEventHandler** (`FControlGameEventHandler.java:295-312`) — Uses `Game`:
- `event.maingame().getPlayers()` — iterates players to call `updateZone(p, zone)` for 5 zone types (Battlefield, Hand, Graveyard, Exile, Command)
- `event.maingame().isDay()` / `event.maingame().isNight()` — checks daytime to update the matchscreen day/night display. NOTE: `GameView` does NOT have `isDay()`/`isNight()`.

**GameLogFormatter** — No handler. Uses the `Base` default (returns null).

**Analysis:** Same pattern as SubgameStart — a host-side lifecycle event. The GUI switching must happen on the host (needs `Game.getPlayers()`, `PlayerControllerHuman` references, `guis` map). `FControlGameEventHandler` uses `Game` for zone updates and daytime status.

**Recommendation:** Same as SubgameStart — **approach (b)**. Move the lifecycle wiring into `SubgameEffect` directly. For the zone refresh in `FControlGameEventHandler`, replace with a bulk zone update triggered by the event's message (refresh all zones for all players when subgame ends — the handler already does this for every player, so a broadcast "refresh all" is equivalent). For daytime: add a `String dayTime` field to the event (nullable: `"Day"`, `"Night"`, or `null`), computed in the convenience constructor from `maingame.isDay()`/`isNight()`.

**Revised event after refactor:**
```java
public record GameEventSubgameEnd(String message, String dayTime) implements GameEvent {
    public GameEventSubgameEnd(Game maingame, String message) {
        this(message,
             maingame.isDay() ? "Day" : maingame.isNight() ? "Night" : null);
    }
}
```

**FControlGameEventHandler update:** Replace `event.maingame().getPlayers()` zone iteration with a broadcast zone refresh for all players (obtainable from `GameView.getPlayers()`). Replace `event.maingame().isDay()`/`isNight()` with `event.dayTime()`.

##### 3. `GameEventPlayerControl` — **`PlayerController` has limited usage, mostly replaceable**

**Record fields:** `Player player`, `LobbyPlayer oldLobbyPlayer`, `PlayerController oldController`, `LobbyPlayer newLobbyPlayer`, `PlayerController newController`.

**GameLogFormatter** (`GameLogFormatter.java:155-167`) — Reads:
- `event.newLobbyPlayer()` → `LobbyPlayer` — `.getName()` for log message. `LobbyPlayer` is NOT serializable (Investigation 6) — replace with `String newLobbyPlayerName`.
- `event.player()` → `Player` — `.getName()` for log message.
- Logic: If `newLobbyPlayer == null`, log "player has restored control of themselves." If `newLobbyPlayer.getName().equals(p.getName())`, return null (no log). Otherwise, log "player controlled target player."
- **No `PlayerController` access at all.** Only needs `Player.getName()` and `LobbyPlayer.getName()`.

**FControlGameEventHandler** (`FControlGameEventHandler.java:231-246`) — Reads:
- `ev.player().getGame().isGameOver()` — early exit if game is over. Engine-only access.
- `ev.newController() instanceof PlayerControllerHuman` — checks if the new controller is human to get a reference to the human controller for `setGameController()`.
- `ev.newController()` — cast to `PlayerControllerHuman` if applicable, passed to `matchController.setGameController(PlayerView, PlayerControllerHuman)`.
- `PlayerView.get(ev.player())` — converts Player to PlayerView.

**MatchUiEventVisitor** — No handler (uses `Base` default — returns null). The `visit(GameEventPlayerControl)` is not overridden in `HostedMatch.MatchUiEventVisitor`.

**Analysis:**
- `GameLogFormatter` only needs `PlayerView` (for name) and `LobbyPlayer` (for name). No `PlayerController` access. Easy.
- `FControlGameEventHandler` needs:
  1. Game-over check: Replace with `GameView.isGameOver()` or similar. The handler has access to `matchController` (an `IGuiGame`) which should have access to `GameView`.
  2. `instanceof PlayerControllerHuman` check: This is fundamentally host-side — the client doesn't have `PlayerControllerHuman` instances. For a remote client, the control-change event means "your view of who controls whom changed" — the actual controller wiring is host-side.
  3. `setGameController(PlayerView, PlayerControllerHuman)`: Passes the controller to `CMatchUI`/`IGuiGame`. On the host, this wires up the correct human controller. On a remote client, this would need to be handled differently (via the existing `setOriginalGameController` protocol path).

**Recommendation:** The `PlayerController` fields (`oldController`, `newController`) are **host-only lifecycle data** — they're used to wire up which `PlayerControllerHuman` handles input for a player. Remote clients don't need this. `LobbyPlayer` is NOT serializable (Investigation 6) — replace with `String` names. Replace `Player` with `PlayerView`. The `FControlGameEventHandler` handler stays host-side (it runs per-human-player on the host); for a remote client, the event serves as a trigger to refresh the player control display.

**Revised event after refactor:**
```java
public record GameEventPlayerControl(PlayerView player, String oldLobbyPlayerName,
        String newLobbyPlayerName, boolean newControllerIsHuman) implements GameEvent {

    public GameEventPlayerControl(Player player, LobbyPlayer oldLobbyPlayer,
            PlayerController oldController, LobbyPlayer newLobbyPlayer,
            PlayerController newController) {
        this(PlayerView.get(player),
             oldLobbyPlayer != null ? oldLobbyPlayer.getName() : null,
             newLobbyPlayer != null ? newLobbyPlayer.getName() : null,
             newController instanceof PlayerControllerHuman);
    }
}
```

**FControlGameEventHandler update:**
- Game-over check: Use `humanController.getGui()` → check if game view reports game over. Or pass `boolean isGameOver` as an event field.
- `instanceof PlayerControllerHuman`: Use new `event.newControllerIsHuman()` boolean. But the handler also needs the actual `PlayerControllerHuman` reference for `setGameController()`. **This is host-only** — on the host, `FControlGameEventHandler` can obtain the controller from `humanController` field or from the player's controller chain. The simplest approach: the host-side handler checks `event.newControllerIsHuman()` and if true, looks up the human controller from its own context (it already has `humanController` field).
- **Key insight:** `FControlGameEventHandler` is constructed per-human-player with a specific `PlayerControllerHuman`. When control changes, the handler checks if the NEW controller is the SAME human controller it was built for. If so, it wires up `setGameController`. The remote client doesn't need this wiring — it just needs a UI refresh signal. So the `PlayerControllerHuman` reference is genuinely host-only.

**Alternative simpler approach:** Don't change the `FControlGameEventHandler.visit(GameEventPlayerControl)` method at all for the initial refactor. It runs host-side only. The convenience constructor on the event stores the view-based fields for serialization/forwarding, but the host-side handler can use the `Player`/`PlayerController` references directly via the convenience constructor's captured values. **Problem:** Records don't allow extra non-canonical fields. The convenience constructor delegates to the canonical constructor, which only has view fields. Host-side code gets view fields.

**Actual recommended approach:** Keep the `FControlGameEventHandler` handler working by providing enough data:
1. `event.player()` → `PlayerView` — sufficient for name, `isGameOver` check moves to GameView
2. `event.newControllerIsHuman()` → `boolean` — sufficient for the instanceof check
3. For `setGameController(PlayerView, PlayerControllerHuman)` — the handler already has `humanController` field. When `newControllerIsHuman` is true and the control target matches, use the existing `humanController`. When false, pass null.

#### Summary *(AMENDED per TRT feedback)*

| Event | Approach |
|-------|----------|
| ~~`GameEventSubgameStart`~~ | **Reclassified as `UiEvent`** (per TRT feedback). Not serialized, not forwarded. Handlers unchanged. Original approach (b) superseded. |
| ~~`GameEventSubgameEnd`~~ | **Reclassified as `UiEvent`** (per TRT feedback). Not serialized, not forwarded. Handlers unchanged. Original approach (b) superseded. |
| `GameEventPlayerControl` | Replace `Player` → `PlayerView`, `LobbyPlayer` → `String` name, drop `PlayerController`, add `boolean newControllerIsHuman`. Handler uses existing `humanController` field. *(Unchanged from original recommendation.)* |

#### Impact on Plan *(AMENDED)*

**Step 3 (Complex Events):** Update `GameEventPlayerControl` record definition per the revised signature above. Subgame events are **removed from GameEvent scope** — reclassified as `UiEvent` (per TRT feedback). No `SubgameEffect` extraction needed.

**Step 4 (Visitor Updates):**
- `FControlGameEventHandler.visit(GameEventPlayerControl)`: Replace `ev.player().getGame().isGameOver()` with GameView check. Replace `ev.newController() instanceof PlayerControllerHuman` with `ev.newControllerIsHuman()`. Obtain `PlayerControllerHuman` from the handler's own `humanController` field.
- `MatchUiEventVisitor`: Subgame handlers unchanged (events reclassified as `UiEvent`, stay host-local).

**New work in Step 3:** ~~Refactor `SubgameEffect.resolveSubgame()`~~ — **Removed** (per TRT feedback). Subgame events reclassified as `UiEvent`; no extraction needed. The only new work is the `GameEventPlayerControl` signature change and `GameEventGameOutcome` pre-computed fields.

### Investigation 5: GameOutcome Serializability — COMPLETE

**Resolves:** Open Question 5

#### GameOutcome Class Analysis

`GameOutcome` (`forge-game/.../GameOutcome.java`) does **NOT** implement `Serializable`. It is a regular class with the following fields:

| Field | Type | Serializable? |
|-------|------|---------------|
| `lastTurnNumber` | `int` | Yes (primitive) |
| `lifeDelta` | `int` | Yes (primitive) |
| `winningTeam` | `int` | Yes (primitive) |
| `winCondition` | `GameEndReason` (enum) | Yes (enum) |
| `playerRating` | `HashMap<RegisteredPlayer, PlayerStatistics>` | **No** — `RegisteredPlayer` not serializable |
| `playerNames` | `HashMap<RegisteredPlayer, String>` | **No** — `RegisteredPlayer` keys |
| `anteResult` | `Map<RegisteredPlayer, AnteResult>` | **No** — `RegisteredPlayer` keys (`AnteResult` itself is `Serializable`) |

#### Transitive Dependency Chain

`RegisteredPlayer` is **deeply non-serializable** — it contains `Deck originalDeck`, `Deck currentDeck`, `LobbyPlayer`, `List<PaperCard>`, `Iterable<IPaperCard>`, and many other fields. Making it serializable would require serializing the entire deck/card data model — completely out of scope.

`PlayerStatistics` is NOT serializable but simple: `int openingHandSize`, `int timesMulliganed`, `int turnsPlayed`, `PlayerOutcome outcome`.

`PlayerOutcome` is NOT serializable but trivial: `String altWinSourceName`, `GameLossReason lossState` (enum), `String loseConditionSpell`.

`LobbyPlayer` is an abstract class, NOT serializable. Has subclasses `LobbyPlayerHuman`, `LobbyPlayerAi`.

#### Event Record

```java
public record GameEventGameOutcome(GameOutcome result, Collection<GameOutcome> history)
    implements GameEvent { ... }
```

#### Consumer Analysis

| Visitor | What it reads from `GameOutcome` | Serializable equivalent |
|---------|--------------------------------|------------------------|
| `GameLogFormatter` | `result.getLastTurnNumber()` → int | `int lastTurnNumber` |
| `GameLogFormatter` | `result.getOutcomeStrings()` → `List<String>` | Pre-computed `List<String>` |
| `GameLogFormatter` | `generateSummary(history)` → iterates each `GameOutcome` in history, calling `getWinningPlayer()` (→ `RegisteredPlayer`) and `getPlayerNames()` (→ `HashMap<RegisteredPlayer, String>`) to build a per-player win count | Pre-computed `String matchSummary` |
| `EventVisualizer` | `result.getWinningLobbyPlayer()` → `LobbyPlayer`, compared via `Objects.equals(lobbyPlayer, player)` | `String winningLobbyPlayerName` + comparison by name |
| `FControlGameEventHandler` | Nothing — just sets `gameOver = true` | No data needed |

#### Recommended Approach: Pre-Computed Fields

Making `GameOutcome` serializable is infeasible (deep `RegisteredPlayer` → `Deck` dependency). Instead, pre-compute all needed data as serializable fields in the event record's canonical constructor, populated from `GameOutcome` in the convenience constructor.

```java
public record GameEventGameOutcome(
        int lastTurnNumber,
        List<String> outcomeStrings,
        String winningPlayerName,   // LobbyPlayer name for EventVisualizer comparison
        String matchSummary         // pre-computed from history for GameLogFormatter
) implements GameEvent {

    /** Convenience constructor — extracts serializable data from engine objects. */
    public GameEventGameOutcome(GameOutcome result, Collection<GameOutcome> history) {
        this(result.getLastTurnNumber(),
             result.getOutcomeStrings(),
             result.getWinningLobbyPlayer() != null
                 ? result.getWinningLobbyPlayer().getName() : null,
             computeMatchSummary(result, history));
    }

    private static String computeMatchSummary(GameOutcome result,
            Collection<GameOutcome> history) {
        // Replicates GameLogFormatter.generateSummary() logic:
        // iterate history, count wins per player name, format as string.
        // This moves the RegisteredPlayer iteration to the host side.
        if (history == null || history.isEmpty()) return "";
        GameOutcome first = history.iterator().next();
        HashMap<RegisteredPlayer, String> players = first.getPlayerNames();
        HashMap<RegisteredPlayer, Integer> winCount = new HashMap<>();
        for (GameOutcome game : history) {
            RegisteredPlayer winner = game.getWinningPlayer();
            winCount.merge(winner, 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<RegisteredPlayer, String> entry : players.entrySet()) {
            int amount = winCount.getOrDefault(entry.getKey(), 0);
            sb.append(entry.getValue()).append(": ").append(amount).append(" ");
        }
        return sb.toString().trim();
    }
}
```

#### Visitor Updates Required

**GameLogFormatter.visit(GameEventGameOutcome):**
- `ev.result().getLastTurnNumber()` → `ev.lastTurnNumber()`
- `ev.result().getOutcomeStrings()` → `ev.outcomeStrings()`
- `generateSummary(ev.history())` → use `ev.matchSummary()` directly. The `generateSummary` method can be removed or kept as dead code cleanup.

**EventVisualizer.visit(GameEventGameOutcome):**
- `Objects.equals(event.result().getWinningLobbyPlayer(), player)` → `event.winningPlayerName() != null && event.winningPlayerName().equals(player.getName())`. Note: `LobbyPlayer.equals()` compares by object identity by default (no custom equals). The current code uses `Objects.equals` which also does identity comparison. But `PlayerView.isLobbyPlayer()` compares by name. Since the winning lobby player and the local `player` field will never be the same object instance over the network, name comparison is the correct approach for the serializable version. **Check:** Verify that local (non-network) usage still works — on the host, both the `EventVisualizer.player` and `getWinningLobbyPlayer()` ARE the same object, so identity comparison works. After the refactor, name comparison also works for the host (names are unique per player). No regression.

**FControlGameEventHandler.visit(GameEventGameOutcome):** No change — doesn't access `GameOutcome` data.

#### Impact on Plan

- **Step 3 (Complex Events):** `GameEventGameOutcome` joins the complex events list. It gets 4 pre-computed fields replacing the `GameOutcome`/`Collection<GameOutcome>` fields. The `computeMatchSummary` private static method (~15 lines) replicates `generateSummary` logic.
- **Step 4 (Visitor Updates):** `GameLogFormatter` and `EventVisualizer` updates are minor — field accessor name changes.
- **No Step 1 changes needed.** `GameOutcome` itself is NOT modified — the data extraction happens in the event's convenience constructor.

### Investigation 6: Netty Serialization Round-Trip — COMPLETE

**Resolves:** Risk "Netty serialization fails for view-based events"

#### Serialization Mechanism

Forge uses **standard Java Object Serialization** with two optimizations:

1. **LZ4 compression:** All traffic is wrapped in `LZ4BlockOutputStream`/`LZ4BlockInputStream` for bandwidth reduction.
2. **Thin class descriptors:** `CObjectOutputStream`/`CObjectInputStream` replace full Java class descriptor metadata with just the class name (1 byte marker + UTF string), reducing per-object overhead.

**Pipeline** (`FGameClient.java:52-62`, `FServerManager.java`):
```
CompatibleObjectEncoder → LZ4 compress → 4-byte length header → wire
wire → length-based framing → LZ4 decompress → CompatibleObjectDecoder
```

**Encoder signature:** `class CompatibleObjectEncoder extends MessageToByteEncoder<Serializable>` — the `msg` parameter type is `Serializable`. **Objects MUST implement `Serializable` to be sent.**

#### Protocol Dispatch

All method calls are wrapped in `GuiGameEvent implements IdentifiableNetEvent` (which extends `Serializable`):

```java
public final class GuiGameEvent implements IdentifiableNetEvent {
    private static final long serialVersionUID = 6223690008522514574L;
    private final int id;
    private final ProtocolMethod method;  // enum — inherently serializable
    private final Object[] objects;       // arguments — each must be Serializable
}
```

**Sending:** `GameProtocolSender.send(ProtocolMethod method, Object... args)` → wraps in `GuiGameEvent` → `channel.writeAndFlush()`.

**Receiving:** `GameProtocolHandler.channelRead()` → unwrap `GuiGameEvent` → `Method.invoke(toInvoke, args)` via reflection on `IGuiGame`/`IGameController`.

#### View Object Serializability — All Confirmed

All view objects extend `TrackableObject implements Serializable`:

| View Class | Serializable? | serialVersionUID |
|-----------|---------------|------------------|
| `TrackableObject` (base) | Yes — `implements Serializable` | `7386836745378571056L` |
| `GameEntityView` | Yes (inherits) | `3789732662891715652L` |
| `CardView` | Yes (inherits) | `-3624090829028979255L` |
| `PlayerView` | Yes (inherits) | `7005892740891549086L` |
| `SpellAbilityView` | Yes (inherits) | `2514234930798754769L` |
| `StackItemView` | Yes (inherits) | `6733415646691356052L` |
| `GameView` | Yes (inherits) | `8522884512960961528L` |

Non-serializable fields in `TrackableObject` are already marked `transient`:
- `TrackableObject.tracker` — `transient Tracker` (reconstructed on receive)
- `GameView.game` — `transient`
- `GameView.match` — `transient`

These objects are **already sent over the wire** via existing protocol methods (e.g., `setGameView(GameView)`, `updateLives(Iterable<PlayerView>)`, `getAbilityToPlay(..., List<SpellAbilityView>, ...)`). No new serialization work needed for them.

#### GameEvent Record Serialization Requirements

**Q: Do `GameEvent` records need `implements Serializable`?**
**Yes.** The `CompatibleObjectEncoder` requires `Serializable`. When a `GameEvent` is an argument to a `ProtocolMethod` call, it's stored in the `Object[] objects` array of `GuiGameEvent` and serialized via `ObjectOutputStream.writeObject()`.

**Required change:** `GameEvent` interface must extend `Serializable`:
```java
public interface GameEvent extends Event, Serializable { ... }
```
Or alternatively, `Event` can extend `Serializable`:
```java
public interface Event extends Serializable { }
```

Since Java records can implement `Serializable` and their canonical constructor is automatically used for deserialization, this is straightforward. No `serialVersionUID` is technically required for records (they use a different serialization mechanism than regular classes), but adding one is good practice.

**Q: Will convenience constructors cause deserialization issues?**
**No.** Java records are deserialized using the **canonical constructor** only. The convenience constructor (accepting engine types) is never called during deserialization — it's a compile-time overload used at event creation on the host. The canonical constructor (accepting view types) is what gets called when deserializing on the client. This is exactly the desired behavior:

```
Host creates event:    new GameEventFoo(card, player)     ← convenience constructor
                       → internally calls this(CardView.get(card), PlayerView.get(player))
                       → canonical constructor stores views

Serialization:         ObjectOutputStream writes: CardView, PlayerView fields

Client deserializes:   ObjectInputStream → canonical constructor(CardView, PlayerView)
                       → client gets view-typed fields directly ✓
```

#### Non-Serializable Field Types Found

| Type | Used In | Serializable? | Resolution |
|------|---------|---------------|------------|
| `CardView` | ~25 events | Yes (TrackableObject) | No action |
| `PlayerView` | ~25 events | Yes (TrackableObject) | No action |
| `SpellAbilityView` | 4 events | Yes (TrackableObject) | No action |
| `StackItemView` | 1 event | Yes (TrackableObject) | No action |
| `GameEntityView` | 3 events | Yes (TrackableObject) | No action |
| `ZoneType` | 2 events | Yes (enum) | No action |
| `EventValueChangeType` | ~3 events | Yes (enum) | No action |
| `DamageType` | 1 event | Yes (enum) | No action |
| `CounterType` | ~2 events | Yes (enum) | No action |
| `LobbyPlayer` | 1 event (`GameEventPlayerControl`) | **No** — abstract class, not `Serializable` | **Replace with `String` name** |
| `Multimap<GameEntityView, CardView>` | 1 event | Yes (Guava Multimap is `Serializable` if keys/values are) | No action |
| `List<CardView>` | ~3 events | Yes (ArrayList + CardView) | No action |
| `Collection<CardView>` | ~2 events | Yes | No action |
| `Collection<PlayerView>` | 1 event | Yes | No action |
| Primitives / Strings | all events | Yes | No action |

**Key finding: `LobbyPlayer` is NOT serializable.** It's an abstract class with subclasses `LobbyPlayerHuman`, `LobbyPlayerAi`, etc. — none implement `Serializable`. It also has an abstract method `hear()` making serialization conceptually wrong (it's a behavior-carrying object, not data).

**Impact on `GameEventPlayerControl`:** Investigation 4 proposed keeping `LobbyPlayer oldLobbyPlayer` and `LobbyPlayer newLobbyPlayer` as event fields because "`LobbyPlayer` is already serializable." **This is wrong — `LobbyPlayer` is NOT serializable.** These fields must be replaced with `String` names.

**Revised `GameEventPlayerControl` record:**
```java
public record GameEventPlayerControl(
        PlayerView player,
        String oldLobbyPlayerName,    // was LobbyPlayer (not serializable)
        String newLobbyPlayerName,    // was LobbyPlayer (not serializable)
        boolean newControllerIsHuman
) implements GameEvent {

    public GameEventPlayerControl(Player player, LobbyPlayer oldLobbyPlayer,
            PlayerController oldController, LobbyPlayer newLobbyPlayer,
            PlayerController newController) {
        this(PlayerView.get(player),
             oldLobbyPlayer != null ? oldLobbyPlayer.getName() : null,
             newLobbyPlayer != null ? newLobbyPlayer.getName() : null,
             newController instanceof PlayerControllerHuman);
    }
}
```

**GameLogFormatter impact:** Currently reads `event.newLobbyPlayer().getName()` and `event.player().getName()`. After: `event.newLobbyPlayerName()` and `event.player().getName()`. Trivial change.

#### Step 1 Addition: `GameEvent extends Serializable`

Add `Serializable` to the `GameEvent` interface (or its parent `Event`). This is a one-line change that makes all 57 event records serializable. Since records' fields are the only state, and all fields will be view-typed (already serializable) after the refactor, no further annotation is needed on individual records.

```java
// Option A: On GameEvent directly
public interface GameEvent extends Event, Serializable { ... }

// Option B: On Event (broader, covers UiEvent too if needed later)
public interface Event extends Serializable { }
```

**Recommended: Option A.** Only `GameEvent` needs network serialization. `UiEvent` is GUI-only and doesn't need to be serializable.

#### Summary

| Question | Answer |
|----------|--------|
| Serialization mechanism | Java ObjectOutputStream + LZ4 compression + thin descriptors |
| Custom codec needed? | No — use existing `CompatibleObjectEncoder`/`CompatibleObjectDecoder` pipeline |
| Must implement `Serializable`? | **Yes** — encoder type parameter is `Serializable` |
| View objects serializable? | **Yes** — all extend `TrackableObject implements Serializable`, already sent over wire |
| Records + convenience constructors? | **No issue** — records deserialize via canonical constructor (view-typed), convenience constructor only used at creation time |
| `LobbyPlayer` serializable? | **No** — must replace with `String` name in `GameEventPlayerControl` |
| New work for Step 1 | Add `Serializable` to `GameEvent` interface (~1 line) |
| New work for Step 3 | Replace `LobbyPlayer` fields with `String` names in `GameEventPlayerControl` |
| Serialization unit test | Serialize sample events via `ObjectOutputStream`, deserialize, compare fields |

### Investigation Order

```
Investigation 1 (View APIs) ──────────► COMPLETE ✓
        │
        ▼
Investigation 2 (GameLogFormatter) ──► COMPLETE ✓

Investigation 3 (Protocol Methods) ──► COMPLETE ✓

Investigation 4 (Excluded Events) ──► COMPLETE ✓

Investigation 5 (GameOutcome) ──────► COMPLETE ✓

Investigation 6 (Serialization) ────► COMPLETE ✓
```

**All investigations complete.** Key findings from Investigation 6: Forge uses Java Object Serialization + LZ4 compression. Objects MUST implement `Serializable` — the encoder type parameter enforces this. All view objects already do (via `TrackableObject`). `GameEvent` interface needs `extends Serializable` added (1 line). Record deserialization uses the canonical constructor (view-typed), so convenience constructors pose no issue. `LobbyPlayer` is NOT serializable — must replace with `String` names in `GameEventPlayerControl` (corrects Investigation 4's incorrect "already serializable" claim).

**Plan status: Ready for maintainer review.** All investigations complete, all open questions resolved (except Q6 — maintainer coordination, which is the purpose of this review). Estimated scope: ~70-75 files, ~1205 lines changed, single PR with commit-per-step structure.
