# KeywordView Records Implementation Plan

## Table of Contents

- [Goal](#goal)
- [Background](#background)
- [KeywordView Record](#keywordview-record)
- [Storage & Serialization](#storage--serialization)
- [Game-Layer Computation](#game-layer-computation)
  - [Keyword abilities](#keyword-abilities)
  - [Keyword actions (API detection)](#keyword-actions-api-detection)
  - [Detection metadata on KeywordAction enum](#detection-metadata-on-keywordaction-enum)
  - [Keyword abilities — detection reference](#keyword-abilities--detection-reference)
  - [Keyword actions — detection reference](#keyword-actions--detection-reference)
- [Consumer Migration](#consumer-migration)
- [Files Affected](#files-affected)
- [Implementation Order](#implementation-order)
- [Part 2: hoveroptions Branch Cleanup](#part-2-hoveroptions-branch-cleanup)
- [Open Questions](#open-questions)

## Goal

Replace the `keyword -> string -> re-parse -> extract display data` pipeline for the keyword tooltips functionality in #9806 with pre-computed `KeywordView` records stored in `CardStateView`. Covers both keyword abilities (Flying, Protection, Escape) and keyword actions (Scry, Goad, Support). Replaces `KeywordKey`, `ProtectionKey`, and `HexproofKey` with a single structured collection.

Implements issue # 9918.

## Background

### Current pipeline

```
Card.getKeywords()                         (KeywordCollection — game layer)
  -> Card.getKeywordKey()                  (sorted, comma-joined original strings)
  -> CardStateView.KeywordKey              (TrackableProperty, StringType)
  -> [network delta sync as string]
  -> KeywordInfoUtil.buildKeywords()       (re-parses each token via Keyword.getInstance())
  -> List<KeywordData>                     (name, reminderText, typeParam)
  -> additional enrichment                 (count annotation, action scanning, dedup, sorting)
  -> GUI renders
```

Problems:
- **Round-trip waste**: keyword -> string -> re-parse -> extract. The game layer already has structured keyword data; converting to string and re-parsing is pointless.
- **Network clients lack keyword infrastructure**: Remote clients re-parse keyword strings via `Keyword.getInstance()`, which requires the full keyword parsing stack. Pre-computed records would let thin clients render tooltips without it.
- **Keyword actions require oracle scanning**: No structured data exists for keyword actions (Scry, Goad, etc.). The GUI scans oracle text with regexes, causing false positives (Support), substring collisions (Manifest vs Manifest Dread), and missing parameters (Escape cost).
- **Scattered keyword properties**: `KeywordKey`, `ProtectionKey`, `HexproofKey`, and 30+ boolean flags all encode different facets of the same keyword collection. `ProtectionKey` and `HexproofKey` use ad-hoc compact string encodings.

### What changes

```
Card.getKeywords() + Card.getSpellAbilities()    (game layer)
  -> computeKeywordViews()                        (builds records for abilities + actions)
  -> CardStateView.KeywordViews                   (TrackableProperty, new list type)
  -> [network delta sync as structured data]
  -> GUI reads List<KeywordView> directly          (no re-parsing)
  -> minimal enrichment                            (dynamic counts only)
  -> GUI renders
```

## KeywordView Record

```java
// forge-game/src/main/java/forge/game/card/KeywordView.java
public record KeywordView(
    Keyword keyword,      // enum reference, null for keyword actions
    String name,          // display header: "Flying", "Protection from red and blue",
                          //   "Escape — {2}{B}, exile 3", "Scry"
    String reminderText,  // formatted reminder text
    String typeParam      // structured parameter, nullable:
                          //   - Affinity: "artifact" (for count annotation)
                          //   - Protection: "RU" or "everything" (for icon derivation)
                          //   - Hexproof: "generic" or "R" (for icon derivation)
                          //   - Keyword actions: null typically
) implements java.io.Serializable {}
```

### Design rationale

- **`Keyword keyword` field** follows Hanmac's `Multimap<Keyword, KeywordView>` suggestion. Enables programmatic grouping (`views.stream().filter(v -> v.keyword() == Keyword.PROTECTION)`) for icon derivation and Level-up detection. Null for keyword actions (identified by `name`).
- **String display fields** are pre-computed by the game layer. The GUI renders them directly without needing `Keyword.getInstance()` or `KeywordInfoUtil.buildKeywords()` re-parsing.
- **`typeParam`** carries structured data for annotation and icon rendering. For Protection/Hexproof, it replaces the compact string encodings currently in `ProtectionKey`/`HexproofKey`. For Affinity/Devotion/Domain, it carries the type expression for count annotation.
- **Java record** (not class) — immutable, auto-equals/hashCode, compact. Java 17+ is the project minimum.

### What the record replaces

| Current | Replaced by |
|---------|------------|
| `KeywordKey` (comma-separated string) | `List<KeywordView>` — structured data, no re-parsing |
| `ProtectionKey` (compact color string) | `KeywordView` records where `keyword == PROTECTION`, icon key in `typeParam` |
| `HexproofKey` (compact color string) | `KeywordView` records where `keyword == HEXPROOF`, icon key in `typeParam` |
| `KeywordInfoUtil.buildKeywords()` re-parsing | Direct consumption of records — method becomes thin wrapper or removed |
| `KeywordInfoUtil.addKeywordActions()` oracle scanning | Actions pre-computed as records by game layer (API detection) |
| `KeywordInfoUtil.addMissingKeywordsFromFlags()` | No longer needed — records are authoritative |

### What the record does NOT replace

| Property | Why kept |
|----------|---------|
| Boolean flags (`HasFlying`, `HasDeathtouch`, etc.) | Used for icon rendering and quick checks across ~30 consumers. 1 byte each in delta sync. Removing would require every `hasFlying()` call site to iterate the collection. |
| `AbilityText` | Full rules text — serves a different purpose than keyword tooltips |
| `CantHaveKeyword` | Negative constraint set — orthogonal to keyword display |

## Storage & Serialization

### TrackableProperty

```java
// In TrackableProperty.java
KeywordViews(TrackableTypes.KeywordViewListType, FreezeMode.RespectsFreeze),
```

Single new property replacing `KeywordKey`, `ProtectionKey`, and `HexproofKey`.

### TrackableType

New `KeywordViewListType` in `TrackableTypes.java`:

```java
public static final TrackableType<List<KeywordView>> KeywordViewListType =
    new TrackableType<List<KeywordView>>() {
        @Override protected List<KeywordView> getDefaultValue() { return Collections.emptyList(); }

        @Override protected void serialize(TrackableSerializer ts, List<KeywordView> value) {
            ts.write(value.size());
            for (KeywordView kv : value) {
                ts.write(kv.keyword() != null ? kv.keyword().ordinal() : -1);
                ts.write(kv.name());
                ts.write(kv.reminderText());
                ts.write(kv.typeParam() != null ? kv.typeParam() : "");
            }
        }

        @Override protected List<KeywordView> deserialize(
                TrackableDeserializer td, List<KeywordView> oldValue) {
            int count = td.readInt();
            List<KeywordView> result = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int kwOrd = td.readInt();
                Keyword kw = kwOrd >= 0 ? Keyword.values()[kwOrd] : null;
                String name = td.readString();
                String reminder = td.readString();
                String typeParam = td.readString();
                result.add(new KeywordView(kw, name, reminder,
                    typeParam.isEmpty() ? null : typeParam));
            }
            return result;
        }
    };
```

This replaces the existing `KeywordCollectionViewType` TODO stub. Uses `Keyword.ordinal()` for efficient enum serialization (enums are stable within a build).

### Delta sync behavior

`List<KeywordView>` is compared by `equals()` (record auto-equals). When keywords change, the entire list is re-serialized. This is the same granularity as the current `KeywordKey` string — a keyword change always resends the full string. No per-element delta tracking needed.

## Game-Layer Computation

### Keyword abilities

In `CardStateView.updateKeywords()` (or a new helper called from there), build `KeywordView` records from the card's `KeywordCollection`:

```java
List<KeywordView> views = new ArrayList<>();
Map<Keyword, KeywordView> mergeTargets = new LinkedHashMap<>();

for (KeywordInterface inst : c.getKeywords()) {
    Keyword kw = inst.getKeyword();
    String title = inst.getTitle();
    String reminder = inst.getReminderText();
    String typeParam = extractTypeParam(inst);    // Affinity type, Protection colors, etc.

    // Merge duplicates (Protection from red + Protection from blue -> Protection from red and blue)
    if (kw.isMultipleRedundant() || !mergeTargets.containsKey(kw)) {
        KeywordView view = new KeywordView(kw, title, reminder, typeParam);
        views.add(view);
        mergeTargets.put(kw, view);
    } else {
        // Merge into existing entry (update name, reminder, typeParam)
        KeywordView existing = mergeTargets.get(kw);
        KeywordView merged = mergeKeywordViews(existing, inst);
        views.set(views.indexOf(existing), merged);
        mergeTargets.put(kw, merged);
    }
}
```

The merging logic currently lives in `KeywordInfoUtil.buildKeywords()` (GUI layer). It moves to the game layer since `KeywordView` records should be display-ready.

### Keyword actions (API detection)

After building ability records, detect keyword actions from the card's SpellAbilities, triggers, costs, and replacements:

```java
Set<String> detectedActions = new LinkedHashSet<>();  // dedup

for (SpellAbility sa : c.getAllSpellAbilities()) {
    // 1. Direct Effect class match (32 actions)
    detectByApiType(sa, detectedActions, views);

    // 2. Named param match (Support$, Bolster$, Populate$, etc.) (6 actions)
    detectByParams(sa, detectedActions, views);

    // 3. Cost type match (CostExert, CostBehold, etc.) (5 actions)
    detectByCosts(sa, detectedActions, views);

    // 4. AlterAttribute match (Suspect, Harness) (2 actions)
    detectByAttributes(sa, detectedActions, views);
}

// 5. Oracle text fallback for Fateseal, Transform/Convert (~3 actions)
detectByOracleText(c.getOracleText(), detectedActions, views);
```

Each detector creates a `KeywordView(null, action.getDisplayName(), action.getReminderText(), null)` — keyword field is null since these are actions, not abilities.

### Detection metadata on KeywordAction enum

To avoid hardcoding 45 if-else chains, add detection metadata to the `KeywordAction` enum:

```java
public enum KeywordAction {
    SCRY(false, DetectionMethod.EFFECT, "ScryEffect"),
    SUPPORT(false, DetectionMethod.PARAM, "Support$"),
    EXERT(false, DetectionMethod.COST, "CostExert"),
    SUSPECT(false, DetectionMethod.ATTRIBUTE, "Suspect"),
    FATESEAL(false, DetectionMethod.ORACLE, null),
    // ...
}
```

The detection method and target string let the computation loop be data-driven rather than a giant switch statement.

### Keyword abilities — detection reference

Keyword abilities are already fully detected via the `Keyword` enum and `KeywordCollection`. Each has a typed class (`SimpleKeyword`, `KeywordWithCost`, `KeywordWithAmount`, `KeywordWithType`, etc.) providing `getTitle()`, `getReminderText()`, and typed parameter accessors. The `KeywordView` record is built directly from these — no detection logic needed beyond iterating the collection.

Display edge cases to handle during record construction:

| Keyword | Class | Issue | Fix in `computeKeywordViews()` |
|---------|-------|-------|-------------------------------|
| Escape | `KeywordWithCost` | Header shows "Escape" without cost | Use `getCost()` for full header: "Escape — {cost}" |
| Craft | `Craft` | Header shows "Craft {mana}" without exile types | Use `getTitle()` or parse original string for full display |
| Equip | `Equip` | Non-standard equip variants confuse display | Already partially handled; verify edge cases |
| Trample | `Trample` | "Trample" detected separately from "Trample over planeswalkers" | Deduplicate: suppress base if variant exists |
| Protection | `Protection` | Multiple protections need merging | Merge during record construction; combine `typeParam` for icon key |

### Keyword actions — detection reference

#### Basic actions (excluded from tooltips — `basic=true`)

| # | Action | Notes |
|---|--------|-------|
| 1 | Activate | Fundamental game action |
| 2 | Attach | |
| 3 | Cast | |
| 4 | Counter | |
| 5 | Create | |
| 6 | Destroy | |
| 7 | Discard | |
| 8 | Double | |
| 9 | Triple | |
| 10 | Exchange | |
| 11 | Exile | |
| 12 | Play | |
| 13 | Reveal | |
| 14 | Sacrifice | |
| 15 | Search | |
| 16 | Shuffle | |
| 17 | Tap/Untap | |
| 18 | Set in Motion | Archenemy action |
| 19 | Abandon | Archenemy action |

These are excluded from tooltip display and will not produce `KeywordView` records.

#### Non-basic actions with direct Effect class (DetectionMethod.EFFECT)

| # | Action | Effect class | Notes |
|---|--------|-------------|-------|
| 20 | Scry | `ScryEffect` | Also occurs as cost (`CostScry` if exists) and in triggers/REs |
| 21 | Surveil | `SurveilEffect` | |
| 22 | Mill | `MillEffect` | Also occurs as cost |
| 23 | Fight | `FightEffect` | |
| 24 | Goad | `GoadEffect` | |
| 25 | Investigate | `InvestigateEffect` | |
| 26 | Explore | `ExploreEffect` | |
| 27 | Connive | `ConniveEffect` | |
| 28 | Discover | `DiscoverEffect` | |
| 29 | Cloak | `CloakEffect` | |
| 30 | Manifest | `ManifestEffect` | Substring collision with Manifest Dread resolved by API |
| 31 | Manifest Dread | `ManifestDreadEffect` | Distinct effect class — no collision |
| 32 | Amass | `AmassEffect` | Includes creature type param |
| 33 | Learn | `LearnEffect` | |
| 34 | Incubate | `IncubateEffect` | |
| 35 | The Ring Tempts You | `RingTemptsYouEffect` | |
| 36 | Venture | `VentureEffect` | |
| 37 | Vote | `VoteEffect` | |
| 38 | Clash | `ClashEffect` | |
| 39 | Detain | `DetainEffect` | |
| 40 | Regenerate | `RegenerateEffect` | |
| 41 | Meld | `MeldEffect` | |
| 42 | Proliferate | `CountersProliferateEffect` | |
| 43 | Planeswalk | `PlaneswalkEffect` | Oracle false positive ("planeswalker") eliminated by API |
| 44 | Open an Attraction | `OpenAttractionEffect` | |
| 45 | Assemble | `AssembleContraptionEffect` | Un-set action |
| 46 | Villainous Choice | `VillainousChoiceEffect` | |
| 47 | Time Travel | `TimeTravelEffect` | |
| 48 | Endure | `EndureEffect` | |
| 49 | Airbend | `AirbendEffect` | |
| 50 | Earthbend | `EarthbendEffect` | |
| 51 | Blight | `BlightEffect` | |

#### Non-basic actions detectable via params on generic effects (DetectionMethod.PARAM)

| # | Action | Host effect | Detection param | Notes |
|---|--------|-----------|-----------------|-------|
| 52 | Support | `CountersPut` | `Support$` | Refactored by Hanmac (#9870) |
| 53 | Bolster | `CountersPut` | `Bolster$` | Refactored by Hanmac (#9872) |
| 54 | Populate | `CopyPermanent` | `Populate$` | Param exists on master |
| 55 | Roll to Visit | `RollDice` | Optional param | Per Jetz72 |
| 56 | Adapt | Various | Refactored | Was keyword, now SpellAbility (#9854) |
| 57 | Monstrosity | Various | Refactored | Was keyword, now SpellAbility (#9859) |

#### Non-basic actions detectable via cost classes (DetectionMethod.COST)

| # | Action | Cost class | Notes |
|---|--------|-----------|-------|
| 58 | Exert | `CostExert` | |
| 59 | Behold | `CostBehold` / `CostBeholdExile` | |
| 60 | Collect Evidence | `CostCollectEvidence` | |
| 61 | Forage | `CostForage` | |
| 62 | Waterbend | `CostWaterbend` | |

#### Non-basic actions detectable via attribute params (DetectionMethod.ATTRIBUTE)

| # | Action | Host effect | Detection | Notes |
|---|--------|-----------|-----------|-------|
| 63 | Suspect | `AlterAttribute` | `Attributes$` contains "Suspect"/"Suspected" | |
| 64 | Harness | `AlterAttribute` | `Attributes$` contains "Harnessed" | |

#### Non-basic actions requiring oracle text fallback (DetectionMethod.ORACLE)

| # | Action | Why no API | Notes |
|---|--------|-----------|-------|
| 65 | Fateseal | Indistinguishable from generic `Scry`-like effect | ~10 cards. Could add marker param to eliminate fallback. |
| 66 | Transform | `SetState` — same API as Convert, flip, MDFC | ~many cards. Could add marker param for Convert's 15 cards. |
| 67 | Convert | `SetState` — same API as Transform | Rules-identical to Transform per Jetz72. |

#### Game concepts (not 701.x actions)

In the `KeywordAction` enum for tooltip display but aren't keyword actions per the comprehensive rules:

| # | Concept | Detection | Notes |
|---|---------|----------|-------|
| — | Devotion | SVar `Count$Devotion.*` on `CardFace` | Game concept, not action |
| — | Domain | `Keyword` enum | Ability word — detected as keyword ability |
| — | Metalcraft | `Keyword` enum | Ability word — detected as keyword ability |
| — | Threshold | `Keyword` enum | Ability word — detected as keyword ability |
| — | Delirium | `Keyword` enum | Ability word — detected as keyword ability |

Domain, Metalcraft, Threshold, and Delirium are ability words with `Keyword` enum entries — they will appear as keyword ability `KeywordView` records, not action records. Devotion is detected via SVar and handled by `annotateKeywordCounts()` on the GUI side.

#### Detection summary

| Category | Count | Detection method |
|----------|-------|-----------------|
| Basic (excluded) | 19 | N/A — no records produced |
| Direct Effect class | 32 | `SpellAbility.getApiType()` match |
| Param on generic effect | 6 | `sa.hasParam("X$")` |
| Cost class | 5 | Cost type inspection |
| Attribute param | 2 | `AlterAttribute` + `Attributes$` |
| Oracle fallback needed | 3 | Fateseal, Transform, Convert |
| Game concepts | 5 | SVar / keyword ability (not action records) |
| **Total** | **67** (+5 concepts) | **45 API-detectable, 3 oracle, 19 basic** |

## Consumer Migration

### KeywordInfoUtil.buildKeywords()

**Before:** Parses `keywordKey` string, calls `Keyword.getInstance()` per token, builds `List<KeywordData>`.
**After:** Reads `List<KeywordView>` from `CardStateView.getKeywordViews()`. Either returns records directly (if callers migrate to `KeywordView`) or wraps them in `KeywordData` for backward compat:

```java
public static List<KeywordData> buildKeywords(CardStateView state) {
    return state.getKeywordViews().stream()
        .map(kv -> new KeywordData(kv.name(), kv.reminderText(), kv.typeParam()))
        .collect(Collectors.toList());
}
```

Eventually `KeywordData` can be removed entirely and callers use `KeywordView` directly.

### KeywordInfoUtil.addKeywordActions()

**Removed.** Actions are pre-computed as `KeywordView` records by the game layer. No oracle text scanning needed (except the 3 oracle-fallback actions which are handled game-side).

### KeywordInfoUtil.addMissingKeywordsFromFlags()

**Removed.** The records are the authoritative source — no need to cross-check boolean flags.

### KeywordInfoUtil.annotateKeywordCounts()

**Kept** as a GUI-side post-processing step. Dynamic counts (Devotion, Affinity, Domain) depend on current game state visible to the controller. The game layer *could* compute these, but they change every time the board changes and would cause excessive delta sync traffic. Cheaper to compute client-side from the player's battlefield.

### KeywordInfoUtil.sortByOracleText()

**Kept** initially. Sorting by oracle text appearance is a display concern. Could be moved game-side later if the records carry a sort key.

### FCardImageRenderer — Level up check

**Before:** `state.getKeywordKey().contains("Level up")`
**After:** `state.getKeywordViews().stream().anyMatch(kv -> kv.keyword() == Keyword.LEVEL_UP)`

Or add a `HasLevelUp` boolean flag (following existing pattern). The boolean flag is simpler and avoids iterating the list in a hot rendering path.

### VField — card state equality check

**Before:** `cardState.getKeywordKey().equals(cState.getKeywordKey())`
**After:** `cardState.getKeywordViews().equals(cState.getKeywordViews())`

Record auto-equals makes list equality work correctly.

### CardPanel / CardRenderer — icon rendering (Protection/Hexproof)

**Before:** Parse `getProtectionKey()` / `getHexproofKey()` compact strings.
**After:** Filter `getKeywordViews()` by keyword enum and read `typeParam`:

```java
// Protection icons — desktop CardPanel example
String protKey = state.getKeywordViews().stream()
    .filter(kv -> kv.keyword() == Keyword.PROTECTION)
    .map(KeywordView::typeParam)
    .filter(Objects::nonNull)
    .collect(Collectors.joining());
// protKey is now "RU", "everything", etc. — same format as old ProtectionKey
// Existing icon selection logic applies unchanged
```

```java
// Hexproof icons
String hexKey = state.getKeywordViews().stream()
    .filter(kv -> kv.keyword() == Keyword.HEXPROOF)
    .map(kv -> kv.typeParam() != null ? kv.typeParam() + ":" : "generic:")
    .collect(Collectors.joining());
// hexKey is now "generic:", "R:", etc. — same format as old HexproofKey
```

To avoid repeating this stream logic in both CardPanel (desktop) and CardRenderer (mobile), add convenience methods to `CardStateView`:

```java
public String deriveProtectionKey() {
    // stream + filter + join as above
}
public String deriveHexproofKey() {
    // stream + filter + join as above
}
```

These are **computed from** the records, not stored as separate properties. Icon rendering code calls these instead of `getProtectionKey()` / `getHexproofKey()`, with minimal changes to the icon selection logic itself.

## Files Affected

### New files
| File | Purpose |
|------|---------|
| `forge-game/.../card/KeywordView.java` | The record class |

### Modified files
| File | Change |
|------|--------|
| `forge-game/.../card/CardView.java` | Add `KeywordViews` property, `getKeywordViews()`, `deriveProtectionKey()`, `deriveHexproofKey()`. Modify `updateKeywords()` to compute records. Remove `KeywordKey`, `ProtectionKey`, `HexproofKey` properties. |
| `forge-game/.../trackable/TrackableProperty.java` | Add `KeywordViews`. Remove `KeywordKey`, `ProtectionKey`, `HexproofKey`. |
| `forge-game/.../trackable/TrackableTypes.java` | Add `KeywordViewListType`. Remove `KeywordCollectionViewType` TODO stub. |
| `forge-game/.../keyword/KeywordAction.java` | Add detection metadata (method, target string) per action. |
| `forge-game/.../card/Card.java` | Remove `getKeywordKey()`, `getProtectionKey()`, `getHexproofKey()`. |
| `forge-gui/.../card/KeywordInfoUtil.java` | Refactor `buildKeywords()` to consume records. Remove `addKeywordActions()`, `addMissingKeywordsFromFlags()`. |
| `forge-gui-desktop/.../arcane/CardPanel.java` | Migrate `getProtectionKey()` / `getHexproofKey()` calls to `deriveProtectionKey()` / `deriveHexproofKey()`. |
| `forge-gui-desktop/.../arcane/CardInfoPopup.java` | Migrate `buildKeywords(keywordKey, ...)` to `buildKeywords(state)`. |
| `forge-gui-desktop/.../special/CardZoomer.java` | Same migration. |
| `forge-gui-desktop/.../imaging/FCardImageRenderer.java` | Migrate Level-up check. |
| `forge-gui-mobile/.../card/CardRenderer.java` | Migrate `getProtectionKey()` / `getHexproofKey()` calls. |
| `forge-gui-mobile/.../match/views/VField.java` | Migrate equality check from `getKeywordKey()` to `getKeywordViews()`. |

## Implementation Order

### Step 1: Record + TrackableType infrastructure
- Create `KeywordView` record
- Add `KeywordViewListType` to `TrackableTypes`
- Add `KeywordViews` property to `TrackableProperty`
- Add `getKeywordViews()` to `CardStateView`
- Populate records in `updateKeywords()` for keyword abilities only (port merging logic from `buildKeywords()`)
- **Keep** `KeywordKey`, `ProtectionKey`, `HexproofKey` alongside for now

### Step 2: Keyword action computation
- Add detection metadata to `KeywordAction` enum
- Add action detection logic to `updateKeywords()` (or helper)
- Append action `KeywordView` records to the list

### Step 3: Migrate tooltip consumers
- Refactor `KeywordInfoUtil.buildKeywords()` to consume records
- Remove `addKeywordActions()` and `addMissingKeywordsFromFlags()`
- Update `CardInfoPopup`, `CardZoomer` callers

### Step 4: Migrate icon consumers + remove old properties
- Add `deriveProtectionKey()` / `deriveHexproofKey()` to `CardStateView`
- Migrate `CardPanel` (desktop) and `CardRenderer` (mobile)
- Migrate `FCardImageRenderer` Level-up check
- Migrate `VField` equality check
- Remove `KeywordKey`, `ProtectionKey`, `HexproofKey` from `TrackableProperty`
- Remove `getKeywordKey()`, `getProtectionKey()`, `getHexproofKey()` from `Card`

### Step 5: Cleanup
- Remove `KeywordCollectionViewType` TODO stub from `TrackableTypes`
- Remove `KeywordData` class if all callers migrated to `KeywordView`
- Remove `addKeywordActions()` oracle scanning infrastructure (if fully replaced)

## Part 2: hoveroptions Branch Cleanup

Once KeywordView records are implemented, the following code on the `hoveroptions` branch becomes redundant or can be significantly simplified. This section catalogs the cleanup opportunities.

### KeywordInfoUtil.java — near-complete elimination

The bulk of `KeywordInfoUtil` exists to re-parse keyword strings and scan oracle text. With pre-computed records, most of it goes away:

| Method | Lines | What happens |
|--------|-------|-------------|
| `buildKeywords()` | ~64–150 | **Removed.** Re-parsing via `Keyword.getInstance()`, parameterized merging (Protection, Equip display), and deduplication all move game-side. Replaced by direct read of `getKeywordViews()`. |
| `addKeywordActions()` | ~156–290 | **Removed.** Oracle text regex scanning for 67 keyword actions. Replaced by game-layer API detection. |
| `addMissingKeywordsFromFlags()` | ~297–323 | **Removed.** Cross-checking boolean flags against parsed keywords is unnecessary when records are authoritative. |
| `sortByOracleText()` | ~329–340 | **Kept initially**, or moved game-side if records carry a sort key. |
| `annotateKeywordCounts()` | ~348–686 | **Kept** as client-side post-processing. Dynamic counts (Devotion, Affinity, Domain) change with board state and are cheaper to compute locally. |
| `colorNamesToSymbols()` | ~729–737 | **Kept** if reminder text formatting stays GUI-side, otherwise moves game-side. |
| `KeywordData` inner class | ~40–56 | **Removed** once all callers migrate to `KeywordView`. |

**Net result:** ~500+ lines removed from KeywordInfoUtil. What remains is count annotation and possibly symbol formatting.

### Tooltip call sites — simplified orchestration

Both `CardInfoPopup` and `CardZoomer` currently orchestrate a 5-step pipeline. This collapses to 1–2 steps:

**CardInfoPopup.java** (~lines 285–304):
```java
// Before: 5 calls
keywords = buildKeywords(keywordKey, addedNames);
addMissingKeywordsFromFlags(keywords, state, addedNames);
addKeywordActions(keywords, oracleText, addedNames, cardName);
sortByOracleText(keywords, oracleText);
annotateKeywordCounts(keywords, cardView);

// After: 1-2 calls
keywords = state.getKeywordViews();  // or wrap in KeywordData if needed
annotateKeywordCounts(keywords, cardView);  // only step that remains
```

**CardZoomer.java** (~lines 329–343): Same simplification.

### Protection icon rendering — eliminate if-else chains

Both platforms have ~70-line if-else chains parsing `getProtectionKey()` strings. These become a single call to `deriveProtectionKey()`:

**CardPanel.java** (desktop, ~lines 710–782):
```java
// Before: 72-line if-else chain
if (getProtectionKey().contains("everything") || getProtectionKey().contains("allcolors")) { ... }
else if (getProtectionKey().contains("coloredspells")) { ... }
else if (getProtectionKey().equals("R")) { ... }
else if (getProtectionKey().equals("G")) { ... }
// ... 15+ more cases

// After: same logic, but reads from deriveProtectionKey()
// The if-else chain itself doesn't change — it just reads from a different source.
// Future cleanup: replace with a map lookup.
```

**CardRenderer.java** (mobile, ~lines 1157–1233): Same pattern, ~76 lines. Same migration.

**Note:** The if-else chains themselves are pre-existing code not introduced by `hoveroptions`. The cleanup here is changing the data source from `getProtectionKey()` to `deriveProtectionKey()`. A further cleanup (converting the chains to map lookups) is a separate improvement opportunity, not a direct consequence of KeywordView records.

### Hexproof icon rendering — minor simplification

**CardPanel.java** (~lines 640–647) and **CardRenderer.java** (~lines 1041–1045):
- `getHexproofKey().split(":")` parsing replaced by `deriveHexproofKey()`
- ~5 lines per platform

### FCardImageRenderer.java — Level up checks

Three occurrences of `getKeywordKey().contains("Level up")` at lines ~192, ~311, ~389:
- Replaced by `hasLevelUp()` boolean flag or `getKeywordViews().stream().anyMatch(...)` check
- Boolean flag preferred in a rendering hot path

### VField.java — token stacking equality

Two occurrences of `getKeywordKey().equals(cState.getKeywordKey())` at lines ~139, ~150:
- Replaced by `getKeywordViews().equals(cState.getKeywordViews())`
- Record auto-equals makes this work correctly

### CardRenderer.java — Flash/Flashback check

~Lines 829–835: `keywordKey.contains("Flash")` / `keywordKey.contains("Flashback")` parsing:
- Replaced by checking `KeywordView` records or existing boolean flags
- Minor — ~7 lines

### Cleanup summary

| Category | Files | Lines removed/simplified |
|----------|-------|------------------------|
| KeywordInfoUtil re-parsing & oracle scanning | KeywordInfoUtil.java | ~500 lines removed |
| Tooltip orchestration pipeline | CardInfoPopup.java, CardZoomer.java | ~35 lines simplified |
| Protection/Hexproof data source migration | CardPanel.java, CardRenderer.java | ~10 lines changed (data source swap) |
| Level up string check | FCardImageRenderer.java | ~3 lines changed |
| Token stacking equality | VField.java | ~2 lines changed |
| Flash/Flashback check | CardRenderer.java | ~7 lines simplified |
| **Total** | **7 files** | **~550+ lines removed, ~50 lines simplified** |

## Open Questions

1. **Merging logic location**: The Protection/Hexproof merging currently in `KeywordInfoUtil.buildKeywords()` needs to move game-side. Should it live in `CardStateView.updateKeywords()` directly, or in a `KeywordViewBuilder` helper class? The helper is cleaner but adds a new class (guidelines say avoid unless necessary).

2. **Keyword action detection location**: Should action detection live in `CardStateView.updateKeywords()` alongside ability record computation, or in a separate method on `Card`? `updateKeywords()` already has access to the `Card` object. Putting it there keeps it in one place but makes the method longer.

3. **Oracle text fallback for actions**: The 3 oracle-fallback actions (Fateseal, Transform, Convert) need oracle text scanning even in the new system. Should this scanning happen game-side (in the record computation) or remain GUI-side as a post-processing step? Game-side is cleaner (records are complete) but means `Card` needs to scan its own oracle text.

4. **Record immutability vs. count annotation**: `annotateKeywordCounts()` currently mutates `KeywordData` in place. With immutable records, it would need to create new `KeywordView` instances. Should we add a mutable wrapper for the GUI layer, or accept the allocation cost of creating new records?

5. **Keyword enum stability across versions**: Serialization uses `Keyword.ordinal()`. If a Keyword enum entry is inserted in the middle, ordinals shift and network clients on different versions break. Should we use `Keyword.name()` (string, slower but stable) instead? Or document that both sides must be the same version?

6. **`extractTypeParam` for icon keys**: Protection's `typeParam` needs to carry the compact color code (e.g., "RU") for icon derivation. The current `getProtectionKey()` method on `Card` does this analysis by inspecting `Protection.fromWhat`. Should `extractTypeParam()` replicate that logic, or can it call into the existing `getProtectionKey()` logic and decompose the result?
