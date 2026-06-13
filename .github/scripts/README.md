# Card-script review

An advisory check that reviews changed card scripts on a PR and posts terse inline
comments. It runs two independent, low-risk checks and **never fails the check** —
a reviewer decides what to act on.

## What it checks

1. **`cardlint.py` — deterministic linter.** Mechanical checks on the card-script
   DSL: undefined `Execute$`/`SubAbility$`/`Choices$` refs (a bad `Execute$` means
   the card *fails to load*), duplicate params, illegal mana tokens, a missing
   `ManaCost`, near-miss / absent-from-corpus param keys, loyalty abilities missing
   `Planeswalker$ True`, and more.

2. **Scryfall frame fact-check** (`card_script_review.py`). Compares the card *frame*
   — name, type line, power/toughness, mana cost, loyalty — against the printed card.
   Catches transcription slips like a missing `Legendary`, an instant/sorcery swap,
   or a wrong mana symbol. **If the card isn't on Scryfall (e.g. an unreleased card)
   it stays silent.** Lookup is exact-match first; a guarded fuzzy fallback recovers
   name typos only when the match is within ~2 edits.

## Why it stays stable across engine changes

This is the property to preserve when editing:

- **The linter is corpus-driven, not engine-coupled.** Its notion of a valid param
  comes from every *other* card in the repo (`key_freq`), so when the engine adds or
  renames a param, the corpus reflects it and the linter adapts on its own.
- **The fact-check only touches frame fields**, which are stable Scryfall API fields,
  not ability internals. A change to how an ability is scripted affects none of them.
- **Unknown findings degrade gracefully.** If the linter grows a new finding code,
  `terse_comment()` falls back to the linter's own (already readable) message
  instead of failing.

So neither a new card mechanic nor an engine refactor should require rewriting this.
If you find yourself special-casing a particular card here, stop — frame checks and
mechanical lint are all this is meant to do.

## Run it locally

```sh
printf '%s\n' forge-gui/res/cardsfolder/upcoming/your_card.txt > changed.txt
python .github/scripts/card_script_review.py changed.txt        # JSON comments on stdout
python .github/scripts/cardlint.py forge-gui/res/cardsfolder/upcoming/your_card.txt  # raw linter
```

`cardlint.py` is also usable on its own as a standalone linter.

## Files

- `cardlint.py` — the linter.
- `card_script_review.py` — orchestrates linter + Scryfall, emits `{path, line, body}` JSON.
- `../workflows/card-script-review.yml` — runs the above on card-touching PRs and
  posts the comments (de-duped across re-runs).

## Security model

Almost all PRs come from forks, where a plain `pull_request` token is read-only and
can't comment. The workflow therefore uses `pull_request_target`, which runs in the
base repo with a write token. The rule that keeps that safe: **it never executes PR
code.** It runs only the base repo's scripts (this directory, checked out from the
target branch) and pulls in just the PR's `cardsfolder/*.txt` files as data to lint.
The changed-file filter is restricted to `cardsfolder/*.txt`, so a PR cannot
substitute its own version of these scripts, and nothing from the PR is ever built
or run. If you extend this workflow, preserve that property — do not add a step that
checks out or executes PR content.
