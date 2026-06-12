# Design Spec — Card-Art Deck Sleeves

Status: Draft (uncommitted)
Target branch: claude/exciting-noether-5s22zq
Builds on: branch `origin/desktop-custom-url-sleeves` (repurposed, not discarded)

> This revision incorporates two adversarial code reviews. The first killed the
> "free reuse of the avatar pipeline" framing; the second (of the picker widget)
> killed the "assemble existing widgets, no new dialog" framing. Both sets of
> findings and their resolutions are in Section 12. Net: the design reuses the
> custom-sleeve branch's transport/fetch wholesale, but the picker is a new
> master-detail dialog and the seat-sleeve identity must become "index or key"
> (Section 5). The genuinely new work is scoped honestly in Section 11.

---

## 1. Summary

Let a player use any Magic card's artwork as their deck sleeve, chosen from a
searchable picker, saved in a local library (add / delete), and shown to
networked opponents.

The chosen card travels over the wire as Forge's portable card image key
(form: `c:` name `|` edition `|` artIndex). Each client resolves that key to a
`PaperCard` against its own card database and fetches that card's Scryfall
art-crop image through a dedicated fetch path, then centre-crops it into the
sleeve. No arbitrary URL is transmitted or fetched.

### Goals
- Pick a card via search, preview its centre-cropped art as a sleeve, save it.
- A local library of saved card-art sleeves with add / delete, shown as tiles in
  the existing sleeve grid alongside the built-in sleeves.
- Opponents see your card-art sleeve, gated by the existing
  `UI_SHOW_CUSTOM_SLEEVES` preference.
- Both desktop and mobile — but via separate, platform-native pickers, not a
  shared widget (the desktop and mobile card-browse widgets do not cross over;
  see Section 8).

### Non-goals
- No arbitrary-URL sleeves (the URL entry path on the source branch is removed).
- No new network host or trust relationship — Forge already fetches card art
  from Scryfall.
- No cross-version netplay support: multiplayer is same-build only (Section 10).

---

## 2. Key idea

Reuse the source branch's existing "String carried through the whole lobby"
machinery, but change what the String means and what the fetch does with it:

- Branch today: the String is an arbitrary image URL; the fetch downloads that
  URL into the sleeve cache.
- This design: the String is a card image key; the fetch resolves the key to a
  `PaperCard`, builds that card's Scryfall art-crop URL, and downloads it into
  the sleeve cache.

Everything downstream of "a sleeve image file exists in the cache" (desktop
compositing, mobile rendering, the show / hide preference) is unchanged.

---

## 3. What already exists (verified against source)

### Built-in sleeves (shipped)
- Identity: `LobbyPlayer.sleeveIndex` (int).
- Transport: `PlayerView.updateSleeveIndex` to `TrackableProperty.SleeveIndex`.
- Lobby pick: `SleeveSelector` (desktop) / `SleevesSelector` (mobile) render a
  dynamic grid from `FSkin.getSleeves()`; persisted per seat in `FPref.UI_SLEEVES`.
- Render: mobile `MatchController.java:170`
  (`FTextureRegionImage(FSkin.getSleeves().get(idx))`); desktop
  `ImageCache.sleeveIndexOf` to `FSkin.getSleeveImage(idx)`, cache key
  `__SLEEVE_%d__#%dx%d`.

### Source branch `origin/desktop-custom-url-sleeves` (reused)
- A `String` field threaded through the full lobby surface:
  `LobbyPlayer` to `PlayerView` / `TrackableProperty` (String type) to
  `LobbySlot` to `GameLobby` to `HostedMatch` / `LocalLobby` / `OfflineLobby` /
  `ServerGameLobby` to `UpdateLobbyPlayerEvent`.
- A per-seat sleeve fetch into `CACHE_SLEEVE_PICS_DIR`, and a desktop compositor
  that loads that file and renders it on hidden-card backs.
- The `UI_SHOW_CUSTOM_SLEEVES` preference plus its preferences-screen checkbox.
- The `CustomSleeves` string codecs (base64url, comma-joined, leading `*` marks
  the selected slot) used for storing per-seat entries in a preference.

