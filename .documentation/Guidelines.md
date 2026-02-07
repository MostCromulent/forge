# Key Patterns & Guidelines

**IMPORTANT: Claude must follow these patterns and architectural features when writing code in this branch.**

This section summarizes recurring themes from PR feedback for quick reference.

## Table of Contents
- [General Principles](#general-principles)
- [Code Style](#code-style)
- [Architecture](#architecture)
- [Network-Specific Guidelines](#network-specific-guidelines)
- [Testing](#testing)
- [Architecture Reference](#architecture-reference)
    - [Inheritance Hierarchy](#inheritance-hierarchy)
    - [Layer Responsibilities](#layer-responsibilities)
    - [Where Does My Code Go? — Decision Checklist](#where-does-my-code-go--decision-checklist)
    - [Red Flags — Signs You're in the Wrong Layer](#red-flags--signs-youre-in-the-wrong-layer)

## General Principles
- **Keep it simple:** Code should be simple, easy to follow, and use as few lines as possible while still achieving the desired functionality.
- **Minimal diff:** Prefer small, focused changes over large refactors. The fewer lines changed, the easier to review and less risk of introducing bugs. Do not make cosmetic fixes (whitespace, formatting, style) to code that isn't otherwise being changed for functional reasons — it creates diff noise and draws reviewer scrutiny to unrelated code.
- **Search before creating:** Before implementing new functionality, search the codebase for existing mechanisms that solve the same or a similar problem. Before creating a new helper method, search the file for existing functions with equivalent logic. Before adding a new system (e.g., a timer, a polling loop, a status display), search broadly for existing mechanisms that already do the same thing. Enhance the existing mechanism rather than creating a parallel one.
- **Avoid over-engineering:** Solve the problem at hand with the simplest approach that works. Don't introduce new classes, event types, or abstractions when existing infrastructure can be reused. Prefer modifying 3 files over creating 10 new ones. If a feature can be built by composing existing mechanisms do that instead of building new framework.
- **Trace changes across execution contexts:** Before implementing, enumerate the runtime contexts where the affected code will execute: local single-player, local multiplayer, network host, network client, AI opponent. Verify the change is appropriate (or correctly no-op'd) in each. If a flag or property is set on one side of a client-server boundary, check whether it needs to be set on the other side too.
- **Trace callers before modifying:** Before changing a method's behavior, search for all call sites and understand the contexts they run in. A method called from one place is safe to change; a method called from network callbacks, UI threads, and game logic simultaneously needs careful consideration. For network code, trace the full path: who sends, what serializes it, what deserializes it, who receives.
- **Check for dead code:** When a change removes callers or replaces a mechanism, check whether any methods, fields, or branches become unreachable. Remove dead code in the same change rather than leaving it for later.

## Code Style
- **Check hotkey conflicts:** When assigning keyboard shortcuts, search for `VK_F[key]` and `getKeyStroke` in the codebase to ensure no conflicts with hardcoded menu accelerators (e.g., F1=Help, F11=Fullscreen).
- **Wrap parseInt/parseLong in try-catch:** System property parsing should handle `NumberFormatException` gracefully with fallback to defaults.
- **Add @Override annotations:** When implementing interface methods, always add `@Override` annotation.
- **Meaningful toString():** Classes used in logging/debugging should override `toString()` rather than inheriting from Object.
- **Intuitive naming:** Names of files, classes, and methods should communicate purpose without needing to read the implementation. Prefer `hasNetGame()` over `checkNet()`, `cancelAwaitNextInput()` over `resetTimer()`, `NetworkGuiGame` over `NetGG`. Names should describe *what* they answer or do, not *how* — a reader skimming a call site or class hierarchy should understand the intent immediately.
- **`this` in anonymous classes/lambdas:** Inside anonymous inner classes (e.g., `TimerTask`, `Runnable`) and lambdas nested within them, `this` refers to the anonymous class, not the enclosing class. Use `EnclosingClass.this` (e.g., `AbstractGuiGame.this`) when passing the outer instance.

## Architecture
- **Demand-driven computation:** Expensive operations (iterating all cards, getting all abilities) should only be performed when actually needed, not proactively or on every update cycle. Consider the performance cost of helper methods that might be called frequently (e.g., on every priority pass or network update).
- **Keep engine clean:** GUI-specific logic (UI hints, styling) belongs in View classes, not in forge-game engine classes like Player.java or PhaseHandler.java.
- **Fix bugs at the closest layer:** Errors and bug fixes should be solved in the closest layer that is effective. For example, a network serialization issue should be fixed in the network layer, not by adding guards in the game engine. A card rules bug belongs in forge-game, not worked around in forge-gui. Fixing at the source keeps the codebase clean and avoids defensive code proliferating through unrelated layers.
- **Platform-neutral code for platform-neutral features:** If a feature is intended to work across platforms (desktop and mobile), implement the *state and logic* in shared code (e.g., `AbstractGuiGame`, `forge-gui`) rather than in platform-specific classes. Display that uses platform-specific APIs (Swing components, libgdx widgets) belongs in platform subclasses (`CMatchUI`, `MatchController`). However, simple text messages and lightweight UI logic that is identical across platforms may live in `AbstractGuiGame` to avoid duplication — prefer one implementation in the base class over two identical copies in subclasses.
- **Check for mobile GUI:** Desktop-only features must check `GuiBase.getInterface().isLibgdxPort()` and return early/disable for mobile. Users switching between desktop and mobile share preferences.
- **Isolate network code:** Network-specific functionality should be in dedicated classes (`NetworkGuiGame`, `NetGameController`) rather than added to core classes like `AbstractGuiGame`. Keep core game classes free of network dependencies so they remain usable in non-network contexts.
- **Prefer `GuiBase.isNetworkplay(game)` over `GuiBase.isNetworkplay()`:** The no-arg version is a static field that can hold stale values when mixing lobby types. When an `IGuiGame` reference is available, always pass it to the overloaded `isNetworkplay(IGuiGame)` which queries `game.isNetGame()` directly. Only use the no-arg version in code with no game context (e.g., utility classes, views). **Important:** `isNetGame()` must return `true` for *all* game instances in a network match — both the server-side proxy (`NetGuiGame`) and the host's local GUI (`CMatchUI`). If adding a new code path gated on `isNetGame()`, test it from the host's perspective, not just the remote client's.

## Network-Specific Guidelines
- **Delta sync efficiency:** When adding or modifying `TrackableObject` properties, register them in `TrackableProperty` with the correct `TrackableType` so delta tracking picks them up. Failing to do this causes full-state fallbacks that defeat the purpose of delta sync.
- **Reconnection safety:** Game initialization must follow the sequence: session establishment, then state transmission. Do not send game state before the client has confirmed its session — this causes the client to silently drop packets.
- **Serialization compatibility:** Changes to objects serialized over the network (anything in `TrackableProperty`, `DeltaPacket`, lobby messages) must maintain backwards compatibility or include version-aware migration logic.
- **Thread safety:** Network callbacks execute on Netty threads, not the game thread. Access to shared state (e.g., `gameControllers`, `gameView`, tracker collections) from network callbacks must be synchronized or delegated to the game thread via `FThreads.invokeInEdtAndWait()` or equivalent.
- **Bandwidth awareness:** Prefer delta updates over full state. When adding new data to network packets, consider whether it changes frequently — high-frequency data belongs in delta tracking, not in per-packet headers.
- **Distinguish events from continuous state:** When code runs during network play, ask: "Will this generate network traffic? Is that once, or on every tick?" One-time state transitions (e.g., "player is now waiting") should be sent as a single network event. Locally-derived continuous state (timers, counters, animations) must be computed on each side independently — never stream tick-by-tick updates over the wire.
- **Account for client-server asymmetry:** A network match has three execution contexts, not two: (1) the **host's local GUI** (`CMatchUI` with `isNetGame() == true`, created by `FServerManager.getGui()`) — sees full game state but displays locally; (2) the **server-side proxy** (`NetGuiGame`) — serializes `IGuiGame` calls over the wire to the remote client; (3) the **remote client** (`CMatchUI`/`NetworkGuiGame` receiving protocol calls) — has only a proxy view of game state. When branching on network status, verify the behavior is correct in all three contexts. The host's local GUI is the most commonly forgotten — it participates in the match alongside `NetGuiGame` but is a separate `IGuiGame` instance. **Design from the client's perspective first** — the client is the constrained side. Ask: "What does the client need to know, and how will it receive that information?" If a feature requires data the client doesn't have, the server must explicitly provide it (via delta properties, protocol messages, or lobby initialization). Don't assume that because something is reachable on the server, it's also reachable on the client.

## Testing
- **Headless CI compatibility:** Test classes must not depend on GUI components (`FOptionPane`, `JOptionPane`, etc.) that fail in headless CI environments. Use headless alternatives or skip GUI-dependent tests in CI.
- **Multi-process test isolation:** Tests spawning subprocesses must handle classpath/JAR discovery robustly across different environments (local dev vs CI).
- **Test gating with `skipUnlessStressTestsEnabled()`:** Stress and integration tests (batch tests, multiplayer tests, comprehensive/quick delta sync tests) must call `skipUnlessStressTestsEnabled()` as their first line so they are skipped during CI's default `mvn clean test`. Only pass `-Drun.stress.tests=true` for local validation runs.
- **Always-run tests:** Unit tests (deck loader, game result, configuration parsing), `testServerStartAndStop`, `testTrueNetworkTraffic`, and `DeltaSyncUnitTest` must NOT be gated — they should always pass in CI without extra flags.
- **CI checkstyle:** The root `checkstyle.xml` only enforces `RedundantImport` and `UnusedImports`. Fix these before pushing — they will fail the CI build.

---

## Architecture Reference

This section provides detailed architectural guidance for the GUI layer. When the decision checklist or red flags below conflict with the general guidelines above, the more specific rule wins.

### Inheritance Hierarchy

On the `NetworkPlay` branch (`MostCromulent/forge`):

```
IGuiGame (interface, forge-gui)
  └─ AbstractGuiGame (abstract, forge-gui; also implements IMayViewCards)
       ├─ NetworkGuiGame (abstract, forge-gui) — adds network delta sync
       │    ├─ CMatchUI (forge-gui-desktop) — Swing desktop implementation
       │    └─ NetGuiGame (forge-gui) — server-side network proxy
       └─ MatchController (forge-gui-mobile) — libgdx mobile implementation
```

Note: On upstream master (`Card-Forge/forge`), `NetworkGuiGame` does not exist —
`CMatchUI` and `NetGuiGame` extend `AbstractGuiGame` directly.

### Layer Responsibilities

#### `IGuiGame` — Interface Contract (forge-gui)
Defines the method signatures that any GUI implementation must provide. This is the
contract the game engine programs against. Changes here affect all platforms. No default
methods — every method must be implemented (or stubbed) by the concrete class.

#### `AbstractGuiGame` — Shared Game-UI State (forge-gui)
Implements `IGuiGame` and `IMayViewCards`. Platform-agnostic state management and
convenience methods. Contains:
- Player tracking (current player, local players, game controllers)
- Game state flags (pause, speed, daytime)
- Card visibility rules (`mayView`, `mayFlip`)
- UI state tracking (highlighted cards, selectable cards)
- Auto-pass / auto-yield state management
- Await-next-input timer mechanism (`awaitNextInput`/`cancelAwaitNextInput`)
- Choice/input convenience wrappers (`one()`, `many()`, `getInteger()`, etc.)
- Concede/spectator logic
- No-op stubs for optional interface methods (`refreshField`, `refreshCardDetails`, etc.)
- No-op stubs for network methods (`applyDelta`, `fullStateSync`, etc.) overridden in
  network-aware subclasses

**What does NOT belong here:** Anything that manages Swing/libgdx components, implements
platform-specific rendering, or uses platform-specific APIs. Simple text messages and
lightweight UI logic that is identical across all platforms *may* live here to avoid
duplication (prefer one implementation over two identical copies in subclasses). But if
the display differs per platform or uses platform APIs, it belongs in a subclass.

#### `NetworkGuiGame` — Network Delta Sync (forge-gui) — NetworkPlay branch only
Extends `AbstractGuiGame` with network-specific deserialization, delta packet
application, and tracker state management. All network protocol logic lives here,
keeping the base class free of network dependencies. Does not exist on upstream master.

#### `CMatchUI` — Desktop Match Screen (forge-gui-desktop)
The Swing-based desktop implementation. On upstream master it extends `AbstractGuiGame`
directly; on the `NetworkPlay` branch it extends `NetworkGuiGame`. This is where
desktop-specific display logic, Swing component management, and screen coordination
belong. Implements `ICDoc` (controller) and `IMenuProvider`. Owns references to desktop
panel controllers (`CAntes`, `CCombat`, `CDependencies`, `CDetailPicture`, `CDev`,
`CDock`, `CLog`, `CPrompt`, `CStack`).

#### `MatchController` — Mobile Match Screen (forge-gui-mobile)
The libgdx-based mobile implementation. On upstream master, `MatchController` supports
network play via the legacy protocol. On the `NetworkPlay` branch, it extends
`AbstractGuiGame` directly (not `NetworkGuiGame`) — delta sync is not yet implemented
for mobile. Uses the singleton pattern (`MatchController.instance`). Mobile-specific
display and interaction logic belongs here.

#### `V*` Views (forge-gui-desktop: `forge.screens.match.views`)
Pure Swing UI components (`VField`, `VHand`, `VPrompt`, `VStack`, etc.). Each panel
view implements `IVDoc<C*>` and defines how a panel *looks* — layout, Swing components,
rendering. Views hold a reference to their corresponding controller.

Note: `VMatchUI` is the top-level match screen view and implements `IVTopLevelUI` (not
`IVDoc`), so it follows a different pattern from the per-panel views.

#### `C*` Controllers (forge-gui-desktop: `forge.screens.match.controllers`)
Per-panel controllers (`CField`, `CHand`, `CPrompt`, `CLog`, etc.). Each implements
`ICDoc` and manages the behavior of its corresponding `V*` view. Controllers hold a
reference to `CMatchUI` and their `V*` view.

Exception: `CDetailPicture` is a composite controller that manages `CDetail` and
`CPicture` together. It does not itself implement `ICDoc`.

### Where Does My Code Go? — Decision Checklist

Before adding or modifying GUI code, work through this checklist top-to-bottom.
The first matching rule wins:

1. **Does it define a new capability the game engine needs from the UI?**
   Add the method signature to `IGuiGame`. Provide a concrete implementation in
   `AbstractGuiGame` if the logic is shared across platforms, otherwise leave it
   unimplemented there so each platform subclass (`CMatchUI`, `MatchController`) must
   provide its own.

2. **Is it shared game-UI state that both desktop and mobile need identically?**
   (e.g., tracking which cards are selectable, auto-yield flags, player controller mappings)
   `AbstractGuiGame`.

3. **Is it a convenience wrapper that delegates to abstract methods?**
   (e.g., `one()` calls `getChoices()`, `confirm()` calls overloaded `confirm()`)
   `AbstractGuiGame` — this is the template method pattern already used there.

4. **Does it involve network protocol, delta packets, or tracker synchronization?**
   `NetworkGuiGame`. (On upstream master where `NetworkGuiGame` does not exist: the
   network-aware subclasses `CMatchUI`/`NetGuiGame`, or `AbstractGuiGame` with no-op
   stubs.)

5. **Does it use platform-specific APIs, or does the display differ between desktop
   and mobile?**
   `CMatchUI` (desktop) or `MatchController` (mobile). However, simple text messages
   and lightweight UI logic that is identical across all platforms may live in
   `AbstractGuiGame` to avoid duplication — see rule #2.

6. **Does it coordinate multiple desktop panels or manage screen-level concerns?**
   (e.g., targeting overlay, floating zones, keyboard shortcuts, menus)
   `CMatchUI`.

7. **Does it control the behavior of a specific desktop UI panel?**
   The corresponding `C*` controller (e.g., `CPrompt`, `CField`, `CLog`).

8. **Does it define how a desktop panel looks — layout, Swing components, rendering?**
   The corresponding `V*` view (e.g., `VPrompt`, `VField`, `VLog`).

### Red Flags — Signs You're in the Wrong Layer

Some of these anti-patterns already exist in the codebase as technical debt. Do not add new instances of them.

- **Adding `javax.swing.*` or `java.awt.*` imports to anything in `forge-gui/`.**
  The `forge-gui` module is shared across platforms. Swing imports mean desktop-specific
  code that belongs in `forge-gui-desktop`.

- **Adding platform-specific display logic to `AbstractGuiGame`.**
  Code that uses Swing/libgdx APIs or differs between desktop and mobile belongs in
  `CMatchUI` or `MatchController`. Simple text messages identical across platforms are
  acceptable in `AbstractGuiGame` to avoid duplication.

- **Checking `GuiBase.getInterface().isLibgdxPort()` in `AbstractGuiGame` to branch
  on platform.**
  Platform-specific branches should be handled by overriding methods in the appropriate
  subclass, not by runtime platform checks in the shared base. (The `isLibgdxPort()`
  checks in `setCurrentPlayer()` and `mayView()` already violate this — do not extend
  the pattern.)

- **Putting game-state logic (auto-yield decisions, controller management) in a `V*`
  view class.**
  Views are for layout and rendering. State logic goes in the corresponding `C*`
  controller or `CMatchUI`.

- **Moving local-only logic into a shared layer without considering network propagation.**
  If code previously ran only locally (e.g., a timer in `CMatchUI`) and you refactor it
  into a shared class (`AbstractGuiGame`, `InputLockUI`), check whether the shared layer's
  state changes are now serialized over the network. Shared-layer state is often synced
  automatically — what was a harmless local update may become constant network traffic.
