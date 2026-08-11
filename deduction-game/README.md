# The Thornfield Verdict — a deduction game

A small, self-contained deduction game in the tradition of *Return of the Obra Dinn*
and *The Case of the Golden Idol*: read a scene, examine the evidence, then fill in
the blanks of a verdict — **who is who, who killed whom, how, and why**.

There is exactly one case ("Case No. 47 — Thornfield Manor"). Every answer is pinned
by the evidence; nothing requires guessing. One suspect has the motive, the means, and
the opportunity all at once — the others are ruled out by alibi, by lack of means, or
by having wanted the victim alive.

## How to play

1. Open `index.html` in any modern browser. No build step, no dependencies, no network.
2. Click the evidence cards on the left to reveal clues. Each ends with a short **Note**
   summarizing what it establishes.
3. On the right, tap a blank in the verdict, then pick a word from the bank to fill it.
   Tapping a filled blank again clears it.
4. Press **Deliver the Verdict**. Correct lines lock in green; lines that don't fit the
   evidence flare red so you can reconsider them. Get all seven right to close the case.

## Notes

- Pure HTML/CSS/JS in a single file — open it directly or host it anywhere static.
- Theme-aware (a night "evidence board" in dark mode, an aged-paper dossier in light),
  with a manual toggle in the corner.
- Respects `prefers-reduced-motion`.

This folder is independent of the surrounding project; it's a standalone toy.
