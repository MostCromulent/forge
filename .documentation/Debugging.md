# Known Bugs and Issues

This file tracks known bugs in the Forge codebase, particularly for the NetworkPlay branch. It serves as a reference for debugging sessions and tracks progress on fixes.

---

## Active Bugs

*No active bugs at this time.*

---

## Resolved Bugs

| # | Bug | Branch | Resolution | Commit |
|---|-----|--------|------------|--------|
| 1 | /skipreconnect AI takeover not working | NetworkPlay | Race condition fix: reorder operations to replace controller before clearing inputs | ea49b699e4 |
| 2 | Phase marker not updating on client | NetworkPlay | Fixed delta sync to track phase changes | - |
| 3 | Client hand not visible during mulligan | NetworkPlay | Changed GAMEVIEW_DELTA_KEY from 0 to Integer.MIN_VALUE to avoid ID collision | f06d2da2a7 |
| 4 | Collection deserialization fails to find object id=1 | NetworkPlay | Implemented composite delta keys throughout + fixed client-side type-specific lookup in createObjectFromData | 1d564ab2d8 |
| 5 | Checksum mismatch every 20 packets | NetworkPlay | Changed `getPhase().hashCode()` to `getPhase().ordinal()` - hashCode differs between JVMs, ordinal is consistent | 12aeccaac4 |
| 6 | GameView ID in checksum causing mismatch | NetworkPlay | Removed `gameView.getId()` from checksum in both DeltaSyncManager and NetworkGuiGame - GameView ID is a local JVM identifier that differs between server and client | - |
| 7 | Multiplayer (3-4 player) games failing with 0% success | NetworkPlay | Per-client property tracking: Multiple clients share GameView; first client's clearAllChanges() cleared state for all. Added independent checksum tracking per client in DeltaSyncManager. Comprehensive test: 97% success (97/100 games) | 715cc4da68 |
| 8 | HeadlessNetworkClient auto-response race condition causing game timeouts | NetworkPlay | Replaced unsynchronized `new Thread()` calls with single-threaded `ScheduledExecutorService` to serialize all auto-responses. Each new prompt cancels pending responses to prevent stale actions. Comprehensive test: 96% success (96/100 games) | 2aad2f9938 |
| 9 | Log messages appear duplicated in single-JVM network tests | NetworkPlay | Added `isServerSide()` and `getLogPrefix()` methods to NetworkGuiGame. Log messages now include `[Server]` or `[Client]` prefix to distinguish the source. | 4b0ea811a3 |
| 10 | Intermittent checksum mismatch in 4-player games (PlayerView ID mismatch) | NetworkPlay | Modified GameClientHandler.java to extract server-assigned PlayerView IDs from GameView and apply them to RegisteredPlayers before Game creation, ensuring consistent IDs between server and client. | - |
| 11 | "Address already in use: bind" port conflict during tests | NetworkPlay | Added `SO_REUSEADDR` option to server socket in FServerManager.java; added 500ms delay between batches in MultiProcessGameExecutor.java | ebc9a8823e |
| 12 | Multiplayer (3+) desync - Collection lookup failures | NetworkPlay | Split new object creation into two phases: 1a) create all objects, 1b) apply properties. Fixed logging to show proper type names. | - |
| 13 | Multiplayer checksum mismatch - Player ordering | NetworkPlay | Sort players by ID before computing checksum in both server and client to ensure consistent iteration order. | - |
| 14 | NullPointerException in disconnect handler | NetworkPlay | Race condition: game thread set `currentGameSession=null` between null check and use. Fixed by capturing in local variable before use. | - |
| 15 | TreeSet casting error in DeltaSync serialization | NetworkPlay | Type mismatch: `CantHaveKeyword` property defined as `StringListType` but `CardView.updateCantHaveKeyword()` stores a `TreeSet`. Fixed serializer to cast to `Collection<String>` instead of `List<String>`. | - |
| 16 | GameView ID Mismatch in Checksum (4-player games) | NetworkPlay | Race condition: Game loop could advance Phase while `collectDeltas()` was running. Fixed by capturing Turn/Phase snapshot at START of delta collection, then using snapshot values for checksum computation. Ensures checksum matches what client will have after applying delta. | - |
| 17 | Client Connection Timeout (4-player games) | NetworkPlay | Slot assignment race condition: `RemoteClient.index` defaulted to 0 (host slot), causing early `LobbyUpdateEvent` to report wrong slot. Fixed by: (1) Initialize index to -1 (UNASSIGNED_SLOT), (2) Only set slot in event if client has valid slot, (3) Client waits for valid slot before counting down connected latch, (4) Added `synchronized` to `connectPlayer()` to prevent concurrent slot assignment. | - |

---

## Debug Infrastructure

### NetworkDebugLogger

Location: `forge-gui/src/main/java/forge/gamemodes/net/NetworkDebugLogger.java`

Configurable logging for network debugging. Logs go to:
- Console (configurable level)
- File: `forge-gui-desktop/logs/`

Usage:
```java
NetworkDebugLogger.log("[Component] Message with %s formatting", args);
NetworkDebugLogger.debug("[Component] Detailed debug info");
NetworkDebugLogger.warn("[Component] Warning message");
NetworkDebugLogger.error("[Component] Error message");
```

### Log Prefixes

Key log prefixes used by NetworkDebugLogger:
- `[chooseSpellAbilityToPlay]` - Priority decisions (PlayerControllerHuman)
- `[InputQueue]` - Input stack management
- `[InputSyncronizedBase]` - Latch operations
- `[AI Takeover]` - AI conversion process
- `[DeltaSync]` - Delta synchronization