### Scryfall art-crop URL builder (reused)
- `ImageUtil.getScryfallDownloadUrl(PaperCard, face, setCode, langCode, useArtCrop)`
  (`ImageUtil.java:209`). With `useArtCrop = true` it emits
  `version = art_crop` (`ImageUtil.java:233`), built from the card's collector
  number and edition — stable identity, not the wrapping art index.

### Card-picking primitives (partly reused — see Section 8 for the real scope)
The picker is NOT a free assembly of existing widgets. What actually exists and
is reusable:
- `CardImageGrid` (`forge.toolbox.special.CardImageGrid`): a standalone
  `JList`-backed thumbnail grid — `setItems(List<PaperCard>)` / `getSelected()` /
  double-click / `getComponent()`. It is built to hold **one card's printings**
  (a handful); it renders full-card thumbnails via `ImageCache`, and its
  `iconCache` is cleared only on `dispose()`. Reused as the right pane, but it
  must be repopulated and its cache evicted on each selection change (Section 8).
- `ChangePrintingDialog.show(PaperCard) -> PaperCard` (desktop only): the deck
  editor's per-card printing picker, built on `CardImageGrid` + a search field +
  a `private enum ArtStyle` (All / Standard / Borderless / Full Art / ...). It is
  name-scoped and pre-selects the current printing, so it never hits the
  null-selection case the composite dialog will (Section 8).
- `ListChooser` (`forge.gui.ListChooser`) is a self-contained **modal**, not an
  embeddable pane — its filtered-list logic is copyable but the component is not
  reusable as the left pane. The left list is new code.
- Mobile has neither `CardImageGrid` nor `ChangePrintingDialog`; its art step is
  `GuiChoose.oneOrNone(message, getAllCardsNoAlt(name), callback)`
  (`FDeckEditor.java:1876-1893`). The mobile picker is therefore separate.
- `CardManager` (the deck-editor browser) IS embeddable (`FDeckViewer` does it)
  but is a heavyweight `JPanel` needing an `ItemManagerConfig`; rejected in favour
  of the lighter master-detail composite (Section 8).
- The selected `PaperCard.getImageKey(false)` is the sleeve key — the same
  `name | edition | artIndex` identity Forge already manages as "preferred art"
  (`CardDb.setPreferredArt`). The round-trip (key -> resolved printing's collector
  number) is correct for same-build play (`getCardFromSet` filters on `artIndex`,
  `CardDb.java:756`); see the cross-version caveat in Section 10.

### Avatar-from-card (pattern reference only — NOT proven over the network)
- `LobbyPlayer.avatarCardImageKey` to `TrackableProperty.AvatarCardImageKey` to
  `CardAvatarImage(imageKey)`; its `draw()` does scale-to-cover centre-crop into
  an arbitrary box.
- Set in exactly two places, both single-player-versus-AI Planar Conquest
  (`ConquestController.java:126`, `ConquestEvent.java:216`). It has never crossed
  a real network socket, so it is a useful *shape* to copy but not evidence that
  card-image keys resolve cross-client. We validate that ourselves (Section 13).

### Image key portability
`PaperCard.getImageKey(false)` returns `c:` stripAccents(name) `|` edition `|`
artIndex — composed only from card identity. Resolution of artIndex is database
dependent (`ImageUtil.getImageRelativePath` wraps it modulo the card's art
count), so it is only guaranteed identical when both clients share the same card
database. Same-build multiplayer (Section 10) guarantees exactly that.

---

## 4. Reuse map

