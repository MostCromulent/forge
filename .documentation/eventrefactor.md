# GameEvent View Refactor Plan

## Goal

Make `GameEvent` subclasses network-serializable by replacing engine object references (`Card`, `Player`, `SpellAbility`, `Zone`) with their serializable view counterparts (`CardView`, `PlayerView`, `SpellAbilityView`, `ZoneType`). This enables forwarding raw game events to network clients, allowing each client to process events locally using its own `IGameEventVisitor` implementations, settings, and locale.

**Target:** Forge master (Card-Forge/forge). Single PR.
**Branch architecture:** `IGuiGame` → `AbstractGuiGame` → `CMatchUI` / `NetGuiGame` / `MatchController` (no `NetworkGuiGame` or delta sync).

## Current State

| Metric | Count |
|--------|-------|
| GameEvent subclasses (records) | 57 |
| Already serializable (primitives/enums only) | 10 |
| Reference non-serializable engine objects | 47 |
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
| `Game` | 2 | None | Server-only events, exclude |
| `PlayerController` | 1 | None | Server-only event, exclude |
| `Multimap<GameEntity, Card>` | 1 | `Multimap<GameEntityView, CardView>` | Needs conversion helper |
| `Map<GameEntity, Multimap<Card,Card>>` | 1 | Equivalent with views | Needs conversion helper |
| `Collection<Card>` | 4 | `Collection<CardView>` | `CardView.getCollection()` exists |
| `Collection<Player>` | 2 | `Collection<PlayerView>` | Needs helper |
| `GameOutcome` | 1 | Already serializable? | Verify |

### Visitor Implementations

| Visitor | Module | Needs Engine Objects? | Network-Relevant? |
|---------|--------|----------------------|-------------------|
| `FControlGameEventHandler` | forge-gui | No (already converts to views) | Yes — primary client consumer |
| `FControlGamePlayback` | forge-gui | Minimal (1 SpellAbility access) | Yes — client replay |
| `EventVisualizer` | forge-gui | Yes (SVar, mana abilities) | Yes — client sound effects |
| `GameLogFormatter` | forge-game | Moderate (toString/getName) | Possible — client-side logging |
| `MatchUiEventVisitor` | forge-gui | Yes (PlayerController, Game) | No — server-only lifecycle |

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

### Server-Only Events (Excluded)

Three events reference engine-only types (`Game`, `PlayerController`) and are only consumed by `MatchUiEventVisitor` on the host. These are excluded from the view refactor:

- `GameEventSubgameStart(Game subgame, String message)` — subgame lifecycle
- `GameEventSubgameEnd(Game maingame, String message)` — subgame lifecycle
- `GameEventPlayerControl` — references `PlayerController`

These 3 events are documented as not network-serializable and must not be forwarded.

## CardView Gaps for EventVisualizer

`EventVisualizer` is the most demanding consumer. `CardView.CardStateView` already exposes:
- `isCreature()`, `isArtifact()`, `isEnchantment()`, `isLand()`, `isPlaneswalker()` — all present
- `getName()` — present

**Missing from CardView:**
1. `isInstant()` / `isSorcery()` — Not on `CardStateView`. Need adding (trivial — delegate to `getType().isInstant()` / `getType().isSorcery()`).
2. `hasSVar("SoundEffect")` / `getSVar("SoundEffect")` — Engine-only. Used for `ScriptedEffect` card-specific sounds. **Already excluded** from network forwarding in soundfix branch. Client-side EventVisualizer would skip ScriptedEffect logic.
3. `getManaAbilities()` → `AbilityManaPart.getOrigProduced()` — Used for land color sound selection. Not on CardView.

### Addressing the Land Sound Gap

For the land color sound (`getLandSound()` in EventVisualizer), the logic inspects mana abilities to determine what colors the land produces. This data is not available on `CardView`.

**Options (choose during implementation):**
1. **Add a `manaColors` tracked property to CardView** — a simple `String` like `"WUB"` computed from the card's mana abilities. Updated when the card enters the battlefield. EventVisualizer reads this instead of inspecting abilities. ~10 lines in CardView + Card.
2. **Accept degraded land sounds on client** — Client plays generic `OtherLand` for all lands. Host plays full color-specific sounds locally. Simple, zero new infrastructure.
3. **Forward land sounds as SoundEffectType** — Keep the existing `hearSoundEffect` protocol method specifically for land sounds (where the host computes the color). Everything else uses event forwarding.

