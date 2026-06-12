# Design Spec — Card-Art Deck Sleeves

Status: Draft (uncommitted)
Target branch: claude/exciting-noether-5s22zq
Builds on: branch `origin/desktop-custom-url-sleeves` (repurposed, not discarded)

> This revision incorporates an adversarial code review. The earlier "mostly
> free reuse of the avatar pipeline" framing was wrong on several load-bearing
> points; see Section 12 for the findings and how each is resolved. Net: the
> design now reuses *more* of the existing custom-sleeve branch than before, and
> the genuinely new work is scoped honestly in Section 11.

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
- Desktop and mobile parity.

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

### Card browser + printing chooser (reused — this is the picker)
- `CardManager` (desktop `forge.itemmanager.CardManager`, and the mobile
  equivalent) is an embeddable card browser with search, filtering, and a
  card-image preview. Already reused by the spell shop (`SpellShopManager`), deck
  viewer (`FDeckViewer`), and every deck editor (`new CardManager(detailPicture,
  ...)`).
- Art / printing selection already exists: desktop
  `ChangePrintingDialog.show(PaperCard) -> PaperCard`
  (`forge.screens.deckeditor.ChangePrintingDialog`), the deck editor's "Change
  Printing", with art-style filters (All / Standard / Borderless / Full Art /
  Showcase / Extended Art / Retro Frame / Promo). Mobile uses
  `GuiChoose.oneOrNone(message, getAllCardsNoAlt(name), callback)` over the card's
  printings, exactly as the deck editor's "Change Preferred Art" menu does
  (`FDeckEditor.java:1876-1893`).
- Both art selectors return a concrete `PaperCard`; `getName()` / `getEdition()` /
  `getArtIndex()` compose the sleeve image key directly. This is the same
  `name | set | artIndex` identity Forge already manages as "preferred art"
  (`CardDb.setPreferredArt`), so the sleeve key introduces no new identity concept.

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
| Sleeve grid with dynamic tiles + Random | `SleeveSelector` / `SleevesSelector` (already iterate a variable-length map) | A "My card art" section: library tiles + Add tile + per-tile delete |
| Choose a card + its art | `CardManager` browser (deck editor / spell shop / deck viewer) + `ChangePrintingDialog.show()` (desktop) / `GuiChoose.oneOrNone` over `getAllCardsNoAlt(name)` (mobile) | An "Add art" launcher that wires the returned `PaperCard` to a key — no new picker widget |
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
- "Add art" opens the picker (8.2).
- Selecting any tile sets this seat's selection (built-in index or card key).

### 8.2 Choosing a card + art (reuse the existing widgets — no new dialog)

There is no bespoke search / matches / printing / preview dialog. The two halves
already exist (Section 3) and are wired together by a thin launcher:

```
  [Add art] tile
       |
       v
  CardManager card browser (search + filter + image preview)   <- existing
       |  pick a card
       v
  ChangePrintingDialog.show(card)  (desktop)                   <- existing
  GuiChoose.oneOrNone(getAllCardsNoAlt(name)) (mobile)         <- existing
       |  returns the chosen PaperCard (set + artIndex fixed)
       v
  key = chosen.getImageKey(false)
  append key to UI_SLEEVE_ART_LIBRARY (dedup) and select it
```

- Card selection: the existing `CardManager` browser, same component the deck
  editor / spell shop / deck viewer embed — search, filter, and card preview
  come for free.
- Printing / art: the existing `ChangePrintingDialog.show()` (desktop) or the
  deck editor's "Change Preferred Art" `GuiChoose.oneOrNone` flow (mobile). Both
  already enumerate printings with art-style filtering and hand back a concrete
  `PaperCard`.
- The returned `PaperCard.getImageKey(false)` is the sleeve key (Section 5).
- Tokens are excluded by the launcher's card filter (Section 6); double-faced /
  meld use the front face.

The only genuinely new UI is the "Add art" launcher and the library tiles in the
sleeve grid (8.1). An optional centre-cropped sleeve preview can be shown at the
confirm step using the same `CardSleeveImage` render, but the existing card-image
preview in `CardManager` / `ChangePrintingDialog` already conveys the choice.

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

---

## 11. Net new work (honest scope)

1. The art-crop fetch branch (Section 6) — resolve key to card, force
   `useArtCrop = true`, cache into the sleeve directory.
2. The explicit resolve-or-fallback render check on both platforms (Section 7).
3. `CardSleeveImage` (mobile) modelled on `CardAvatarImage`, or reuse the
   file-based compositor; rekey the desktop cache id from URL-hash to key-hash.
4. The library + selection prefs over the existing `CustomSleeves` codecs
   (`UI_SLEEVE_ART_LIBRARY`, `UI_SLEEVE_ART_KEYS`).
5. Grid additions: library tiles, Add tile, per-tile delete (both selectors).
6. An "Add art" launcher that opens the existing `CardManager` browser +
   `ChangePrintingDialog` / `GuiChoose` printing flow and turns the returned
   `PaperCard` into a library key — no new search / printing / preview widget.
7. Localization strings; remove the arbitrary-URL entry dialog and its
   https-only / size / dimension caps (no longer needed — fetch is Scryfall-only).

Reused with little or no change: the entire lobby String-threading scaffold, the
sleeve cache fetch plumbing, the desktop hidden-card compositor, the
`UI_SHOW_CUSTOM_SLEEVES` preference and checkbox, the `CustomSleeves` codecs, and
the Scryfall art-crop URL builder.

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

---

## 13. Validation

- Two-client networked match on the same build: confirm a card-art key set by one
  player renders as a centre-cropped sleeve on the other, and that
  `UI_SHOW_CUSTOM_SLEEVES = false` suppresses it to the built-in sleeve.
- Unresolved key (simulate a missing card): confirm fallback to built-in sleeve,
  no blank render.
- Cold cache: confirm built-in sleeve shows until the art-crop finishes
  downloading, then upgrades.
- Desktop and mobile parity for the above.

---

## 14. Suggested phasing

1. Rename branch `sleeveUrl` to `sleeveArtKey` end to end; keep the lobby
   scaffold and the show / hide preference.
2. Art-crop fetch branch (Section 6) + the resolve-or-fallback render check.
3. `CardSleeveImage` / desktop compositor rekey; verify centre-crop on both.
4. Library + selection prefs over `CustomSleeves` codecs.
5. Grid tiles + add / delete; the card-art picker reusing deck-editor search.
6. Remove the arbitrary-URL dialog and its caps; localization; validation pass.
