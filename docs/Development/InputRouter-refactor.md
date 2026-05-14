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
  card-click and dialog paths). Mobile single-pick already honors DR via
  `GameEntityPicker`'s tab; mobile multi-pick continues to fall back to a
  public reveal in this PR (see Non-Goals).
- Move the card-click-vs-dialog decision out of PCH into an `InputRouter`
  that asks the GUI a single capability question.
- Push every selection-related `isLibgdxPort()` check and preference read
  out of PCH into the `IGuiGame` impls.
- Make the temp-show contract uniform across selection methods so the
  asymmetric patterns can't drift further.

## Non-Goals

- Shrinking PCH overall. The refactor removes ~200–300 lines of selection
  boilerplate; lifecycle, network sync, mana payment, mulligan, etc. are
  untouched.
- **Honoring DR for mobile multi-pick.** The libgdx order widget would
  need a new surface for DR cards alongside its source/dest columns, and
  that's a mobile UX change with no obvious right answer. Mobile keeps
  the current two-stage popup-then-picker for `RevealYouChoose` /
  `RevealTgtChoose` discard; the chooser-aware engine reveal is gated on
  `!isLibgdxPort()` so mobile users don't lose information. Listed as
  follow-up work.
- Renaming `Input*` classes. The subsystem keeps its current names.
- Changing the AI controller. `PlayerControllerAi` doesn't go through the
  router; `PlayerController` abstract shape is unchanged.
- Generalizing `IGuiGame.many(...)` for non-`GameEntity` callers (dice,
  deck pools).

## Design

### Request value objects

New package `forge.gui.input.router` in `forge-gui` holds three request
shapes plus a result type.

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

`OrderRequest` covers the simple ordering case (order-the-blockers,
sideboard). Fields: `sourceCards`, `destCards`, `title`, `topCaption`,
optional reference card. **Not** used for `arrangeForScry` /
`arrangeForSurveil` / `orderMoveToZoneList` — see PCH changes.

`ManipulateRequest` covers `manipulateCardList` / `arrangeForMove`.
Fields: `title`, `cards`, `manipulable`, `toTop`, `toBottom`, `toAnywhere`.

`SelectionResult<T>` = `{ List<T> selected; boolean cancelled }`.
`cancelled` is distinct from "selected an empty set" so optional prompts
are unambiguous.

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

Replaces every manual `tempShowCards / endTempShowCards` pair. The
snapshot/restore is required because `PCH.tempShownCards` (`:156`) is a
single flat list and `endTempShowCards()` (`:185-193`) clears
unconditionally; engine effects outside the selection path also call
`tempShowCards` (`ChangeZoneEffect`, `PlayEffect`, `DigEffect`), and a
router selection running inside an outer engine scope would otherwise
wipe the outer cards on close. `snapshotTempShown()` /
`restoreTempShown(...)` are added to PCH for this purpose. (Long-term,
turning `tempShownCards` into a counted set or stack would let
PCH-side and engine-side callers freely nest — out of scope here.)

### Router

```java
public interface InputRouter {
    <T extends GameEntity> SelectionResult<T> select(SelectionRequest<T> req);
    List<Card> order(OrderRequest req);
    ImmutablePair<CardCollection, CardCollection> manipulate(ManipulateRequest req);
}
```

```java
public final class HumanInputRouter implements InputRouter {
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
    // runCardClickSelection: build InputSelectEntitiesFromList, showAndWait
    // runDialogSelection:    gui.chooseSingleEntityForEffect / chooseEntitiesForEffect with DR
}
```

The router doesn't know which platform it's on, doesn't read preferences,
and doesn't construct `Input*` objects for any reason other than
dispatch.

### IGuiGame additions

One new method:

```java
boolean supportsCardClickSelection(Set<ZoneType> zones);
```

Asks: "can the user satisfy a selection prompt by interacting with cards
already visible on screen in these zones?" "Click" is shorthand — taps on
mobile, mouse clicks on desktop, FloatingZone keyboard hotkeys all
qualify.

