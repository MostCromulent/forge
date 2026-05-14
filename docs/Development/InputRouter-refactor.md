# InputRouter refactor

## Summary

`PlayerControllerHuman` (PCH) tangles three concerns in the same methods:
what input the rules engine needs from the player, how the UI should
present that prompt (card-click vs dialog), and where the platform-specific
branches live. The PR discussion about consolidating opponent-hand
reveal+discard surfaced the symptom: `DelayedReveal` advertises an
"inline-reveal-into-the-selection" contract that three of four `IGuiGame`
implementations don't honor, because PCH never gave them a clean way to.

This refactor extracts the input-vs-display split into a dedicated
`InputRouter` layer, finishes the `DelayedReveal` contract on both GUI
implementations, and pushes platform-specific UI decisions
(`isLibgdxPort`, `UI_SELECT_FROM_CARD_DISPLAYS`) down into the `IGuiGame`
impls where they belong.

Scope is one PR, bounded to the selection / order / manipulate surface of
PCH. The `Input*` subsystem, mulligan, attack/block, mana payment,
replacement effects, and dev-mode tooling are out of scope.

## Motivation

PCH currently mixes three concerns:

1. **Game-logic decisions** — turning rules-engine asks ("pick N cards from
   this set") into player input.
2. **Input mode selection** — choosing between two completely different UI
   metaphors: card-click selection (`InputSelectEntitiesFromList` →
   `InputQueue`) and dialog selection (`getGui().many` /
   `chooseEntitiesForEffect`).
3. **Platform branching** — `isLibgdxPort()` checks scattered across PCH for
   "skip this confirm on mobile", "show a different dialog on mobile", etc.

The mixing has concrete costs:

- PCH is 3572 lines. Of that, ~25% is selection routing, with the same
  `tempShowCards(...) / build Input / showAndWait / endTempShowCards()`
  pattern repeated across `chooseCardsForEffect`,
  `chooseSingleEntityForEffect`, `chooseEntitiesForEffect`,
  `chooseCardsToDiscardFrom`. Each instance hand-rolls the temp-show
  pairing, and the pairings aren't uniform across methods (e.g.
  `chooseCardsToDiscardFrom`'s self-discard branch skips the temp-show
  call — defensible today because the cards are in own hand, fragile
  if the call site is ever extended to non-self cases).
- `DelayedReveal` advertises (in its Javadoc) that it lets revealed cards
  appear in the same dialog as cards being selected. In practice, three of
  the four `IGuiGame` selection implementations don't honor that — they
  call a separate `reveal(...)` popup and then open the picker. `CMatchUI`
  carries two `//TODO: Merge this into search dialog` comments
  acknowledging this. The engine compensates by issuing its own
  `game.getAction().reveal(...)` before the selection, producing the
  two-stage UX the original PR discussion was trying to consolidate.
- 14 references to `isLibgdxPort()` or preference reads inside PCH; 157
  `getGui()` calls. The controller knows which platform it is on, which is
  a layering smell.

## Goals

- Make `DelayedReveal` a contract every `IGuiGame` implementation honors. Once
  this lands, the engine never has to issue a separate `reveal(...)` call
  before a selection.
- Move the card-click-vs-dialog decision out of PCH and into an
  `InputRouter` that asks the GUI a single capability question.
- Push every `isLibgdxPort()` check and selection-related preference read
  out of PCH into the `IGuiGame` implementations.
- Collapse the repeated tempShow/Input/dialog boilerplate in PCH onto one
  helper.
- Make the temp-show contract uniform across selection methods so the
  asymmetric patterns can't drift further.

## Non-Goals

- Shrinking PCH overall. PCH stays large because it carries lifecycle,
  network sync, mana payment, mulligan, replacement effects, full-control,
  and dev-mode tooling. The refactor removes ~300–500 lines of selection
  boilerplate; the rest is untouched.
- Renaming `InputSelectCardsFromList`, `InputSelectEntitiesFromList`,
  `InputQueue`, etc. The `Input*` subsystem keeps its current names.
- Changing the AI controller. `PlayerControllerAi` doesn't go through the
  router; the abstract `PlayerController` shape is unchanged.
- Generalizing `IGuiGame.many(...)` for non-`GameEntity` callers (dice,
  deck pools). Those callers keep using `many` unchanged.
