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
- **Platform-neutral code for platform-neutral features:** If a feature is intended to work across platforms (desktop and mobile), implement it in shared code (e.g., `AbstractGuiGame`, `forge-gui`) rather than in platform-specific classes. Platform-specific code should only contain genuinely platform-specific logic.
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
