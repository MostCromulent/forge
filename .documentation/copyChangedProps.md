# copyChangedProps IndexOutOfBoundsException — Investigation Report

**Branch:** `fix/copyChangedProps-IOOBE`
**Base:** `master` (`a3cf59eae3`)
**Date:** 2026-03-01

## Problem

Network multiplayer clients freeze after a random amount of time. The host is unaffected. The freeze is silent — no error dialog, no crash, just an unresponsive client.

## Root Cause

Two bugs in `TrackableCollectionType.copyChangedProps()` (TrackableTypes.java), which runs on every network message to synchronize game state from server to client.

### Bug 1: remove/add index corruption (primary)

The original code replaced tracked objects in a collection using `remove(i)` followed by `add(i, obj)`:

```java
newCollection.remove(i);
newCollection.add(i, existingObj);
```

`FCollection` is a hybrid list+set data structure. `remove(i)` shifts all subsequent list indices down by one. `add(i, obj)` goes through `insert()` which has complex repositioning logic involving both the list and set. During a forward iteration (`for i = 0; i < size; i++`), the index shift means elements are skipped or double-processed, and the interaction between list and set operations causes `IndexOutOfBoundsException`.

This bug has existed since 2020, hidden by a blanket `catch (IndexOutOfBoundsException)` with `continue` — the exception was caught and silently swallowed, allowing the loop to continue with corrupted state. A single test game produced approximately 500 silent exceptions.

The February 2026 GameEvent refactor increased network message volume roughly 10x, amplifying the rate of silent corruption until it became visible as client freezes.

### Bug 2: set/list size divergence from concurrent access (secondary)

`FCollection.size()` returns `set.size()`, but `FCollection.get(i)` uses `list.get(i)`. If the internal HashSet and ArrayList diverge in size, iterating `for (i = 0; i < size(); i++)` tries to access list indices that don't exist.

The set and list diverge because `copyChangedProps` runs on both the Netty IO thread (via `beforeCall` → `replicatePlayerView`) and the EDT (via `setGameView`), both operating on the same tracked `FCollection` instances without synchronization. This causes structural corruption of the HashSet's internal HashMap — confirmed by the diagnostic finding that `set.contains(element)` returns `true` while `set.remove(element)` returns `false` for the same element in the same call, which is only possible when the HashMap's bucket structure is corrupted by concurrent modification.

### Additional issue: NPE in replicatePlayerView

`GameClientHandler.replicatePlayerView()` calls `tracker.getObj()` without a null check. If a PlayerView ID isn't registered in the tracker (e.g., timing issue during game setup), the resulting `NullPointerException` propagates to Netty's `exceptionCaught()`, which closes the channel silently — another path to the "client freeze" symptom.

## Practical Effect

### On gameplay

- **Client freezes:** The accumulated state corruption from hundreds of silent exceptions per game eventually causes the client to stop responding. The host continues normally.
- **Stale display state:** When `copyChangedProps` hits an exception and skips elements, card views and player views on the client fall behind the server's state. Cards may show stale power/toughness, wrong zones, or missing counters.
- **Silent disconnections:** The NPE path closes the network channel with no user-visible error, making the client appear frozen when it has actually disconnected.

### On stability

- **Progressive degradation:** Each exception is individually recoverable (caught and continued), but the corruption compounds. The client works initially, then becomes increasingly unreliable.
- **Timing-dependent:** The freeze depends on message volume and timing, making it difficult to reproduce consistently. Some games complete normally; others freeze within minutes.
- **Masked by catch block:** The blanket exception catch (added in 2020) hid the bug for six years. The only symptom was gradual state drift, which wasn't obvious during casual play.

## Changes

### 1. `FCollection.replace(int, T)` — atomic element swap

**File:** `forge-core/.../util/collect/FCollection.java`

New method that replaces an element at a given index, updating both the internal list and set:

```java
public T replace(final int index, final T element) {
    final T old = list.set(index, element);
    if (old != element) {
        set.remove(old);
        set.add(element);
    }
    return old;
}
```

**Benefit:** Enables in-place element replacement in O(1) without the index-shifting side effects of `remove()` + `add()`. Keeps the list and set in sync, which the existing `set(int, T)` method deliberately does not do (it only updates the list, as documented and relied on by callers in `GameAction` and `TriggerHandler`).

**Why a new method:** The existing `set(int, T)` has intentional semantics — it skips the set update. Changing `set()` would break those callers. A new method avoids altering existing contracts.

