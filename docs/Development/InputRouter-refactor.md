# Selection dispatch cleanup

## Summary

`PlayerControllerHuman` (PCH) hand-rolls the card-click-vs-dialog
selection decision in ~10 places, each repeating the same
`tempShowCards / build Input / showAndWait / endTempShowCards` shape and
each independently making a platform check
(`isLibgdxPort()` + `UI_SELECT_FROM_CARD_DISPLAYS`). The PR discussion
about consolidating opponent-hand reveal+discard surfaced a downstream
symptom: the engine emits a separate `reveal(...)` popup before the
selection because no path could fold the reveal into the picker.

This refactor consolidates the dispatch into one private PCH helper,
adds a single capability predicate on `IGuiGame`, plumbs `DelayedReveal`
through the discard flow now that the underlying widgets honor it, and
makes the temp-show contract uniform. **No new package, no router class,
no value objects** — a private helper plus a small `AutoCloseable`.

## Prerequisites

Two adjacent pieces of work do the rendering this spec depends on:

- **PR #10660 (merged): opponent-hand reveal+discard via FloatingZone.**
  The card-click selection path on desktop already shows DR cards as a
  consequence of FloatingZone being a passive zone display:
  `InputSelectEntitiesFromList` is the selection gate, FloatingZone
  shows the relevant zone, and DR cards (which for the discard use case
  live in that same zone) are visible without any explicit DR rendering.
  This spec does not touch FloatingZone or the card-click path.
- **DualCardBox refactor** (see `dual-card-box-refactor.md`). Replaces
  `DualListBox` with a card-grid widget behind a new
  `IGuiGame.chooseCardPiles` method. `getGui().many(...)` (the dialog
  primitive used by `chooseCardsToDiscardFrom` and friends) routes
  through `chooseCardPiles` after that lands. The widget already
  supports a per-card "draggable" gate via `ListCardArea`'s
  `cardPanelDraggable` override — so DR honoring on the dialog path is
  *source pile = options ∪ DR.cards; draggable = options*, with no new
  widget infrastructure. Both DR shapes (DR-as-options where they're
  equal; DR-as-context where options ⊆ DR.cards) collapse onto the same
  gate.

Order of landing: this spec assumes DualCardBox is in or close behind.
If DualCardBox slips, the engine-side reveal change still works (mobile
keeps today's UX; desktop chooser gets the popup removed but the DR
inline rendering depends on DualCardBox's widget for the
`chooseCardsToDiscardFrom` path).

Scope is one PR, bounded to PCH's selection surface. The `Input*`
subsystem, mulligan, attack/block, mana payment, replacement effects,
and dev-mode tooling are out of scope.

## Motivation

Concrete costs of the current mixing:

- PCH is 3572 lines; ~25% is selection routing. The same
  `tempShowCards / build Input / showAndWait / endTempShowCards` pattern
  repeats across `chooseCardsForEffect`, `chooseSingleEntityForEffect`,
  `chooseEntitiesForEffect`, `chooseCardsToDiscardFrom`. Each call site
  hand-rolls the pairing and the pairings aren't uniform —
  `chooseCardsToDiscardFrom`'s self-discard branch skips the temp-show
  call, defensible today (cards are in own hand) but fragile if extended.
- The engine emits its own `game.getAction().reveal(...)` before each
  `RevealYouChoose` / `RevealTgtChoose` selection, producing a two-stage
  UX. With DualCardBox honoring DR inline on the dialog path and PR
  #10660 already covering the card-click path, the engine no longer
  needs to issue a separate chooser-facing reveal for these effects on
  desktop.
- 14 `isLibgdxPort()` / preference reads inside PCH; 157 `getGui()`
  calls. The controller knows which platform it is on — a layering smell.

## Goals

- Move the card-click-vs-dialog decision out of PCH's per-method bodies
  into one capability predicate on `IGuiGame`.
