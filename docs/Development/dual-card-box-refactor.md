# DualCardBox: card-grid partition choices

Status: proposal
Scope: forge-gui (engine plumbing), forge-gui-desktop (new widget), forge-gui-mobile (fallback)

## Summary

Introduce a single GUI primitive, `chooseCardPiles`, that handles every
multi-card choice expressible as "split a list of cards into one or more
labeled piles, with optional ordering per pile." Back it on desktop with a
new Swing widget, `DualCardBox`, that renders the choice as two card-grid
panes with a center control bar. On libgdx and headless, fall back to the
existing dialog widgets behind the same interface.

Migrate the engine call sites that today emit two consecutive dialogs
(scry, surveil) or text-list dialogs over card collections (manipulate,
contraption crank, choose-cards-for-effect fallback) onto the new API.

## Motivation

Today, every multi-card choice in `PlayerControllerHuman` follows one of two
paths:

1. **In-zone selection** via `InputSelectCardsFromList` /
   `InputSelectEntitiesFromList`, taken when `useSelectCardsInput` returns
   true. The player clicks cards directly in their existing zone display.
2. **Dialog fallback** via `getGui().many(...)` or `getGui().order(...)`,
   which renders a `DualListBox` of text rows with a card preview pane.

Path 2 is taken whenever path 1 isn't viable: cards in zones outside the
display whitelist, cards with no zone, engine-thread invocations, or
libgdx. The dialog widget shows card *names* as a `JList`, not the cards
themselves. For scry, surveil, fact-or-fiction-style piles, and similar
effects, this is a noticeably worse UX than the in-zone path.

Worse, ordering and partitioning choices currently require **two sequential
dialogs**. `arrangeForScry` calls `getGui().many(...)` to pick the cards
going to the bottom, then `getGui().order(...)` to order the remaining
top — two separate modal interactions for a single game action.

`DualCardBox` collapses both into one card-grid widget that visually
mirrors the player's existing mental model of zone displays.

## Goals

- Provide one `IGuiGame` method, `chooseCardPiles(ChoiceRequest)`, that
  expresses any number of labeled destination piles with per-pile
  `min`/`max`/`ordered` constraints, plus a global total constraint.
- Render this on desktop as a single dialog containing two card-grid
  panes with a center control bar, OK gated on constraint satisfaction.
- Migrate `arrangeForScry`, `arrangeForSurveil`,
  `chooseContraptionsToCrank`, `manipulateCardList`, and the dialog
  fallbacks in `chooseCardsForEffect` / `chooseCardsToDiscardFrom` to the
  new method.
- Preserve net play, AI, and headless behavior via a default fallback that
  delegates to the existing `many` + `order` methods.

## Non-goals

- No changes to `FloatingZone` or to the in-zone selection path
  (`InputSelectCardsFromList`). Those continue to work as today.
- No changes to single-card pickers (`getGui().one`, `oneOrNone`,
  `getChoices`) or to non-card-payload choices (counter types, color
  choices, replacement-effect ordering, etc.).
- No mobile or libgdx port of the new widget; mobile continues to render
  these choices via the existing dialogs through the fallback path.
- No new abstraction (`CardSource`) over `FloatingZone`'s data binding.
  That is a separable piece of work and is not required to ship
  `DualCardBox`.
- No support for two simultaneously ordered destination piles. The data
  model accepts the configuration but the widget does not render
  reordering affordances on more than one pile. If a card ever requires
  this, the affordance is added then.

## Background

### Existing widgets

`DualListBox` (`forge-gui-desktop/src/main/java/forge/gui/DualListBox.java`,
487 lines) is the current backing widget for `getGui().many(...)` and
`getGui().order(...)`. It is an `FDialog` with two `FList<T>` panes, a
center column of `>` / `>>` / `<` / `<<` move buttons, OK/Auto buttons,
and selection-driven card preview via `matchUI.setCard(...)`. It handles
min/max constraints on remaining source items and supports a sideboarding
mode. The interaction model is the right one; the rendering is text rows.

