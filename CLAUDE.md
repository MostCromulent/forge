# Claude Code Guidelines for Forge

## GUI Layer Architecture

Forge's GUI is split across multiple Maven modules. The most common mistake is placing
platform-specific UI display logic into `AbstractGuiGame` (the shared abstract layer)
instead of into the platform-specific subclasses where it belongs.

### Inheritance Hierarchy

```
IGuiGame (interface, forge-gui)
  └─ AbstractGuiGame (abstract, forge-gui)
       ├─ NetworkGuiGame (abstract, forge-gui) — adds network delta sync
       │    ├─ CMatchUI (forge-gui-desktop) — Swing desktop implementation
       │    └─ NetGuiGame (forge-gui) — server-side network proxy
       └─ MatchController (forge-gui-mobile) — libgdx mobile implementation
```

### Layer Responsibilities (As-Is)

#### `IGuiGame` — Interface Contract (forge-gui)
Defines ~113 method signatures that any GUI implementation must provide. This is the
contract the game engine programs against. Changes here affect all platforms.

#### `AbstractGuiGame` — Shared Game-UI State (forge-gui)
Platform-agnostic state management and convenience methods. Contains:
- Player tracking (current player, local players, game controllers)
- Game state flags (pause, speed, daytime)
- Card visibility rules (`mayView`, `mayFlip`)
- UI state tracking (highlighted cards, selectable cards)
- Auto-pass / auto-yield state management
- Await-next-input timer (the basic mechanism, not display formatting)
- Choice/input convenience wrappers (`one()`, `many()`, `getInteger()`, etc.)
- Concede/spectator logic
- No-op stubs for optional interface methods (`refreshField`, `refreshCardDetails`, etc.)

**What does NOT belong here:** Anything that constructs display strings for specific UI
contexts, formats visual output, manages Swing/libgdx components, or implements
rendering logic. If it's about *how something looks* rather than *what state the game is
in*, it belongs in a subclass.

#### `NetworkGuiGame` — Network Delta Sync (forge-gui)
Extends `AbstractGuiGame` with network-specific deserialization, delta packet
application, and tracker state management. All network protocol logic lives here,
keeping the base class free of network dependencies.

#### `CMatchUI` — Desktop Match Screen (forge-gui-desktop)
The Swing-based desktop implementation. Extends `NetworkGuiGame`. This is where
desktop-specific display logic, Swing component management, and screen coordination
belong. Implements `ICDoc` (controller) and `IMenuProvider`. Owns references to all
desktop panel controllers (`CField`, `CHand`, `CPrompt`, `CLog`, etc.).

#### `MatchController` — Mobile Match Screen (forge-gui-mobile)
The libgdx-based mobile implementation. Extends `AbstractGuiGame` directly (not
`NetworkGuiGame`). Singleton pattern. Mobile-specific display and interaction logic
belongs here.

#### `V*` Views (forge-gui-desktop: `forge.screens.match.views`)
Pure Swing UI components (`VField`, `VHand`, `VPrompt`, `VStack`, etc.). Each
implements `IVDoc<C*>` and defines how a panel *looks* — layout, Swing components,
rendering. Views hold a reference to their corresponding controller.

#### `C*` Controllers (forge-gui-desktop: `forge.screens.match.controllers`)
Per-panel controllers (`CField`, `CHand`, `CPrompt`, `CLog`, etc.). Each implements
`ICDoc` and manages the behavior of its corresponding `V*` view. Controllers hold a
reference to `CMatchUI` and their `V*` view.

### Where Does My Code Go? — Decision Checklist

Before adding or modifying GUI code, work through this checklist top-to-bottom.
The first matching rule wins:

1. **Does it define a new capability the game engine needs from the UI?**
   Add it to `IGuiGame`. Implement in `AbstractGuiGame` (if shared logic) or leave
   abstract for platform-specific implementations.

2. **Is it shared game-UI state that both desktop and mobile need identically?**
   (e.g., tracking which cards are selectable, auto-yield flags, player controller mappings)
   `AbstractGuiGame`.

3. **Is it a convenience wrapper that delegates to abstract methods?**
   (e.g., `one()` calls `getChoices()`, `confirm()` calls overloaded `confirm()`)
   `AbstractGuiGame` — this is the template method pattern already used there.