| Impl | Returns |
|---|---|
| `CMatchUI` (desktop) | true if zones are Battlefield/own Hand, OR `UI_SELECT_FROM_CARD_DISPLAYS` is on and every zone is FloatingZone-openable (Battlefield/Hand/Library/Graveyard/Exile/Flashback/Command/Sideboard) |
| `MatchController` (mobile) | true only if every zone is Battlefield or own Hand |
| `PlayerControllerForTests`, `HeadlessNetworkGuiGame` | false |
| `RemoteClientGuiGame` | desktop or mobile predicate against cached `client.isLibgdx()` from lobby handshake; no new protocol |

`chooseSingleEntityForEffect` and `chooseEntitiesForEffect` keep their
signatures. Implementations change to actually honor DR:

- **`CMatchUI`** — resolves the two `//TODO: Merge this into search
  dialog` comments at `:1252, :1263`. These refer to the modal `GuiChoose`
  dialog, **not** `FloatingZone`. Two desktop surfaces need work:
  - Dialog path (`GuiChoose.order` / `one` / `oneOrNone`): add a DR cards
    panel to the existing Swing modal.
  - Card-click path (FloatingZone + `InputSelectEntitiesFromList`): the
    selection itself doesn't need any new UI — `InputSelectEntitiesFromList`
    already auto-completes when min/max bounds are satisfied, and
    Esc/right-click cancels via the existing prompt area, so FloatingZone
    needs no prompt/OK/cancel surface. DR cards are shown by opening a
    separate read-only DR FloatingZone alongside the selectable one,
    labeled by zone. Both are passive displays; the chooser interacts
    with whichever card they want, the selection auto-resolves.
- **`MatchController`** — implements `supportsCardClickSelection` and
  nothing else. `chooseEntitiesForEffect` keeps routing to
  `SGuiChoose.order(...)`; the libgdx order widget continues not to
  display DR cards. Mobile users see the existing two-stage UX (separate
  reveal popup, then picker) for `RevealYouChoose` / `RevealTgtChoose`.
  Adding DR honoring to the libgdx order widget is follow-up work; see
  Non-Goals. The single-pick path (`chooseSingleEntityForEffect` →
  `GameEntityPicker` with DR tab) already honors DR and is unchanged.

### PCH changes

**Full router migration** (selection methods, ~25–45 lines each → request
build + router call):

- `chooseCardsForEffect` (`:471`)
- `chooseSingleEntityForEffect` (`:585`)
- `chooseEntitiesForEffect` (`:629`)
- `chooseCardsToDiscardFrom` (`:1142`)
- `chooseCardsToDiscard` self-discard (`:1556`)
- `manipulateCardList` (`:944`) / `arrangeForMove` (`:951`) via
  `ManipulateRequest`

**Partial migration** (`TempReveal` only, custom logic stays):

- `chooseContraptionsToCrank` (`:514`) — cranked/uncranked column split
  is too specialized for `SelectionRequest`.
- `arrangeForScry` (`:967`), `arrangeForSurveil` (`:1012`),
  `orderMoveToZoneList` (`:1068`) — chain selection → ordering →
  preference branches in ways `OrderRequest` doesn't usefully flatten
  (`UI_SELECT_FROM_CARD_DISPLAYS` branching, size==1 special cases via
  `willPutCardOnTop` / `InputConfirm.confirm`, a 12-way `ZoneType` switch
  with `topOfDeck` reverse logic). They keep custom logic but use
  `TempReveal` and route inner GUI calls through a thinner helper that
  asks `gui.supportsCardClickSelection`.

**Removed:** `useSelectCardsInput` (both overloads, `:429-469`) —
replaced by `gui.supportsCardClickSelection`. Manual
`tempShowCards`/`endTempShowCards` pairs in migrated call sites —
replaced by `TempReveal`. Private `tempShow(...)` overloads on PCH stay
(still called by `TempReveal` and engine-side callers).

`isLibgdxPort()` checks at `:811, :1439, :1707, :1866, :2216` are **not**
in the selection path; they stay in PCH for this PR. See the
confirm-against-card follow-up.

### Engine-side change

