# Advisory: Byte-Level DeltaSyncManager vs. TrackableObject.changedProps

## Context

The NetworkPlay/main branch introduces ~1500 lines of new serialization infrastructure for network delta sync:
- `DeltaSyncManager` (866 lines) — walks object graph, detects changes, serializes to `byte[]`
- `NetworkPropertySerializer` (583 lines) — type-dispatch binary serialization for each TrackableProperty type
- `NetworkTrackableSerializer` (87 lines) — binary DataOutputStream adapter

Meanwhile, TrackableObject already has a built-in change tracking system:
- `changedProps` (EnumSet) — populated automatically when `set()` is called
- `serialize(TrackableSerializer)` — iterates changedProps, writes only changed properties
- `TrackableTypes` — each type already has `serialize(TrackableSerializer, T)` and `deserialize(TrackableDeserializer, T)`

The question: is the byte-level DeltaSyncManager approach justified, or could changedProps be leveraged more directly?

## Analysis

### What changedProps already provides
- **Automatic property-level change detection** at `TrackableObject.java:67-90` — every call to `set()` compares values and adds to `changedProps`
- **Delta serialization** at `TrackableObject.java:116-123` — `serialize()` iterates only changedProps, writes ordinal + value, then clears
- **Per-type serialization dispatch** via `TrackableTypes.java` — every TrackableType has a `serialize(ts, value)` method already

### What DeltaSyncManager does differently (and why)

**1. Per-client change tracking (`lastSentPropertyChecksums`)**

This is the real motivator. `changedProps` is a single global set per object. In a 3-4 player game, each remote client has its own DeltaSyncManager. If client A's manager calls `clearChanges()`, client B's manager would miss those changes.

DeltaSyncManager solves this by maintaining its own change detection via property hashCode checksums (line 369-443). It never reads `changedProps` at all for existing objects — it compares `computePropertyChecksum(value)` against `lastSentPropertyChecksums`.

**This is valid but arguably over-engineered.** Simpler alternatives:
- Per-client `EnumSet<TrackableProperty>` inside TrackableObject (or a thin wrapper)
- A monotonic version counter per property — each client tracks what version it last sent
- A timestamp/sequence approach: mark each property change with a sequence number, each client tracks its high-water mark

Any of these would allow reusing changedProps' *mechanism* while fixing the multi-client issue.

**2. Binary serialization format**

The existing `TrackableSerializer` is text-based with delimiter `(char)5`, writing to `BufferedWriter` (file I/O). Not suitable for network.

`NetworkTrackableSerializer` writes to `DataOutputStream` in binary. `NetworkPropertySerializer` has 583 lines of type-specific binary encoding.

**However, note that `TrackableTypes` already has per-type `serialize(TrackableSerializer, T)` methods.** The text-based TrackableSerializer and the binary NetworkTrackableSerializer have nearly identical method signatures (`write(String)`, `write(boolean)`, `write(int)`, `write(float)`, `write(TrackableCollection)`). If TrackableSerializer were refactored to an interface, a binary implementation could be dropped in with minimal new code, reusing all the existing type dispatch in TrackableTypes.

The remaining gap: `NetworkPropertySerializer` handles some types differently for network efficiency (e.g., writing CardView/PlayerView as ID-only references, inline CardStateView serialization, MARKER_NULL/PRESENT/SKIP protocol). Some of this is genuinely network-specific optimization. But much of it is straightforward type dispatch that parallels what TrackableTypes already does.

**3. Object graph walking and new/removed object detection**

`DeltaSyncManager.collectDeltas()` walks GameView -> Players -> Zones -> Cards -> Attachments -> Stack -> Combat. It tracks which objects have been sent (`sentObjectIds`) and which are new or removed.

**This is genuinely new functionality** that has no equivalent in the existing system. TrackableObject.serialize() handles a single object — it doesn't know about the game hierarchy. Something must walk the graph.

This part of DeltaSyncManager is well-justified and would be needed regardless of approach.

### The duplication concern

`NetworkPropertySerializer.serialize()` is a 500+ line switch on TrackableType that mirrors the type dispatch already in `TrackableTypes.java`. Every time a new TrackableProperty type is added, both must be updated. This is the core maintenance burden.

Compare:
- `TrackableTypes.BooleanType.serialize(ts, value)` — already handles Boolean serialization
- `NetworkPropertySerializer.serialize()` line 74-77 — writes MARKER_PRESENT + Boolean to DataOutputStream

These do the same thing via different code paths.

## Recommendation

**The changedProps mechanism provides the core change detection and per-type serialization dispatch that DeltaSyncManager reimplements at the byte level.** A more class-based approach would reduce duplication significantly.

### What to keep
- **DeltaSyncManager's graph walking** (collectDeltas, collectPlayerDeltas, collectCardDelta) — this is necessary and has no existing equivalent
- **The DeltaPacket/FullStatePacket transport model** — well-structured
- **Binary format for network** — text-based serialization is not suitable

### What could be refactored toward changedProps
1. **Extract a Serializer interface** from TrackableSerializer so binary and text implementations share the same contract. TrackableTypes' existing `serialize(ts, value)` methods would then work for both file I/O and network without duplication.

2. **Per-client change tracking at the object level** instead of per-client checksum comparison in DeltaSyncManager. Options:
   - `Map<Integer, EnumSet<TrackableProperty>>` per client (keyed by DeltaSyncManager identity)
   - Or a simple sequence counter: each `set()` increments a per-property version, each client tracks its high-water mark

   Either approach would let DeltaSyncManager query "what changed since I last asked?" from the object itself, rather than maintaining a parallel checksum system.

3. **NetworkPropertySerializer shrinks dramatically** — the bulk of its type dispatch is redundant with TrackableTypes. What remains would be network-specific optimizations (ID-only references, inline objects, null markers).

### Caveat: module boundaries
One counterargument: TrackableObject lives in `forge-game`, while network code is in `forge-gui`. Adding network-awareness to TrackableObject might be seen as polluting the game engine. However, per-client change tracking is really about "multiple consumers of change data" — it's not inherently network-specific. The freeze mechanism already handles a similar multi-consumer pattern.