**Risk:** Low. The method is a simple composition of `list.set()`, `set.remove()`, and `set.add()` — all standard Java collection operations. The only caller is `copyChangedProps`. Under concurrent access, `set.remove(old)` can fail if the HashSet is structurally corrupted (see Bug 2), but this is a pre-existing condition and the failure is benign — the set ends up with a stale extra element, which does not affect list-based iteration.

### 2. Rewritten `copyChangedProps` loop

**File:** `forge-game/.../trackable/TrackableTypes.java`

Four sub-changes:

**a) `replace(i, existingObj)` instead of `remove(i)` + `add(i, existingObj)`**

Eliminates Bug 1 entirely. The old pattern was fundamentally broken on FCollection during forward iteration. `replace()` does an in-place swap with no index shifting.

- **Benefit:** Eliminates ~500 silent `IndexOutOfBoundsException`s per test game. Prevents the progressive state corruption that leads to client freezes.
- **Risk:** None. The old code's intent was to swap an element at a specific index — `replace()` does exactly that, correctly.

**b) Loop bound changed from `size()` to `listSize()`**

`size()` returns `set.size()`. `get(i)` uses `list.get(i)`. When the set and list diverge in size (Bug 2), `size()` can be larger than the list, causing `get(i)` to throw `IndexOutOfBoundsException`. Using `listSize()` guarantees the loop bound matches the indexable range.

- **Benefit:** Makes the loop immune to the HashSet corruption from concurrent access. Even if the set has phantom elements from thread-safety issues, the loop only iterates over elements that actually exist in the list.
- **Risk:** If the set has FEWER elements than the list (the reverse of what we observed), this would iterate more elements than the set reports. In practice, the observed corruption is always set > list (concurrent `add` without matching `remove`), so this direction is not a concern. Even if it occurred, the extra iterations would just process additional valid list elements.
- **Con:** This is a mitigation, not a fix, for the concurrent access problem. The set can still be corrupted; we just no longer crash because of it.

**c) Removed the no-op CardView/StackItem branch**

The original code had a branch for `CardViewCollectionType` and `StackItemViewListType` that did `remove(i); add(i, newObj)` — removing an element and re-adding the same object at the same index. This was a no-op. The intent was to NOT replace with the existing tracked object for card types (keep the server's version), which is equivalent to simply skipping the replacement.

- **Benefit:** Removes dead code and makes the logic clearer. The skip is now implicit — the `if` condition excludes card types, so no replacement happens for them.
- **Risk:** None. The branch had no effect (confirmed by analysis of `remove` + `add` with the same object at the same index).

**d) Removed the blanket `catch (IndexOutOfBoundsException)`**

The catch block (added in 2020) silently swallowed every IOOBE and continued the loop. This masked Bug 1 for six years, allowing silent state corruption to accumulate.

- **Benefit:** If an IOOBE occurs in future (from a new bug), it will be visible immediately rather than hidden. Removes the false safety net that allowed the original bug to persist.
- **Risk:** If there is an undiscovered code path that can still cause IOOBE, it will now crash visibly instead of being silently caught. This is intentional — visible failures are preferable to silent corruption. Testing on this branch confirms zero IOOBEs after the fix.

### 3. `FCollection.readObject()` — deserialization safety

**File:** `forge-core/.../util/collect/FCollection.java`

```java
private void readObject(java.io.ObjectInputStream in)
        throws java.io.IOException, ClassNotFoundException {
    in.defaultReadObject();
    set.clear();
    set.addAll(list);
}
```