`game.getAction().reveal()` (`DiscardEffect.java:245`) fans out to every
player's controller (`GameAction.java:2243-2248`), not just the
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

Existing overloads remain; `skipChooser == null` preserves current
behavior. `DiscardEffect` for `RevealYouChoose` / `RevealTgtChoose`
(`:244-261`) uses the overload with `skipChooser = chooser` **only when
the chooser is on desktop** (`!chooser.getController().getGui().isLibgdxPort()`).
For mobile choosers, the existing reveal-everyone path is used and
mobile sees today's two-stage UX. The check is a single conditional in
`DiscardEffect`; not worth a capability predicate since it degenerates
to the platform check after the mobile-DR descope.

In both cases DR is passed to `chooseCardsToDiscardFrom`. Desktop
chooser sees the cards inline (no popup). Mobile chooser sees the popup
(and the DR is ignored by mobile multi-pick).

Adds a `DelayedReveal` parameter to `PlayerController.chooseCardsToDiscardFrom`
(abstract). AI and test impls ignore it.

`RevealDiscardAll` (`:209-225`) keeps its existing reveal — no follow-up
selection dialog to fold the chooser's view into.

## File-by-file change list

**New** (`forge-gui/src/main/java/forge/gui/input/router/`):
`SelectionRequest`, `OrderRequest`, `ManipulateRequest`,
`SelectionResult`, `TempReveal`, `InputRouter`, `HumanInputRouter`.

**Modified:**

- `IGuiGame.java` — add `supportsCardClickSelection`.
- `CMatchUI.java` — implement predicate; DR rendering in
  `chooseSingleEntityForEffect`/`chooseEntitiesForEffect` (dialog +
  FloatingZone paths).
- `MatchController.java` — implement predicate only. No DR-routing
  change (out of scope; see Non-Goals).
- `PlayerControllerHuman.java` — router migration; delete
  `useSelectCardsInput`; replace tempShow pairs with `TempReveal`; add
  `snapshotTempShown` / `restoreTempShown`.
- `PlayerControllerForTests.java`, `HeadlessNetworkGuiGame.java` —
  predicate returns false.
- `RemoteClientGuiGame.java` — predicate against cached
  `client.isLibgdx()`. No protocol change required.
- `PlayerController.java` — DR param on `chooseCardsToDiscardFrom`.
- `PlayerControllerAi.java` — accept and ignore the new param.
- `DiscardEffect.java` — `RevealYouChoose`/`RevealTgtChoose` use the
  chooser-aware reveal (gated on `!isLibgdxPort()`) and pass DR.
- `GameAction.java` — chooser-aware `reveal(...)` overload.

## Behavior compatibility

Opponents, spectators, replay viewers, and network observers see
byte-identical behavior — same public reveal popup as today. Mobile
choosers also see today's behavior unchanged (two-stage reveal popup
then picker for `RevealYouChoose` / `RevealTgtChoose`). The only change
is desktop chooser UX: in `RevealYouChoose` / `RevealTgtChoose` discard,
the desktop chooser no longer sees a separate reveal popup; revealed
cards appear inline in the selection dialog (or sibling FloatingZone for
the card-click path). One click removed for desktop, no information
loss anywhere. Any other UX change is a bug.

## Risks

- **`CMatchUI` DR rendering is the highest-risk piece.** Two desktop
  surfaces need work: a DR panel inside the `GuiChoose` modal (the
  long-standing TODO) and a read-only DR FloatingZone alongside the
  selectable FloatingZone in the card-click path. The card-click side is
  cheap (FloatingZone is already a passive card display and
  `InputSelectEntitiesFromList` auto-completes on bounds — no new prompt
  infrastructure needed); the `GuiChoose` panel needs visual design
  (layout, distinction from selectable cards). Mitigation: prototype the
  `GuiChoose` panel early so review can iterate before the surrounding
  refactor lands.
- **DR-as-context vs DR-as-options.** DR cards aren't always a subset of
  selectable options — `DigEffect.java:201`, `ChangeZoneEffect.java:1057`,
  `ChooseCardEffect.java:257` pass disjoint DR sets so the chooser has
  context. The separate-DR-FloatingZone / separate-DR-panel approach
  handles this naturally (DR lives in its own surface; no question
  which set is selectable).
