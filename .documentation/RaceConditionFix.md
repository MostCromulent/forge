# Fix Network Game Race Condition
 
## Context
 
The batching commit (742c7ba3) introduced a race condition for network games. Before batching, `GameEventForwarder.receiveGameEvent()` called `gui.handleGameEvent(ev)` directly on the game thread. The call chain `handleGameEvent → updateGameView → send → channel.writeAndFlush().sync()` blocked the game thread until Netty finished encoding the GameView. No concurrent modification was possible.
 
After batching, events are queued and flushed via `invokeInEdtLater(this::flush)`, dispatching to Swing EDT. Now the game thread is free while EDT blocks on `.sync()` and Netty I/O thread serializes the GameView — allowing the game thread to concurrently modify `FCollection` objects (backed by unsynchronized `ArrayList` + `HashSet`), causing `ArrayIndexOutOfBoundsException`.
 
**Goal**: Keep the batching benefit (1 `updateGameView()` per batch instead of per event) while eliminating the race condition, without modifying the game engine.
 
## Approach
 
Flush events on the game thread (not EDT), using time + queue size thresholds for batching. Add automatic flushing before any network send in `NetGuiGame` via re-entrancy-protected logic.
 
## Changes
 
### 1. `forge-gui/src/main/java/forge/gui/control/GameEventForwarder.java`
 
Remove EDT dispatch. Add time/size thresholds for proactive game-thread flushing. Expose public `flush()` method.
 
- Remove `invokeInEdtLater(this::flush)` — events will no longer be dispatched to EDT
- Remove `volatile boolean flushQueued` flag (no longer needed)
- Add `lastFlushTime` tracking and constants `FLUSH_INTERVAL_NS` (50ms) and `FLUSH_SIZE_THRESHOLD` (50)
- In `receiveGameEvent()`: queue event, then check time/size thresholds and flush synchronously if exceeded
- Make `flush()` public so `NetGuiGame` can call it
 
### 2. `forge-gui/src/main/java/forge/gamemodes/net/server/NetGuiGame.java`
 
Add forwarder reference and auto-flush before all network sends.
 
- Add field `private GameEventForwarder forwarder`
- Add field `private boolean flushing = false` (re-entrancy guard)
- Add method `setForwarder(GameEventForwarder)`
- Add private method `flushPendingEvents()`: if forwarder is set and not already flushing, set `flushing = true`, call `forwarder.flush()`, set `flushing = false`
- Modify private `send()`: call `flushPendingEvents()` before `sender.send()`
- Modify private `sendAndWait()`: call `flushPendingEvents()` before `sender.sendAndWait()`
 
Re-entrancy protection ensures:
- When `forwarder.flush()` → `handleGameEvents()` → `send()` → `flushPendingEvents()`: the `flushing` flag is `true`, so it's a no-op (correct — we're already inside a flush)
- When `showPromptMessage()` → `send()` → `flushPendingEvents()`: the flag is `false`, so pending events get flushed first (correct — ensures events reach client before the prompt)
 
### 3. `forge-gui/src/main/java/forge/gamemodes/match/HostedMatch.java` (~line 228-229)
 
Wire the forwarder to `NetGuiGame`.
 
Change:
```java
game.subscribeToEvents(new forge.gui.control.GameEventForwarder(gui));
```
To:
```java
forge.gui.control.GameEventForwarder forwarder = new forge.gui.control.GameEventForwarder(gui);
((forge.gamemodes.net.server.NetGuiGame) gui).setForwarder(forwarder);
game.subscribeToEvents(forwarder);
```
 
## Why This Works
 
1. **No race**: All flushes run on the game thread. `writeAndFlush().sync()` blocks the game thread during Netty I/O encoding. Game thread can't modify `FCollection` objects while they're being serialized.
 
2. **Batching preserved**: Events accumulate between flush triggers. Each flush sends 1 `updateGameView()` + N events (via `handleGameEvents()`), not N `updateGameView()` calls.
 
3. **Proactive flushing**: Time threshold (50ms) ensures the client gets updates during non-interactive phases (e.g., AI turns). Size threshold (50) prevents excessive queue growth.
 
4. **Guaranteed flush before interaction**: Every `send()`/`sendAndWait()` call flushes pending events first, so the client always has current state before prompts, dialogs, or choices.
 
5. **No engine changes**: All logic stays in `GameEventForwarder` and `NetGuiGame`.
 
## Trade-offs
 
- Some methods that explicitly call `updateGameView()` may cause a redundant GameView send after a flush that already included one. This is at most 1 extra send per interaction — far better than pre-batching's N sends per N events.
- The 50ms time threshold means client updates during fast AI play arrive in ~50ms batches rather than instantly. This is a reasonable latency for network play.
 
## Verification
 
1. Start a network game (host + client)
2. Play with a simple land-only deck (which previously triggered the race every turn)
3. Verify no `ArrayIndexOutOfBoundsException` crashes
4. Verify game events display correctly on the client (phase changes, card plays, combat)
5. Verify interactive prompts work (priority, choices, combat damage assignment)
6. Run existing tests: `mvn test -pl forge-gui` (if any network-related tests exist)
 
