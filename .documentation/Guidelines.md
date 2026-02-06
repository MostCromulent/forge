# Key Patterns & Guidelines

**IMPORTANT: Claude must follow these patterns when writing code in this branch.**

This section summarizes recurring themes from PR feedback for quick reference.

## General Principles
- **Keep it simple:** Code should be simple, easy to follow, and use as few lines as possible while still achieving the desired functionality.
- **Minimal diff:** Prefer small, focused changes over large refactors. The fewer lines changed, the easier to review and less risk of introducing bugs. Do not make cosmetic fixes (whitespace, formatting, style) to code that isn't otherwise being changed for functional reasons — it creates diff noise and draws reviewer scrutiny to unrelated code.
- **Minimize core changes:** Network-specific logic should be isolated in dedicated subclasses (e.g., `NetworkGuiGame`) rather than added to core classes like `AbstractGuiGame`.
- **Avoid over-engineering:** Solve the problem at hand with the simplest approach that works. Don't introduce new classes, event types, or abstractions when existing infrastructure can be reused. Prefer modifying 3 files over creating 10 new ones. If a feature can be built by composing existing mechanisms do that instead of building new framework.

## Code Style
- **Avoid duplicate functions and mechanisms:** Before creating new helper methods, search the file for existing functions with equivalent logic. More broadly, before adding a new system (e.g., a timer, a polling loop, a status display), search the codebase for existing mechanisms that already do the same thing. Enhance the existing mechanism rather than creating a parallel one.
- **Check hotkey conflicts:** When assigning keyboard shortcuts, search for `VK_F[key]` and `getKeyStroke` in the codebase to ensure no conflicts with hardcoded menu accelerators (e.g., F1=Help, F11=Fullscreen).
- **Wrap parseInt/parseLong in try-catch:** System property parsing should handle `NumberFormatException` gracefully with fallback to defaults.
- **Add @Override annotations:** When implementing interface methods, always add `@Override` annotation.
- **Meaningful toString():** Classes used in logging/debugging should override `toString()` rather than inheriting from Object.
- **Intuitive naming conventions:** when naming files or functions the name should be as intuitive as possible so developers can understand its purpose and function.

## Architecture
- **Demand-driven computation:** Expensive operations (iterating all cards, getting all abilities) should only be performed when actually needed, not proactively or on every update cycle.
- **Keep engine clean:** GUI-specific logic (UI hints, styling) belongs in View classes, not in forge-game engine classes like Player.java or PhaseHandler.java.
- **Fix bugs at the closest layer:** Errors and bug fixes should be solved in the closest layer that is practicable and effective. For example, a network serialization issue should be fixed in the network layer, not by adding guards in the game engine. A card rules bug belongs in forge-game, not worked around in forge-gui. Fixing at the source keeps the codebase clean and avoids defensive code proliferating through unrelated layers.
- **Platform-neutral code for platform-neutral features:** If a feature is intended to work across platforms (desktop and mobile), implement the *state and logic* in shared code (e.g., `AbstractGuiGame`, `forge-gui`) rather than in platform-specific classes. However, display formatting, UI messages, and visual presentation always belong in platform subclasses (`CMatchUI`, `MatchController`) even when the feature is cross-platform — shared code should expose data, subclasses decide how to present it.
- **Check for mobile GUI:** Desktop-only features must check `GuiBase.getInterface().isLibgdxPort()` and return early/disable for mobile. Users switching between desktop and mobile share preferences.
- **Isolate network code:** Network-specific functionality should be in dedicated classes (NetworkGuiGame, NetGameController) rather than polluting core game classes.

## Network-Specific Guidelines
- **Delta sync efficiency:** When modifying TrackableObject properties, ensure delta tracking is properly maintained to avoid full-state fallbacks.
- **Reconnection safety:** Any changes to game initialization sequence must maintain reconnection compatibility - session establishment before state transmission.
- **Serialization compatibility:** Changes to serialized objects must maintain backwards compatibility or include migration logic.
- **Thread safety:** Network code handles concurrent operations - ensure proper synchronization when accessing shared state.

## Performance
- **Check cost of helpers:** Consider the performance cost of helper methods that might be called frequently (e.g., on every priority pass or network update).
- **Bandwidth awareness:** Network operations should minimize data transfer - prefer delta updates over full state when possible.

## Testing
- **Headless CI compatibility:** Test classes must not depend on GUI components (`FOptionPane`, `JOptionPane`, etc.) that fail in headless CI environments. Use headless alternatives or skip GUI-dependent tests in CI.
- **Multi-process test isolation:** Tests spawning subprocesses must handle classpath/JAR discovery robustly across different environments (local dev vs CI).

## Documentation
- **Update NetworkPlay.md:** Core architectural changes should be reflected in `.documentation/NetworkPlay.md` in the NetworkPlay/dev branch.
- **Track known issues:** Add bugs to `.documentation/Debugging.md` with full context in the NetworkPlay/dev branch.

---

## GUI Layer Architecture Reference

This section provides detailed architectural guidance for the GUI layer. When the
decision checklist or red flags below conflict with the general guidelines above,
the more specific rule wins.

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

On upstream master (`Card-Forge/forge`), `NetworkGuiGame` does not exist. `CMatchUI`
and `NetGuiGame` extend `AbstractGuiGame` directly:

```
IGuiGame (interface, forge-gui)
  └─ AbstractGuiGame (abstract, forge-gui; also implements IMayViewCards)
       ├─ CMatchUI (forge-gui-desktop) — Swing desktop implementation
       ├─ NetGuiGame (forge-gui) — server-side network proxy
       └─ MatchController (forge-gui-mobile) — libgdx mobile implementation
```