- Refactoring mulligan, attack/block, mana payment, replacement effects, or
  any flow that doesn't fit the "select from a pool" / "order a list" /
  "manipulate a list" shape.

## Design

### Package layout

New package: `forge.gui.input.router` in `forge-gui`.

```
forge-gui/src/main/java/forge/gui/input/router/
  SelectionRequest.java
  OrderRequest.java
  ManipulateRequest.java
  SelectionResult.java
  TempReveal.java
  InputRouter.java
  HumanInputRouter.java
```

### Request value objects

`SelectionRequest<T extends GameEntity>` — the universal description of a
"pick from a pool" prompt:

```java
public final class SelectionRequest<T extends GameEntity> {
    public final FCollectionView<T> options;
    public final int min, max;
    public final String title;
    public final DelayedReveal delayedReveal;   // nullable
    public final SpellAbility sa;               // nullable
    public final Player targetedPlayer;         // for {player} substitution
    public final boolean cancellable;
    public final Map<String, Object> params;    // escape hatch
    public static <T extends GameEntity> Builder<T> builder() { ... }
}
```

`OrderRequest` — covers the simple ordering case (the order-the-blockers
flow, sideboard ordering, etc.). Fields: `sourceCards`, `destCards`,
`title`, `topCaption`, optional reference card.

`arrangeForScry`, `arrangeForSurveil`, and `orderMoveToZoneList` do
**not** route through `OrderRequest` — they chain selection, ordering,
and preference branches in ways that don't usefully flatten. See "PCH
changes" below for how those are handled (partial migration).

`ManipulateRequest` — covers `manipulateCardList` and `arrangeForMove`.
Fields: `title`, `cards`, `manipulable`, `toTop`, `toBottom`, `toAnywhere`.

`SelectionResult<T>` — `{ List<T> selected; boolean cancelled }`. The
`cancelled` flag is distinct from "selected an empty set" so that optional
prompts can be unambiguous.

Three request types is enough to cover everything in PCH's selection
surface. `min`/`max` doesn't translate cleanly to ordering operations, and a
single mega-request becomes a 30-field options bag that nothing wants to
build. Three is the minimum that doesn't paper over real semantic
differences.

### TempReveal

```java
public final class TempReveal implements AutoCloseable {
    public static TempReveal open(PlayerControllerHuman pch,
                                   Iterable<? extends GameEntity> options,
                                   DelayedReveal dr) {
        List<Card> outerScope = pch.snapshotTempShown();
        pch.tempShow(options);
        if (dr != null) pch.tempShow(dr.getCards());
        return new TempReveal(pch, outerScope);
    }
    @Override public void close() {
        pch.endTempShowCards();
        pch.restoreTempShown(outerScope);
    }
}
```

Replaces every manual `tempShowCards(...) / endTempShowCards()` pair.
Try-with-resources gives a structurally enforced pair.

**Important:** `PCH.tempShownCards` (`:156`) is a single flat
`ArrayList`, and `PCH.endTempShowCards()` (`:185-193`) clears it
unconditionally. Engine effects outside the selection path also call
`tempShowCards` on the controller (see e.g. `ChangeZoneEffect`,
`PlayEffect`, `DigEffect`). If a router selection runs while an outer
engine scope has live temp-shown cards, a naive `close()` that just
called `endTempShowCards()` would wipe the outer scope too. The
snapshot/restore in the sketch above prevents that. The supporting
`snapshotTempShown()` / `restoreTempShown(...)` helpers are added to PCH
as part of this PR.

(A more invasive fix — turning `tempShownCards` into a counted set or
true stack — is a cleaner long-term shape and would let PCH-side and
engine-side callers freely nest. Out of scope here; snapshot/restore is
sufficient for the selection paths the router touches.)

### Router