| Capability | Reused component | New work |
|---|---|---|
| Per-seat identity + lobby + network transport of the choice | The source branch's String-through-lobby scaffold (rename `sleeveUrl` to `sleeveArtKey`; same wiring end to end) | none structural — rename + retype semantics |
| Build the Scryfall art-crop URL for a card | `ImageUtil.getScryfallDownloadUrl(..., useArtCrop = true)` | none |
| Fetch the art-crop image to the sleeve cache | The branch's per-seat sleeve fetch (`doFetch` into `CACHE_SLEEVE_PICS_DIR`) and `ImageFetcher` base | A fetch branch that, given a card key, resolves the card and fetches its art-crop URL regardless of `UI_CARD_ART_FORMAT` (Section 6) |
| Composite + centre-crop into the sleeve box | Desktop: the branch's file-to-`__SLEEVEURL_%s__` compositor (rekeyed). Mobile: a `CardSleeveImage` modelled on `CardAvatarImage.draw()` cover-crop, OR the same file-based render | Thin `CardSleeveImage` / rekey of the cache id |
| Sleeve grid with dynamic tiles + Random | `SleeveSelector` / `SleevesSelector` (iterate a variable-length map) | A "My card art" section: library tiles + Add tile + per-tile delete. **Caveat:** tiles are currently pure integer-index identity (`PlayerPanel` parses an int out of the label name); card-art tiles carry a key, so tile/selection identity must become "index or key" (Section 5) |
| Choose a card + its art (desktop) | `CardImageGrid` (right pane, verbatim use) | A new master-detail dialog: a from-scratch filtered left list (ListChooser logic copied, not reused) + the `CardImageGrid` right pane + a sleeve-preview box. Use `getAllCardsNoAlt(name)`, drop the `ArtStyle` filter for v1 (Section 8) |
| Choose a card + its art (mobile) | `GuiChoose.oneOrNone` over `getAllCardsNoAlt(name)` | A separate mobile picker (card list + GuiChoose) — no widget shared with desktop (Section 8) |
| Per-seat selection + library persistence | The branch's `CustomSleeves` codecs | Two prefs (Section 5) written through those codecs |
| Suppress opponents' custom sleeves | `UI_SHOW_CUSTOM_SLEEVES` + its checkbox | none |
| Built-in fallback when hidden / unresolved | The existing `SleeveIndex` render path | An explicit resolve-or-fallback check (Section 7) — this is NOT inherited behavior |

---

## 5. Data model + storage

### Identity (per seat — transmitted)
- Rename the branch's `sleeveUrl` String to `sleeveArtKey` across the lobby
  surface (LobbyPlayer, PlayerView, the String `TrackableProperty`, LobbySlot,
  GameLobby, HostedMatch, Local / Offline / Server lobbies, UpdateLobbyPlayerEvent).
  Value is a card image key, or empty for "use the built-in sleeve."
- Existing `SleeveIndex` stays as the built-in identity and fallback.

### Seat-sleeve identity is "index OR key" — the first thing to de-risk
Today a sleeve is an `int` end to end: `PlayerPanel.sleeveIndex`, the `UI_SLEEVES`
pref, `FSkin.getSleeves().get(int)`, `ImageCache.sleeveIndexOf`,
`PlayerView.getSleeveIndex()`, and mobile's `Consumer<Integer>`. Tiles even encode
identity as `"SleeveLabel"+index`, and `PlayerPanel` reads the selection back by
parsing that int out of the label name (`PlayerPanel.java:716`).

A card-art sleeve has no int. The branch already provides the transport/render half
(the parallel `sleeveArtKey` string with key-else-index precedence, Section 7), so
the remaining work is purely in the **selector**: tiles and the read-back must
carry "built-in index OR card-art key", replacing the int-parse-from-label. The
random-sleeve and used-sleeve dedup logic (`PlayerPanel.java:767-771`,
`getUsedSleeves`) stays index-only (it never picks a card-art tile). This identity
change is Phase 1 (Section 14) and must land before any picker UI, because until
it exists the picker has nowhere valid to write its result.

### Preferences
| Pref | Origin | Meaning |
|---|---|---|
| `UI_SLEEVES` | existing | per-seat built-in sleeve index — unchanged |
| `UI_SLEEVE_ART_KEYS` | rename of the branch's per-seat URL pref | per-seat selected card-art key + selected flag, via `CustomSleeves.encodeSlot` |
| `UI_SLEEVE_ART_LIBRARY` | new | local list of saved card-art keys (base64url, comma-joined) — never transmitted |
| `UI_SHOW_CUSTOM_SLEEVES` | existing (branch) | suppress opponents' card-art sleeves |

