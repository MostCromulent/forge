# NetworkPlay Test Infrastructure

## Table of Contents

- [Overview](#overview)
- [Validation Metrics](#validation-metrics-what-does-success-look-like)
  - [Metrics Hierarchy](#metrics-hierarchy)
  - [Primary Metric 1: ActualNetwork vs FullState](#primary-metric-1-actualnetwork-vs-fullstate)
  - [Primary Metric 2: Checksum Validation](#primary-metric-2-checksum-validation)
  - [Primary Metric 3: Success Rate](#primary-metric-3-success-rate)
  - [Interpreting Results](#interpreting-results)
- [Technical Implementation](#technical-implementation)
- [UnifiedNetworkHarness API](#unifiednetworkharness-api)
- [Testing Functions](#testing-functions)
- [Configuration](#configuration)
- [Test Metrics](#test-metrics)
- [Use Case Examples](#use-case-examples)
- [Output Files](#output-files)
- [File Inventory](#file-inventory-14-files)

---

## Overview

The NetworkPlay test infrastructure provides automated testing for Forge's network play features, specifically focused on **delta sync validation**.

This document outlines the validation metrics, functions, and configuration of these testing tools following a significant consolidation on 03/02/2026.

**Location:** `forge-gui-desktop/src/test/java/forge/net/`


---

## Validation Metrics: what does success look like?

This section explains the key metrics used to validate delta sync correctness and efficiency, how they are collected, and how to interpret results.

### Metrics Hierarchy

DeltaSync validation has three primary metrics:

| Metric | Purpose | Pass Threshold |
|--------|---------|----------------|
| ActualNetwork vs FullState | Bandwidth efficiency | >= 90% savings |
| Checksum Validation | State integrity | 0 mismatches |
| Success Rate | End-to-end reliability | >= 90% |

**Validation passes when ALL primary metrics meet thresholds** (see `AnalysisResult.passesValidation()` at line 707).

---
### Primary Metric 1: ActualNetwork vs FullState

This is the **core bandwidth efficiency metric**. It compares actual bytes sent over TCP using delta sync against what would have been sent with full state serialization using the legacy network protocol.

#### Three Bandwidth Measurements

| Measurement | What It Represents                                           | How Calculated |
|-------------|--------------------------------------------------------------|----------------|
| **Approximate** | Estimated delta size (no compression)                        | `DeltaPacket.getApproximateSize()` : sums object delta bytes + new object data |
| **ActualNetwork** | Real TCP bytes sent (delta + compression/overhead)           | `NetworkByteTracker.getTotalBytesSent()` : actual socket writes |
| **FullState** | Baseline legacy network protocl: full GameView serialization | `estimateFullStateSize()` via `ObjectOutputStream` |

#### Collection Workflow

**Location:** `forge-gui/.../net/server/NetGuiGame.java` lines 150-181

```
1. Before sending delta packet:
   networkBytesBefore = getActualNetworkBytesSent()    // line 152

2. Send delta packet:
   send(ProtocolMethod.applyDelta, delta)              // line 154

3. After sending, calculate metrics:
   deltaSize = delta.getApproximateSize()              // line 164
   fullStateSize = estimateFullStateSize(gameView)     // line 165
   actualNetworkBytes = networkBytesAfter - networkBytesBefore  // line 167

4. Calculate savings percentages:
   savings = (1 - deltaSize/fullStateSize) × 100       // line 173
   actualSavings = (1 - actualNetworkBytes/fullStateSize) × 100  // line 174

5. Log to NetworkDebugLogger:
   "[DeltaSync] Packet #N: Approximate=X bytes, ActualNetwork=Y bytes, FullState=Z bytes"
```

#### FullState Baseline Calculation

The FullState measurement uses LZ4-compressed `ObjectOutputStream` serialization (lines 202-214), matching the compression used by the actual network layer:

```java
ByteArrayOutputStream baos = new ByteArrayOutputStream();
LZ4BlockOutputStream lz4Out = new LZ4BlockOutputStream(baos);
ObjectOutputStream oos = new ObjectOutputStream(lz4Out);
oos.writeObject(gameView);
oos.close();
return baos.size();  // Compressed serialized size
```

This represents what the legacy full-state protocol would send over the wire, providing an apples-to-apples comparison with ActualNetwork bytes.


>### Error in Previous Calculation Methodology
>
>During consolidation of testing infrastructure (2026-02-03) an error was identified in the methodology previously applied for calculating ActualNetwork vs FullState bandwidth savings.
>
>The bandwidth savings calculations were comparing **compressed** delta bytes against **uncompressed** full state bytes, resulting in inflated savings percentages.
>
>
>| Measurement | Compression Applied |
>|-------------|---------------------|
>| **ActualNetwork** | LZ4 compressed (via `CompatibleObjectEncoder`) |
>| **FullState** | Uncompressed (raw `ObjectOutputStream`) |
>
>`estimateFullStateSize()` was using raw serialization without compression:
>```java
>// OLD - INCORRECT
>ObjectOutputStream oos = new ObjectOutputStream(baos);  // No compression
>oos.writeObject(gameView);
>return baos.size();  // Uncompressed size
>```
>
>In reality the `CompatibleObjectEncoder` (line 30) applies LZ4 compression to **all network traffic**. If the legacy protocol sent full state updates, those would **also** be LZ4 compressed, but this was not reflected in the calculations.
>
>This comparison was unfair - we were claiming credit for both delta sync efficiency AND compression, when compression would apply equally under both approaches.
>
>Once this error was identified a fix was applied so that `estimateFullStateSize()` now applies the same LZ4 compression as the network layer, as outlined above. This ensures a valid apples-for-applies comparison between delta sync and legacy network protocol.
>
>#### Implications
>
>1. **Delta sync still provides significant benefits** - the bandwidth savings are genuine, but marginally smaller than previously reported.
>2. **Historical test results** (prior to 2026-02-03) showing bandwidth savings should be considered marginally inflated.
>3. **Future test results** will show lower but more accurate savings percentages.
>
>A 100-game `ComprehensiveDeltaSyncTest` conducted **after** this fix was applied still reported ActualNetwork vs FullState bandwidth savings of 98.1%; lower than the ~99.5% reported in previous tests but still a significant improvement over baseline.




#### Analysis Workflow

**Log Parsing:** `NetworkLogAnalyzer.java` line 34-35
```java
Pattern.compile("\\[DeltaSync\\] Packet #(\\d+): Approximate=(\\d+) bytes, ActualNetwork=(\\d+) bytes, FullState=(\\d+) bytes")
```

**Aggregation:** `AnalysisResult.java` lines 61-75
```java
totalDeltaBytes = sum of ActualNetwork bytes across all packets
totalFullStateBytes = sum of FullState bytes across all packets
averageBandwidthSavings = 100 × (1 - totalDeltaBytes / totalFullStateBytes)
```

---



### Primary Metric 2: Checksum Validation

Checksums verify that client and server game state remain synchronized.

#### What's Checksummed

**Location:** `DeltaSyncManager.computeStateChecksum()` lines 734-758

```java
int hash = 17;
hash = 31 * hash + gameView.getTurn();
hash = 31 * hash + gameView.getPhase().ordinal();
for (PlayerView player : sortedPlayers) {  // Sorted by ID for consistency
    hash = 31 * hash + player.getId();
    hash = 31 * hash + player.getLife();
}
```

Checksums cover: **Turn number**, **Phase**, **Player IDs**, **Life totals**

#### Validation Frequency

Checksums are computed and logged every **20 packets** (configurable via `CHECKSUM_INTERVAL`).

#### Detection

**Log Pattern:** `NetworkLogAnalyzer.java` line 49-50
```java
Pattern.compile("CHECKSUM MISMATCH|checksum mismatch|desync", Pattern.CASE_INSENSITIVE)
```

A checksum mismatch indicates **state desynchronization** - a critical failure.

### Primary Metric 3: Success Rate

Games must complete without errors. Classification by `GameLogMetrics.FailureMode`:

| Mode | Meaning |
|------|---------|
| `NONE` | Game completed successfully |
| `TIMEOUT` | Game exceeded time limit |
| `CHECKSUM_MISMATCH` | State desynchronization detected |
| `EXCEPTION` | Unhandled error during game |
| `INCOMPLETE` | Game started but didn't finish |
---
### Interpreting Results

#### What "Good" Looks Like

```
Validation: PASSED
- Success Rate: 96% (>= 90% required)
- Bandwidth Savings: 98.4% (>= 90% required)
- Checksum Mismatches: 0 (must be 0)
```

#### Failure Patterns

| Pattern | Likely Cause |
|---------|--------------|
| Low bandwidth savings (<90%) | Delta too large, too many new objects per packet |
| Checksum mismatch | View serialization bug, race condition |
| TIMEOUT failures | Infinite loop, deadlock, slow AI |
| EXCEPTION failures | Null pointer, serialization error |

##### "Object ID not found" Warnings

This non-fatal warning occurs when delta application references a CardView ID that doesn't exist in the client's tracker. Games typically complete successfully despite these warnings.
- Missing objects are skipped during delta application (not treated as fatal errors)
- The delta sync uses graceful degradation - if a referenced object isn't found, it's logged and the property update is skipped
- Periodic checksum validation catches critical desyncs; if checksums match, game state is consistent enough to continue
- If checksum mismatch is detected, automatic full state resync recovers the client to correct state

Identified Causes:

1. **View type ID collisions** (FIXED): Different object types (CardView, PlayerView, StackItemView) have separate ID counters that can collide. Fixed by implementing composite delta keys that encode both type and ID, preventing collisions between different object types. (Bug #4, commit 1d564ab2d8)

2. **Copy/clone card effects** (NOT YET ADDRESSED): Certain spell effects that create copies or clones of cards may create CardView objects that aren't properly synchronized. The server includes copy card IDs in delta packets before the new objects are sent to the client.

---
## Technical Implementation

### Core Components

#### UnifiedNetworkHarness

The unified test harness supporting all game configurations:

- **2-player local AI**: `playerCount(2).remoteClients(0)` - No TCP traffic
- **2-player remote**: `playerCount(2).remoteClients(1)` - Real delta sync
- **3-player multiplayer**: `playerCount(3).remoteClients(2)` - Multiple clients
- **4-player multiplayer**: `playerCount(4).remoteClients(3)` - Maximum clients

Key behaviors:
- Auto-allocates ports via `PortAllocator`
- Initializes `FModel` and `HeadlessGuiDesktop` as needed
- Configures `ServerGameLobby` slots based on player/client counts
- Spawns `HeadlessNetworkClient` threads for remote players
- Supports `useAiForRemotePlayers(true)` for server-side AI takeover
- Collects metrics from both server tracker and client counters

#### HeadlessGuiDesktop
Extends `GuiDesktop` to bypass display requirements. Key behaviors:
- `hostMatch()` - Creates `HostedMatch` without GUI registration
- `getNewGuiGame()` - Returns `HeadlessNetworkGuiGame` for delta sync support
- `invokeInEdtLater/Now/AndWait()` - Executes immediately (no EDT)

#### HeadlessNetworkClient
Real TCP network client for delta sync testing:
- Connects via `FGameClient` to server
- Receives and processes delta sync packets
- Auto-responds to prompts (mulligan, priority, cleanup discard)
- Tracks packets received and bytes transferred

#### HeadlessNetworkGuiGame
Extends `NetworkGuiGame` for proper delta packet processing:
- Implements `applyDelta()` for delta sync reception
- Tracks delta packets and bytes for metrics
- All GUI methods are no-ops returning safe defaults

### Batch Execution

#### ComprehensiveTestExecutor
Orchestrates mixed player-count testing:
- Configurable game distribution (default: 50x2p, 30x3p, 20x4p)
- Supports both sequential (same JVM) and parallel (multi-process) execution
- Shuffles game order to distribute player counts throughout run

#### MultiProcessGameExecutor
Parallel execution via separate JVM processes:
- Spawns `ComprehensiveGameRunner` as subprocess per game
- Assigns unique ports via `PortAllocator`
- Collects results via stdout parsing
- Handles timeouts and process cleanup

### Log Analysis

#### NetworkLogAnalyzer
Parses network debug logs for metrics:
- Extracts delta packet counts and byte sizes
- Identifies errors and checksum mismatches
- Tracks game completion status and turn counts
- Provides context extraction around errors

#### AnalysisResult
Aggregates metrics and generates reports:
- Per-player-count statistics
- Bandwidth savings calculations
- Error categorization by failure mode
- Markdown report generation

---

## UnifiedNetworkHarness API

The `UnifiedNetworkHarness` provides a builder-pattern API for all network test configurations:

```java
// 2-player local AI (NETWORK_LOCAL mode equivalent)
UnifiedNetworkHarness.GameResult result = new UnifiedNetworkHarness()
    .playerCount(2)
    .remoteClients(0)
    .execute();

// 2-player with remote client (NETWORK_REMOTE mode equivalent)
UnifiedNetworkHarness.GameResult result = new UnifiedNetworkHarness()
    .playerCount(2)
    .remoteClients(1)
    .execute();

// 3-player multiplayer with remote clients
UnifiedNetworkHarness.GameResult result = new UnifiedNetworkHarness()
    .playerCount(3)
    .remoteClients(2)
    .useAiForRemotePlayers(true)
    .execute();

// 4-player multiplayer
UnifiedNetworkHarness.GameResult result = new UnifiedNetworkHarness()
    .playerCount(4)
    .remoteClients(3)
    .gameTimeout(300000)
    .useAiForRemotePlayers(true)
    .execute();
```

### Builder Methods

| Method | Description |
|--------|-------------|
| `playerCount(int)` | Set number of players (2-4) |
| `remoteClients(int)` | Set number of remote TCP clients (0 to playerCount-1) |
| `gameTimeout(long)` | Set game timeout in milliseconds (default: 300000) |
| `connectionTimeout(long)` | Set connection timeout in milliseconds (default: 30000) |
| `port(int)` | Use specific port instead of auto-allocating |
| `useAiForRemotePlayers(boolean)` | Swap remote player controllers to AI after game starts |
| `decks(List<Deck>)` | Set specific decks for players |
| `decks(Deck, Deck)` | Set decks for 2-player game |

### GameResult Class

The unified result class for all network test configurations:

| Field | Type | Description |
|-------|------|-------------|
| `playerCount` | int | Number of players in game |
| `remoteClientCount` | int | Number of remote TCP clients |
| `success` | boolean | Test passed all criteria |
| `gameStarted` | boolean | Game successfully started |
| `gameCompleted` | boolean | Game finished normally |
| `turnCount` | int | Number of turns played |
| `winner` | String | Name of winning player |
| `deltaPacketsReceived` | long | Delta packets received by clients |
| `totalDeltaBytes` | long | Total bytes via delta sync |
| `deckNames` | List<String> | Names of decks used |
| `errorMessage` | String | Error message if failed |

---


## Testing Functions

### CI Test Categories:
- **Default CI tests**: `DeltaSyncUnitTest` (39 tests) + `NetworkPlayIntegrationTest` unit tests (5) + `testTrueNetworkTraffic` - run with every CI build
- **Stress tests**: All other game tests in `NetworkPlayIntegrationTest` - require `-Drun.stress.tests=true`

### Test Files

| File | Location | Purpose |
|------|----------|---------|
| `NetworkPlayIntegrationTest.java` | `forge-gui-desktop/.../forge/net/` | Integration tests with real network I/O |
| `DeltaSyncUnitTest.java` | `forge-gui-desktop/.../forge/gamesimulationtests/` | Unit tests for delta sync components |

---

### DeltaSyncUnitTest

**Location:** `forge-gui-desktop/src/test/java/forge/gamesimulationtests/DeltaSyncUnitTest.java`

Fast unit tests for delta sync components. No network I/O - tests individual classes in isolation.

| Category | Tests | What's Tested |
|----------|-------|---------------|
| DeltaPacket | 4 | Packet creation, empty packets, checksums, size calculation |
| DeltaSyncManager | 5 | Client registration, acknowledgment, sequence tracking, unregistration |
| GameSession | 6 | Session creation, player registration, connection state, pause/resume |
| Reconnection | 4 | Token validation, reconnection handling, timeout, disconnected player tracking |
| MockClient | 4 | Connection, reconnection, delta tracking, full state override |
| Serialization | 5 | ObjectOutputStream overhead, size accuracy, empty packet size |
| NetworkByteTracker | 5 | Byte tracking, reset, enable/disable, stats summary |

**Run all:** `mvn -pl forge-gui-desktop verify -Dtest="DeltaSyncUnitTest"`

---

### NetworkPlayIntegrationTest

**Location:** `forge-gui-desktop/src/test/java/forge/net/NetworkPlayIntegrationTest.java`

Integration tests with real network I/O.

#### Unit Tests (always run in CI)

| Test | Explanation |
|------|-------------|
| `testDeckLoaderHasPrecons` | Verifies that `TestDeckLoader` can find quest precon decks. Ensures test infrastructure has access to the 424 precon decks needed for random deck selection. |
| `testDeckLoaderCanLoadDeck` | Verifies that `TestDeckLoader.getRandomPrecon()` returns a valid deck with at least 40 cards. Ensures decks are properly loaded and playable. |
| `testGameResultInitialization` | Verifies `GameResult` class correctly stores and reports game metrics (turns, winner, bytes sent). Tests the result collection infrastructure. |
| `testGameTestModeEnum` | Verifies `GameTestMode` enum values (`NETWORK_LOCAL`, `NETWORK_REMOTE`) have correct properties. Tests `usesRemoteClient()` returns expected values. |
| `testServerStartAndStop` | Verifies `FServerManager` can start and stop a server on a given port. Tests basic server lifecycle without running a game. |

#### Single Game Integration Tests

| Test | CI | Explanation |
|------|-----|-------------|
| `testTrueNetworkTraffic` | **Default** | **Key Delta Sync Test.** Runs a 2-player game with an actual TCP network client. Uses **minimal 10-card basic land decks** for fast execution (~3 turns, ~25 seconds). Deck legality temporarily disabled for this test only. Validates: client connection, delta packet count > 0, game completion. |
| `testUnifiedHarnessLocalMode` | Stress | Runs a 2-player AI-vs-AI game using `UnifiedNetworkHarness.remoteClients(0)`. Uses `ServerGameLobby` with `FServerManager` (network infrastructure). Both players are local AI - no remote client connection. Validates the network game hosting pathway works. |
| `testMultiplayer3Player` | Stress | Runs a 3-player free-for-all game using `UnifiedNetworkHarness.playerCount(3).remoteClients(2)` with real network clients. 1 local AI host + 2 remote `HeadlessNetworkClient` instances. Each remote client receives delta packets independently. Validates multiplayer delta sync with concurrent clients. |
| `testMultiplayer4Player` | Stress | Same as 3-player test but with 4 players using `UnifiedNetworkHarness.playerCount(4).remoteClients(3)`. 1 local AI host + 3 remote clients. Tests delta sync scaling with more concurrent connections. |

#### Batch Tests

There are three execution methods: **Loop** (simple loop, same JVM, local AI), **Sequential** (same JVM with real TCP clients, better for debugging), and **Parallel** (separate JVM per game, fastest for large runs).

| Test | Explanation |
|------|-------------|
| `testBatchTesting` | **Loop method.** Runs 3 games in a loop using `UnifiedNetworkHarness` with `remoteClients(0)`. Uses local AI mode (server with local AI players, no remote client). All games run in the same JVM process. Simplest approach, good for basic validation. |
| `testConfigurableSequential` | **Sequential method.** Configurable via `-Dtest.gameCount` and `-Dtest.timeoutMs`. Allows running any number of sequential games with custom timeout. Use `-Dtest.gameCount=3` for quick validation. |
| `testConfigurableParallel` | **Parallel method.** Configurable via `-Dtest.gameCount` and `-Dtest.timeoutMs`. Allows running any number of parallel games with custom timeout. Expects 80% success rate. Use `-Dtest.gameCount=2` or `-Dtest.gameCount=3` for quick parallel tests. |

#### Comprehensive Delta Sync Tests

| Test | Explanation |
|------|-------------|
| `runComprehensiveDeltaSyncTest` | **Main Validation Test.** Default: 100 games (50x 2-player, 30x 3-player, 20x 4-player). Uses parallel multi-process execution for speed. Runs log analysis after completion. Validates against success rate >= 90%, bandwidth efficiency, and zero checksum mismatches. Generates detailed markdown report. Configurable via `-Dtest.2pGames`, `-Dtest.3pGames`, `-Dtest.4pGames` for any game mix including 2-player-only (`-Dtest.3pGames=0 -Dtest.4pGames=0`) or multiplayer-only (`-Dtest.2pGames=0`). |
| `runQuickDeltaSyncTest` | Smaller scale: 10 games (5x 2-player, 3x 3-player, 2x 4-player). Good for quick validation during development. Same validation criteria as comprehensive test. |
| `analyzeExistingLogs` | Parses existing log files without running new games. Useful for re-analyzing previous test runs. Can target specific batch by ID via `-Dtest.batchId=YYYYMMDD-HHMMSS`. |

---
## Configuration

### System Properties for Test Execution

| Property | Default | Description |
|----------|---------|-------------|
| `run.stress.tests` | `false` | Enable test execution (required) |
| `test.2pGames` | `50` | Number of 2-player games |
| `test.3pGames` | `30` | Number of 3-player games |
| `test.4pGames` | `20` | Number of 4-player games |
| `test.batchSize` | `10` | Parallel batch size |
| `test.timeoutMs` | `300000` | Per-game timeout (5 min) |
| `test.gameCount` | `3` | Games for configurable tests |

### Test Modes

| Mode | Description |
|------|-------------|
| `NETWORK_REMOTE` | **Default.** Real TCP client connection with actual network traffic. True delta sync testing. |
| `NETWORK_LOCAL` | Network stack active but all players are local AI. No actual network packets sent. |

**When to use each mode:**

| Mode | Use Case |
|------|----------|
| `NETWORK_REMOTE` | **Delta sync validation** - Verifies packets are correctly serialized, transmitted over TCP, and deserialized. Use for validating network protocol correctness. This is the primary testing mode. |
| `NETWORK_LOCAL` | **Server infrastructure testing** - Tests `ServerGameLobby` and `FServerManager` code paths without network overhead. Useful for quick smoke tests or when debugging server-side logic that doesn't involve actual packet transmission. |

**Key difference:** `NETWORK_REMOTE` creates a real `HeadlessNetworkClient` that connects via TCP socket and receives delta sync packets over the network. `NETWORK_LOCAL` runs both players in the same process with no network I/O - the "network" code paths execute but no packets leave the machine.

### Deck Configuration

By default, tests use random Quest precon decks loaded by `TestDeckLoader`. There are 424 precon decks available covering all colors and strategies.

**Default Behavior:**
- Each game randomly selects two different precon decks
- Decks are loaded from `forge-gui/res/quest/precons/`
- Random selection ensures diverse game states for thorough testing

**Minimal Test Decks:**

`TestDeckLoader.createMinimalDeck(landName, count)` creates basic-land-only decks for fast CI testing:
- Games end quickly as players can only play lands and deck out
- Deck legality checking must be disabled to use decks smaller than 60 cards
- Used by `testTrueNetworkTraffic` with 10-card decks for ~3-turn games

---

## Test Metrics

Tests collect and validate the following metrics:

### Per-Game Metrics

| Metric | Description | Collected By |
|--------|-------------|--------------|
| **Game Completed** | Whether the game finished normally (not timeout/error) | `GameResult` |
| **Turn Count** | Number of turns played before game ended | `GameResult` |
| **Winner** | Name of winning player (or null for draw) | `GameResult` |
| **Delta Packets Received** | Number of delta sync packets received by client | `HeadlessNetworkClient` |
| **Total Delta Bytes** | Total bytes received via delta sync | `HeadlessNetworkClient` |
| **Game Duration** | Time from game start to completion (ms) | `GameResult` |

### Batch/Comprehensive Test Metrics

| Metric | Description | Target |
|--------|-------------|--------|
| **Success Rate** | Percentage of games completing without errors | >= 90% |
| **Bytes per Delta Packet** | Average packet size (efficiency measure) | < 200 bytes |
| **Checksum Mismatches** | Games where client/server state diverged | 0 |
| **Per-Player-Count Success** | Success rate broken down by 2p/3p/4p games | >= 80% each |

### Log Analysis Metrics

| Metric | Description | Source |
|--------|-------------|--------|
| **Bandwidth Savings** | Percentage saved vs full state sync | Log parsing |
| **Failure Mode** | Classification: NONE, TIMEOUT, CHECKSUM_MISMATCH, EXCEPTION, INCOMPLETE | `GameLogMetrics.FailureMode` |
| **First Error Turn** | Turn number when first error occurred (-1 if none) | Log parsing |
| **Error Context** | Log lines surrounding errors for debugging | `NetworkLogAnalyzer` |

### Validation Criteria

Comprehensive tests pass when **all** of these criteria are met:

| Criterion | Requirement | Rationale |
|-----------|-------------|-----------|
| Success Rate | >= 90% | Most games should complete without errors |
| Bytes per Delta | < 200 | Delta sync should be bandwidth-efficient |
| Checksum Mismatches | = 0 | Client and server must stay synchronized |
| 2-Player Success | >= 80% | Core game mode must be reliable |
| 3-Player Success | >= 80% | Multiplayer must work |
| 4-Player Success | >= 80% | Larger multiplayer must work |

---

## Use Case Examples

### Run All Network Tests

```bash
mvn -pl forge-gui-desktop -am verify -Drun.stress.tests=true
```

### Run Specific Test

```bash
mvn -pl forge-gui-desktop -am verify \
    -Dtest="NetworkPlayIntegrationTest#testTrueNetworkTraffic" \
    -Drun.stress.tests=true \
    -Dsurefire.failIfNoSpecifiedTests=false
```

### Run Comprehensive 100-Game Test

```bash
mvn -pl forge-gui-desktop -am verify \
    -Dtest="NetworkPlayIntegrationTest#runComprehensiveDeltaSyncTest" \
    -Drun.stress.tests=true \
    -Dsurefire.failIfNoSpecifiedTests=false
```

### Run Custom Game Distribution

```bash
mvn -pl forge-gui-desktop -am verify \
    -Dtest="NetworkPlayIntegrationTest#runComprehensiveDeltaSyncTest" \
    -Dtest.2pGames=20 -Dtest.3pGames=10 -Dtest.4pGames=5 \
    -Drun.stress.tests=true \
    -Dsurefire.failIfNoSpecifiedTests=false
```

### Run Quick 10-Game Validation

```bash
mvn -pl forge-gui-desktop -am verify \
    -Dtest="NetworkPlayIntegrationTest#runQuickDeltaSyncTest" \
    -Drun.stress.tests=true \
    -Dsurefire.failIfNoSpecifiedTests=false
```

### Run Sequential Tests (Same JVM, Better for Debugging)

```bash
mvn -pl forge-gui-desktop -am verify \
    -Dtest="NetworkPlayIntegrationTest#testConfigurableSequential" \
    -Dtest.gameCount=3 \
    -Drun.stress.tests=true \
    -Dsurefire.failIfNoSpecifiedTests=false
```

### Analyze Existing Logs

```bash
# Analyze most recent comprehensive test logs
mvn -pl forge-gui-desktop -am verify \
    -Dtest="NetworkPlayIntegrationTest#analyzeExistingLogs" \
    -Drun.stress.tests=true \
    -Dsurefire.failIfNoSpecifiedTests=false

# Analyze specific batch by ID
mvn -pl forge-gui-desktop -am verify \
    -Dtest="NetworkPlayIntegrationTest#analyzeExistingLogs" \
    -Dtest.batchId=20260127-213221 \
    -Drun.stress.tests=true \
    -Dsurefire.failIfNoSpecifiedTests=false
```

---

## Output Files

All test outputs are saved to the **Forge network logs directory**:

**Location by platform:**
- **Windows**: `%APPDATA%\Forge\networklogs\` (e.g., `C:\Users\<name>\AppData\Roaming\Forge\networklogs\`)
- **macOS**: `~/Library/Application Support/Forge/networklogs/`
- **Linux**: `~/.forge/networklogs/`

| File Pattern | Description |
|--------------|-------------|
| `network-debug-BATCHID-batch*-game*-*.log` | Per-game debug logs with full delta sync metrics |
| `network-debug-BATCHID-results.md` | Batch test results summary (e.g., `network-debug-run20260204-200033-results.md`) |
| `quick-test-results-TIMESTAMP.md` | Quick test results summary |

---

## File Inventory (14 files)

After consolidation, the testing infrastructure consists of **14 files** focused on network testing.

| File | Lines | Description |
|------|-------|-------------|
| **Entry Point** | | |
| `NetworkPlayIntegrationTest.java` | ~505 | All tests consolidated here (15 test methods) |
| **Core Harness** | | |
| `UnifiedNetworkHarness.java` | ~620 | Unified harness for all game configurations (2-4 players, 0+ remote clients) |
| **Network Client** | | |
| `HeadlessNetworkClient.java` | ~578 | TCP client for testing |
| `HeadlessNetworkGuiGame.java` | ~348 | Network GUI mock with delta processing |
| **GUI Mock** | | |
| `HeadlessGuiDesktop.java` | ~222 | Desktop GUI mock |
| **Executors** | | |
| `ComprehensiveTestExecutor.java` | ~376 | Batch orchestration |
| `MultiProcessGameExecutor.java` | ~580 | Parallel JVM spawning (uses `UnifiedNetworkHarness.GameResult` directly) |
| `ComprehensiveGameRunner.java` | ~130 | JVM subprocess entry point (uses `UnifiedNetworkHarness.GameResult` directly) |
| **Configuration** | | |
| `GameTestMode.java` | ~64 | Mode enum (NETWORK_LOCAL, NETWORK_REMOTE) |
| `TestDeckLoader.java` | ~167 | Deck loading |
| `PortAllocator.java` | ~105 | Port allocation |
| **Analysis** | | |
| `analysis/NetworkLogAnalyzer.java` | ~734 | Log parsing |
| `analysis/GameLogMetrics.java` | ~283 | Per-game log metrics |
| `analysis/AnalysisResult.java` | ~775 | Aggregated results |

**Total: ~5,100 lines across 14 files**

### Files Removed

The following files were removed during cleanup:

| Removed File | Reason |
|--------------|--------|
| `TestConfiguration.java` | Superseded by `ComprehensiveTestExecutor.fromSystemProperties()` |
| `GameEventListener.java` | Dead code (verbose=false at runtime, query methods never called) |

Result class hierarchies were also consolidated:

| Removed Class | Replacement |
|---------------|-------------|
| `ComprehensiveGameRunner.GameRunResult` | `UnifiedNetworkHarness.GameResult` used directly |
| `MultiProcessGameExecutor.GameResult` | `UnifiedNetworkHarness.GameResult` used directly |

Earlier consolidation also removed:

| Removed File | Replacement |
|--------------|-------------|
| `AutomatedGameTestHarness.java` | `UnifiedNetworkHarness` with `remoteClients(0)` |
| `NetworkClientTestHarness.java` | `UnifiedNetworkHarness` with `remoteClients(1)` |
| `scenarios/MultiplayerNetworkScenario.java` | `UnifiedNetworkHarness` with `playerCount(3-4).remoteClients(2-3)` |
| `GameTestHarnessFactory.java` | Direct use of `UnifiedNetworkHarness` |
| `GameTestMetrics.java` | `UnifiedNetworkHarness.GameResult` |