`ListCardArea`
(`forge-gui-desktop/src/main/java/forge/view/arcane/ListCardArea.java`,
217 lines) is the current backing widget for `manipulateCardList(...)`.
It extends `FloatingCardArea`, which is the same base class as
`FloatingZone`. It already supports:

- `setDragEnabled(true)` plus a `mouseDragEnd` handler that reorders cards
  by drag,
- `cardPanelDraggable(panel)` override that restricts drag to a
  manipulable subset of visible cards,
- `toTop` / `toBottom` / `toAnywhere` constraints that gate which positions
  a drag may target,
- left-click-to-top / right-click-to-bottom as alternate move primitives.

A comment at the top of the file flags the missing infrastructure
explicitly: *"Really should have a difference between visible cards and
moveable cards, but that would require considerable changes to card
panels and elsewhere."*

`DualCardBox` is essentially the composition of these two widgets: take
`DualListBox`'s layout, control bar, and OK gating; replace the `FList<T>`
panes with `ListCardArea`-style card grids.

### Existing engine plumbing

`PlayerControllerHuman.useSelectCardsInput(...)` (around line 436) routes
between the in-zone and dialog paths. When it returns false, the dialog
path is taken via one of:

- `getGui().many(...)` — multi-pick, unordered
- `getGui().order(...)` — single-pile, ordered
- `getGui().many(...)` followed by `getGui().order(...)` — partition with
  ordered remainder (scry, surveil)
- `getGui().manipulateCardList(...)` — single pile, partial reorder

All of these are partition-shaped and become one `chooseCardPiles` call.

## Proposed design

### Data model

```java
record ChoiceRequest(
    String title,
    PlayerView prompter,                       // who is making the choice
    CardView source,                           // the spell or ability prompting it
    TrackableCollection<CardView> pool,        // initial left-pane contents
    List<Pile> destinations,                   // one or more right-side piles
    int totalMin,                              // global lower bound on total moved
    int totalMax,                              // global upper bound (-1 for unbounded)
    boolean cancelable
) { }

record Pile(
    String id,                                 // stable key used in ChoiceResult
    String label,                              // shown above the pane
    int min,                                   // per-pile lower bound
    int max,                                   // per-pile upper bound (-1 for unbounded)
    boolean ordered,                           // numbered badges + reorder affordances
    TrackableCollection<CardView> initial      // optional pre-population
) { }

record ChoiceResult(
    Map<String, List<CardView>> byPile         // pile id -> ordered card list
) { }
```

The data model accepts an arbitrary number of destination piles. The v1
widget renders at most one destination pane (the right pane) and treats
the pool as the implicit "other" pile. Two-pile choices (Fact or Fiction)
are modeled as one destination pile with the remainder staying in the
pool. Three-or-more-pile choices are not in scope.

The `pool` field carries `TrackableCollection<CardView>` because the
choice is rendered against view objects; conversion back to `Card`
happens at the call site via the existing `GameEntityViewMap` pattern.

### IGuiGame surface

```java
// in forge-gui/src/main/java/forge/gui/interfaces/IGuiGame.java
default ChoiceResult chooseCardPiles(ChoiceRequest request) {
    // Default fallback: render the request as many(...) followed,
    // for any ordered pile, by order(...). See "Fallback strategy."
    return defaultChooseCardPiles(request);
}
```

A `default` method on the interface gives libgdx, headless, and AI
implementations a working fallback without per-implementation overrides.
The desktop `CMatchUI` overrides with the `DualCardBox` renderer.

### ProtocolMethod entry

```java
// in forge-gui/src/main/java/forge/gamemodes/net/ProtocolMethod.java
chooseCardPiles (Mode.SERVER, ChoiceResult.class, ChoiceRequest.class),
```