4. **Does it involve network protocol, delta packets, or tracker synchronization?**
   `NetworkGuiGame`.

5. **Does it format display strings, build UI messages, or manage visual presentation
   for a specific platform?**
   `CMatchUI` (desktop) or `MatchController` (mobile). NOT `AbstractGuiGame`.

6. **Does it coordinate multiple desktop panels or manage screen-level concerns?**
   (e.g., targeting overlay, floating zones, keyboard shortcuts, menus)
   `CMatchUI`.

7. **Does it control the behavior of a specific desktop UI panel?**
   The corresponding `C*` controller (e.g., `CPrompt`, `CField`, `CLog`).

8. **Does it define how a desktop panel looks — layout, Swing components, rendering?**
   The corresponding `V*` view (e.g., `VPrompt`, `VField`, `VLog`).

### Red Flags — Signs You're in the Wrong Layer

- **Adding `javax.swing.*` or `java.awt.*` imports to anything in `forge-gui/`.**
  The `forge-gui` module is shared across platforms. Swing imports mean desktop-specific
  code that belongs in `forge-gui-desktop`.

- **Adding display string formatting (time formatting, player name display, "Waiting for
  X..." messages) to `AbstractGuiGame`.**
  Display presentation belongs in `CMatchUI` or `MatchController`. `AbstractGuiGame`
  should only pass raw data (player views, state flags) — subclasses decide how to
  present it.

- **Adding a `Timer`/`TimerTask` loop in `AbstractGuiGame` that calls
  `showPromptMessage()` to update what the user sees.**
  Periodic UI refresh is a display concern. The base class owns the *mechanism*
  (`awaitNextInput`/`cancelAwaitNextInput`) but not the *presentation* of what gets
  shown during the wait.

- **Checking `GuiBase.isNetworkplay()` or `GuiBase.getInterface().isLibgdxPort()` in
  `AbstractGuiGame` to branch on platform.**
  Platform-specific branches should be handled by overriding methods in the appropriate
  subclass, not by runtime platform checks in the shared base.

- **Putting game-state logic (auto-yield decisions, controller management) in a `V*`
  view class.**
  Views are for layout and rendering. State logic goes in the corresponding `C*`
  controller or `CMatchUI`.

### Concrete Example — What Not To Do

**Bad:** Adding a "Waiting for [Player]... (5s)" feature by putting `getWaitingMessage()`,
`findWaitingForPlayerName()`, `getElapsedTimeString()`, and a 1-second
`scheduleTimerUpdate()` loop directly into `AbstractGuiGame.awaitNextInput()`.

This embeds display formatting (time strings, player name lookup for display) and a
periodic UI refresh loop into the shared abstract layer. It works, but it violates
separation: now the platform-agnostic base class dictates exactly what the waiting
message looks like on every platform.

**Better:** Keep `AbstractGuiGame.awaitNextInput()` as the basic mechanism (single delayed
prompt). Override the prompt content in `CMatchUI` and `MatchController` separately to
add platform-specific waiting messages with elapsed time. Or, have `AbstractGuiGame`
expose the await start timestamp and let subclasses format their own display.

## Project Module Summary

| Module | Purpose |
|---|---|
| `forge-core` | Core engine, card mechanics, rules |
| `forge-game` | Game session, player interactions, game flow |
| `forge-ai` | Computer opponent decision logic |
| `forge-gui` | Shared UI abstractions, interfaces, scripting resources |
| `forge-gui-desktop` | Swing desktop GUI (screen layout, rendering, desktop-specific logic) |
| `forge-gui-mobile` | libgdx mobile GUI logic |
| `forge-gui-android` | Android backend (depends on forge-gui-mobile) |
| `forge-gui-ios` | iOS backend (depends on forge-gui-mobile) |

## Desktop View-Controller Naming Convention

- `V*` prefix = View class (Swing UI component, implements `IVDoc`)
- `C*` prefix = Controller class (behavior/logic, implements `ICDoc`)
- Each `V*` has a corresponding `C*` (e.g., `VField`/`CField`, `VPrompt`/`CPrompt`)
- Top-level screens: `VMatchUI`/`CMatchUI`, `VDeckEditorUI`/`CDeckEditorUI`, `VHomeUI`/`CHomeUI`
