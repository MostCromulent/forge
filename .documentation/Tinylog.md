# Tinylog Logging Strategy

## Current State

### Master (after #9915 minlog-to-tinylog migration)

Logging is minimal on master:
- **Config:** 3-line `tinylog.properties` — console writer at INFO, no file writer
- **File logging:** Manual `System.out`/`System.err` capture to `forge.log` via `MultiplexOutputStream` in `ExceptionHandler`
- **Forge code:** ~9 `Logger.*()` calls across 7 files, mostly converted from minlog as `debug()` even though they're operationally useful
- **Third-party:** Netty and jUPnP route through SLF4J -> tinylog bridge; Netty `LoggingHandler` hardcoded to INFO

### NetworkPlay/main (already implemented)

The NetworkPlay branch already has a full tagged network logging system (commit `a24b7d5d7b`):

- **`NetworkLogWriter`** — custom tinylog writer (registered via `META-INF/services/org.tinylog.writers.Writer`) that routes `NETWORK`-tagged entries to per-instance files based on `ThreadContext("logfileKey")`. Replaces logback's SiftingAppender.
- **`NetworkDebugLogger`** — facade class using `Logger.tag("NETWORK")` with:
  - Configurable file-level gating (TRACE/DEBUG/INFO/WARN/ERROR via `FPref.NET_FILE_LOG_LEVEL`)
  - Batch IDs for correlating logs across test runs
  - Per-game instance routing (each game gets its own log file)
  - Log cleanup with grace period and max file limits
  - System info headers, hex dump utilities, path sanitization
- **tinylog.properties** defines three writers:
  - `writerdefault` — console, INFO+, general output
  - `writerNetConsole` — console, NETWORK tag, INFO+, network-specific console output
  - `writerNetFile` — custom `network log` writer, NETWORK tag, TRACE+, per-instance files

**Key:** The network logging is fully self-contained via the NETWORK tag — it doesn't pollute the main log, and it can be independently set to any verbosity level.

## Problems to Solve

1. **Forge's own debug calls are invisible at production level (INFO).** The minlog migration kept calls as `debug()`, but most are things users/devs want to see by default (startup confirmation, download errors).
2. **Switching to DEBUG to see Forge output floods logs with Netty/jUPnP noise.** These libraries are extremely verbose at DEBUG and TRACE — Netty logs every packet, jUPnP logs every UPnP discovery message. There's no way to raise Forge verbosity without also getting buried in library chatter.
3. **No per-package filtering on master.** A single global level controls everything.
4. **No structured file logging on master.** The `forge.log` capture is a raw stdout/stderr dump, not a proper log file with rotation.

## Proposal

### 1. Production Log Level: INFO

Ship with `level = info` globally. This is the standard choice — it gives operational visibility without noise.

### 2. Re-level Forge's Own Logger Calls

Most existing `Logger.debug()` calls should be `Logger.info()` since they represent operationally useful information users want in logs by default:

| File | Current | Proposed | Reason |
|------|---------|----------|--------|
| `ExceptionHandler.java` | `debug` | `info` | Startup confirmation — users should see it |
| `AiController.java` (attacker) | `debug` | `debug` | Per-creature, high-volume during AI turns — keep debug |
| `AiController.java` (phase) | `debug` | `debug` | Per-phase, moderate volume — keep debug |
| `Card.java` (damage) | `debug` | `debug` | Per-damage-event, high-volume — keep debug |
| `CardFaceSymbols.java` | `info` | `info` | Already correct — warns about unrecognized symbols |
| `GuiDownloadService.java` | `error` | `error` | Already correct |
| `GuiDownloadZipService.java` | `error` | `error` | Already correct |
| `SaveFileData.java` | `error` | `error` | Already correct |

**Rule of thumb going forward:**
- **INFO** — Default for Forge code. Operational events you'd want in a user's log: startup, connections, game events, warnings. Use this unless you have a reason not to.
- **DEBUG** — Verbose/exotic cases only: per-card-per-turn decisions, per-packet data, internal state dumps. Things that would clutter a normal log.
- **WARN/ERROR** — Problems and failures, as usual.
- **TRACE** — Almost never in Forge code. Reserved for per-packet or per-frame level detail.

### 3. Suppress Noisy Libraries with Per-Package Levels

Tinylog supports `level@package.name` syntax for per-package overrides. This is the key to solving the Netty/jUPnP noise problem:

```properties
# Global default
level = info

# Suppress noisy libraries (they flood at DEBUG/TRACE)
level@io.netty           = warn
level@org.jupnp          = warn
level@org.eclipse.jetty  = warn
```

This means:
- At production level (INFO), library output is already mostly quiet, but the overrides ensure even stray INFO-level chatter from them is suppressed.
- **When a dev switches to `level = debug` to diagnose Forge issues, Netty/jUPnP stay at WARN** — Forge debug output is readable without drowning in library noise.
- To actually debug Netty itself, a dev explicitly overrides: `level@io.netty = debug`.

The per-package overrides are independent of the NETWORK tag system. The NETWORK tag handles Forge's own network logging (via `NetworkDebugLogger`). The `level@` overrides handle the *third-party library* noise that comes through SLF4J.

### 4. Add Package Name to Log Format

Include `{class-name}` in the format so developers can immediately see where a log line originates — critical when library output does appear:

```properties
writerdefault.format = {date: HH:mm:ss} [{level|min-size=5}] {class-name}: {message}
```

Example output:
```
14:23:01 [INFO ] FServerManager: Server started on port 36743
14:23:01 [DEBUG] AiController: Computer just assigned Lightning Bolt as an attacker.
14:23:02 [WARN ] LoggingHandler: Channel inactive
```

The `{class-name}` pattern gives the simple class name (no package prefix), keeping lines concise while providing origin context. When you need the full package to disambiguate, switch to `{class}`.

### 5. Add a Proper File Writer (Rolling) for Main Log

Replace the manual `MultiplexOutputStream` hack with tinylog's built-in rolling file writer for the *main* (non-network) log:

```properties
# File — detailed with rotation
writerFile           = rolling file
writerFile.level     = debug
writerFile.file      = {dynamic}/forge_{count}.log
writerFile.latest    = {dynamic}/forge.log
writerFile.format    = {date: yyyy-MM-dd HH:mm:ss.SSS} [{level|min-size=5}] [{thread}] {class}: {message}
writerFile.policies  = startup, size: 10mb
writerFile.backups   = 5
```

Notes:
- **File writer at DEBUG, console at INFO.** Users see clean console output; the file captures more detail for post-mortem debugging. The per-package suppression (`level@io.netty = warn`) still applies, so the file isn't flooded with Netty noise either.
- **`{dynamic}` path** needs to be set programmatically at startup to point to the user's Forge directory (`ForgeConstants.USER_DIR`). Tinylog supports this via `DynamicSegment.setText()`. Alternatively, use a system property and set it before tinylog initializes.
- **Rotation:** New file on each startup + when file exceeds 10MB, keeping 5 backups. This prevents unbounded growth while preserving enough history.
- **The `MultiplexOutputStream` capture in `ExceptionHandler` can eventually be removed** once all logging goes through tinylog rather than raw `System.out.println()`. That's a separate cleanup.

### 6. Network Logging — Already Done

The NetworkPlay/main branch already provides:
- **Separate per-instance log files** via `NetworkLogWriter` + `NETWORK` tag
- **Independent verbosity control** — network file writer accepts TRACE+, with internal gating via `NetworkDebugLogger.fileLevel`
- **Console filtering** — `writerNetConsole` shows NETWORK-tagged messages at INFO+ on console
- **Cleanup & rotation** — `NetworkDebugLogger.cleanupOldLogs()` with configurable max files

No additional work needed here. When NetworkPlay merges to master, the network logging infrastructure comes with it.

## Proposed tinylog.properties (Full — Post-Merge)

This combines master's general logging needs with NetworkPlay/main's existing network writers:

```properties
# === Global Level ===
level                    = info

# Suppress noisy third-party libraries even when Forge is at debug
level@io.netty           = warn
level@org.jupnp          = warn
level@org.eclipse.jetty  = warn

# === Console Writer (general) ===
writerConsole            = console
writerConsole.level      = info
writerConsole.format     = {date: HH:mm:ss} [{level|min-size=5}] {class-name}: {message}

# === Main Log File ===
writerFile               = rolling file
writerFile.level         = debug
writerFile.file          = {dynamic}/forge_{count}.log
writerFile.latest        = {dynamic}/forge.log
writerFile.format        = {date: yyyy-MM-dd HH:mm:ss.SSS} [{level|min-size=5}] [{thread}] {class}: {message}
writerFile.policies      = startup, size: 10mb
writerFile.backups       = 5

# === Network Console Writer (NETWORK tag only) ===
writerNetConsole         = console
writerNetConsole.tag     = NETWORK
writerNetConsole.level   = info
writerNetConsole.format  = [{date: HH:mm:ss.SSS}] [{level}] {message}

# === Network File Writer (NETWORK tag only, per-instance routing) ===
writerNetFile            = network log
writerNetFile.tag        = NETWORK
writerNetFile.level      = trace
writerNetFile.format     = [{date: HH:mm:ss.SSS}] [{level}] {message}
```

## Implementation Steps

1. **On master:** Update `tinylog.properties` with per-package suppression and `{class-name}` in format.
2. **On master:** Re-level `ExceptionHandler.debug("Error handling registered!")` to `info()`.
3. **On master (optional):** Add rolling file writer for main `forge.log`. Requires `DynamicSegment` setup at startup.
4. **On merge from NetworkPlay/main:** Network writers come automatically. Verify no conflicts with the new general writers.
5. **Eventually:** Remove `MultiplexOutputStream` capture once all output goes through tinylog — lower priority, separate PR.

## Key Decisions

| Question | Decision | Rationale |
|----------|----------|-----------|
| Production level? | **INFO** | Standard; DEBUG is too noisy with third-party libs |
| How to tame Netty/jUPnP? | **Per-package `level@` overrides to WARN** | They stay quiet even when Forge goes to DEBUG |
| Separate network log? | **Already done** on NetworkPlay/main via NETWORK tag + `NetworkLogWriter` |
| Main log file rotation? | **tinylog rolling file writer** | Replace manual MultiplexOutputStream hack |
| Format? | **Include `{class-name}`** | Shows log origin without package verbosity |
| Network vs library noise? | **Two independent mechanisms** | NETWORK tag = Forge's own net logging; `level@` = third-party SLF4J suppression |