**Recommendation:** Option 2 for the initial refactor (simplest, no new tracked properties), with Option 1 as a follow-up if users notice.

## Implementation Order

All work lands in a single PR. Implementation proceeds in this order to keep the branch compilable at each step.

### Step 1: Foundation

**Scope:** Add missing view methods, create conversion utilities.

1. **Add to `CardView.CardStateView`:**
   - `isInstant()` → `return getType().isInstant();`
   - `isSorcery()` → `return getType().isSorcery();`

2. **Add collection conversion helpers** (if not already present):
   - `PlayerView.getCollection(Iterable<Player>)` — static helper mirroring `CardView.getCollection()`
   - `GameEntityView.getCollection(Iterable<GameEntity>)` — if needed for combat events

3. **Verify** `SpellAbilityView` exposes `getHostCard()` → `CardView` and `isSpell()`. Add if missing.

4. **Verify** `PlayerView` exposes `getLobbyPlayer()`. Add if missing.

5. **Verify** `GameOutcome` is serializable. Add `Serializable` if needed.

**Files changed:** ~5
**Risk:** Very low — additive only.

### Step 2: Simple Events (~20 records)

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

### Step 3: Complex Events (~12 records)

**Scope:** Events with `Zone`, `SpellAbility`, `Mana`, collections, and complex types.

| Event | Complexity |
|-------|-----------|
| `GameEventCardChangeZone` | `Zone` → `ZoneType` |
| `GameEventZone` | `Card` → `CardView`, `SpellAbility` → `SpellAbilityView` (ZoneType already enum) |
| `GameEventSpellAbilityCast` | `SpellAbility` → `SpellAbilityView`, `SpellAbilityStackInstance` → `StackItemView` |
| `GameEventSpellResolved` | `SpellAbility` → `SpellAbilityView` |
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

**Files changed:** ~16 event records
**Risk:** Medium. Collection conversions need null-safety. `Mana` simplification changes what data is available.

### Step 4: Visitor Updates

**Scope:** Update all 5 visitors to compile and behave correctly with view-based event fields.

**FControlGameEventHandler:**
- Remove all `.getView()` calls on event fields (now redundant).
- Net effect: code simplification.

**EventVisualizer:**
- `visit(GameEventSpellResolved)`: `evt.spell().getHostCard()` now returns `CardView`. Change `source.isCreature()` etc. to `source.getCurrentState().isCreature()`.
- `visit(GameEventZone)`: `card.isLand()` → `card.getCurrentState().isLand()`. For `getManaAbilities()` (land color sounds): accept degraded land sounds on view-based path (return `OtherLand` when mana abilities unavailable).
- `visit(GameEventBlockersDeclared)`: `event.defendingPlayer().getLobbyPlayer()` — use `PlayerView` equivalent.
- `hasSpecificCardEffect()`: Accept `CardView`. `hasSVar()` not on views → always return `false` (ScriptedEffect excluded from network forwarding anyway).
- `getScriptedSoundEffectName()`: This method is only called from `SoundSystem` on the host, which subscribes directly to the `Game` EventBus. The host still fires events with engine objects via the convenience constructors, so the canonical view fields are populated from `.getView()` calls. **However**, `getScriptedSoundEffectName()` casts events to access engine-specific fields (`evSpell.spell().getHostCard()` as `Card`, `evZone.card()` as `Card`). After the refactor these return `CardView`, not `Card`. This method needs one of:
  - (a) Overload accepting `CardView` with a view-compatible implementation
  - (b) Keep it working — `CardView` has `.getName()` which is sufficient for the filename lookup. `hasSVar` check can fall through to name-based lookup. This is likely the simplest path.

**GameLogFormatter:**
- Audit every `visit()` method. Key methods accessed on Card/Player:
  - `.toString()`, `.getName()` → exist on views
  - `.getZone()` → CardView has zone info
  - Game-rule introspection (`.isLand()`, `.isCreature()`) → exist on `CardStateView`
