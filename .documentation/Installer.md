# Forge Installer Improvement Plan

## Contents

- [Problem Statement](#problem-statement)
- [Current Architecture](#current-architecture)
- [Potential Improvements](#potential-improvements)
  - [Launch4j Wrapper for Installer](#launch4j-wrapper-for-installer)
  - [Optional JRE Download](#optional-jre-download)
  - [Delta Update Mechanism](#delta-update-mechanism)
  - [Settings Migration](#settings-migration)
  - [Card Script Migration](#card-script-migration)
  - [Shortcuts (Optional)](#shortcuts-optional)
- [Summary](#summary)
  - [Stable Install Directory](#stable-install-directory)
- [Out of Scope](#out-of-scope-future-consideration)
- [References](#references)

## Problem Statement

The current IzPack-based installer has some potential usability issues:

1. **No upgrade support** — each version needs to be installed to a new directory, creating bloat
2. **No settings migration** — `forge.profile.properties` is not carried forward between versions
3. **No extracted card script migration** — users who unpack `cardsfolder.zip` for performance lose that on upgrade
4. **Java dependency barrier** — users must install Java before they can run the installer JAR
5. **No shortcuts** — no desktop or start menu shortcuts created
6. **No previous install detection** — installer is unaware of any existing Forge installation

## Current Architecture

- **Framework:** IzPack 5.2.4 (produces a runnable JAR)
- **Wizard panels:** HTMLInfoPanel -> TargetPanel -> PacksPanel -> InstallPanel -> FinishPanel
- **Packs:** Two required packs (app archive + Unix scripts)
- **Default dir:** `$project.build.finalName$` (version-specific, e.g. `forge-installer-2.0.11`)
- **Launch4j:** Already used to wrap `forge.exe` / `forge-adventure.exe` as Windows launcher stubs
- **Config files:** `forge-installer/libs/install.xml`, `forge-installer/pom.xml`
- **macOS:** Separate `osx` profile using AppBundler + create-dmg (not covered by this plan)

## Potential Improvements

### Launch4j Wrapper for Installer

**Goal:** Solve the chicken-and-egg problem — users without Java can't run the Java-based installer.

**Approach:**

1. Add a second Launch4j execution in `forge-installer/pom.xml` that wraps the IzPack output JAR
2. Produces `forge-installer-{version}.exe` — a native Windows executable that:
   - Checks for Java 17+
   - If not found, opens the Liberica/Adoptium download page (same as `forge.exe` already does)
   - If found, launches the IzPack installer JAR
3. `dontWrapJar: true` (same pattern as existing Launch4j usage — launcher stub, not embedded)

**This does NOT bundle a JRE** — it just provides a native entry point that gives a helpful error instead of a confusing "this file can't be opened" experience. The `.exe` is tiny (~50KB).

**Limitation:** Users still need to install Java manually. But the UX goes from "double-click JAR, nothing happens" to "double-click EXE, get a download link."

### Optional JRE Download

**Goal:** Fully automate Java installation for users who don't have it.

**Approach — three options:**

#### Option A: Launch4j with bundled JRE download logic

- Enhance the Launch4j wrapper to detect missing Java and offer to download + install it automatically
- Launch4j itself doesn't support this, so this would require a small native bootstrap:
   - A batch/PowerShell script wrapped by Launch4j that checks `java -version`, and if absent, downloads Adoptium MSI via `curl`/`Invoke-WebRequest` and runs it silently
   - Then launches the IzPack installer

#### Option B: JRE download as an installer pack

- Add an optional IzPack pack: "Install Java Runtime (required if not already installed)"
- The pack downloads Adoptium JRE from the internet during install and runs the MSI/PKG silently
- IzPack supports `<executable>` with `type="bin"` for running downloaded installers
- **Requires internet access during install**

**Recommendation:** Option B is the cleanest separation of concerns. The installer gets a bundled minimal JRE so it always works, and Forge itself prompts for a full JRE via Launch4j if needed.

### Delta Update Mechanism

**Goal:** Replace full-archive downloads (~400MB+) with selective file downloads (~10-50MB typical), using the existing `AutoUpdater` infrastructure.

**Context:** Forge already checks for new versions on startup and prompts to update (`AutoUpdater` in `forge-gui`, triggered by `FControl.initialize()` for snapshots and manually via the title bar marquee or Utilities menu). Currently it downloads the entire installer JAR or tar.bz2 and re-runs the full install process. This improvement replaces that with manifest-based delta downloads. No jpackage required — this is pure Java, extending the existing update code.

#### How It Works

**Build time — manifest generation:**

Each release build produces a manifest file listing every distribution file with its SHA-256 hash and size:

```
# forge-manifest-2.0.12.txt
forge-gui-desktop-2.0.12-jar-with-dependencies.jar  sha256=abc123...  size=85000000
res/cardsfolder/cardsfolder.zip                       sha256=def456...  size=290000000
res/editions/Aetherdrift.crf                          sha256=789abc...  size=45000
forge.exe                                             sha256=...        size=52000
...
```

The manifest is published alongside each release on `releases.cardforge.org` or GitHub releases. A local copy is saved in the install directory after each update.

Implementation: Maven Ant task or a small script that walks the assembled distribution directory, hashes each file, and writes the manifest.

**Update time — in the running application:**

1. `AutoUpdater` fetches the new version's manifest (~50KB)
2. Compares each entry against the local manifest (or hashes local files if no saved manifest)
3. Builds three lists:
   - **Changed:** hash differs → download new version
   - **New:** in remote manifest but not local → download
   - **Deleted:** in local manifest but not remote → delete
4. Downloads changed/new files to a staging directory (`update-staging/`)
5. Only after ALL downloads succeed, swaps files into place
6. Saves the new manifest locally
7. Restarts Forge

The existing `GuiDownloadZipService` progress dialog can be reused for download progress.

**Why deltas are small for Forge:** Most of the archive is `cardsfolder.zip` (~290MB) and the fat JAR (~85MB). Between snapshot builds, `cardsfolder.zip` often doesn't change at all. A typical snapshot update would download just the new JAR and a handful of changed resource files — maybe 10-50MB instead of 400MB+.

#### Handling File Dependencies and Consistency

The manifest is a **complete declaration of what should exist** in the target version. If one file change makes another file redundant, the deleted file simply won't appear in the new manifest and gets removed. If a refactor splits one JAR into two, the old JAR is deleted and two new ones are downloaded.

**User-created files are safe:** The updater only touches files listed in the old or new manifest (distribution files). User-created files (custom decks saved locally, unpacked card scripts, `forge.profile.properties`, etc.) are ignored — they aren't in any manifest.

**Atomic consistency** — preventing half-updated installs if the updater crashes mid-download:

1. All changed files download to a staging directory (e.g., `update-staging/`)
2. Only after all downloads succeed and verify (hash check), swap into place: rename old → `.bak`, rename staged → live
3. On next launch, clean up `.bak` files
4. If the swap is interrupted, the next launch detects incomplete staging and either retries the swap or falls back to a full re-download

This staged approach ensures the install is always in a consistent state — either fully old version or fully new version, never a mix.

#### Implementation Options

**Option A: Custom (recommended — fits existing architecture)**

Extend `AutoUpdater` directly:
- New Java code for manifest parsing, hash comparison, selective download, and staged swap
- Reuses existing version checking, UI prompts, progress dialogs, and restart logic
- New Maven build step to generate the manifest (Ant checksum task)
- Individual files hosted at a known URL pattern (e.g., `releases.cardforge.org/forge/{version}/{filepath}`)

**Option B: update4j library**

[update4j](https://github.com/update4j/update4j) (~100KB single JAR) provides manifest-based delta updates out of the box:
- XML configuration with checksums, sizes, OS filters
- Automatic delta downloads
- Supports file signing for integrity verification
- Staged updates with atomic swap built in
- Trade-off: external dependency, less control over UX integration

**Option C: Per-file binary diffs (rsync-like)**

Tools like xdelta/bsdiff produce binary patches of individual files (e.g., a 5MB diff for an 85MB JAR). Most bandwidth-efficient but requires generating diffs between every consecutive version pair at release time and applying binary patches client-side. Probably not worth the complexity — file-level deltas (Option A/B) already reduce downloads by 80-90%.

**Recommendation:** Option A. The existing `AutoUpdater` already does most of the work. The missing piece is manifest comparison and selective downloading instead of full-archive downloading.

#### Server Requirements

Minimal. The release server needs to host:
- The manifest file per version (~50KB)
- Individual files accessible by path (or a ZIP of just the changed files)

Both `releases.cardforge.org` and GitHub releases can serve this. No new infrastructure required — just an additional artifact published per release.

### Settings Migration

**Goal:** Automatically carry forward `forge.profile.properties` when upgrading.

**Approach — optional pack with conditional logic:**

1. Add a `UserInputPanel` before `PacksPanel` that asks:
   - "Upgrading from a previous version?" (checkbox)
   - "Previous install directory:" (directory picker, shown conditionally)
2. Add an optional pack "Migrate settings from previous install" (`required="no"`, `preselected="yes"`)
3. The pack uses an `<executable>` post-install action (or a custom `InstallerListener`) to:
   - Copy `forge.profile.properties` from the previous dir to `$INSTALL_PATH/`
   - Warn if the file doesn't exist at the specified path

**Alternative simpler approach:** Since user data is stored in platform appdata dirs by default (`%APPDATA%/Forge/`, `~/.forge/`, etc.), the main thing that needs migrating is the profile properties file itself. Once the install directory is stable, the file persists across upgrades because the directory doesn't change. This migration feature is primarily needed as a one-time path for users upgrading from the old version-specific directory layout to the new stable one.

**IzPack features used:** `UserInputPanel`, `conditions`, `variables`, `<executable>` or `InstallerListener`

### Card Script Migration

**Goal:** Preserve unpacked `cardsfolder/` for users who extract it for performance.

**Approach:**

1. During the install, before extracting the archive, check if `$INSTALL_PATH/res/cardsfolder/` exists as a directory (not just `cardsfolder.zip`)
2. If it does, back it up (rename to `cardsfolder.bak/`)
3. After archive extraction (which writes `cardsfolder.zip`), offer to restore the unpacked folder
4. Implement as an optional pack or post-install `<executable>` script

**Note:** If the default install dir is stable, the archive extraction with `override="true"` would replace `cardsfolder.zip` but wouldn't delete an existing `cardsfolder/` directory. The main concern is that stale card scripts from an old version might conflict with new ones. A clean re-extract is probably better — so the real feature here is "automatically unpack cardsfolder.zip after install" as an option.

### Shortcuts (Optional)

**Goal:** Offer to create desktop and start menu shortcuts.

**Changes:**
- Add IzPack's built-in `ShortcutPanel` to `install.xml` (between InstallPanel and FinishPanel)
- `ShortcutPanel` presents checkboxes — the user chooses which shortcuts to create (or none)
- Configure shortcuts for `forge.exe`, `forge-adventure.exe`, and optionally `adventure-editor.exe`
- IzPack handles Windows shortcuts natively and `.desktop` files on Linux

**IzPack config addition to `install.xml`:**
```xml
<panel classname="ShortcutPanel" id="shortcuts"/>
```
Plus a `shortcutSpec.xml` resource defining the shortcuts.

## Summary

| Improvement | Files Changed | Impact |
|-------------|---------------|--------|
| Stable install dir | `default-dir.txt` | Eliminates version bloat |
| Settings migration | `install.xml`, new `UserInputPanel` config | Preserves user settings |
| Card script handling | `install.xml`, post-install script | Niche but appreciated |
| Shortcuts | `install.xml`, new `shortcutSpec.xml` | Basic usability |
| Launch4j wrapper | `forge-installer/pom.xml` | Native Windows entry point |
| JRE automation | `pom.xml`, jlink config, scripts | Removes #1 adoption barrier |
| Delta updates | `AutoUpdater.java`, manifest build step, server config | Reduces update downloads by ~90% |

### Stable Install Directory

**Goal:** Stop side-by-side version bloat by defaulting to a stable path.

**Changes:**
- `forge-installer/libs/default-dir.txt`: Change from `$project.build.finalName$` to a stable path (e.g. `Forge` on Windows, which IzPack resolves relative to Program Files or user home)
- The `override="true"` on the pack file entries already handles overwriting existing files

**Prerequisite:** Currently, side-by-side installs are the recommended approach *because* the installer doesn't safely handle in-place upgrades — it blindly extracts over the existing directory without preserving settings, card scripts, or shortcuts. Changing the default to a stable directory only makes sense once the installer can handle upgrades properly. Settings migration, card script handling, and shortcuts should be in place first, otherwise the "upgrade" experience is worse than side-by-side (user loses their config with no easy rollback).

**Risk:** Users who rely on side-by-side installs for rollback lose that ability. Consider documenting how to do manual side-by-side if desired.


## Out of Scope (Future Consideration)

- **Replace IzPack with jpackage entirely** — produces native `.msi`/`.deb`/`.rpm` with bundled JRE. The "right" long-term answer but a major project requiring platform-specific CI pipelines and maintainer buy-in.
- **macOS installer improvements** — the `osx` profile uses AppBundler + create-dmg which is a separate system. Similar principles apply but implementation differs.

## References

- IzPack 5 documentation: http://izpack.org/documentation/
- Launch4j: http://launch4j.sourceforge.net/
- Current installer config: `forge-installer/libs/install.xml`
- Current installer POM: `forge-installer/pom.xml`
- Launch4j config: `forge-gui-desktop/pom.xml` (search for `launch4j`)
- User discussion: `C:\Users\Angas\Downloads\Installer.txt`