The card image key round-trips safely through the `CustomSleeves` base64url
codec — the encoded token contains none of `*` `,` `=`, so it does not collide
with the preference `KEY=VALUE`, comma-join, or selected-marker conventions
(verified in review). The local library slot index is never transmitted; only
the key is.

---

## 6. The art-crop fetch branch (new)

A sleeve-specific path, added alongside the branch's existing sleeve fetch:

1. Input: a card image key (from `sleeveArtKey`, or chosen in the picker).
2. Resolve the key to a `PaperCard` (CardDb / `ImageUtil` key-to-card lookup).
   If it does not resolve, stop — the render path falls back to the built-in
   sleeve (Section 7).
3. Build the art-crop URL via
   `ImageUtil.getScryfallDownloadUrl(card, "", null, lang, true)` — front face,
   `useArtCrop = true`, independent of `UI_CARD_ART_FORMAT`.
4. Download into `CACHE_SLEEVE_PICS_DIR` under a filename derived from the card
   identity (hash of the key — reuse `CustomSleeves.cacheFileName`).
5. Notify observers so the sleeve re-renders once cached.

This is the single most important new piece, and the reason the default-`Full`
art-format gate no longer matters: sleeves request art-crop directly rather than
going through the user's card-art preference. Because the output is a cached
file, the desktop compositor needs no `getCardArt` (which is a stub on desktop);
it loads the file just as it loaded an arbitrary-URL download before.

Tokens are excluded by the picker (Section 7.2); double-faced and meld cards use
the front face.

---

## 7. Transport + render precedence

On every client, per player:

```
key = sleeveArtKey for this player
if key is non-empty
   and (this is my own player OR UI_SHOW_CUSTOM_SLEEVES is true)
   and key resolves to a PaperCard in the local DB:
        ensure the art-crop file is cached (fetch if missing)   // Section 6
        if cached:  render the centre-cropped card art
        else:       render built-in sleeve at SleeveIndex       // until cached
else:
        render built-in sleeve at SleeveIndex
```

The explicit resolve-and-cached checks are mandatory new code: an unresolved key
or an uncached file must fall through to `SleeveIndex`, otherwise the renderer
draws a blank / placeholder rather than the built-in sleeve. Only the key string
crosses the wire; each client fetches its own art-crop from Scryfall — the same
host Forge already uses for card images.

---

## 8. UI

### 8.1 Sleeve grid (library + add / delete)
```
+- Select Sleeve for Player 1 ----------------------------------+
|  Built-in                                                     |
|  +------+ +------+ +------+ +------+ +------+                  |
|  |  ?   | | MtG  | |Boros | |Simic | |Izzet |   ...           |
|  |Random| | back | |      | |      | |      |                 |
|  +------+ +------+ +------+ +------+ +------+                  |
|  My card art                                                  |
|  +------+ +------+ +------+ +------+ +. . . +                  |
|  |#Niv #| |#Bolt#| |#Sol #| |#Uro #| .  +   .                 |
|  |#Miz #| |#    #| |#Ring#| |#    #| . Add  .                 |
|  |   x  | |   x  | |   x  | |   x  | . art. .                 |
|  +------+ +------+ +------+ +------+ +. . . +                  |
+---------------------------------------------------------------+
```
- Built-in section unchanged (Random + sprite sleeves).
- "My card art": one tile per `UI_SLEEVE_ART_LIBRARY` entry, preview = the
  centre-cropped card art.
- `x` on hover / selected removes the entry from the library.
- "Add art" opens the picker (8.2 desktop, 8.3 mobile).
- Selecting a built-in tile sets this seat's `SleeveIndex`; selecting a card-art
  tile sets `sleeveArtKey` and clears the built-in selection (Section 5 identity).