- This is the highest-risk visitor. Each visit method must be verified individually.

**FControlGamePlayback:**
- `visit(GameEventSpellResolved)`: `evt.spell().getHostCard()` → now returns `CardView`. Minor update.

**MatchUiEventVisitor:**
- No changes — only handles excluded server-only events.

**Files changed:** ~5
**Risk:** Medium-high for GameLogFormatter. Low for others.

### Step 5: Network Integration

**Scope:** Add `GameEvent` forwarding to the existing master branch protocol pipeline.

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
       // Dispatch to local visitors — sound, GUI updates, etc.
       // Subclasses (CMatchUI, MatchController) can override for platform-specific handling.
   }
   ```

5. **Forward from `HostedMatch.MatchUiEventVisitor.receiveGameEvent()`:**
   ```java
   @Subscribe
   public void receiveGameEvent(final GameEvent evt) {
       evt.visit(this);

       // Forward view-safe events to remote clients
       for (IGuiGame gui : guis.values()) {
           if (gui instanceof NetGuiGame) {
               gui.handleGameEvent(evt);
           }
       }
   }
   ```

   Note: Events excluded from serialization (`GameEventSubgameStart`, `GameEventSubgameEnd`, `GameEventPlayerControl`) must be filtered out — either by a type check or by catching serialization exceptions.

6. **Client-side event dispatch in `AbstractGuiGame.handleGameEvent()`:**
   The client needs to run its local `FControlGameEventHandler` and `EventVisualizer` on received events. On master, `FControlGameEventHandler` is instantiated per-game in `HostedMatch`, not in the GUI. For the client path:
   - `AbstractGuiGame.handleGameEvent()` dispatches to `EventVisualizer` for sounds
   - GUI state updates from events are already handled by the existing protocol methods (`updateZones`, `updateCards`, etc.) which delta sync would replace on /main — on master, these existing methods continue to handle state, and event forwarding handles only **transient feedback** (sounds, log entries, animations)

   **Important architectural note:** On master, the primary purpose of event forwarding is transient effects (sounds, combat log). Persistent state (card positions, life totals) continues to use the existing per-method protocol (`updateZones`, `updateLives`, etc.). Event forwarding does NOT replace the state synchronization path — it supplements it.

**Files changed:** ~6-8
**Risk:** Medium. Events must survive Netty serialization round-trip.

### Step 6: Remove Replaced Protocol Methods

**Scope:** Remove per-feature protocol methods that are now redundant because the client processes forwarded events directly.

Candidates for removal (only if event forwarding fully covers their behavior):
- `hearSoundEffect` → client-side EventVisualizer handles forwarded events
- `notifyStackAddition` / `notifyStackRemoval` → if client's `FControlGameEventHandler` processes the forwarded spell events

**Conservative approach:** For this PR, only remove `hearSoundEffect` (from soundfix branch). Leave other protocol methods in place — they handle state synchronization that event forwarding doesn't replace on master.

**Files changed:** ~4-5

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
| GameLogFormatter breaks with view types | Medium | Medium | Audit every visit() method. CardView/PlayerView have getName(), toString(). Test with actual game logs. |
| EventVisualizer produces wrong sounds | Low | Low | Card type checks exist on CardView. Only SVar and mana abilities are missing (handled). |
| Netty serialization fails for view-based events | High | Low | CardView/PlayerView already serialize over Netty in existing protocol. Add serialization unit tests. |
| Collection conversion drops null entries | Medium | Medium | Use null-safe helpers. `GameEntityView.get()` / `CardView.get()` must handle null. |
| Record constructor ambiguity | Low | Very Low | Java resolves overloads by exact parameter types. Card vs CardView, Player vs PlayerView are unambiguous. |
| Performance: `.getView()` called at every event creation | Very Low | N/A | `.getView()` is a field access (O(1)). Events are created ~10-30 times per turn. Negligible. |
| Large PR is hard to review | Medium | Medium | Clear commit-per-step structure. Each commit is mechanical and independently verifiable. Event record changes are repetitive (same pattern 47 times). |
| Master has no integration test infrastructure | Medium | Medium | `testTrueNetworkTraffic` exists on master for basic protocol validation. Manual network testing required for event forwarding. |

## Testing Strategy

1. **Compilation:** `mvn -pl forge-gui -am compile` after each step.

2. **Unit tests:**
   - CI tests (`mvn -U -B clean test`) must pass — checkstyle (imports), existing unit tests.
   - Add serialization round-trip test: serialize a sample of migrated events via Java ObjectOutputStream, deserialize, compare fields.

3. **Existing network test:**
   - `testTrueNetworkTraffic` — validates basic protocol (exists on master).

4. **Manual network testing:**
   - Host + client game after Step 5 to verify event forwarding and sound on client.

## Estimated Scope (Single PR)

| Step | Files Changed | Lines Changed (est.) |
|------|---------------|---------------------|
| Step 1: Foundation | ~5 | ~50 |
| Step 2: Simple Events | ~27 | ~350 |
| Step 3: Complex Events | ~16 | ~300 |
| Step 4: Visitor Updates | ~5 | ~200 |
| Step 5: Network Integration | ~6-8 | ~100 |
| Step 6: Cleanup | ~4-5 | ~-50 (removal) |
| **Total** | **~63-66** | **~950** |

## Guidelines Compliance

- **Minimal diff:** Convenience constructors mean zero changes at 167 creation sites. Diff is concentrated in event definitions (mechanical, repetitive) and visitor updates (5 files).
- **Search before creating:** Reuses existing `CardView.get()`, `CardView.getCollection()`, `PlayerView.get()` static helpers. Collection conversion helpers are private statics inside each event record — no new utility class.
- **Avoid over-engineering:** No new abstract framework. Records stay records. Visitor interface unchanged. `IGuiGame` gets one new method (`handleGameEvent`) in Step 5 — justified because it represents a new capability (event dispatch) not reachable through existing object graphs.
- **Prefer forwarding game events:** This IS the implementation of that guideline.
- **Trace changes across execution contexts:** Events are created on the host (forge-game), consumed by visitors on both host and client (forge-gui), and forwarded via `NetGuiGame` (forge-gui). Convenience constructors ensure host-side creation is unchanged. Client receives view-based events that are directly usable.
- **Don't expand interfaces for trivial access:** `IGameEventVisitor` is unchanged. The single new `IGuiGame.handleGameEvent()` is a genuine new capability.
- **Isolate network code:** Event forwarding dispatch stays in `NetGuiGame`/`HostedMatch`. Events themselves are module-agnostic (forge-game). Client-side handling is in `AbstractGuiGame`.
- **Fix bugs at the closest layer:** View conversion happens at the event layer (closest to the serialization problem).
- **Platform-neutral:** `handleGameEvent()` default lives in `AbstractGuiGame` (shared). Platform-specific dispatch (if any) overrides in `CMatchUI`/`MatchController`.

## Open Questions

1. **Does `PlayerView` expose `getLobbyPlayer()`?** EventVisualizer's `visit(GameEventBlockersDeclared)` and `visit(GameEventGameOutcome)` check lobby player identity. If not exposed, need to add it or find an alternative.

2. **Does `SpellAbilityView` expose `getHostCard()` returning `CardView`?** EventVisualizer and FControlGamePlayback both access the host card through SpellAbility. If not, need to add it.

3. **Does `SpellAbilityView` expose `isSpell()`?** EventVisualizer checks this in `visit(GameEventSpellResolved)`.

4. **GameLogFormatter depth:** How deeply does it introspect engine objects? A full audit is needed in Step 4. If it accesses methods not on views (e.g., `card.getZone().getCards()`), those visit methods may need to retain engine object access via a separate code path, or the missing data may need adding to view classes.

5. **GameOutcome serializability:** `GameEventGameOutcome` contains `GameOutcome` and `Collection<GameOutcome>`. Need to verify `GameOutcome` is serializable or add serialization support.

6. **Maintainer coordination:** The guidelines state "Do not refactor existing events without coordinating with maintainers." This plan should be reviewed and approved by tool4ever/TRT before implementation begins.

7. **Event filtering for serialization:** The 3 excluded server-only events (`GameEventSubgameStart`, `GameEventSubgameEnd`, `GameEventPlayerControl`) must not be forwarded in Step 5. Decide on filtering mechanism: type whitelist, marker interface, or try-catch around serialization.
