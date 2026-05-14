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
  `chooseCardsToDiscardFrom`. The `chooseCardsToDiscardFrom` self-discard
  branch silently forgets the `tempShowCards` call.
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
- Eliminate the asymmetric tempShow usage that's a latent source of bugs.

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

`OrderRequest` — covers `arrangeForScry`, `arrangeForSurveil`,
`orderMoveToZoneList`, the order-the-blockers flow. Fields:
`sourceCards`, `destCards`, `title`, `topCaption`, top/bottom/anywhere
flags, optional reference card.

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
        pch.tempShow(options);
        if (dr != null) pch.tempShow(dr.getCards());
        return new TempReveal(pch);
    }
    @Override public void close() { pch.endTempShowCards(); }
}
```

Replaces every manual `tempShowCards(...) / endTempShowCards()` pair.
Try-with-resources makes the asymmetric `chooseCardsToDiscardFrom`
self-discard bug structurally impossible.

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
  `//TODO: Merge this into search dialog` comments). The FloatingZone now
  has a prompt header and card display; DR cards are shown alongside the
  selectable set in the same UI surface, with the same OK/cancel.
- **`MatchController`** — `chooseEntitiesForEffect` mirrors what
  `chooseSingleEntityForEffect` already does: tabbed `GameEntityPicker`
  with a reveal tab. The existing `GameEntityPicker` is reused; only the
  multi-select tab variant needs adding (uses `FChoiceList` in multi mode).

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

- `chooseCardsForEffect` (`:471`)
- `chooseSingleEntityForEffect` (`:585`)
- `chooseEntitiesForEffect` (`:629`)
- `chooseCardsToDiscardFrom` (`:1142`)
- `chooseCardsToDiscard` self-discard (`:1556`)
- `chooseContraptionsToCrank` (`:514`) — keeps custom logic for the
  cranked/uncranked column split, but the `tempShowCards`/`endTempShowCards`
  pair goes through `TempReveal`
- `arrangeForScry` (`:967`)
- `arrangeForSurveil` (`:1012`)
- `orderMoveToZoneList` (`:1068`)
- `manipulateCardList` (`:944`) / `arrangeForMove` (`:951`)

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

`DiscardEffect` (`forge-game/.../ability/effects/DiscardEffect.java:244-261`)
drops its explicit `game.getAction().reveal(dPHand, p)` call for the
`RevealYouChoose` / `RevealTgtChoose` modes. Instead, it constructs a
`DelayedReveal` for the hand and passes it to `chooseCardsToDiscardFrom`.