### 8.2 Desktop picker — a new master-detail dialog

A single `FDialog` / `FOptionPane` with a searchable card-name list on the left
and the selected card's printings on the right. This is new code, but each pane
is built from an existing primitive (Section 3).

```
+- Add Card-Art Sleeve ---------------------------------------+
| Search [ niv|        ]   |  Printings of: Niv-Mizzet, Parun |
| +----------------------+ |  +-----------------------------+ |
| | Niv-Mizzet, Parun  > | |  | [GRN]  [MYB]  [PLST]  [SLD]  | |
| | Niv-Mizzet, Firemind | |  |  *                          | |
| | Niv-Mizzet Reborn    | |  +-----------------------------+ |
| | Niv-Mizzet, Genius   | |  Sleeve preview:  +------+       |
| +----------------------+ |                   |######|       |
|   FList + FTextField     |   CardImageGrid    |######|       |
|   (filtered, debounced)  |   (printings)      +------+       |
|                                       [ OK ]   [ Cancel ]    |
+-------------------------------------------------------------+
```

Behaviour and the review fixes baked in:
- **Left list** — a from-scratch filtered `FList` over `CardDb.getUniqueCards()`
  (~25k unique-by-rules names; primary names only, so DFC backfaces and tokens do
  not appear as separate entries). The ListChooser filter logic is copied, not
  reused (it is a modal, not a pane). Filtering MUST be debounced — a per-keystroke
  linear scan + accent-normalize over 25k entries is otherwise a visible hitch.
- **Right grid** — `CardImageGrid`, repopulated on each left selection via
  `grid.setItems(getAllCardsNoAlt(name))`. Use `getAllCardsNoAlt`, NOT
  `getAllCards`: the latter indexes alternate-face and flavor names and would
  surface foreign printings (e.g. "Fire" -> "Fire // Ice"), yielding a wrong key.
- **iconCache** — `CardImageGrid` only clears its icon cache on `dispose()`;
  browsing many names would grow it unbounded, so evict / bound it on each left
  selection change.
- **Null selection** — after repopulating, `grid.getSelected()` is null until the
  user clicks a printing (selection is preserved only if the same card persists).
  OK is disabled until `getSelected() != null`; the OK handler never dereferences
  a null. (`ChangePrintingDialog` avoids this by pre-selecting; the composite
  cannot.)
- **Sleeve preview** — the grid shows full-card thumbnails (needed to tell
  printings apart), but the delivered sleeve is a centre-cropped art-crop. A small
  preview box renders the actual sleeve using the Section 9 sleeve renderer, so
  what-you-see matches what-you-get.
- **ArtStyle filter** — dropped for v1. The enum is `private` to
  `ChangePrintingDialog` and its section-matching is meaningful only in curated
  set contexts; across the full pool it is mostly noise.
- **EDT / disposal** — assert EDT (as `ChangePrintingDialog` does); call
  `grid.dispose()` on close. Fast selection changes briefly show placeholder
  thumbnails until async image fetch completes — acceptable.

On OK: `key = grid.getSelected().getImageKey(false)` -> append to
`UI_SLEEVE_ART_LIBRARY` (dedup) and select it for this seat.

### 8.3 Mobile picker — separate, native

