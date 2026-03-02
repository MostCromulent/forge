# KeywordView Records Implementation Plan

## Goal

Replace the `keyword -> string -> re-parse -> extract display data` pipeline in the `hoveroptions` branch with pre-computed `KeywordView` records stored in `CardStateView`. Covers both keyword abilities (Flying, Protection, Escape) and keyword actions (Scry, Goad, Support). Replaces `KeywordKey`, `ProtectionKey`, and `HexproofKey` with a single structured collection.

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

After building ability records, detect keyword actions from the card's SpellAbilities, triggers, costs, and replacements. This is the Phase 2 detection from the [keyword detection refactor](keyword-detection-refactor.md):

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

The detection method and target string let the computation loop be data-driven rather than a giant switch statement. See the [keyword detection refactor](keyword-detection-refactor.md) tables for the full mapping.

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

## Open Questions

1. **Merging logic location**: The Protection/Hexproof merging currently in `KeywordInfoUtil.buildKeywords()` needs to move game-side. Should it live in `CardStateView.updateKeywords()` directly, or in a `KeywordViewBuilder` helper class? The helper is cleaner but adds a new class (guidelines say avoid unless necessary).

2. **Keyword action detection location**: Should action detection live in `CardStateView.updateKeywords()` alongside ability record computation, or in a separate method on `Card`? `updateKeywords()` already has access to the `Card` object. Putting it there keeps it in one place but makes the method longer.

3. **Oracle text fallback for actions**: The 3 oracle-fallback actions (Fateseal, Transform, Convert) need oracle text scanning even in the new system. Should this scanning happen game-side (in the record computation) or remain GUI-side as a post-processing step? Game-side is cleaner (records are complete) but means `Card` needs to scan its own oracle text.

4. **Record immutability vs. count annotation**: `annotateKeywordCounts()` currently mutates `KeywordData` in place. With immutable records, it would need to create new `KeywordView` instances. Should we add a mutable wrapper for the GUI layer, or accept the allocation cost of creating new records?

5. **Keyword enum stability across versions**: Serialization uses `Keyword.ordinal()`. If a Keyword enum entry is inserted in the middle, ordinals shift and network clients on different versions break. Should we use `Keyword.name()` (string, slower but stable) instead? Or document that both sides must be the same version?

6. **`extractTypeParam` for icon keys**: Protection's `typeParam` needs to carry the compact color code (e.g., "RU") for icon derivation. The current `getProtectionKey()` method on `Card` does this analysis by inspecting `Protection.fromWhat`. Should `extractTypeParam()` replicate that logic, or can it call into the existing `getProtectionKey()` logic and decompose the result?