- Remove the separate engine-side reveal popup for desktop choosers in
  `RevealYouChoose` / `RevealTgtChoose` discard (mobile keeps today's
  two-stage UX).
- Make the temp-show contract uniform across selection methods via a
  private `TempReveal` AutoCloseable, with snapshot/restore so the
  router can run nested in an outer engine temp-show scope.

## Non-Goals

- **A separate router class or package.** The dispatch helper lives
  inside PCH as a private method until there's a second consumer that
  would justify extracting it.
- **Request value objects.** Selection arguments stay as method
  parameters.
- **DR rendering for non-discard call sites.** `chooseSingleEntityForEffect`
  (used by tutors, fetches) and `chooseEntitiesForEffect` on desktop
  today render via `getGui().one` / `oneOrNone` / `order`. DualCardBox
  explicitly keeps these separate (text-list payloads can be `PlayerView`,
  not just cards). They retain today's two-stage UX —
  `reveal(...)` popup then picker — until a follow-up routes them
  through a DR-aware widget.
- **Honoring DR for mobile multi-pick.** Listed as follow-up.
- Shrinking PCH overall; renaming `Input*` classes; changing the AI
  controller; generalizing `IGuiGame.many(...)` for non-`GameEntity`
  callers.

## Design

### IGuiGame predicate

One new method:

```java
boolean supportsCardClickSelection(Set<ZoneType> zones);
```

Asks: "can the user satisfy a selection prompt by interacting with
cards already visible on screen in these zones?" "Click" is shorthand —
taps on mobile, mouse clicks on desktop, FloatingZone keyboard hotkeys
all qualify.

| Impl | Returns |
|---|---|
| `CMatchUI` (desktop) | true if zones are Battlefield/own Hand, OR `UI_SELECT_FROM_CARD_DISPLAYS` is on and every zone is FloatingZone-openable (Battlefield/Hand/Library/Graveyard/Exile/Flashback/Command/Sideboard) |
| `MatchController` (mobile) | true only if every zone is Battlefield or own Hand |
| `PlayerControllerForTests`, `HeadlessNetworkGuiGame` | false |
| `RemoteClientGuiGame` | desktop or mobile predicate against cached `client.isLibgdx()` from lobby handshake; no new protocol |

### TempReveal

Private static class inside PCH:

```java
private static final class TempReveal implements AutoCloseable {
    private final PlayerControllerHuman pch;
    private final List<Card> outerScope;

    static TempReveal open(PlayerControllerHuman pch,
                           Iterable<? extends GameEntity> options,
                           DelayedReveal dr) {
        List<Card> outerScope = pch.snapshotTempShown();
        pch.tempShow(options);
        if (dr != null) pch.tempShow(dr.getCards());
        return new TempReveal(pch, outerScope);
    }
    public void close() {
        pch.endTempShowCards();
        pch.restoreTempShown(outerScope);
    }
}
```

The snapshot/restore is required because `PCH.tempShownCards` (`:156`)
is a single flat list and `endTempShowCards()` (`:185-193`) clears
unconditionally; engine effects outside the selection path also call
`tempShowCards` (`ChangeZoneEffect`, `PlayEffect`, `DigEffect`), and a
selection running inside an outer engine scope would otherwise wipe the
outer cards on close. `snapshotTempShown()` / `restoreTempShown(...)`
are added to PCH for this purpose. (Long-term, turning `tempShownCards`
into a counted set or stack would let callers freely nest — out of
scope here.)

### Dispatch helper

One private method in PCH replaces the duplicated tempShow / build-input
/ showAndWait / endTempShow / fallback-to-dialog pattern:

