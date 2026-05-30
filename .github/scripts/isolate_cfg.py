#!/usr/bin/env python3
"""Isolate each jpackage launcher to a single jar + its own main class.

jpackage puts every --input jar on the shared classpath and gives added
launchers the primary launcher's main class. This rewrites each launcher's
.cfg so it loads only its own uber-jar with its own main class, matching
Forge's per-app `java -jar X` model and avoiding cross-jar class conflicts.

Usage: isolate_cfg.py <app-cfg-dir> <CfgName>=<jar>=<mainclass> [...]
"""
import sys
import pathlib

cfg_dir = pathlib.Path(sys.argv[1])
for spec in sys.argv[2:]:
    name, jar, mainclass = spec.split("=", 2)
    cfg = cfg_dir / f"{name}.cfg"
    text = cfg.read_text()

    # jpackage emits the platform's path separator after $APPDIR; reuse it.
    sep = "\\"
    for ln in text.splitlines():
        if ln.startswith("app.classpath=$APPDIR"):
            sep = ln[len("app.classpath=$APPDIR")]
            break

    out = []
    for ln in text.splitlines():
        if ln.startswith("app.classpath="):
            continue
        if ln.startswith("app.mainclass="):
            out.append(f"app.mainclass={mainclass}")
            out.append(f"app.classpath=$APPDIR{sep}{jar}")
            continue
        out.append(ln)
    cfg.write_text("\n".join(out) + "\n")
    print(f"isolated {cfg.name} -> {jar} ({mainclass})")