This requires adding a `DelayedReveal` parameter to
`PlayerController.chooseCardsToDiscardFrom` (the abstract method). AI impl
ignores the parameter (it doesn't render). Test controllers ignore it.

`RevealDiscardAll` (`:209-225`) keeps its explicit reveal call — the
"reveal everything, then discard all of it" flow has no follow-up
selection dialog to fold the reveal into.

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
- `forge-gui-mobile/src/forge/card/GameEntityPicker.java` — add multi-select
  tab variant (extension of existing single-select pattern).
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
  drop explicit `game.getAction().reveal()` for `RevealYouChoose` /
  `RevealTgtChoose`; pass DR to `chooseCardsToDiscardFrom`.

## Behavior compatibility

Behavior is intended to be identical except in two cases:

1. **`RevealYouChoose` / `RevealTgtChoose` discard on desktop with
   `UI_SELECT_FROM_CARD_DISPLAYS` on** — the separate reveal popup with OK
   button is gone. Revealed hand appears in the FloatingZone alongside the
   selection prompt. Net effect: one user click removed, no information
   loss.
2. **Same flow on mobile** — the separate reveal popup is gone. Revealed
   hand appears as a tab in the `GameEntityPicker`, alongside the
   selectable cards. Net effect: one tap removed, no information loss.

Everywhere else, the goal is byte-identical UX. If users notice anything
besides the discard-flow change, treat it as a bug.

## Risks

**The `CMatchUI` DR rendering change is the highest-risk piece.** The
existing FloatingZone needs to host both the selectable set and the DR
cards in a way that's visually clear about which is which. Mitigation:
render DR cards in a separate FloatingZone tile (re-using the
already-extended FloatingZone with prompt headers from the prior PR), or as
a labeled section within the same window. Decide based on what reads more
naturally in playtesting.

**`InputSelectEntitiesFromList` is generic over `<T extends GameEntity>` but
some current callers use `InputSelectCardsFromList` (the `<Card>`-typed
specialization).** The router uses the entities variant uniformly; the
cards specialization is still used directly by paths outside the router
(e.g. `chooseTargets`, the discard-your-own-hand flow). Both classes
continue to exist.

**`DiscardEffect` change touches a hot path.** `RevealYouChoose` is on
Inquisition, Thoughtseize, Mind Warp, and similar high-frequency cards.
Mitigation: ensure the AI controller path (which is most of the test
volume) is exercised in test runs before merging.

## Test plan

- Existing test suite under
  `forge-gui-desktop/src/test/java/forge/gamesimulationtests/` should pass
  unchanged. `PlayerControllerForTests` returning false for
  `supportsCardClickSelection` means tests go through the dialog path,
  which is what they already do.
- Manual play test on desktop:
  - Cast Inquisition / Thoughtseize / Mind Warp on opponent. Confirm: no
    separate reveal popup; hand appears in FloatingZone; selection works
    by clicking; Ctrl+1-9 hotkeys work; cancel/ok behave correctly.
  - Cast an effect using DR (Demonic Tutor, fetchlands, scry-and-search
    effects). Confirm DR cards appear in the selection dialog rather than
    a separate popup.
  - Scry / Surveil / Reorder graveyard. Confirm no regression.
  - Discard from own hand. Confirm no regression.
  - Toggle `UI_SELECT_FROM_CARD_DISPLAYS` off. Confirm fallback to dialog
    selection for non-default zones.
- Manual play test on mobile:
  - Same Inquisition-style flow. Confirm hand appears as a tab in
    `GameEntityPicker`; selection works; OK/cancel behave correctly.
  - DR-using effects: confirm DR cards appear as a tab in the picker.
  - Cast self-affecting effects (own discard, scry, surveil). Confirm no
    regression.
- Network play smoke test:
  - One desktop client, one network client. Run a discard effect. Confirm
    no protocol errors; confirm the network client gets a usable prompt
    even if its capability defaults to dialog selection.

## Estimated diff size

| Area | Lines added | Lines removed |
|---|---|---|
| 7 new files in `forge.gui.input.router` (value objects + router) | ~400 | 0 |
| `CMatchUI` DR rendering (resolves both TODOs) | ~50–100 | ~10 |
| Mobile `GameEntityPicker` multi-select tab + `MatchController` DR honoring | ~40–70 | ~5 |
| `IGuiGame` interface + capability impls on all five `IGuiGame` classes | ~30 | 0 |
| PCH selection/order/manipulate methods | ~60–90 | ~300–500 |
| Engine-side (`PlayerController`, `PlayerControllerAi`, `DiscardEffect`) | ~15 | ~5 |

Rough totals: **~600–700 lines added, ~320–520 lines removed, net delta
~+200 to +300, 12–14 files touched** (7 new, 5–7 modified).

Most of the line count is boilerplate — value objects with named fields,
ceremonial signature updates across `IGuiGame` implementors. The
substantive review surface is small: `HumanInputRouter` (~100 lines), the
`CMatchUI` DR rendering integration with FloatingZone, the mobile
multi-select picker, and the `DiscardEffect` engine-side change. Plan for
a medium-large refactor PR; expect reviewer time to concentrate on those
four areas.

## Open questions

- **Should `GameEntityPicker` multi-select reuse the same `FChoiceList`
  widget as single-select, or extend?** Depends on whether `FChoiceList`
  supports a real multi-mode or only single. Probably needs a small
  widget-level change; size is unknown until someone reads the mobile
  toolbox more carefully.
- **Should `OrderRequest` and `ManipulateRequest` share a base class?**
  They have overlapping fields (`title`, reference card). Probably not
  worth abstracting until a third order-like request appears.

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
