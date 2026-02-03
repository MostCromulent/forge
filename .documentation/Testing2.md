# NetworkPlay Test Infrastructure

## Overview

The NetworkPlay test infrastructure provides automated headless testing for Forge's network play features, specifically focused on **delta sync validation**. After consolidation, the infrastructure consists of **16 files** focused on network testing.

**Location:** `forge-gui-desktop/src/test/java/forge/net/`

### Key Capabilities

| Capability | Description |
|------------|-------------|
| **Delta Sync Validation** | Verify bandwidth-efficient game state synchronization |
| **Headless Execution** | Full games without display server (no X11/Wayland) |
| **Real Network Traffic** | TCP connections with actual packet transmission |
| **2-4 Player Support** | Multiplayer games with multiple remote clients |
| **Batch Testing** | 100+ games via multi-process parallel execution |
| **Log Analysis** | Automated parsing for bandwidth and error metrics |

### File Inventory (16 files)

| File | Lines | Description |
|------|-------|-------------|
| **Entry Point** | | |
| `NetworkPlayIntegrationTest.java` | ~584 | All tests consolidated here |
| **Core Harness** | | |
| `UnifiedNetworkHarness.java` | ~630 | Unified harness for all game configurations (2-4 players, 0+ remote clients) |
| **Network Client** | | |
| `HeadlessNetworkClient.java` | ~578 | TCP client for testing |
| `HeadlessNetworkGuiGame.java` | ~348 | Network GUI mock with delta processing |
| **GUI Mock** | | |
| `HeadlessGuiDesktop.java` | ~222 | Desktop GUI mock |
| **Executors** | | |
| `ComprehensiveTestExecutor.java` | ~376 | Batch orchestration |
| `MultiProcessGameExecutor.java` | ~660 | Parallel JVM spawning |
| `ComprehensiveGameRunner.java` | ~240 | JVM subprocess entry point |
| **Configuration** | | |
| `GameTestMode.java` | ~64 | Mode enum (NETWORK_LOCAL, NETWORK_REMOTE) |
| `TestConfiguration.java` | ~237 | System property loading |
| `TestDeckLoader.java` | ~167 | Deck loading |
| `PortAllocator.java` | ~105 | Port allocation |
| `GameEventListener.java` | ~267 | Event logging |
| **Analysis** | | |
| `analysis/NetworkLogAnalyzer.java` | ~734 | Log parsing |
| `analysis/GameLogMetrics.java` | ~283 | Per-game log metrics |
| `analysis/AnalysisResult.java` | ~775 | Aggregated results |

**Total: ~5,500 lines across 16 files**

### Files Consolidated

The following files were removed during consolidation:

| Removed File | Replacement |
|--------------|-------------|
| `AutomatedGameTestHarness.java` | `UnifiedNetworkHarness` with `remoteClients(0)` |
| `NetworkClientTestHarness.java` | `UnifiedNetworkHarness` with `remoteClients(1)` |
| `scenarios/MultiplayerNetworkScenario.java` | `UnifiedNetworkHarness` with `playerCount(3-4).remoteClients(2-3)` |
| `GameTestHarnessFactory.java` | Direct use of `UnifiedNetworkHarness` |
| `GameTestMetrics.java` | `UnifiedNetworkHarness.GameResult` |

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

### Test Entry Point: NetworkPlayIntegrationTest

All tests are in `NetworkPlayIntegrationTest.java`. Tests require `-Drun.stress.tests=true` to execute.

#### Unit Tests (always run with stress flag)

| Test | Explanation |
|------|-------------|
| `testDeckLoaderHasPrecons` | Verifies that `TestDeckLoader` can find quest precon decks. Ensures test infrastructure has access to the 424 precon decks needed for random deck selection. |
| `testDeckLoaderCanLoadDeck` | Verifies that `TestDeckLoader.getRandomPrecon()` returns a valid deck with at least 40 cards. Ensures decks are properly loaded and playable. |
| `testGameResultInitialization` | Verifies `GameResult` class correctly stores and reports game metrics (turns, winner, bytes sent). Tests the result collection infrastructure. |
| `testGameTestModeEnum` | Verifies `GameTestMode` enum values (`NETWORK_LOCAL`, `NETWORK_REMOTE`) have correct properties. Tests `usesRemoteClient()` returns expected values. |
| `testConfigurationParsing` | Verifies `TestConfiguration` correctly parses system properties for decks, test mode, player count, and iterations. Ensures command-line configuration works. |
| `testServerStartAndStop` | Verifies `FServerManager` can start and stop a server on a given port. Tests basic server lifecycle without running a game. |

#### Single Game Integration Tests

| Test | Explanation |
|------|-------------|
| `testFullAutomatedGame` | Runs a 2-player AI-vs-AI game using `UnifiedNetworkHarness.remoteClients(0)`. Uses `ServerGameLobby` with `FServerManager` (network infrastructure). Both players are local AI - no remote client connection. Validates the network game hosting pathway works. |
| `testTrueNetworkTraffic` | **Key Delta Sync Test.** Runs a 2-player game with an actual TCP network client using `UnifiedNetworkHarness.remoteClients(1)`. Server hosts with one local AI player. `HeadlessNetworkClient` connects as remote player via TCP. Verifies delta sync packets are sent and received. Validates: client connection, delta packet count > 0, game completion. |
| `testMultiplayer3Player` | Runs a 3-player free-for-all game using `UnifiedNetworkHarness.playerCount(3).remoteClients(2)` with real network clients. 1 local AI host + 2 remote `HeadlessNetworkClient` instances. Each remote client receives delta packets independently. Validates multiplayer delta sync with concurrent clients. |
| `testMultiplayer4Player` | Same as 3-player test but with 4 players using `UnifiedNetworkHarness.playerCount(4).remoteClients(3)`. 1 local AI host + 3 remote clients. Tests delta sync scaling with more concurrent connections. |
| `testUnifiedHarnessLocalMode` | Verifies `UnifiedNetworkHarness` with `remoteClients(0)` runs games with local AI only. Tests that no remote clients are created. |