- **`InputSelectEntitiesFromList` vs `InputSelectCardsFromList`.** Router
  uses the entities variant uniformly; the `<Card>`-typed specialization
  is still used by paths outside the router (`chooseTargets`,
  own-discard). Both classes continue to exist.
- **`DiscardEffect` change touches a hot path** (Inquisition, Thoughtseize,
  Mind Warp). Mitigation: exercise the AI controller path (most of test
  volume) before merging.

## Estimated diff size

| Area | Added | Removed |
|---|---|---|
| 7 new files (value objects + router) | ~400 | 0 |
| `CMatchUI` DR rendering (GuiChoose panel — bulk; DR FloatingZone — small, since FloatingZone is already a passive display) | ~70–120 | ~10 |
| Mobile `MatchController` (predicate only) | ~5 | 0 |
| `IGuiGame` interface + 5 impls of `supportsCardClickSelection` | ~30 | 0 |
| PCH full router migration | ~50–80 | ~200–300 |
| PCH partial migration (scry / surveil / orderMoveToZoneList / contraptions) | ~30 | ~30 |
| PCH `tempShownCards` snapshot/restore | ~20 | 0 |
| Engine-side (`GameAction.reveal` overload, `PlayerController`, `PlayerControllerAi`, `DiscardEffect`) | ~40 | ~10 |

Rough totals: **~645–705 added, ~250–350 removed, net ~+350 to +450,
12–14 files** (7 new, 5–7 modified).

Substantive review surface concentrates in three places, by risk:
`CMatchUI` DR rendering, the `GameAction.reveal` chooser-aware overload
(network-visible primitive), `HumanInputRouter` + `TempReveal`
reentrancy. Prototype these early so review can iterate.

## Follow-up work

- **Honor DR in mobile multi-pick.** Extend the libgdx order widget (or
  a wrapper) to display DR cards alongside its source/dest columns —
  third column, popover, or "Revealed" toggle. After that lands,
  `DiscardEffect`'s `!isLibgdxPort()` gate can be dropped and mobile
  choosers see the same single-stage UX as desktop.
- **Prose Javadoc** on `DelayedReveal`, `TempReveal`, the router types,
  and surviving PCH entry points. Should follow immediately while
  context is fresh.
- **Rename `Input*` classes** to align with `CardClickSelection`.
  Mechanical.
- **Generalize the chooser dialog path** (`many` / `order` /
  `chooseEntitiesForEffect`) into a single `IGuiGame.openChooserDialog`.
- **Confirm-against-card primitive** — see below.

### Confirm-against-card primitive

Five `isLibgdxPort()` references remain in PCH after this PR:

| Line | Method | Pattern |
|---|---|---|
| `:811` | trigger confirm | mobile: card-anchored confirm; desktop: `InputConfirm.confirm` |
| `:1439` | `confirmReplacementEffect` | same |
| `:1707` | `notifyOfValue` | mobile: card-anchored confirm with OK; desktop: `getGui().message(...)` |
| `:1866` | `confirmPayment` | same as `:811` / `:1439` |
| `:2216` | `revealAISkipCards` | mobile: card images (libgdx can't zoom name lists); desktop: name-list dialog |

Four of the five share one shape — wrap a confirm/notify in a
card-anchored dialog on mobile, generic dialog on desktop. One follow-up
PR adds:

```java
boolean confirmAgainstCard(CardView card, String question,
                            String yesLabel, String noLabel, boolean defaultYes);
void notifyAgainstCard(CardView card, String message);
```

…and migrates those four call sites. `:2216` is a different shape
(different data representation, not different dialog) — fix it with
`gui.revealUnplayableCards(message, unplayable)` letting each impl
choose name list vs card images. Rides along or its own tiny PR.

After both follow-ups, PCH has zero `isLibgdxPort` references; every
platform decision lives in the `IGuiGame` impl that needs it — the end
state the InputRouter refactor was structuring toward.