```java
private <T extends GameEntity> List<T> dispatchSelection(
        FCollectionView<T> options, int min, int max, String title,
        DelayedReveal dr, SpellAbility sa, Player targetedPlayer,
        boolean cancellable) {
    if (options.isEmpty()) {
        if (dr != null) reveal(dr);
        return List.of();
    }
    if (!cancellable && options.size() == 1) {
        if (dr != null) reveal(dr);
        return List.of(Iterables.getFirst(options, null));
    }
    String message = MessageUtil.formatMessage(title, player, targetedPlayer);
    try (TempReveal tr = TempReveal.open(this, options, dr)) {
        if (getGui().supportsCardClickSelection(zonesOf(options))) {
            InputSelectEntitiesFromList<T> input = new InputSelectEntitiesFromList<>(
                this, min, max, options, sa);
            input.setMessage(message);
            input.setCancelAllowed(cancellable);
            input.showAndWait();
            return new ArrayList<>(input.getSelected());
        }
        // Dialog path. For min == 1 == max, routes to single-entity picker
        // (text-list; DR popup remains until DR-aware widget covers this).
        // For multi-pick, routes to chooseEntitiesForEffect → order →
        // DualCardBox-backed chooseCardPiles, where DR is honored via the
        // source-pile draggability gate.
        GameEntityViewMap<T, GameEntityView> map = GameEntityView.getMap(options);
        if (min == 1 && max == 1) {
            GameEntityView v = getGui().chooseSingleEntityForEffect(
                message, map.getTrackableKeys(), dr, cancellable);
            return v == null ? List.of() : List.of(map.get(v));
        }
        List<GameEntityView> vs = getGui().chooseEntitiesForEffect(
            message, map.getTrackableKeys(), min, max, dr);
        return map.addToList(vs, new ArrayList<>());
    }
}
```

Helper is private; each PCH method passes its own `T extends GameEntity`.
No platform check, no preference read, no `Input*`-vs-`getGui()`
decision inside the calling methods.

### PCH method shapes after migration

Each selection method becomes a 2–4 line wrapper. Example —
`chooseSingleEntityForEffect`, currently 42 lines (`:585–626`):

```java
@Override
public <T extends GameEntity> T chooseSingleEntityForEffect(
        FCollectionView<T> options, DelayedReveal dr, SpellAbility sa,
        String title, boolean isOptional, Player targetedPlayer,
        Map<String,Object> params) {
    List<T> r = dispatchSelection(options, 1, 1, title, dr, sa, targetedPlayer, isOptional);
    return r.isEmpty() ? null : r.get(0);
}
```

**Full migration** (methods that become wrappers):

- `chooseCardsForEffect` (`:471`)
- `chooseSingleEntityForEffect` (`:585`)
- `chooseEntitiesForEffect` (`:629`)
- `chooseCardsToDiscardFrom` (`:1142`)
- `chooseCardsToDiscard` self-discard (`:1556`)

**Partial migration** (`TempReveal` only; custom logic stays):

- `orderMoveToZoneList` (`:1068`) — 12-way `ZoneType` switch with
  preference-driven graveyard early-return and `topOfDeck` reverse
  logic. Doesn't usefully flatten; keeps custom logic but uses
  `TempReveal` and asks `gui.supportsCardClickSelection` directly.

(Other methods I originally listed here — `arrangeForScry`,
`arrangeForSurveil`, `chooseContraptionsToCrank`, `manipulateCardList` —
are migrated by DualCardBox onto `chooseCardPiles`, not by this PR.)

**Removed:** `useSelectCardsInput` (both overloads, `:429–469`) —
replaced by `gui.supportsCardClickSelection`. Manual
`tempShowCards`/`endTempShowCards` pairs in migrated call sites —
replaced by `TempReveal`. Private `tempShow(...)` overloads stay
(still called by `TempReveal` and engine-side callers).

`isLibgdxPort()` checks at `:811, :1439, :1707, :1866, :2216` are
**not** in the selection path; they stay in PCH for this PR. See the
confirm-against-card follow-up.

### Engine-side change

`game.getAction().reveal()` (`DiscardEffect.java:245`) fans out to every
player's controller (`GameAction.java:2243–2248`), not just the
chooser's. Opponents, spectators, replay viewers, and network observers
see the reveal popup as public game information. Dropping the engine
call would silently regress that.