`RemoteClientGuiGame.chooseCardPiles` delegates to `sendAndWait` exactly
as `many` and `order` do today. The records must implement
`Serializable`; `Pile.initial` and `pool` use the existing
`TrackableCollection` serialization.

### Widget architecture

```
DualCardBox extends FDialog
  ├── leftPane:   PoolPane     (extends FloatingCardArea)
  ├── centerBar:  ChoiceControls
  │      ├── moveToDestButton (>)
  │      ├── moveToPoolButton (<)
  │      ├── moveUpButton     (▲)        // only if any pile is ordered
  │      ├── moveDownButton   (▼)        // only if any pile is ordered
  │      ├── selectionCount   ("3 / 5")
  │      ├── okButton
  │      └── cancelButton
  └── rightPane:  DestPane     (extends FloatingCardArea)
```

`PoolPane` and `DestPane` are inner classes or sealed siblings of
`FloatingCardArea`. They reuse:

- Card-grid layout, `CardPanel` rendering, sort toggle (right-click),
  scroll handling — inherited.
- Drag-reorder within a pane — present on `FloatingCardArea` via
  `setDragEnabled` / `mouseDragEnd` / `cardPanelDraggable`. `DestPane`
  enables this when its `Pile.ordered` is true; `PoolPane` never does.
- Click-to-move semantics — `PoolPane.mouseLeftClicked` moves the card
  to the destination pile; `DestPane.mouseLeftClicked` moves it back to
  the pool. This mirrors `ListCardArea`'s left/right-click conventions
  but with cross-pane semantics.

`DestPane` adds:

- Order-position badges on each card, painted in a distinct color
  (suggested red) to distinguish from hotkey-selection badges used
  elsewhere. The badge label is `1 + indexInPile`.
- A header label showing `Pile.label` and the running `n / max` count.

`PoolPane` adds:

- The existing search filter (if present in the codebase at
  implementation time) and sort toggle.
- A header label showing `"Available"` (configurable) and the running
  remaining-count.

`ChoiceControls` is a thin panel mirroring `DualListBox`'s center column
but with reorder buttons added. The OK gating logic is lifted from
`DualListBox._setButtonState`: enable when every pile's `min` is met,
every pile's `max` is not exceeded, and the global `totalMin`/`totalMax`
constraint holds.

### Cross-pane drag-and-drop

Deferred to a follow-up PR. The v1 widget supports click-to-move and
button-bar moves only. Drag is supported *within* a pane (already
implemented in `FloatingCardArea`) but not *between* panes.

When added, cross-pane DnD is implemented via a Swing glass pane that
intercepts mouse events during a drag, computes drop targets by
coordinate-mapping into either pane, and previews drop position with the
same insertion-marker pattern `ListCardArea` already uses.

### Fallback strategy

```java
// in IGuiGame (default method)
default ChoiceResult defaultChooseCardPiles(ChoiceRequest request) {
    // Single ordered destination, pool stays as remainder:
    //   render as order(...)
    // Single unordered destination, pool stays as remainder:
    //   render as many(...)
    // Single ordered destination + ordered remainder (scry/surveil shape):
    //   render as many(...) followed by order(...) on the remainder
    // Multiple destinations: enumerate and render sequentially.
    // Pre-populated destination: pass via existing destChoices parameter.
    ...
}
```

The default implementation is the systematic translation from
`ChoiceRequest` to the existing `many`/`order` calls. Mobile and headless
inherit this behavior unchanged, and net play sees no protocol change in
the fallback path because the server-side implementation still terminates
in the existing protocol methods.

This means migrating an engine call site to `chooseCardPiles` is
**behavior-preserving on libgdx and headless**: the same dialogs render
in the same order. Desktop is the only path that visibly changes, and
only when `CMatchUI` overrides `chooseCardPiles`.

## Migration plan

Each migrated call site collapses some combination of `tempShowCards` +
`many` + `order` + `GameEntityViewMap` boilerplate into a single
`chooseCardPiles` call plus one map conversion of the result.

