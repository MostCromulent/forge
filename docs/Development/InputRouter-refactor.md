# Selection dispatch cleanup

## Summary

`PlayerControllerHuman` (PCH) hand-rolls the card-click-vs-dialog
selection decision in ~10 places, each repeating the same
`tempShowCards / build Input / showAndWait / endTempShowCards` shape and
each independently making a platform check
(`isLibgdxPort()` + `UI_SELECT_FROM_CARD_DISPLAYS`). The PR discussion
about consolidating opponent-hand reveal+discard surfaced a downstream
symptom: `DelayedReveal`'s "inline-reveal-into-selection" contract is
honored by only one of four `IGuiGame` selection implementations,
because PCH never gave them a clean way to.

This refactor consolidates the dispatch into a single PCH helper,
finishes the `DelayedReveal` (DR) contract on desktop, and pushes
platform branching out of PCH into `IGuiGame` via one new predicate.
**No new package, no router class, no request value objects** — just a
private dispatch helper and a small `AutoCloseable`.

Scope is one PR, bounded to PCH's selection / order / manipulate
surface. The `Input*` subsystem, mulligan, attack/block, mana payment,
replacement effects, and dev-mode tooling are out of scope.

## Motivation

Concrete costs of the current mixing:

- PCH is 3572 lines; ~25% is selection routing. The same
  `tempShowCards / build Input / showAndWait / endTempShowCards` pattern
  repeats across `chooseCardsForEffect`, `chooseSingleEntityForEffect`,
  `chooseEntitiesForEffect`, `chooseCardsToDiscardFrom`. Each call site
  hand-rolls the pairing and the pairings aren't uniform —
  `chooseCardsToDiscardFrom`'s self-discard branch skips the temp-show
  call, defensible today (cards are in own hand) but fragile if extended.
- `DelayedReveal`'s Javadoc says it lets revealed cards appear in the
  same dialog as cards being selected. Three of four `IGuiGame` selection
  implementations don't honor that — they call a separate `reveal(...)`
  popup and then open the picker. `CMatchUI` carries two
  `//TODO: Merge this into search dialog` comments acknowledging this.
  The engine compensates with its own `game.getAction().reveal(...)`
  before the selection, producing the two-stage UX.
- 14 `isLibgdxPort()` / preference reads inside PCH; 157 `getGui()`
  calls. The controller knows which platform it is on — a layering smell.

## Goals

- Make `DelayedReveal` a contract that desktop honors fully (both
  card-click and dialog paths). Mobile single-pick already honors DR
  via `GameEntityPicker`'s tab; mobile multi-pick continues to fall
  back to a public reveal in this PR (see Non-Goals).
- Move the card-click-vs-dialog decision out of PCH's per-method bodies
  into one private helper that asks `IGuiGame` a single capability
  question.
- Push every selection-related `isLibgdxPort()` check and preference
  read out of PCH into the `IGuiGame` impls.
- Make the temp-show contract uniform across selection methods.

## Non-Goals

- **A separate router class or package.** The dispatch helper lives
  inside PCH as a private method until there's a second consumer that
  would justify extracting it.
- **Request value objects.** Selection arguments stay as method
  parameters; no `SelectionRequest` / `OrderRequest` / `ManipulateRequest`
  scaffolding.
- **Honoring DR for mobile multi-pick.** The libgdx order widget would
  need a new surface for DR cards alongside its source/dest columns,
  and that's a mobile UX change with no obvious right answer. Mobile
  keeps the current two-stage UX for `RevealYouChoose` /
  `RevealTgtChoose` discard. Listed as follow-up.
- Shrinking PCH overall. The refactor removes ~250–350 lines of
  selection boilerplate but doesn't touch lifecycle, network sync,
  mulligan, etc.
- Renaming `Input*` classes; changing the AI controller; generalizing
  `IGuiGame.many(...)` for non-`GameEntity` callers.

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

### DR honoring on desktop

`chooseSingleEntityForEffect` / `chooseEntitiesForEffect` on `CMatchUI`
keep their signatures but their implementations change to actually
honor DR, resolving the two `//TODO: Merge this into search dialog`
comments at `:1252, :1263`. These refer to the modal `GuiChoose` dialog,
**not** `FloatingZone`. Two desktop surfaces need work:

- **Dialog path** (`GuiChoose.order` / `one` / `oneOrNone`): add a DR
  cards panel to the existing Swing modal.
- **Card-click path** (FloatingZone + `InputSelectEntitiesFromList`):
  the selection itself doesn't need any new UI —
  `InputSelectEntitiesFromList` auto-completes when min/max bounds are
  satisfied, and Esc/right-click cancels via the existing prompt area,
  so FloatingZone needs no prompt/OK/cancel surface. Open a separate
  read-only DR FloatingZone alongside the selectable one, labeled by
  zone. Both are passive displays; the selection auto-resolves.

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

Helper is private and untyped at its callers' generic boundary — each
PCH method passes its own `T extends GameEntity`. No platform check,
no preference read, no `Input*`-vs-`getGui()` decision inside the
calling methods.

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

- `chooseContraptionsToCrank` (`:514`) — cranked/uncranked column split
  too specialized to flatten.