Mobile has no `CardImageGrid` / `ChangePrintingDialog`, so this is an independent
picker, not the desktop dialog:
- A card-name list (mobile's searchable list / `ItemManager` image view) ->
  pick a card.
- `GuiChoose.oneOrNone(message, getAllCardsNoAlt(name), callback)` over the
  printings (the deck editor's exact "Change Preferred Art" call); skipped if one
  printing.
- `chosen.getImageKey(false)` -> library + select.

The two pickers share the data model (Section 5) and the render path (Sections
6-7, 9), but not UI code.

---

## 9. Centre-crop

Art-crop is landscape (about 1.3 to 1.4 to 1); the sleeve box is portrait
(360 x 500, about 0.72 to 1). Scale-to-cover keeps full height and trims
left / right. The geometry in `CardAvatarImage.draw()` already handles a portrait
box correctly (verified in review), so a `CardSleeveImage` clone — or the
file-based compositor — applies the same math.

```
   art-crop (wide)            sleeve (tall)
  +----+--------+----+       +--------+
  | .. |########| .. |  -->  |########|   full height kept,
  +----+--------+----+       +--------+   sides trimmed
```

---

## 10. Versioning, portability, degradation

- Multiplayer is same-build only. Forge has no netcode version handshake and the
  Trackable delta stream serializes enum keys by name, so an older client would
  throw on the new `sleeveArtKey` property / lobby field rather than ignore it.
  This is acceptable: networked matches already assume identical builds and a
  server that lives only a game or two. No version gate is added.
- Same build implies identical card databases, so a transmitted key resolves to
  the same `PaperCard` and the art-crop URL (built from collector number) is
  identical on both clients. This removes the cross-database art-index ambiguity.
- If a key fails to resolve, or art is not yet cached, the render path falls back
  to the player's built-in `SleeveIndex` (Section 7).
- Persisted-library caveat: `artIndex` ordering is assigned at card-DB load from
  edition-file entry order (`CardDb.java:386`). It is identical across clients on
  the same build, but is not guaranteed stable if edition files are re-ordered in
  a future Forge version. A key saved in `UI_SLEEVE_ART_LIBRARY` could therefore
  resolve to a *different* art after an upgrade. This is cosmetic and self-healing
  (re-pick), but worth noting since the library persists across versions.

---

## 11. Net new work (honest scope)

1. **Seat-sleeve identity = index OR key** (Section 5) — the de-risk-first item.
   Make `SleeveSelector`/`SleevesSelector` tiles and `PlayerPanel` read-back carry
   "built-in index or card-art key" instead of int-parse-from-label. (Transport /
   render already provided by the branch's parallel `sleeveArtKey`.)
2. The art-crop fetch branch (Section 6) — resolve key to card, force
   `useArtCrop = true`, cache into the sleeve directory.
3. The explicit resolve-or-fallback render check on both platforms (Section 7).
4. `CardSleeveImage` (mobile) modelled on `CardAvatarImage`, or reuse the
   file-based compositor; rekey the desktop cache id from URL-hash to key-hash.
   This renderer is also the sleeve-preview box in the desktop picker (Section 8).
5. The library + selection prefs over the existing `CustomSleeves` codecs
   (`UI_SLEEVE_ART_LIBRARY`, `UI_SLEEVE_ART_KEYS`).
6. Grid additions: library tiles, Add tile, per-tile delete (both selectors).
7. **Desktop picker** — the new master-detail dialog (Section 8.2): a from-scratch
   filtered left list + the reused `CardImageGrid` right pane + the sleeve-preview
   box, with the debounce / `getAllCardsNoAlt` / iconCache-evict / OK-guard fixes.
8. **Mobile picker** — a separate native picker (Section 8.3): card list +
   `GuiChoose.oneOrNone`. Shares the data model and render path, not UI code.
9. Localization strings; remove the arbitrary-URL entry dialog and its
   https-only / size / dimension caps (no longer needed — fetch is Scryfall-only).

Reused with little or no change: the entire lobby String-threading scaffold, the
sleeve cache fetch plumbing, the desktop hidden-card compositor, the
`UI_SHOW_CUSTOM_SLEEVES` preference and checkbox, the `CustomSleeves` codecs, the
Scryfall art-crop URL builder, and `CardImageGrid` (right pane).

Explicitly NOT reused (corrected from earlier drafts): `CardManager` (too heavy),
`ChangePrintingDialog` as a whole (name-scoped; the composite needs all-card
search), `ListChooser` as a pane (it is a modal — only its filter logic is copied).

---

## 12. Resolved review findings

- art-crop not fetched by default (`UI_CARD_ART_FORMAT = Full`): resolved by the
  dedicated fetch branch (Section 6) that forces `useArtCrop = true`.
- Desktop `getCardArt` is a stub returning null: resolved — the file-based fetch
  means desktop never calls it; it composites the cached art-crop file.
- "Proven over the network" was unsupported (avatar keys only ever set in
  single-player Conquest): claim dropped; cross-client resolution is validated by
  us (Section 13), not assumed.
- Lobby-event surface omitted from scope: resolved by reusing (renaming) the
  branch's existing String-through-lobby scaffold rather than deleting it.
- No version gate / graceful cross-version degradation: accepted as a constraint
  — same-build multiplayer only (Section 10).
- art-index portability across DB versions: moot under same-build multiplayer.
- Built-in fallback is not inherited behavior: made an explicit render check
  (Section 7) and listed as new work (Section 11 item 2).
- Verified-good and kept as-is: centre-crop geometry (Section 9), the
  `CustomSleeves` key round-trip (Section 5), cache-path / decode safety.

Picker review (second review):
- Seat-sleeve is int end-to-end; "Add art" cannot just be a tile (BLOCKER):
  resolved by the index-or-key identity change, made Phase 1 (Sections 5, 11).
- `getSelected()` null -> NPE on OK (BLOCKER): OK disabled until a printing is
  selected (Section 8.2).
- `getAllCards(name)` leaks alt-face / flavor-name printings (MAJOR): use
  `getAllCardsNoAlt(name)` (Section 8.2).
- Mobile parity false — no `CardImageGrid` / `ChangePrintingDialog` on mobile
  (MAJOR): the mobile picker is separate by design (Sections 1, 8.3).
- Preview (full card) != result (art-crop) (MAJOR): a sleeve-preview box using the
  Section 9 renderer is added to the desktop picker (Section 8.2).
- `CardImageGrid.iconCache` grows across browsed cards (MINOR): evict on selection
  change (Section 8.2).
- `ListChooser` is a modal, not a reusable pane (MINOR): left list is new code,
  scoped as such (Sections 3, 11).
- 25k-name filter per keystroke (MINOR): debounce (Section 8.2).
- Key round-trip is sound for same-build; cross-version artIndex drift on the
  persisted library (MINOR): noted as a self-healing caveat (Section 10).
- `ArtStyle` is private + curated-set-only (NIT): dropped for v1 (Section 8.2).

---

## 13. Validation

- Two-client networked match on the same build: confirm a card-art key set by one
  player renders as a centre-cropped sleeve on the other, and that
  `UI_SHOW_CUSTOM_SLEEVES = false` suppresses it to the built-in sleeve.
- Unresolved key (simulate a missing card): confirm fallback to built-in sleeve,
  no blank render.
- Cold cache: confirm built-in sleeve shows until the art-crop finishes
  downloading, then upgrades.
- Both desktop and mobile pickers reach a saved, selected card-art sleeve.
- Picker correctness: OK is disabled with nothing selected (no NPE); a card with a
  shared/alt name (e.g. "Fire") lists only its own printings (`getAllCardsNoAlt`);
  the sleeve-preview box matches the rendered in-game sleeve; browsing many cards
  does not grow memory without bound (iconCache eviction).

---

## 14. Suggested phasing

1. **Seat-sleeve identity = index or key** (Section 5): rename branch `sleeveUrl`
   to `sleeveArtKey` end to end, and make selector tiles / `PlayerPanel` read-back
   carry index-or-key. Prove it with a stub "Add art" that writes a hardcoded key.
2. Art-crop fetch branch (Section 6) + the resolve-or-fallback render check; verify
   a hardcoded key transmits and renders a centre-cropped sleeve end to end (this
   is the vertical slice that de-risks the whole design).
3. `CardSleeveImage` / desktop compositor rekey; this renderer also backs the
   picker's sleeve-preview box.
4. Library + selection prefs over `CustomSleeves` codecs.
5. Grid tiles + add / delete.
6. Desktop master-detail picker (Section 8.2) with all baked-in fixes.
7. Mobile picker (Section 8.3).
8. Remove the arbitrary-URL dialog and its caps; localization; validation pass.