### Layer Responsibilities (As-Is)

#### `IGuiGame` — Interface Contract (forge-gui)
Defines 113 method signatures that any GUI implementation must provide. This is the
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

**What does NOT belong here:** Anything that constructs display strings for specific UI
contexts, formats visual output, manages Swing/libgdx components, or implements
rendering logic. If it's about *how something looks* rather than *what state the game is
in*, it belongs in a subclass.

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
The libgdx-based mobile implementation. Extends `AbstractGuiGame` directly (not
`NetworkGuiGame`, even on the `NetworkPlay` branch — mobile has no network play
support). Uses the singleton pattern (`MatchController.instance`). Mobile-specific
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

These anti-patterns already exist in the codebase as technical debt (see "Known Issues"
below). Do not add new instances of them.

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
  The existing `awaitNextInput` timer mechanism already has this problem in a mild form
  (`updatePromptForAwait` passes a localized "Waiting for Opponent" string). Do not make
  it worse by adding more display logic to the timer callback. The base class should own
  the *mechanism* but not the *presentation*.

- **Checking `GuiBase.isNetworkplay()` or `GuiBase.getInterface().isLibgdxPort()` in
  `AbstractGuiGame` to branch on platform.**
  Platform-specific branches should be handled by overriding methods in the appropriate
  subclass, not by runtime platform checks in the shared base. (Lines 79 and 210 of
  `AbstractGuiGame` already violate this — do not extend the pattern.)

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

Note: `AbstractGuiGame` already has a mild form of this problem —
`updatePromptForAwait()` passes a hardcoded "Waiting for Opponent" string to
`showPromptMessage()`, and both `awaitNextInput()` and `updatePromptForAwait()` are
`final`, so subclasses cannot currently override the prompt content.

**Better:** Make the prompt content overridable (e.g., change `updatePromptForAwait` from
`protected final` to `protected`, or have `AbstractGuiGame` expose the await start
timestamp and let subclasses supply the display string). Then `CMatchUI` and
`MatchController` can each format their own platform-specific waiting messages.

### Project Module Summary

| Module | Purpose |
|---|---|
| `forge-core` | Card data model (CardRules, CardType, CardDb), deck structures, static definitions |
| `forge-game` | Game engine, runtime card mechanics, spell abilities, combat, game flow |
| `forge-ai` | Computer opponent decision logic |
| `forge-gui` | Shared UI abstractions, interfaces, scripting resources |
| `forge-gui-desktop` | Swing desktop GUI (screen layout, rendering, desktop-specific logic) |
| `forge-gui-mobile` | libgdx mobile GUI logic |
| `forge-gui-mobile-dev` | Mobile development/testing harness |
| `forge-gui-android` | Android backend (depends on forge-gui-mobile) |
| `forge-gui-ios` | iOS backend (depends on forge-gui-mobile) |
| `forge-lda` | LDA (Latent Dirichlet Allocation) analysis tooling |
| `forge-installer` | Distribution and installer packaging |
| `adventure-editor` | Adventure mode content editor |

Note on `forge-core` vs `forge-game`: "Card mechanics" can be ambiguous. `forge-core`
holds the *static data model* — what a card is (rules text, type line, colors, editions).
`forge-game` holds the *runtime behavior* — what a card does during a game (`Card.java`,
`SpellAbility.java`, `GameAction.java`, `Combat.java`). If you are modifying how a card
effect works at game time, you are almost certainly in `forge-game`, not `forge-core`.

### Desktop View-Controller Naming Convention

- `V*` prefix = View class (Swing UI component, implements `IVDoc<C*>`)
- `C*` prefix = Controller class (behavior/logic, implements `ICDoc`)
- Each panel `V*` has a corresponding `C*` (e.g., `VField`/`CField`, `VPrompt`/`CPrompt`)
- Top-level screen views (`VMatchUI`, `VDeckEditorUI`, `VHomeUI`) implement
  `IVTopLevelUI` instead of `IVDoc` and follow a different pattern
- `CDetailPicture` is a composite controller (manages `CDetail` + `CPicture`) and does
  not implement `ICDoc` directly

---

## Known Issues to Address

Pre-existing technical debt in `AbstractGuiGame`. These violate the architectural
principles above and should not be extended with new instances:

1. **`isLibgdxPort()` platform checks** (lines 79, 210 of `AbstractGuiGame.java`) —
   Runtime platform branching in the shared base class. Should be handled via method
   overrides in subclasses.

2. **Display string in `updatePromptForAwait()`** (line 482) — Constructs a "Waiting for
   Opponent" display string via `Localizer` and passes it to `showPromptMessage()`. This
   display formatting should live in subclasses, but both `updatePromptForAwait()` and
   `awaitNextInput()` are declared `final`, preventing subclass customization.

3. **`Localizer.getMessage()` calls in shared code** — Several calls in concede dialogs
   and `getInteger()` construct user-facing strings. Button labels ("OK"/"Cancel") are
   arguably shared, but status messages ("Waiting for Opponent", "Yielding until end of
   turn") are display concerns that belong in subclasses.

4. **`javax.swing`/`java.awt` imports in `forge-gui`** — Three files in the shared
   module import platform-specific libraries: `AutoUpdater.java`, `JVMOptions.java`,
   `OperatingSystem.java`. These should live in `forge-gui-desktop` or be refactored to
   remove the dependency.