- `arrangeForScry` (`:967`), `arrangeForSurveil` (`:1012`),
  `orderMoveToZoneList` (`:1068`) — chain selection → ordering →
  preference branches that don't usefully flatten (size==1 special
  cases via `willPutCardOnTop` / `InputConfirm.confirm`, 12-way
  `ZoneType` switch with `topOfDeck` reverse logic). They keep custom
  logic but use `TempReveal` and route their inner `getGui().order(...)`
  / `getGui().many(...)` calls through a thinner local helper that asks
  `gui.supportsCardClickSelection`.
- `manipulateCardList` (`:944`) / `arrangeForMove` (`:951`) — could
  fit a dispatch helper of their own, but the simpler version of this
  refactor keeps them as-is + `TempReveal`.

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

Solution: add a chooser-aware overload that excludes one player from the
fan-out:

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

In both cases DR is passed to `chooseCardsToDiscardFrom`. Desktop chooser
sees the cards inline (no popup). Mobile chooser sees the popup, and
the DR is ignored by mobile multi-pick (today's behavior).

Adds a `DelayedReveal` parameter to
`PlayerController.chooseCardsToDiscardFrom` (abstract). AI and test
impls ignore it.

`RevealDiscardAll` (`:209–225`) keeps its existing reveal — no
follow-up selection dialog to fold the chooser's view into.

## File-by-file change list

No new files. All changes are modifications:

- `IGuiGame.java` — add `supportsCardClickSelection`.
- `CMatchUI.java` — implement predicate; DR rendering in
  `chooseSingleEntityForEffect`/`chooseEntitiesForEffect` (dialog +
  sibling-FloatingZone paths).
- `MatchController.java` — implement predicate only.
- `PlayerControllerHuman.java` — add `dispatchSelection`,
  `TempReveal` (private static), `snapshotTempShown` /
  `restoreTempShown`; collapse selection methods to wrappers; partial
  migration for scry/surveil/orderMoveToZoneList/contraptions; delete
  `useSelectCardsInput`.
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
desktop chooser UX: in `RevealYouChoose` / `RevealTgtChoose` discard,
the desktop chooser no longer sees a separate reveal popup; revealed
cards appear inline in the selection dialog (or sibling FloatingZone
for the card-click path). One click removed for desktop, no
information loss anywhere. Any other UX change is a bug.

## Risks

- **`CMatchUI` DR rendering is the highest-risk piece.** Two desktop
  surfaces need work: a DR panel inside the `GuiChoose` modal (the
  long-standing TODO) and a read-only DR FloatingZone alongside the
  selectable one. Card-click side is cheap (FloatingZone is already
  a passive display, no new prompt surface needed); the `GuiChoose`
  panel needs visual design. Mitigation: prototype the panel early so
  review can iterate before the surrounding refactor lands.
- **DR-as-context vs DR-as-options.** DR cards aren't always a subset
  of selectable options — `DigEffect.java:201`,
  `ChangeZoneEffect.java:1057`, `ChooseCardEffect.java:257` pass
  disjoint DR sets so the chooser has context. The
  separate-DR-FloatingZone / separate-DR-panel approach handles this
  naturally.
- **`DiscardEffect` change touches a hot path** (Inquisition,
  Thoughtseize, Mind Warp). Mitigation: exercise the AI controller path
  (most of test volume) before merging.

## Estimated diff size

| Area | Added | Removed |
|---|---|---|
| `IGuiGame` interface + 5 impls of `supportsCardClickSelection` | ~30 | 0 |
| `CMatchUI` DR rendering (GuiChoose panel + sibling FloatingZone) | ~70–120 | ~10 |
| `MatchController` predicate | ~5 | 0 |
| PCH: `dispatchSelection`, `TempReveal`, snapshot/restore, collapsed selection methods | ~150 | ~250–350 |
| PCH partial migration (scry / surveil / orderMoveToZoneList / contraptions) | ~30 | ~30 |
| Engine-side (`GameAction.reveal` overload, `PlayerController`, `PlayerControllerAi`, `DiscardEffect`) | ~40 | ~10 |

Rough totals: **~325–375 added, ~300–400 removed, net ~−75 to +75,
9–10 files modified, 0 new files.**

Substantive review surface concentrates in three places, by risk:
`CMatchUI` DR rendering, the `GameAction.reveal` chooser-aware overload
(network-visible primitive), and `dispatchSelection` + `TempReveal`
reentrancy. Prototype these early so review can iterate.

## Follow-up work

- **Honor DR in mobile multi-pick.** Extend the libgdx order widget
  (or a wrapper) to display DR cards alongside its source/dest
  columns — third column, popover, or "Revealed" toggle. After that
  lands, `DiscardEffect`'s `!isLibgdxPort()` gate can be dropped.
- **Prose Javadoc** on `DelayedReveal`, the dispatch helper,
  `TempReveal`, and surviving PCH entry points.
- **Confirm-against-card primitive** (below) — removes the rest of the
  `isLibgdxPort()` checks from PCH outside the selection path.
- **Extract `dispatchSelection` into its own class** if/when a second
  consumer appears (e.g. when starting on `HumanConfirmationRouter` per
  the broader PCH-decomposition vision). Mechanical refactor at that
  point.
- **Generalize the chooser dialog path** (`many` / `order` /
  `chooseEntitiesForEffect`) into a single
  `IGuiGame.openChooserDialog` method.

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