### `arrangeForScry`
(`PlayerControllerHuman.java` around line 967)

Currently two `getGui()` calls and two `GameEntityViewMap` instances.
After:

```java
ChoiceRequest req = new ChoiceRequest(
    localizer.getMessage("lblScry"),
    player.getView(), CardView.get(sa.getHostCard()),
    CardView.getCollection(topN),
    List.of(new Pile("top", "Top of library",
                     0, topN.size(), true, null)),
    0, topN.size(), false);
ChoiceResult result = getGui().chooseCardPiles(req);
// pool remainder -> bottom; "top" pile -> top
```

Net: ~30 lines removed, ~15 added per call site.

### `arrangeForSurveil`
(`PlayerControllerHuman.java` around line 1012)

Same shape as scry. The non-top destination is the graveyard instead of
the bottom of the library. Modeled identically.

### `chooseContraptionsToCrank`
(`PlayerControllerHuman.java` around line 515)

Pre-populated destination: contraptions previously cranked start in the
right pane. Modeled as a single unordered destination pile with `initial`
populated and `totalMin = 0`, `totalMax = -1`.

### `manipulateCardList`
(`PlayerControllerHuman.java` around line 944)

The directional flags (`toTop`, `toBottom`, `toAnywhere`) map to pile
configurations. A `toTop`-only call becomes one ordered destination pile;
the manipulable subset is the only thing the pool will accept; the rest
of the library is presented as fixed context. This is the case where the
v1 widget is closest to today's `ListCardArea`. Depending on the cost of
preserving the "context" cards in the layout, this migration may be
deferred to a follow-up — see open questions.

### `chooseCardsForEffect` fallback
(`PlayerControllerHuman.java` around line 499)

Direct replacement of `getGui().many(...)`. One unordered destination
pile with `min`/`max`. Engine code shrinks by ~3 lines.

### `chooseCardsToDiscardFrom` and `chooseCardsToDiscardUnlessType`
fallbacks (`PlayerControllerHuman.java` around lines 1149, 1562)

Same shape as `chooseCardsForEffect` fallback.

### Out-of-scope sites in this PR

`orderBlockers`, `orderAttackers`, `exertAttackers`, `enlistAttackers`,
`orderMoveToZoneList` are all single-pile ordered choices and could be
migrated. They are deferred because they reach `getGui().order(...)` via
distinct call paths and the migration would expand PR scope without
shipping new UX.

## Sequencing

Single PR. Sub-staging within the PR:

1. Add records, `IGuiGame.chooseCardPiles` with default impl, protocol
   entry, remote client glue. Verify libgdx/headless still pass tests
   with no call sites migrated.
2. Add `DualCardBox` widget. Wire desktop override.
3. Migrate `chooseContraptionsToCrank` first (simplest — no ordering, no
   per-card complexity). Verify desktop renders correctly.
4. Migrate `arrangeForScry`. Verify the two-step dialog flow on libgdx is
   unchanged.
5. Migrate `arrangeForSurveil`.
6. Migrate `chooseCardsForEffect` / `chooseCardsToDiscardFrom` /
   `chooseCardsToDiscardUnlessType` fallbacks.
7. Decide `manipulateCardList` migration (this PR or follow-up).

If review feedback wants the PR split, the natural fault line is between
step 2 and step 3: ship the widget + protocol with no migrations, then
migrate in a second PR.

Cross-pane DnD is a separate follow-up PR and is not blocking.

## Open questions

1. **`manipulateCardList` migration shape.** The "context cards" pattern
   (most of the library shown but only top 2 manipulable) is awkward to
   express in `ChoiceRequest`. Options: (a) include them in the pool with
   a `frozen` flag per card, (b) skip migration and keep `ListCardArea`
   for this case, (c) introduce a `context` field on `ChoiceRequest`.
   Recommend (b) for v1 and revisit if other use cases emerge.