```java
public interface InputRouter {
    <T extends GameEntity> SelectionResult<T> select(SelectionRequest<T> req);
    List<Card> order(OrderRequest req);
    ImmutablePair<CardCollection, CardCollection> manipulate(ManipulateRequest req);
}

public final class HumanInputRouter implements InputRouter {
    private final PlayerControllerHuman controller;
    private final IGuiGame gui;

    @Override
    public <T extends GameEntity> SelectionResult<T> select(SelectionRequest<T> req) {
        if (req.options.isEmpty()) return handleEmpty(req);
        if (!req.cancellable && req.options.size() == 1) return handleForced(req);

        try (TempReveal tr = TempReveal.open(controller, req.options, req.delayedReveal)) {
            return gui.supportsCardClickSelection(zonesOf(req.options))
                ? runCardClickSelection(req)
                : runDialogSelection(req);
        }
    }

    private <T extends GameEntity> SelectionResult<T> runCardClickSelection(SelectionRequest<T> req) {
        InputSelectEntitiesFromList<T> input = new InputSelectEntitiesFromList<>(
            controller, req.min, req.max, req.options, req.sa);
        input.setMessage(format(req.title, req.targetedPlayer));
        input.setCancelAllowed(req.cancellable);
        input.showAndWait();
        return SelectionResult.of(input.getSelected());
    }

    private <T extends GameEntity> SelectionResult<T> runDialogSelection(SelectionRequest<T> req) {
        GameEntityViewMap<T, GameEntityView> map = GameEntityView.getMap(req.options);
        if (req.min == 1 && req.max == 1) {
            GameEntityView v = gui.chooseSingleEntityForEffect(
                format(req.title, req.targetedPlayer),
                map.getTrackableKeys(), req.delayedReveal, req.cancellable);
            return SelectionResult.ofSingle(map, v);
        }
        List<GameEntityView> vs = gui.chooseEntitiesForEffect(
            format(req.title, req.targetedPlayer),
            map.getTrackableKeys(), req.min, req.max, req.delayedReveal);
        return SelectionResult.ofMany(map, vs);
    }
}
```

The router doesn't know what platform it's on, doesn't read preferences,
doesn't construct `Input*` objects for any reason other than dispatch. The
card-click-vs-dialog fork is a single line that asks the GUI.

### IGuiGame additions

Two new methods on `IGuiGame`:

```java
boolean supportsCardClickSelection(Set<ZoneType> zones);
```

Asks: "if I render a selection prompt right now, can the user satisfy it by
interacting with cards already visible on screen in these zones?" "Click"
is shorthand — taps on mobile, mouse clicks on desktop, and the keyboard
hotkeys added by the FloatingZone work all qualify.

Implementations:

- **`CMatchUI` (desktop)** — true if either (a) the cards are in
  Battlefield or own Hand (the always-visible zones), or (b)
  `UI_SELECT_FROM_CARD_DISPLAYS` is on and every zone is one the desktop
  client can open via FloatingZone (Battlefield, Hand, Library, Graveyard,
  Exile, Flashback, Command, Sideboard).
- **`MatchController` (mobile)** — true only when every zone is
  Battlefield or own Hand. The libgdx UI cannot point at other zones.
- **`PlayerControllerForTests`, `HeadlessNetworkGuiGame`** — false. Tests
  and headless paths don't do click selection.
- **`RemoteClientGuiGame`** — computes the predicate locally against the
  cached `client.isLibgdx()` flag, which is already established at lobby
  handshake time. Same logic shape as the desktop/mobile impls, just
  parameterized by the remote client's announced platform. No new protocol
  traffic.

Existing `chooseSingleEntityForEffect` and `chooseEntitiesForEffect` keep
their signatures. Their implementations change:

- **`CMatchUI`** — render DR cards inside the picker (resolves the two
  `//TODO: Merge this into search dialog` comments). Note these refer to
  the modal `GuiChoose` dialog (Swing `JOptionPane`-style), **not**
  `FloatingZone`. The two desktop selection paths need separate treatment:
  - **Dialog selection path** (`GuiChoose.order` / `one` / `oneOrNone`):
    add a DR cards panel to the existing modal. This is the
    straightforward read of the TODO comments and the smaller of the two
    changes.
  - **Card-click selection path** (FloatingZone + `InputSelectEntitiesFromList`):
    DR cards need to be visible somewhere the chooser will see them
    during selection. `FloatingZone.java` today has no prompt header,
    OK/cancel, or selection-state surface — it's just a card display. The
    options are (a) display DR cards in a separate FloatingZone alongside
    the selectable one, distinguished by zone title, or (b) extend
    FloatingZone with prompt + button surface. Option (a) is much smaller
    and is the recommended approach.