#### Batch Tests

There are three execution methods: **Factory** (simple loop, same JVM, NETWORK_LOCAL), **Sequential** (same JVM with real TCP clients, better for debugging), and **Parallel** (separate JVM per game, fastest for large runs).

| Test | Explanation |
|------|-------------|
| `testBatchTesting` | **Loop method.** Runs 3 games in a loop using `UnifiedNetworkHarness` with `remoteClients(0)`. Uses local AI mode (server with local AI players, no remote client). All games run in the same JVM process. Simplest approach, good for basic validation. |
| `testSequentialThreeGames` | **Sequential method.** Runs 3 games using `ComprehensiveTestExecutor` with `sequential(true)`. Uses `NETWORK_REMOTE` mode with real `HeadlessNetworkClient` TCP connections. Games run one after another in the same JVM. Better for debugging (single process, easier to trace). Isolated log files per game. |
| `testParallelThreeGames` | **Parallel method.** Runs 3 games using `MultiProcessGameExecutor`. Spawns separate JVM processes running `ComprehensiveGameRunner`. Uses `NETWORK_REMOTE` mode. Multiple games run simultaneously. Complete process isolation prevents test interference. |
| `testParallelTwoGames` | **Parallel method.** Quick 2-game parallel test. Same as `testParallelThreeGames` but with only 2 games for faster validation. |
| `testConfigurableSequential` | **Sequential method.** Configurable via `-Dtest.gameCount` and `-Dtest.timeoutMs`. Allows running any number of sequential games with custom timeout. |
| `testConfigurableParallel` | **Parallel method.** Configurable via `-Dtest.gameCount` and `-Dtest.timeoutMs`. Allows running any number of parallel games with custom timeout. Expects 80% success rate. |

#### Comprehensive Delta Sync Tests

| Test | Explanation |
|------|-------------|
| `runComprehensiveDeltaSyncTest` | **Main Validation Test.** Default: 100 games (50x 2-player, 30x 3-player, 20x 4-player). Uses parallel multi-process execution for speed. Runs log analysis after completion. Validates against success rate >= 90%, bandwidth efficiency, and zero checksum mismatches. Generates detailed markdown report. |
| `runQuickDeltaSyncTest` | Smaller scale: 10 games (5x 2-player, 3x 3-player, 2x 4-player). Good for quick validation during development. Same validation criteria as comprehensive test. |
| `runTwoPlayerOnlyTest` | 10 x 2-player games only. Focused testing of the most common game configuration. Uses parallel execution. |
| `runMultiplayerOnlyTest` | 10 games: 5x 3-player + 5x 4-player. Focused testing of multiplayer delta sync. Validates concurrent client handling. |
| `analyzeExistingLogs` | Parses existing log files without running new games. Useful for re-analyzing previous test runs. Can target specific batch by ID via `-Dtest.batchId=YYYYMMDD-HHMMSS`. |

---

## Key Test Metrics

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
| `testMode` | `NETWORK_REMOTE` | `NETWORK_REMOTE` (real TCP) or `NETWORK_LOCAL` (no network I/O) |

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

**Custom Deck Options:**

| Property | Description |
|----------|-------------|
| `deck1` | Path to deck file for player 1 (e.g., `/path/to/deck.dck`) |
| `deck2` | Path to deck file for player 2 |
| `precon1` | Quest precon name for player 1 (e.g., `"Quest Precon - Red"`) |
| `precon2` | Quest precon name for player 2 |

**Priority:** `deck1/deck2` (file path) takes precedence over `precon1/precon2` (precon name), which takes precedence over random selection.

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
    -Dtest="NetworkPlayIntegrationTest#testSequentialThreeGames" \
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

### Run with Specific Decks

```bash
mvn -pl forge-gui-desktop -am verify \
    -Dtest="NetworkPlayIntegrationTest#testWithSystemProperties" \
    -Dprecon1="Quest Precon - Red" \
    -Dprecon2="Quest Precon - Blue" \
    -DtestMode=NETWORK_REMOTE \
    -Diterations=5 \
    -Drun.stress.tests=true \
    -Dsurefire.failIfNoSpecifiedTests=false
```

---

## Validation Criteria

Comprehensive tests validate against these criteria:

| Criterion | Target | Description |
|-----------|--------|-------------|
| Success Rate | >= 90% | Games completing without errors |
| Bytes per Delta | < 200 | Efficient delta packet size |
| Checksum Mismatches | 0 | No desync errors |
| Per-Player Success | >= 80% | Success rate for each player count |

---

## Output Files

Tests generate the following output files in the `logs/` directory:

| File Pattern | Description |
|--------------|-------------|
| `network-debug-runBATCHID-*.log` | Per-game debug logs |
| `comprehensive-test-results-TIMESTAMP.md` | Test results report |
| `analysis-results-TIMESTAMP.md` | Log analysis report |
