# NetworkPlay/main — Delta Synchronization

## Table of Contents

1. [Overview](#overview)
2. [How to Review This PR](#how-to-review-this-pr)
3. [Branch Scope](#branch-scope)
4. [Architectural Overlap with Forge Master Branch](#architectural-overlap-with-forge-master-branch)
5. [Impact on Non-Network Games](#impact-on-non-network-games)
6. [Delta Synchronization](#delta-synchronization)
   - [Problem Statement](#problem-statement)
   - [Solution Architecture](#solution-architecture)
   - [Core Components](#core-components)
   - [Protocol Methods](#protocol-methods)
   - [Data Flow](#data-flow)
   - [Checksum Validation & Auto-Resync](#checksum-validation--auto-resync)
   - [Failure Modes & Error Recovery](#failure-modes--error-recovery)
7. [Files Modified](#files-modified)
   - [Production Code](#production-code)
   - [Testing Code](#testing-code)
8. [Configuration](#configuration)
9. [Debugging](#debugging)
10. [Known Bugs](#known-bugs)
11. [Known Limitations](#known-limitations)
12. [Authorship](#authorship)

---

## Overview

The `NetworkPlay/main` branch introduces **delta synchronization** for Forge's network multiplayer. Instead of sending the complete `GameView` object on every state update, only changed properties are transmitted. Combined with LZ4 compression (pre-existing in the network layer), this achieves **~98% bandwidth reduction** compared to the original full-state approach (measured as actual network bytes vs LZ4-compressed full-state baseline — see [Bandwidth Monitoring](#6-bandwidth-monitoring) for methodology).

This branch also includes a comprehensive **automated testing infrastructure** for validating network play across 2–4 player configurations. Testing is documented separately in [Testing-Consolidated.md](https://github.com/MostCromulent/forge/blob/NetworkPlay/dev/.documentation/Testing-Consolidated.md).

---

## Recommended PR review order

1. **Core engine changes (smallest, highest impact)** — These files modify shared non-network code:
   - `forge-game/.../trackable/TrackableObject.java` — 3 new query methods + `set()` visibility widened from `protected` to `public` (see [Impact on Non-Network Games](#impact-on-non-network-games))
   - `forge-gui/.../match/AbstractGuiGame.java` — 4 no-op stub methods (~22 lines)
   - `forge-gui/.../match/IGuiGame.java` — 4 new method signatures
   - `forge-gui/.../match/IGameController.java` — 2 new method signatures
   - `forge-gui/.../match/GameLobby.java` — Reordered `onGameStarted()` hook (+8 lines)

2. **Protocol and serialization (the core of delta sync):**
   - `DeltaPacket.java`, `FullStatePacket.java` — Packet definitions
   - `NetworkPropertySerializer.java` — Custom binary serialization (~584 lines)
   - `DeltaSyncManager.java` — Server-side delta collection (~849 lines)

3. **Client-side deserialization:**
   - `NetworkGuiGame.java` — Delta application, checksum validation, tracker management (~932 lines)

4. **Integration glue (lower priority):**
   - `NetGuiGame.java`, `GameServerHandler.java`, `GameClientHandler.java`, `ProtocolMethod.java`

5. **Testing infrastructure (can be reviewed separately):**
   - Everything under `forge-gui-desktop/src/test/java/forge/net/`

---

## Branch Scope

This branch contains **only** delta sync and testing. Other NetworkPlay features have been extracted to separate branches:

| Feature | Branch                     | Status               |
|---------|----------------------------|----------------------|
| **Delta Synchronization** | `NetworkPlay/main`         | This branch          |
| **Automated Testing** | `NetworkPlay/main`         | This branch          |
| Reconnection Support | `NetworkPlay/reconnection` | Extracted            |
| Enhanced Chat Notifications | `NetworkPlay/chat`         | Extracted and merged |
| Network UI Improvements | `NetworkPlay/ui`           | Extracted            |

The total network-relevant diff is **roughly** ~12,500 lines: ~4,900 production and ~7,600 testing.

---

## Architectural Overlap with Forge Master Branch

The delta sync implementation modifies several core (non-network) classes. While these modifications enable significant performance improvements, they create integration considerations with ongoing Forge development.

### Summary of Core Class Changes

| File | Module | Modification |
|------|--------|-------------|
| `TrackableObject.java` | forge-game | Added 3 delta sync methods: `hasChanges()`, `getChangedProps()`, `clearChanges()`; changed `set()` visibility from `protected` to `public` |
| `AbstractGuiGame.java` | forge-gui | Added network imports + stub method implementations (network logic in NetworkGuiGame) |
| `NetworkGuiGame.java` | forge-gui | **NEW** — Network deserialization subclass (~930 lines) |
| `GameLobby.java` | forge-gui | Reordered `onGameStarted()` execution sequence |
| `IGameController.java` | forge-gui | Added 2 network protocol methods (`ackSync`, `requestResync`) |
| `IGuiGame.java` | forge-gui | Added delta sync method signatures (`applyDelta`, `fullStateSync`) |

### Architecture: Network Code Isolation

Network-specific deserialization logic is isolated into a dedicated subclass hierarchy:

```
AbstractGuiGame (core game logic, ~900 lines)
    ↑ extends
NetworkGuiGame (network deserialization, ~930 lines)
    ↑ extends
NetGuiGame (server-side network proxy)
```

All network logic (~930 lines) is extracted to the `NetworkGuiGame` subclass. Master branch merges will have minimal conflicts in `AbstractGuiGame`.

---

## Impact on Non-Network Games

Local (non-network) games are unaffected by this branch. Here is why:

**`changedProps` tracking already exists on master.** The `TrackableObject` base class already maintains a `changedProps` `EnumSet` on the master branch — it is populated by `set()` and consumed/cleared by the existing `serialize()`/`deserialize()` cycle used by the current network protocol. This branch adds only 3 query methods (`hasChanges`, `getChangedProps`, `clearChanges`) which are never called outside network code. No new per-property overhead is introduced for local games.

**`set()` visibility widened from `protected` to `public`.** This is required so that `NetworkGuiGame` (which is not a subclass of `TrackableObject`) can apply delta property updates to arbitrary game view objects. This does not change runtime behavior — it only widens access. However, it loosens encapsulation: any code can now call `set()` on a `TrackableObject`, not just subclasses. This is the most invasive change to the game engine in this branch.

**Stub methods in `AbstractGuiGame` and interfaces.** The 4 new no-op stubs in `AbstractGuiGame` (`applyDelta`, `fullStateSync`, `setRememberedActions`, `nextRememberedAction`) are never called in local games. The corresponding `IGuiGame` interface signatures add no overhead — they only require that implementing classes provide an implementation (the stubs in `AbstractGuiGame` satisfy this for all existing subclasses).

**Net cost to non-network games: zero runtime overhead.** The only structural change is the widened `set()` visibility.

---

## Delta Synchronization

### Problem Statement

The original network protocol sent the entire `GameView` object on every state update. For complex board states, this resulted in high bandwidth consumption, increased latency, and poor performance on slower connections.

### Solution Architecture

**Original System (Master Branch):**
- Sends complete `GameView` on every update via `ObjectOutputStream` + LZ4
- Typical game: ~116MB transmitted (LZ4-compressed)

**New System (Delta Sync):**
- Sends full data only for new objects (cards drawn, tokens created, etc.)
- Sends only changed properties for existing objects
- Uses same `ObjectOutputStream` + LZ4 pipeline
- Typical game: ~2.3MB transmitted (measured actual network bytes)

**Result:** ~98% bandwidth reduction (measured as actual network bytes vs LZ4-compressed full-state baseline; 98.0% in 100-game validated test run, 2026-02-07)

```
Full State Approach (Master Branch):
┌─────────┐                                      ┌─────────┐
│ Server  │  Every action sends complete state   │ Client  │
│         │ ═══════════════════════════════════> │         │
│ ~1.2 MB │  ObjectOutputStream + LZ4            │ ~1.2 MB │
└─────────┘                                      └─────────┘
         Result: ~116 MB for typical game

Delta Sync Approach (NetworkPlay/main):
┌─────────┐                                      ┌─────────┐
│ Server  │  Initial: Full state                 │ Client  │
│         │ ═══════════════════════════════════> │         │
│         │  ~1.2 MB (one time)                  │         │
│         │                                      │         │
│         │  Updates: Only changes               │         │
│         │ ───────────────────────────────────> │         │
│ ~38 B   │  ~38 bytes delta payload (typical)   │ ~38 B   │
└─────────┘                                      └─────────┘
         Result: ~2.3 MB actual network bytes (~98% savings)
```

### Core Components

#### 1. TrackableObject Change Tracking (`forge-game/.../trackable/TrackableObject.java`)

The existing `TrackableObject` class (base class for all game view objects) was extended with three delta query methods and one visibility change:

```java
// New methods (added by this branch):
public boolean hasChanges()                      // Check if object has pending changes
public Set<TrackableProperty> getChangedProps()  // Get changed properties (unmodifiable)
public void clearChanges()                       // Clear change flags after acknowledgment

// Existing method (visibility widened from protected to public):
public <T> void set(TrackableProperty key, T value)  // Was: protected
```

The `changedProps` tracking (`EnumSet<TrackableProperty>`) already exists on master — `set()` populates it, and the existing `serialize()`/`deserialize()` cycle consumes and clears it. This branch adds only the three query methods above. The `set()` visibility change is required so that `NetworkGuiGame` can apply delta property updates to arbitrary `TrackableObject` instances (see [Impact on Non-Network Games](#impact-on-non-network-games)).

#### 2. DeltaPacket (`forge-gui/.../gamemodes/net/DeltaPacket.java`)

A serializable packet containing delta updates and new objects:

| Field | Type | Description |
|-------|------|-------------|
| `sequenceNumber` | `long` | Monotonically increasing sequence for ordering |
| `objectDeltas` | `Map<Integer, byte[]>` | Composite delta key → serialized changed properties |
| `newObjects` | `Map<Integer, NewObjectData>` | Composite delta key → full object data (newly created) |
| `removedObjectIds` | `Set<Integer>` | Composite keys of objects that no longer exist |
| `checksum` | `int` | Optional state checksum for validation (0 if not included) |

**NewObjectData** contains: `objectId`, `objectType` (TYPE_CARD_VIEW=0, TYPE_PLAYER_VIEW=1, etc.), and `fullProperties` (all properties serialized in compact binary format).

**Composite Delta Keys:** Object IDs are encoded as composite keys `(type << 28) | (id & 0x0FFFFFFF)` to prevent collisions between different object types that may share the same raw ID (e.g., CardView id=5 vs StackItemView id=5).

#### 3. FullStatePacket (`forge-gui/.../gamemodes/net/FullStatePacket.java`)

Used for initial connection:

| Field | Type | Description |
|-------|------|-------------|
| `sequenceNumber` | `long` | Current sequence number |
| `gameView` | `GameView` | Complete game state |
| `stateChecksum` | `int` | Checksum of the game state for validation |

#### 4. DeltaSyncManager (`forge-gui/.../gamemodes/net/server/DeltaSyncManager.java`)

Server-side manager (~850 lines) that:
- Tracks which objects exist (`trackedObjectIds`) and which have been sent to the client (`sentObjectIds`)
- Distinguishes new objects (need full serialization) from existing objects (only send changes)
- Uses **per-client change tracking** via `lastSentPropertyChecksums` — this is critical for 3–4 player games where multiple `DeltaSyncManager` instances share the same `GameView`
- Builds delta packets by walking the entire GameView hierarchy (players, zones, cards, attachments, stack, combat)
- Manages client acknowledgments
- Detects removed objects
- Periodically includes checksums for validation (every 20 packets)
- Includes safety limits against stack overflow (max attachment depth: 20, max collection size: 1000)

```java
DeltaPacket collectDeltas(GameView gameView)     // Build delta packet
void markObjectsAsSent(GameView gameView)        // Mark objects after full sync
void processAcknowledgment(int clientIndex, long seq)  // Handle client ack
boolean needsFullResync(int clientIndex)          // Check if client >100 packets behind
FullStatePacket createFullStatePacket(GameView)   // Create full state for initial sync
void clearAllChanges(GameView gameView)           // Clear change flags
```

**Per-client change tracking:** Rather than relying on `TrackableObject.hasChanges()` (which gets cleared by the first client), each `DeltaSyncManager` records property value checksums per object. On each delta collection, it compares current property checksums against its own last-sent values to detect changes independently. This fixes a bug where the first client's delta collection would clear change flags, causing subsequent clients to miss updates.

#### 5. Compact Binary Serialization

Custom binary serialization minimizes packet sizes:

- **NetworkPropertySerializer** — Type-aware property serialization that writes primitives and collections efficiently
- **NetworkTrackableSerializer** — Writes `TrackableObject` references as 4-byte IDs instead of full object graphs
- **NetworkTrackableDeserializer** — Reads `TrackableObject` references by looking up IDs in the client's Tracker

This approach reduced CardView serialization from ~96KB (full Java serialization) to ~200 bytes (99.8% reduction).

#### 6. Bandwidth Monitoring

`NetGuiGame` includes bandwidth tracking (controlled via `NetworkDebugConfig`) that measures three values per packet:

1. **Approximate Size** — Theoretical minimum from `DeltaPacket.getApproximateSize()` (excludes serialization overhead and compression)
2. **Actual Network Bytes** — Real bytes measured by `NetworkByteTracker` in the encoder (ground truth)
3. **Full State Estimate** — What would be sent without delta sync (LZ4-compressed `ObjectOutputStream` of entire `GameView`)

### Protocol Methods

**This branch introduces a NEW protocol** — clients and servers must both use NetworkPlay branch code.

New protocol methods added to `ProtocolMethod.java` (`forge-gui/.../gamemodes/net/ProtocolMethod.java`):

**Server → Client:**
- `applyDelta(DeltaPacket)` — Apply incremental changes
- `fullStateSync(FullStatePacket)` — Full state for initial connection

**Client → Server:**
- `ackSync(long sequenceNumber)` — Acknowledge received delta
- `requestResync()` — Request full state resync (automatic desync recovery)

### Data Flow

```
Normal Update Flow:
┌────────────────┐    ┌─────────────────┐    ┌────────────────┐
│  Game Engine   │───>│ DeltaSyncManager│───>│  DeltaPacket   │
│ (modifies state)    │ (collects changes)   │ (minimal data) │
└────────────────┘    └─────────────────┘    └───────┬────────┘
                                                     │
                                                     ▼
┌────────────────┐    ┌─────────────────┐    ┌────────────────┐
│     Client     │<───│   NetGuiGame    │<───│    Network     │
│ (applies delta)     │ (sends packet)       │  (transport)   │
└───────┬────────┘    └─────────────────┘    └────────────────┘
        │
        │ ackSync(sequenceNumber)
        ▼
┌────────────────┐
│ DeltaSyncManager│
│(tracks ack)     │
└────────────────┘
```

### Checksum Validation & Auto-Resync

**Server-Side** (`DeltaSyncManager.java`):
- Computes state checksum every 20 packets (`CHECKSUM_INTERVAL`)
- Checksum includes: turn number, phase ordinal, player IDs and life totals (sorted by player ID for consistency)
- **Bug #16 fix:** Captures turn/phase values at the *start* of delta collection to avoid race conditions with the game loop advancing phase mid-collection

**Client-Side** (`NetworkGuiGame.java`):
- Validates checksum when present in received packet
- Computes local checksum using same algorithm
- On mismatch: Calls `requestResync()` → server sends `FullStatePacket` → client resets to authoritative state

```
Auto-Recovery Flow:
1. Client receives DeltaPacket with checksum
2. Client computes local checksum
3. If mismatch:
   a. Client sends requestResync protocol message
   b. Server sends full state via sendFullState()
   c. Client resets to authoritative state
4. Game continues seamlessly
```

### Failure Modes & Error Recovery

| Scenario | Detection | Recovery | Player Experience |
|----------|-----------|----------|-------------------|
| **Checksum mismatch** | Client computes local checksum every 20 packets; mismatch triggers resync | Client sends `requestResync()` → server replies with `FullStatePacket` → client resets to authoritative state | Seamless — game continues without player action. A brief visual "jump" may occur as the UI resets to the corrected state. |
| **Client falls >100 packets behind** | `DeltaSyncManager.needsFullResync()` checks `sequenceNumber - lastAcked > 100` | Server forces a full state sync on next update | Seamless — equivalent to initial connection sync. |
| **Delta serialization error** | `try-catch` around `collectDeltas()` in `NetGuiGame.updateGameView()` | Falls back to sending complete `GameView` via the original `setGameView` protocol method (graceful degradation) | Seamless — client receives full state instead of delta. Logged as a warning. |
| **Delta deserialization error** | `try-catch` in `NetworkGuiGame.applyDelta()` | Logged; corrupted packet is skipped. Next checksum-bearing packet will detect the desync and trigger auto-resync. | Game may display stale state for up to 20 packets (~20 game actions) until the next checksum triggers resync. |
| **Packet ordering** | Not explicitly enforced by application code | Relies on TCP ordering guarantees via Netty channels. Sequence numbers are included in packets but are used for acknowledgment tracking, not reordering. | N/A — TCP ensures in-order delivery. |
| **Network disconnection** | Netty channel close event | Not handled by this branch (reconnection support is on `NetworkPlay/dev`). The game ends for the disconnected player. | Game terminates for the disconnected player. |

**No retry limit on resync:** There is no cap on how many times a client can request a full resync. In practice, repeated resyncs indicate a systematic deserialization bug rather than transient issues — the bandwidth logging and checksum details in the network log file provide the data needed to diagnose the root cause.

**No game pause during resync:** The game engine continues running on the server while a resync is in flight. The client may miss delta packets sent between requesting and receiving the resync, but the `FullStatePacket` includes the current sequence number, and subsequent deltas will be applied normally from that point.

---

## Files Modified

### Production Code

Roughly ~4,900 lines added vs master. Organized by layer:

#### Core Game Engine (`forge-game`)

| File | Description |
|------|-------------|
| `trackable/TrackableObject.java` | +30 — Added `hasChanges()`, `getChangedProps()`, `clearChanges()`; widened `set()` to `public` |
| `GameSnapshot.java` | +60 — Snapshot creation for state validation |
| `GameView.java` | +8 — Network export support |
| `Match.java` | +25 — Match state management |
| `Card.java` | +45 — Card state tracking |
| `Player.java` | +13 — Player state tracking |
| `SpellAbility.java` | +9 — Spell ability state tracking |
| `StackItemView.java` | +8 — Network deserialization constructor |
| `CardProperty.java` | +8 — Card property definitions |
| Various zone/combat/effect files | ~70 — Minor state tracking updates |

#### Network Protocol & Serialization (`forge-gui/.../gamemodes/net/`)

| File | Description |
|------|-------------|
| **DeltaPacket.java** | **NEW** +181 — Delta update packet with `NewObjectData` inner class |
| **FullStatePacket.java** | **NEW** +74 — Full state packet with checksum |
| **NetworkPropertySerializer.java** | **NEW** +584 — Type-aware compact property serialization |
| **NetworkTrackableSerializer.java** | **NEW** +87 — Writes TrackableObjects as 4-byte IDs |
| **NetworkTrackableDeserializer.java** | **NEW** +215 — Reads TrackableObjects by ID lookup from Tracker |
| **NetworkDebugLogger.java** | **NEW** +729 — Configurable debug logging with per-instance file support |
| **NetworkDebugConfig.java** | **NEW** +102 — Configuration reader (PreferencesStore pattern) |
| **NetworkDebugPreferences.java** | **NEW** +84 — Preference definitions and defaults |
| **NetworkByteTracker.java** | **NEW** +139 — Bandwidth monitoring and metrics |
| **NetworkGameEventListener.java** | **NEW** +131 — Game event capture for analysis |
| `ProtocolMethod.java` | +11 — Added 4 delta sync protocol methods |
| `CompatibleObjectEncoder.java` | +13 — Network-safe object encoding |
| `NetConnectUtil.java` | +27 — Connection utilities |
| `GameProtocolHandler.java` | +15 — Protocol message handling |

#### Server-Side (`forge-gui/.../gamemodes/net/server/`)

| File | Description |
|------|-------------|
| **DeltaSyncManager.java** | **NEW** +849 — Delta collection, per-client tracking, checksum validation |
| `NetGuiGame.java` | +265 — Delta sync integration, bandwidth monitoring, full state sending |
| `FServerManager.java` | +68 — Server management |
| `RemoteClient.java` | +21 — Client state tracking |
| `ServerGameLobby.java` | +13 — Initialize game session on start |

#### Client-Side (`forge-gui/.../gamemodes/net/client/`)

| File | Description |
|------|-------------|
| `FGameClient.java` | +15 — Client connection handling |
| `GameClientHandler.java` | +63 — Handle delta sync events |
| `NetGameController.java` | +10 — `ackSync()` method |

#### GUI & Interfaces

| File | Description |
|------|-------------|
| **NetworkGuiGame.java** | **NEW** +932 — Network deserialization subclass (delta application, checksum validation) |
| `AbstractGuiGame.java` | +28 — Network stub methods |
| `IGuiGame.java` | +26 — Delta sync method signatures |
| `IGameController.java` | — Added `ackSync()`, `requestResync()` |
| `PlayerControllerHuman.java` | +17 — Stub implementations |
| `GameLobby.java` | +8 — Execution order fix |
| `CMatchUI.java` | +39 — Desktop match UI network support |
| Other GUI files | ~35 — Minor integration (InputQueue, InputLockUI, VSubmenuOnlineLobby, etc.) |

### Testing Code

Roughly ~7,600 lines added vs master. All in `forge-gui-desktop/src/test/java/`.

Testing is documented separately in [Testing-Consolidated.md](https://github.com/MostCromulent/forge/blob/NetworkPlay/dev/.documentation/Testing-Consolidated.md).

#### Integration Test Framework (`forge/net/`)

| File | Description |
|------|-------------|
| **NetworkPlayIntegrationTest.java** | +591 — Main test suite (unit, single-game, batch, comprehensive) |
| **UnifiedNetworkHarness.java** | +952 — Central test harness for 2–4 players, local/remote modes |
| **ComprehensiveTestExecutor.java** | +371 — Batch test runner with 2p/3p/4p support |
| **HeadlessNetworkClient.java** | +595 — Headless client for remote player simulation |
| **HeadlessNetworkGuiGame.java** | +348 — Headless GUI for network clients |
| **MultiProcessGameExecutor.java** | +660 — Parallel game execution infrastructure |
| **ComprehensiveGameRunner.java** | +207 — Individual comprehensive test runner |

#### Test Utilities & Analysis (`forge/net/` and `forge/net/analysis/`)

| File | Description |
|------|-------------|
| **DeltaSyncUnitTest.java** | +687 — Unit tests for DeltaPacket, DeltaSyncManager, NetworkByteTracker |
| **NetworkLogAnalyzer.java** | +736 — Analyzes network logs for bandwidth, desync patterns |
| **AnalysisResult.java** | +775 — Result data structures for test analysis |
| **GameLogMetrics.java** | +388 — Metrics extraction from network logs |
| **GameEventListener.java** | +267 — Game event capture and analysis |
| **TestConfiguration.java** | +237 — Test configuration management |
| **HeadlessGuiDesktop.java** | +222 — Headless desktop GUI for CI |
| **LocalNetworkTestHarness.java** | +211 — Local network game harness |
| **TestDeckLoader.java** | +196 — Loads precon decks for testing |
| **PortAllocator.java** | +105 — Network port allocation for tests |
| **GameTestMode.java** | +64 — Test mode configuration |

---

## Configuration

### Delta Sync Toggle

Delta sync is enabled by default (`useDeltaSync = true` in `NetGuiGame.java`). It can be toggled programmatically per client:

```java
netGuiGame.setDeltaSyncEnabled(false); // Falls back to full state sync
```

**Automatic fallback** occurs when:
1. Delta sync is explicitly disabled
2. Initial sync hasn't been sent yet (first connection)
3. Any error occurs during delta serialization (graceful degradation)

There is currently no user-facing UI toggle — the setting is programmatic only.

### Network Debug Preferences

Debug and bandwidth logging settings are stored using Forge's `PreferencesStore` pattern in:
```
{USER_PREFS_DIR}/network.preferences
```
(e.g., `%APPDATA%\Forge\preferences\network.preferences` on Windows)

| Setting | Default | Description |
|---------|---------|-------------|
| `BANDWIDTH_LOGGING_ENABLED` | `true` | Enable bandwidth comparison logging in delta sync |
| `DEBUG_LOGGER_ENABLED` | `true` | Enable the NetworkDebugLogger (file + console output) |
| `CONSOLE_LOG_LEVEL` | `INFO` | Minimum level for console output |
| `FILE_LOG_LEVEL` | `DEBUG` | Minimum level for log file output |
| `MAX_LOG_FILES` | `10` | Maximum log files to retain (0 = unlimited) |
| `LOG_CLEANUP_ENABLED` | `true` | Automatically delete old logs when limit exceeded |

Changes require restarting Forge.

---

## Debugging

### NetworkDebugLogger

All network code uses `NetworkDebugLogger` for logging (never `System.out.println()` or raw `Logger` calls). This ensures logs are captured in network log files for analysis.

#### Log Levels

| Level | Priority | Purpose | Console Default | File Default |
|-------|----------|---------|-----------------|--------------|
| `DEBUG` | 0 | Detailed tracing (hex dumps, per-property details, collection contents) | OFF | ON |
| `INFO` | 1 | Normal operation (sync start/end, summaries, important events) | ON | ON |
| `WARN` | 2 | Potential issues (missing objects, unexpected states) | ON | ON |
| `ERROR` | 3 | Failures and exceptions | ON | ON |

#### Log File Location

Log files are written to `{USER_DIR}/networklogs/` where `USER_DIR` is the Forge user profile directory (defined in `ForgeConstants.NETWORK_LOGS_DIR`). On Windows this is typically `%APPDATA%/Forge/networklogs/`.

```
networklogs/network-debug-20250121-075900-12345.log
```

Filename includes timestamp (YYYYMMDD-HHMMSS) and process ID (for distinguishing multiple instances).

**Log File Header:** Each file includes system diagnostics (Java version, max memory, OS, processor count).

#### Log Management

When `LOG_CLEANUP_ENABLED=true`, the logger automatically deletes old log files when the total exceeds `MAX_LOG_FILES`. Files modified within the last 5 minutes are never deleted (grace period for concurrent instances).

#### Using Debug Logging in Code

```java
NetworkDebugLogger.debug("[Feature] Processing object %d", objectId);    // File only by default
NetworkDebugLogger.log("[Feature] Sync completed: %d objects", count);   // Console + file (INFO)
NetworkDebugLogger.warn("[Feature] Object %d not found", objectId);      // Console + file
NetworkDebugLogger.error("[Feature] Failed: %s", e.getMessage());        // Always logged
NetworkDebugLogger.error("[Feature] Exception:", exception);             // Stack trace
```

#### Hex Dump for Serialization Debugging

```java
NetworkDebugLogger.hexDump("[DeltaSync] Delta bytes:", byteArray, errorPosition);
```

### Bandwidth Logging

Controlled via `BANDWIDTH_LOGGING_ENABLED` preference. When enabled, each delta packet logs three measurements:

```
[DeltaSync] Packet #1: Approximate=320 bytes, ActualNetwork=450 bytes, FullState=1200 bytes
[DeltaSync]   Savings: Approximate=73%, Actual=62% | Cumulative: ...
```

| Measurement | Source | What It Measures |
|-------------|--------|-----------------|
| **Approximate Size** | `DeltaPacket.getApproximateSize()` | Theoretical minimum payload (excludes serialization overhead, compression) |
| **Actual Network Bytes** | `NetworkByteTracker` (in `CompatibleObjectEncoder`) | Real bytes on the wire (ground truth) |
| **Full State Estimate** | `estimateFullStateSize()` (LZ4-compressed ObjectOutputStream) | Baseline for comparison |

**Why cumulative savings (~98%) exceed per-packet savings (60-70%):** Early-game packets include many new objects requiring full serialization (50-70% savings). Late-game packets are mostly small deltas on existing objects (95-99% savings). The ~98% figure is the cumulative average across full games, measured as actual network bytes vs LZ4-compressed full-state baseline (apples-to-apples comparison). An earlier methodology that compared delta bytes against *uncompressed* full state produced an inflated 99.5% figure — this was corrected.

**Bandwidth monitoring performance impact:** When `BANDWIDTH_LOGGING_ENABLED=true` (the default), each delta packet triggers an `estimateFullStateSize()` call that LZ4-compresses the entire `GameView` for comparison purposes. This adds ~1-2% overhead. When disabled, the `NetworkByteTracker` is null and no estimation occurs — 0% overhead. The delta collection traversal itself (`DeltaSyncManager.collectDeltas()`) is inherent to the protocol and always runs; it walks the full GameView hierarchy on every update. This has not been profiled separately on complex board states (e.g., 4-player commander with 50+ permanents each), but includes safety limits (max attachment depth: 20, max collection size: 1000) to bound worst-case traversal.

---

## Known Bugs

See [Debugging.md](https://github.com/MostCromulent/forge/blob/NetworkPlay/dev/.documentation/Debugging.md) for the full list of known bugs (active and resolved) and core engine issues discovered during NetworkPlay testing.

---

## Known Limitations

1. **Desktop only.** Delta sync is currently only supported on the desktop client (`CMatchUI`, which extends `NetworkGuiGame`), whereas the mobile client (`MatchController`) extends `AbstractGuiGame` directly. The `NetworkGuiGame` layer is in the shared `forge-gui` module, so mobile support could be added in the future by having `MatchController` extend `NetworkGuiGame` once PR review is satisfied of desktop implementation.

2. **No protocol version negotiation.** There is no handshake or version check between client and server. Both must be built from the same branch/commit. A version mismatch will produce opaque deserialization errors.

3. **No user-facing delta sync toggle.** Delta sync is enabled by default and can only be toggled programmatically (`netGuiGame.setDeltaSyncEnabled(false)`). There is no UI setting.

4. Objects are not explicitly removed from the client-side Tracker — relies on garbage collection when no longer referenced by the GameView hierarchy.

---

## Authorship

All code on this branch was written by **Claude Code** (Anthropic's AI coding assistant) under human direction and review.

**Human contributions:** Project direction, feature requirements, code review, manual testing, bug reporting, architecture guidance.