- **`MatchController`** — `chooseEntitiesForEffect` routes to
  `GameEntityPicker` (today it bypasses it entirely and calls
  `SGuiChoose.order`, which is why DR is dropped). `FChoiceList` supports
  multi-select natively via its `minChoices` / `maxChoices` /
  `selectedIndices` fields, but `GameEntityPicker`'s current
  single-select form has tap-to-confirm interaction wired in:
  `PickerTab`'s inner `FChoiceList` overrides `onItemActivate` to call
  `parentScreen.optionPane.setResult(0)` immediately when `maxChoices > 0`
  (`GameEntityPicker.java:123-126`). Multi-select needs different
  semantics: tap toggles selection, OK button confirms. The wrapper has
  to be extended to (a) take `min` / `max` and a
  `Consumer<List<GameEntityView>>` callback, (b) gate the auto-confirm
  on `maxChoices == 1`, (c) enable the OK button only when
  `selectedIndices.size()` is within bounds. This is a real mobile UX
  shift — the chooser's interaction model changes from "tap a card to
  pick" (today's `SGuiChoose.order`-style ordering, and today's
  single-select picker) to "tap-toggle, then OK". Worth a quick mobile
  UX review even though the code change is contained.

### PCH changes

Each selection method shrinks to a request build plus a router call. Worked
example — `chooseSingleEntityForEffect`, currently 42 lines at
`PlayerControllerHuman.java:585-626`, becomes:

```java
@Override
public <T extends GameEntity> T chooseSingleEntityForEffect(
        FCollectionView<T> options, DelayedReveal dr, SpellAbility sa,
        String title, boolean isOptional, Player targetedPlayer,
        Map<String,Object> params) {
    return router.select(SelectionRequest.<T>builder()
        .options(options).min(1).max(1)
        .title(title).delayedReveal(dr).sa(sa)
        .targetedPlayer(targetedPlayer).cancellable(isOptional)
        .params(params)
        .build()).first();
}
```

Methods affected:

Full router migration (selection methods):

- `chooseCardsForEffect` (`:471`)
- `chooseSingleEntityForEffect` (`:585`)
- `chooseEntitiesForEffect` (`:629`)
- `chooseCardsToDiscardFrom` (`:1142`)
- `chooseCardsToDiscard` self-discard (`:1556`)

Partial migration (`TempReveal` only, custom logic stays):

- `chooseContraptionsToCrank` (`:514`) — cranked/uncranked column split is
  too specialized for `SelectionRequest`.
- `arrangeForScry` (`:967`), `arrangeForSurveil` (`:1012`),
  `orderMoveToZoneList` (`:1068`) — these chain selection → ordering →
  preference branches in a way `OrderRequest` doesn't usefully flatten.
  `arrangeForScry` for example branches on `UI_SELECT_FROM_CARD_DISPLAYS`,
  special-cases `topN.size() == 1` with `willPutCardOnTop`, and chains
  `arrangeForMove` → `many` → `order`. `arrangeForSurveil` further
  special-cases size==1 with `InputConfirm.confirm` (a card-anchored
  confirm). `orderMoveToZoneList` has a 12-way `ZoneType` switch with
  preference-driven graveyard early-return and `topOfDeck` reverse
  logic. The router can't usefully consume any of these as a single
  `OrderRequest`; pushing each through would require many fields on
  `OrderRequest` (zone, top/bottom, preference resolver) and lose the
  per-effect specialization that makes them readable.

  For this PR, these methods stay in PCH but use `TempReveal` and route
  their inner `getGui().order(...)` / `getGui().many(...)` calls
  through a thinner helper that calls `gui.supportsCardClickSelection`
  itself. They do NOT become `OrderRequest`-shaped.
- `manipulateCardList` (`:944`) / `arrangeForMove` (`:951`) — fit
  `ManipulateRequest` cleanly; keep as full migration.

Helpers that are removed:

- `useSelectCardsInput(...)` (both overloads, `:429-469`) — replaced by
  `gui.supportsCardClickSelection(zones)`.
- Repeated `tempShowCards / endTempShowCards` pairs in the call sites
  listed above — replaced by `TempReveal.open(...)`. Private
  `tempShow(...)` overloads on PCH stay (they're still called by
  `TempReveal` and by non-selection paths).

`isLibgdxPort()` checks at `:811, :1439, :1707, :1866, :2216` — these are
**not** in the selection path. They stay in PCH for this PR. Removing them
is the subject of the "Follow-up: confirm-against-card primitive" section
below.

### Engine-side change