Solution: add a chooser-aware overload that excludes one player from
the fan-out:

```java
public void reveal(CardCollectionView cards, ZoneType zt, Player owner,
                   boolean dontRevealToOwner, String messagePrefix,
                   boolean msgAddSuffix, Player skipChooser);
```

Existing overloads remain. `DiscardEffect` for `RevealYouChoose` /
`RevealTgtChoose` (`:244–261`) uses the overload with
`skipChooser = chooser` **only when the chooser is on desktop**
(`!chooser.getController().getGui().isLibgdxPort()`). For mobile
choosers, the existing reveal-everyone path runs and mobile sees today's
two-stage UX. The platform check is a single conditional; no capability
predicate (it would degenerate to `!isLibgdxPort()` after the
mobile-DR descope).

In both cases DR is passed to `chooseCardsToDiscardFrom`. Desktop
chooser sees the cards inline via DualCardBox's source-pile gate. Mobile
chooser sees the public reveal popup, and the DR is ignored by mobile
multi-pick (today's behavior).

Adds a `DelayedReveal` parameter to
`PlayerController.chooseCardsToDiscardFrom` (abstract). AI and test
impls ignore it.

`RevealDiscardAll` (`:209–225`) keeps its existing reveal — no
follow-up selection dialog to fold the chooser's view into.

## File-by-file change list

No new files. All changes are modifications:

- `IGuiGame.java` — add `supportsCardClickSelection`.
- `CMatchUI.java` — implement predicate. (No DR rendering work here —
  PR #10660 covers card-click; DualCardBox covers the dialog widget.)
- `MatchController.java` — implement predicate.
- `PlayerControllerHuman.java` — add `dispatchSelection`,
  `TempReveal` (private static), `snapshotTempShown` /
  `restoreTempShown`; collapse selection methods to wrappers; partial
  migration for `orderMoveToZoneList`; delete `useSelectCardsInput`.
- `PlayerControllerForTests.java`, `HeadlessNetworkGuiGame.java` —
  predicate returns false.
- `RemoteClientGuiGame.java` — predicate against cached
  `client.isLibgdx()`. No protocol change.
- `PlayerController.java` — DR param on `chooseCardsToDiscardFrom`.
- `PlayerControllerAi.java` — accept and ignore the new param.
- `DiscardEffect.java` — `RevealYouChoose`/`RevealTgtChoose` use the
  chooser-aware reveal (gated on `!isLibgdxPort()`) and pass DR.
- `GameAction.java` — chooser-aware `reveal(...)` overload.

## Behavior compatibility

Opponents, spectators, replay viewers, and network observers see
byte-identical behavior — same public reveal popup as today. Mobile
choosers also see today's behavior unchanged. The only change is
desktop chooser UX in `RevealYouChoose` / `RevealTgtChoose` discard:
the chooser no longer sees a separate reveal popup; revealed cards
appear inline in the selection (FloatingZone for the card-click path,
DualCardBox source pile for the dialog path). One click removed for
desktop chooser, no information loss anywhere.

Other DR-using effects on desktop (tutors, fetches, scry-and-search via
`chooseSingleEntityForEffect`) keep today's two-stage UX —
`reveal(...)` popup then picker — until a follow-up routes them
through a DR-aware widget. Any other UX change is a bug.

## Risks

- **`DiscardEffect` change touches a hot path** (Inquisition,
  Thoughtseize, Mind Warp). Mitigation: exercise the AI controller path
  (most of test volume) before merging.
- **`TempReveal` reentrancy.** Engine effects (`ChangeZoneEffect`,
  `PlayEffect`, `DigEffect`) call `tempShowCards` outside the selection
  path; a router selection running inside one of those scopes uses
  `snapshotTempShown` / `restoreTempShown` to preserve the outer
  scope's cards. Mitigation: integration test that nests a router
  selection inside a `ChangeZoneEffect` temp-show.
- **DualCardBox sequencing.** This spec assumes DualCardBox lands first
  (or alongside). If DualCardBox slips, the engine-side reveal change
  still works on desktop for `chooseCardsToDiscardFrom` — but the
  inline DR rendering for that path depends on DualCardBox. If
  sequencing breaks, fall back to keeping today's reveal-everyone path
  for `RevealYouChoose` / `RevealTgtChoose` until DualCardBox catches
  up. The router predicate and helper are independent and can land
  without DualCardBox.

## Estimated diff size

| Area | Added | Removed |
|---|---|---|
| `IGuiGame` interface + 5 impls of `supportsCardClickSelection` | ~30 | 0 |
| `CMatchUI` predicate impl | ~10 | 0 |
| `MatchController` predicate impl | ~5 | 0 |
| PCH: `dispatchSelection`, `TempReveal`, snapshot/restore, collapsed selection methods | ~150 | ~250–350 |
| PCH `orderMoveToZoneList` partial migration | ~10 | ~10 |
| Engine-side (`GameAction.reveal` overload, `PlayerController`, `PlayerControllerAi`, `DiscardEffect`) | ~40 | ~10 |

Rough totals: **~245–255 added, ~270–370 removed, net ~−25 to −115,
8–9 files modified, 0 new files.**

Substantive review surface: `dispatchSelection` + `TempReveal`
reentrancy, the `GameAction.reveal` chooser-aware overload
(network-visible primitive), and the engine-side libgdx gate in
`DiscardEffect`. No new UI widget work in this PR — that's all
DualCardBox.

## Follow-up work

- **Honor DR in mobile multi-pick.** Extend the libgdx order widget
  (or a wrapper) to display DR cards alongside its source/dest
  columns. After that lands, `DiscardEffect`'s `!isLibgdxPort()` gate
  can be dropped.
- **DR rendering for non-discard `chooseSingleEntityForEffect` /
  `chooseEntitiesForEffect` callers** (tutors, fetches, etc.). Once
  the appropriate widget exists, those methods can be routed through
  it and their two-stage UX collapses. May naturally fall out of
  DualCardBox's longer-term consolidation.
- **Prose Javadoc** on `DelayedReveal`, the dispatch helper,
  `TempReveal`, and surviving PCH entry points.
- **Confirm-against-card primitive** (below) — removes the rest of the
  `isLibgdxPort()` checks from PCH outside the selection path.
- **Extract `dispatchSelection` into its own class** if/when a second
  consumer appears (e.g. `HumanConfirmationRouter` per the broader
  PCH-decomposition vision).

### Confirm-against-card primitive

Five `isLibgdxPort()` references remain in PCH after this PR:

| Line | Method | Pattern |
|---|---|---|
| `:811` | trigger confirm | mobile: card-anchored confirm; desktop: `InputConfirm.confirm` |
| `:1439` | `confirmReplacementEffect` | same |
| `:1707` | `notifyOfValue` | mobile: card-anchored confirm with OK; desktop: `getGui().message(...)` |
| `:1866` | `confirmPayment` | same as `:811` / `:1439` |
| `:2216` | `revealAISkipCards` | mobile: card images (libgdx can't zoom name lists); desktop: name-list dialog |

Four share one shape — wrap a confirm/notify in a card-anchored dialog
on mobile, generic dialog on desktop. One follow-up PR adds:

```java
boolean confirmAgainstCard(CardView card, String question,
                            String yesLabel, String noLabel, boolean defaultYes);
void notifyAgainstCard(CardView card, String message);
```

…and migrates those four call sites. `:2216` is a different shape
(different data representation) — fix with
`gui.revealUnplayableCards(message, unplayable)` letting each impl
choose name list vs card images.

After both follow-ups, PCH has zero `isLibgdxPort` references — every
platform decision lives in the `IGuiGame` impl that needs it.