Rebuilds the internal set from the list after Java deserialization. During deserialization of circular object graphs (e.g., PlayerView A's Opponents contains PlayerView B, whose Opponents contains A), HashSet bucket placement can be incorrect if back-referenced objects have temporary state during the deserialization process. The rebuild guarantees set consistency.

- **Benefit:** Defensive measure against deserialization-related set/list divergence. Ensures every deserialized FCollection starts with a consistent set regardless of object graph complexity. The list is treated as the source of truth.
- **Risk:** Low. The rebuild is O(n) where n is the collection size, adding a small cost to deserialization. For the collections involved (Opponents typically has 1-3 elements, card zones have tens), this is negligible. The `set.clear()` + `set.addAll(list)` pattern is straightforward and cannot introduce new elements — it can only make the set consistent with the list.
- **Con:** Diagnostic investigation confirmed that deserialization actually produces correctly-matched sizes in the current code (zero readObject mismatch messages in testing). The concurrent access corruption (Bug 2) happens after deserialization, not during it. This method is therefore purely defensive — it protects against a theoretical deserialization problem rather than a confirmed one. It is kept because circular references during Java serialization are inherently fragile, and the cost of the defense is near zero.

### 4. `FCollection.listSize()` — list-based size accessor

**File:** `forge-core/.../util/collect/FCollection.java`

```java
public int listSize() {
    return list.size();
}
```

- **Benefit:** Exposes the list size separately from the set size. Needed by `copyChangedProps` for the loop bound (change 2b). Also useful for diagnostics.
- **Risk:** None. Read-only accessor on a private field. Does not modify state.
- **Con:** Adds a method to FCollection's public API that is arguably an implementation detail. The method name and javadoc make clear it is for specialized use rather than general consumption.

### 5. Null check in `replicatePlayerView`

**File:** `forge-gui/.../net/client/GameClientHandler.java`

```java
PlayerView existingPlayerView = tracker.getObj(TrackableTypes.PlayerViewType, newPlayerView.getId());
if (existingPlayerView == null) {
    System.err.println("replicatePlayerView - no existing PlayerView for id " + newPlayerView.getId());
    return;
}
```

- **Benefit:** Prevents a `NullPointerException` from propagating to Netty's `exceptionCaught()` handler, which closes the channel. Without this check, a missing PlayerView ID (possible during game setup or after a deserialization error) causes a silent disconnection that appears as a client freeze.
- **Risk:** If a PlayerView is genuinely missing from the tracker, this skips replication for that view, meaning the client's display of that player may be stale for one message cycle. The `System.err.println` makes the skip visible in logs. In testing, this message was never observed, suggesting the null case does not occur during normal operation — the check is purely defensive.
- **Con:** The log message fires on the IO thread. In a pathological case where the tracker is permanently missing a PlayerView, this would log on every message. This is unlikely in practice and preferable to a silent disconnect.

## Remaining Issue: Concurrent Access to Tracked FCollections

The HashSet corruption from concurrent access (Netty IO thread vs EDT) is not fixed by this branch — it is mitigated. The `listSize()` loop bound prevents IOOBEs, and the `readObject()` prevents deserialization-related divergence, but the underlying thread-safety issue in FCollection remains. The set may accumulate phantom elements over the course of a game. This does not affect correctness of `copyChangedProps` (which now iterates by list), but could theoretically affect other code paths that use `FCollection.size()` or set-based operations (`contains`, `add` uniqueness checks).

### The race condition

`GameProtocolHandler.channelRead()` processes each network message in two stages:

1. **IO thread (synchronous):** `beforeCall()` runs `updateTrackers(args)` then `replicateProps(args)`. For events carrying PlayerView args (e.g., `showPromptMessage`, `updateButtons`), `replicateProps` calls `replicatePlayerView()` → `existingPV.copyChangedProps(newPV)`, which iterates and modifies FCollections on the tracked PlayerView.

2. **EDT (asynchronous):** The actual `IGuiGame` method is queued via `FThreads.invokeInEdtNowOrLater()`. For `setGameView`, this calls `gameView.copyChangedProps(newGameView)`, which recurses into the Players collection and calls `existingPV.copyChangedProps(pvFromGameView)` — modifying the same FCollections on the same tracked PlayerViews.

The IO thread does not wait for the EDT to finish processing the previous message. When message N+1 arrives, the IO thread runs `replicatePlayerView` immediately while the EDT may still be running `setGameView` from message N. Both threads call `existingPV.copyChangedProps()` concurrently, operating on the same FCollection instances with no synchronization.

### Approaches evaluated

#### Approach 1: Synchronize FCollection — not viable

Adding `synchronized` to FCollection methods does not solve the problem. The race is not between individual `set.remove()` / `set.add()` calls — it's between compound iterate-then-replace loops. Two threads can still interleave between `get(i)` and `replace(i, ...)` even if each method is individually synchronized. Preventing that would require locking the entire `copyChangedProps` loop, which means the lock belongs at the caller level, not in FCollection.

Additionally, FCollection is used throughout the entire codebase (AI, combat, zones, triggers). Synchronizing its methods would add lock overhead to all single-threaded game logic for a problem that only exists on the network client.

#### Approach 2: ConcurrentHashMap.newKeySet() — not viable

- **Not serializable:** `newKeySet()` returns a `KeySetView` that cannot be serialized by `ObjectOutputStream`. FCollection is serialized on every network message. This would break the network protocol entirely.
- **Doesn't fix compound operations:** The iterate+replace loop would still be racy — thread-safe individual operations do not provide atomicity across a sequence of operations.
- **Breaks FCollection invariants:** `insert()` does a check-then-act (`set.add()` then `list.indexOf()`). `remove()` does `set.remove()` then `list.remove()`. Concurrent access between these paired operations can desynchronize list and set.
- **Breaks game logic:** `asSet()` is used directly in `Combat.getAttackersAndDefenders()` and `AttackConstraints`. The returned set's type and behavior would change.
- **The set is critical for correctness:** FCollection's set enforces element uniqueness. The `add()` method only adds to the list if `set.add()` returns true. The `size()` method returns `set.size()` as the canonical element count. Replacing the set implementation has wide-reaching consequences.

#### Approach 3: Single-threaded access — viable, multiple options

The correct fix is to ensure `copyChangedProps` runs on only one thread at a time. Four sub-options were evaluated:

**Option A: Remove `replicateProps` from IO thread.** Let EDT handle all property replication via `setGameView`.

Not safe. The server does not send `setGameView` before every event carrying a PlayerView. `NetGuiGame.updateGameView()` is called before `showPromptMessage`, `handleGameEvent`, zone operations, and card selections — but NOT before `updateButtons`, `showWaitingTimer`, or overlay methods. Without `replicateProps` on the IO thread, tracked PlayerViews would have stale properties when those events are processed on EDT.

**Option B: Move `replicateProps` into the EDT runnable.** Instead of running in `beforeCall()` on the IO thread, run `replicateProps` inside the queued `Runnable` just before `method.invoke()`. All `copyChangedProps` calls would then execute sequentially on EDT.

- Pros: No locks, no race condition, clean solution. `updateTrackers` stays on the IO thread (it only sets tracker references, doesn't modify FCollections).
- Cons: Requires modifying `GameProtocolHandler`, which is shared between client and server — the change must not affect server-side behavior. Property replication runs slightly later (when EDT processes the queue rather than on message arrival), but this is negligible since EDT processes tasks in FIFO order and replication runs immediately before the method that needs the updated state.
- Risk: Low. The only behavioral change is timing — replication happens on EDT instead of IO thread. Since the replicated properties are only consumed by EDT code (UI rendering), this is the natural thread for the work.

**Option C: Add a mutex between the two paths.** A `ReentrantLock` acquired by the IO thread before `replicateProps` and by EDT before `gameView.copyChangedProps()`.

- Pros: Minimal code change — two lock/unlock calls.
- Cons: EDT could block waiting for the IO thread (or vice versa), introducing latency jitter. Must use try/finally to prevent deadlock on exception.
- Risk: The critical sections are short (sub-millisecond for small collections like Opponents, a few milliseconds for larger collections like Hand or Battlefield). Blocking would be brief but would occur on every network message.

**Option D: Replicate GameView on IO thread.** In `beforeCall` for `setGameView`, walk the GameView's Players collection and call `replicatePlayerView` for each. Then make `setGameView` on EDT skip `copyChangedProps` (just assign the reference).

- Pros: All `copyChangedProps` runs on the IO thread. No race.
- Cons: Moves potentially expensive work (GameView replication covers all players, all zones, all cards) from EDT to the IO thread, blocking message processing during replication. More invasive change — requires modifying both `GameClientHandler.beforeCall()` and `AbstractGuiGame.setGameView()`.

### Recommendation

**Option B (move `replicateProps` to EDT)** is the cleanest fix. It eliminates the race without locks, without blocking either thread, and without changing the server. The implementation is:

1. Remove `replicateProps(args)` from `GameClientHandler.beforeCall()` (keep `updateTrackers`)
2. Add a pre-invoke hook in `GameProtocolHandler`'s `toRun` Runnable that calls `replicateProps(args)` on EDT, before `method.invoke(toInvoke, args)`

This ensures all `copyChangedProps` calls are serialized on EDT. The `listSize()` mitigation on this branch eliminates the crash symptom in the meantime, making the concurrent access harmless in practice.

## Files Modified

| File | Changes |
|------|---------|
| `forge-core/.../util/collect/FCollection.java` | `replace()`, `listSize()`, `readObject()` |
| `forge-game/.../trackable/TrackableTypes.java` | `copyChangedProps` rewrite |
| `forge-gui/.../net/client/GameClientHandler.java` | Null check in `replicatePlayerView` |