2. **Localization keys.** The widget needs ~10–15 new strings: pane
   headers, button labels, count format ("Selected n / m"), and so on.
   The `en-US.properties` file is shared across modules; the canonical
   key names should follow the existing `lblX` convention.

3. **Numbered-badge color.** Suggested red to distinguish from
   selection-hotkey badges where those exist. Implementation should
   parameterize the color on `CardPanel`'s badge slot rather than
   hard-coding it.

4. **AI behavior.** `PlayerControllerAi` does not override the new
   method. The default fallback's call to `many`/`order` will route AI
   choices through the existing AI implementations. Confirm via test
   that no AI regressions surface from the call-site migrations.

5. **Single-pane (pure-reorder) variants.** Eventually the same widget
   should subsume `getGui().order(...)` for blocker ordering and similar.
   This is out of scope for v1. Confirm the widget's design admits a
   "left pane hidden" mode for that future migration.

6. **Cancel semantics across migrated sites.** Some current dialog calls
   are non-cancelable; some are cancelable with explicit handling.
   `ChoiceRequest.cancelable` carries this, but the per-site mapping
   needs verification during migration — there should be a test or
   manual checklist confirming each migrated site preserves its prior
   cancel behavior.

## Risks

- **Net play regression.** The new protocol entry must round-trip records
  cleanly. Mitigation: a unit test that constructs a `ChoiceRequest`,
  serializes via the same path `RemoteClientGuiGame` uses, deserializes,
  and asserts equality. Run before the first call site is migrated.

- **AI determinism in tests.** `PlayerControllerForTests` may need an
  override if the default fallback's path through `many`/`order` produces
  different AI decisions than expected by existing test cases. Mitigation:
  run the full AI test suite after step 1, before any migrations.

- **Visual regression on the scry/surveil double-dialog.** Players who
  rely on the existing two-step flow may experience the consolidated
  widget as a workflow change. Mitigation: the preference
  `UI_SELECT_FROM_CARD_DISPLAYS` (already a routing gate elsewhere)
  could be respected; when off, `CMatchUI.chooseCardPiles` falls through
  to the default implementation, preserving the old UX.

- **Widget complexity creep.** The "deferred" features (cross-pane DnD,
  context cards, pure-reorder mode, multi-pile destinations) all have
  legitimate use cases. Discipline is required to defer them and not
  accumulate them mid-PR.

## Rollback

Each migrated call site is independent; reverting a single migration is
a few-line change. Reverting the widget is removing one file plus the
override in `CMatchUI`. Reverting the API is reverting the interface
change and the protocol entry. Each step is a clean revert.

The default fallback in `IGuiGame` means the API can ship without any
overrides and without any call site migrations, leaving the codebase
in a working state with the new infrastructure available but inactive.

## Testing strategy

- Unit tests for `ChoiceRequest` / `Pile` / `ChoiceResult` serialization.
- Unit test for the default fallback's `many`+`order` translation across
  representative `ChoiceRequest` shapes.
- Manual desktop smoke test for each migrated call site (scry 3, surveil
  2 of 3, contraption crank with mixed previously-cranked, choose 2 of 5
  for an effect).
- Manual libgdx smoke test confirming the dialog flow is unchanged for
  the same scenarios.
- Net play smoke test for at least one migrated site (recommend scry).
- AI test suite (`forge-ai/src/test/...`) clean run after step 1 and
  after the final migration.

## Out of scope

- `FloatingZone` data-source generalization (`CardSource`).
- Ephemeral FloatingZone for null-zone cards.
- Cross-pane drag-and-drop.
- Mobile / libgdx renderer for the new widget.
- Three-or-more-pile destinations.
- Simultaneously ordered destination piles.
- Migration of `orderBlockers` / `orderAttackers` / `orderMoveToZoneList`.
- Pure single-pane reorder mode (a future use case for the same widget).