`game.getAction().reveal(dPHand, p)` (`DiscardEffect.java:245`) fans out
to **every** player's controller (`GameAction.java:2243-2248` iterates
`game.getPlayers()`), not just the chooser's. The reveal popup that
appears for opponents and spectators is part of public game information
— it shows up in replays and is visible to network observers. We cannot
simply drop the engine reveal call when consolidating the chooser's UX.

The engine-side change is therefore two-part:

1. Add a chooser-aware variant to `GameAction.reveal(...)` that excludes
   one player from the fan-out (the chooser, who will see the cards
   inline via DR):
   ```java
   public void reveal(CardCollectionView cards, ZoneType zt, Player owner,
                      boolean dontRevealToOwner, String messagePrefix,
                      boolean msgAddSuffix, Player skipChooser);
   ```
   Existing overloads remain. `skipChooser == null` preserves current
   behavior.
2. `DiscardEffect` for `RevealYouChoose` / `RevealTgtChoose` (`:244-261`)
   uses the new overload with `skipChooser = chooser` and passes a
   `DelayedReveal` to `chooseCardsToDiscardFrom`. Opponents and spectators
   still see the public reveal popup; the chooser sees the cards inline
   in their selection dialog.

This requires adding a `DelayedReveal` parameter to
`PlayerController.chooseCardsToDiscardFrom` (the abstract method). AI impl
ignores the parameter (it doesn't render). Test controllers ignore it.

`RevealDiscardAll` (`:209-225`) keeps its existing reveal call unchanged —
the "reveal everything, then discard all of it" flow has no follow-up
selection dialog to fold the chooser's view into.

## File-by-file change list

**New files:**

- `forge-gui/src/main/java/forge/gui/input/router/SelectionRequest.java`
- `forge-gui/src/main/java/forge/gui/input/router/OrderRequest.java`
- `forge-gui/src/main/java/forge/gui/input/router/ManipulateRequest.java`
- `forge-gui/src/main/java/forge/gui/input/router/SelectionResult.java`
- `forge-gui/src/main/java/forge/gui/input/router/TempReveal.java`
- `forge-gui/src/main/java/forge/gui/input/router/InputRouter.java`
- `forge-gui/src/main/java/forge/gui/input/router/HumanInputRouter.java`

**Modified:**

- `forge-gui/src/main/java/forge/gui/interfaces/IGuiGame.java` — add
  `supportsCardClickSelection`.
- `forge-gui-desktop/src/main/java/forge/screens/match/CMatchUI.java` —
  implement `supportsCardClickSelection`; fix DR rendering in
  `chooseSingleEntityForEffect` and `chooseEntitiesForEffect` (resolves
  two `//TODO: Merge this into search dialog`).
- `forge-gui-mobile/src/forge/screens/match/MatchController.java` —
  implement `supportsCardClickSelection`; honor DR in
  `chooseEntitiesForEffect` via tabbed `GameEntityPicker`.
- `forge-gui-mobile/src/forge/card/GameEntityPicker.java` — add
  multi-select support: `min` / `max` constructor params,
  `Consumer<List<GameEntityView>>` callback, tap-toggle interaction
  (gate the current auto-confirm on `maxChoices == 1`), OK-button
  enable/disable based on selection bounds. Underlying `FChoiceList`
  multi-select state is already in place.
- `forge-gui/src/main/java/forge/player/PlayerControllerHuman.java` —
  selection methods shrink to router calls; `useSelectCardsInput` deleted;
  manual tempShow pairs replaced.
- `forge-gui-desktop/src/test/java/forge/gamesimulationtests/util/PlayerControllerForTests.java` —
  implement `supportsCardClickSelection` returning false.
- `forge-gui-desktop/src/test/java/forge/net/HeadlessNetworkGuiGame.java` —
  same.
- `forge-gui/src/main/java/forge/gamemodes/net/server/RemoteClientGuiGame.java` —
  implement `supportsCardClickSelection` against the cached
  `client.isLibgdx()` from the lobby handshake. No protocol change
  required — mobile clients already announce their platform at handshake
  time.
- `forge-game/src/main/java/forge/game/player/PlayerController.java` — add
  `DelayedReveal` parameter to `chooseCardsToDiscardFrom`.
- `forge-ai/src/main/java/forge/ai/PlayerControllerAi.java` — accept and
  ignore the new `DelayedReveal` parameter.
- `forge-game/src/main/java/forge/game/ability/effects/DiscardEffect.java` —
  switch `RevealYouChoose` / `RevealTgtChoose` to the chooser-aware
  reveal overload and pass DR to `chooseCardsToDiscardFrom`.
- `forge-game/src/main/java/forge/game/GameAction.java` — add the
  chooser-aware `reveal(...)` overload that excludes one player from the
  fan-out.

## Behavior compatibility

Behavior is intended to be identical for opponents, spectators, replay
viewers, and network observers — they still see the same public reveal
popup they see today. The only change is to the chooser's own UX:

1. **`RevealYouChoose` / `RevealTgtChoose` discard on the chooser's
   desktop with `UI_SELECT_FROM_CARD_DISPLAYS` on** — the chooser no
   longer sees a separate reveal popup. The revealed hand appears in the
   chooser's selection dialog. Net effect: one user click removed for the
   chooser, no information loss anywhere.
2. **Same flow on the chooser's mobile** — the chooser no longer sees a
   separate reveal popup. The revealed hand appears as a tab in the
   chooser's `GameEntityPicker`. Net effect: one tap removed for the
   chooser, no information loss anywhere.

Everywhere else, the goal is byte-identical UX. If observers notice any
change to the public reveal, or if users notice anything besides the
chooser-side popup removal, treat it as a bug.

## Risks

**The `CMatchUI` DR rendering change is the highest-risk piece.** Two
distinct UI surfaces need to honor DR on desktop:

- **The modal dialog path** (`GuiChoose.order` / `one` / `oneOrNone`):
  the two `//TODO: Merge this into search dialog` comments at
  `CMatchUI.java:1252, :1263` have been there for years. Adding a DR
  panel to the existing Swing modal is mechanically straightforward but
  needs visual design (where in the dialog, how distinguished from
  selectable cards) and has been deferred this long for a reason — likely
  no one has had a clean place to put it. Mitigation: prototype the panel
  early in the PR so review can iterate on it before the surrounding
  refactor lands.
- **The FloatingZone path** (card-click selection): `FloatingZone.java`
  has no prompt header, OK/cancel, or selection-state surface today —
  contrary to what an earlier draft of this spec assumed. The recommended
  approach is to open a separate FloatingZone for the DR cards, labeled
  by zone, alongside the selectable one. The chooser interacts with the
  selectable FloatingZone as normal; the DR FloatingZone is read-only.
  Avoids adding prompt/button infrastructure to FloatingZone in this PR.

**`InputSelectEntitiesFromList` is generic over `<T extends GameEntity>` but
some current callers use `InputSelectCardsFromList` (the `<Card>`-typed
specialization).** The router uses the entities variant uniformly; the
cards specialization is still used directly by paths outside the router
(e.g. `chooseTargets`, the discard-your-own-hand flow). Both classes
continue to exist.

**DR cards are not always a subset of selectable options.** The router
contract has to handle two distinct shapes:

- **DR-as-options-equivalent** (the common case, including the
  `RevealYouChoose` discard scenario this PR motivates): the cards being
  shown for reveal ARE the cards being selected from. DR can be folded
  into the selection display as a unified set.
- **DR-as-context** (`DigEffect.java:201`, `ChangeZoneEffect.java:1057`,
  `ChooseCardEffect.java:257`): DR cards are deliberately a disjoint
  set, shown alongside the selectable options so the chooser has full
  context. For example, in `DigEffect`, the chooser might be picking
  from a `valid`-filtered subset of `top`, with the unfiltered `top` as
  DR context. Card-click selection mode needs to render DR cards as
  visible-but-not-selectable. Mitigation: the FloatingZone approach
  recommended above (separate, read-only DR FloatingZone) handles this
  naturally — DR cards live in their own window and there's no question
  about which set is selectable. The dialog path likewise renders DR as
  a separate panel.

**`DiscardEffect` change touches a hot path.** `RevealYouChoose` is on
Inquisition, Thoughtseize, Mind Warp, and similar high-frequency cards.
Mitigation: ensure the AI controller path (which is most of the test
volume) is exercised in test runs before merging.

## Estimated diff size

| Area | Lines added | Lines removed |
|---|---|---|
| 7 new files in `forge.gui.input.router` (value objects + router) | ~400 | 0 |
| `CMatchUI` — DR panel in `GuiChoose` dialog + DR FloatingZone in card-click path | ~120–180 | ~10 |
| Mobile `GameEntityPicker` multi-select (tap-toggle + OK gating) + `MatchController` routing | ~40–60 | ~5 |
| `IGuiGame` interface + `supportsCardClickSelection` impls on all five `IGuiGame` classes | ~30 | 0 |
| PCH selection methods (full router migration) | ~50–80 | ~200–300 |
| PCH partial-migration methods (scry / surveil / orderMoveToZoneList / contraptions, `TempReveal` only) | ~30 | ~30 |
| PCH `tempShownCards` snapshot/restore helpers | ~20 | 0 |
| Engine-side: `GameAction.reveal` chooser-aware overload, `PlayerController.chooseCardsToDiscardFrom` signature, `PlayerControllerAi`, `DiscardEffect` | ~40 | ~10 |

Rough totals: **~730–840 lines added, ~255–355 lines removed, net delta
~+450 to +550, 13–15 files touched** (7 new, 6–8 modified).

The substantive review surface is concentrated in four places, in order
of risk: the `CMatchUI` DR rendering work (both the `GuiChoose` panel
and the DR FloatingZone, neither of which has precedent in the
codebase), the `GameAction.reveal` chooser-aware overload (because it
touches a network-visible game-state primitive), `HumanInputRouter` and
the `TempReveal` snapshot/restore semantics, and the mobile multi-select
interaction model. Plan for a large refactor PR — the line count is
moderate but several pieces are genuinely new behavior and should be
prototyped early so review can iterate on them.

## Follow-up work

Several adjacent cleanups become trivial after this PR lands and are
called out explicitly so they don't get lost:

- **Prose Javadoc** on `DelayedReveal`, `TempReveal`, the router types,
  and the surviving PCH entry points. Should follow immediately while the
  context is fresh.
- **Rename `Input*` classes** to align with the `CardClickSelection`
  vocabulary. Mechanical.
- **Generalize the chooser dialog path** (`many` / `order` /
  `chooseEntitiesForEffect`) into a single `IGuiGame.openChooserDialog`
  method, collapsing `many` and `chooseEntitiesForEffect` into one GUI
  surface.
- **Confirm-against-card primitive** (below) — removes the rest of the
  `isLibgdxPort()` checks from PCH.

### Confirm-against-card primitive

After this PR, five `isLibgdxPort()` references remain in PCH:

| Line | Method | Pattern |
|---|---|---|
| `:811` | trigger confirm dialog | mobile: card-anchored confirm; desktop: `InputConfirm.confirm` |
| `:1439` | `confirmReplacementEffect` | same |
| `:1707` | `notifyOfValue` | mobile: card-anchored confirm with OK; desktop: `getGui().message(...)` |
| `:1866` | `confirmPayment` | same as `:811` / `:1439` |
| `:2216` | `revealAISkipCards` | mobile: render unplayable list as card images (libgdx can't zoom name lists); desktop: name-list dialog |

Four of the five (`:811`, `:1439`, `:1707`, `:1866`) are doing literally
the same thing: "wrap a confirm or notification in a card-anchored dialog
on mobile, fall back to a generic dialog on desktop." That's a single
missing primitive on `IGuiGame`:

```java
boolean confirmAgainstCard(CardView card, String question,
                            String yesLabel, String noLabel, boolean defaultYes);
void notifyAgainstCard(CardView card, String message);
```

Each `IGuiGame` impl picks how to render: mobile uses its existing
card-anchored layout, desktop renders the equivalent of `InputConfirm`.
PCH's four call sites collapse to one-liners that no longer mention
`isLibgdxPort`.

`:2216` (`revealAISkipCards`) is the odd one out — it's not "render the
same dialog differently," it's "represent the same data differently"
because of a libgdx capability gap (no zoom on text lists). Same fix
shape, different signature: add `gui.revealUnplayableCards(message,
unplayable)` and let each impl choose between rendering a name list and
rendering card images.

Suggested scope: one follow-up PR introducing `confirmAgainstCard` and
`notifyAgainstCard`, migrating the four matching call sites. `:2216`
either rides along (it's small) or gets its own tiny PR.

After both follow-ups, PCH has zero `isLibgdxPort` references. Every
platform decision lives in the `IGuiGame` impl that needs it, which is
the end state the InputRouter refactor was structuring toward.
