# Forge Web GUI

Plays Forge in a browser. The Java side (`src/main/java`) runs the engine and serves the page; the browser side
(`src/main/ts`) is TypeScript, bundled into `src/main/resources/web/js/app.js`.

## Building

`mvn install` builds everything, the browser client included. Maven downloads its own Node into `node/`, so
nothing needs installing first. The bundle is a build output and is not committed.

## Running

Run `forge.web.WebMain` from the repository root, or use the **Forge Web** run configuration in IntelliJ, which
builds the bundle before it launches and serves the page straight from `src/main/resources/web`.

## Working on the browser client

With `-Dforge.web.pageDir=forge-gui-web/src/main/resources/web` (the run configuration sets it), the server
reads the page from disk, so a change needs only a browser reload:

- CSS and `index.html` are served as they are.
- TypeScript needs rebuilding. `npm run watch` in this folder rebuilds on every save; `npm run build` type-checks
  and builds once.

Use the Node that Maven installed (`node/node`, `node/npm`) or any Node 22.

## Layout

- `src/main/ts/protocol.ts` describes every message in both directions and the game objects inside them. The
  server writes those messages by hand in `forge.web`, so a field changed there must change here too.
- `src/main/ts/model.ts` is the browser's copy of the game's object table; `forge.web.BrowserModel` applies
  the same rules on the Java side for the tests.
- `src/main/ts/app.ts` receives every message and schedules one render per frame.
