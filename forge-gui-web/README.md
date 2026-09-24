# Forge Web GUI

Plays Forge in a browser. The Java side (`src/main/java`) runs the engine and serves the page; the browser side
(`src/main/ts`) is TypeScript, bundled into `src/main/resources/web/js/app.js`. Code the page needs only now and
then (three.js, for a portrait breaking when a player loses) is split into `js/chunks/` and fetched when first used.

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

## The protocol

Every message between the server and the browser is a Java record in `ToBrowser` (server to browser) or
`FromBrowser` (browser to server). `src/main/ts/protocol.gen.ts` is generated from those records, and from
Forge's `TrackableProperty` for the game objects, so a field is named in one place only.

After changing a record, regenerate the TypeScript and let the compiler show what the client must change:

    mvn -pl forge-gui-web -am test -Dtest=ProtocolTypesTest -Dsurefire.failIfNoSpecifiedTests=false -Dforge.web.writeProtocol=true

`ProtocolTypesTest` fails whenever the committed file is out of date, which also catches a Forge update that
renames or retypes a game property.

A record component may be null only when marked `@Nullable`; it is then left out of the JSON and optional in the
TypeScript. The tests run with assertions on, so a null anywhere else fails them.

## Tests

`mvn test` runs both halves: the Java tests, and the browser client's (Vitest, in `src/test/ts`; `npm test` runs
just those).

`src/test/resources/traces/whole-game.json` is a recorded game: every state message the browser received, and the
table they build. `SharedTraceTest` holds `BrowserModel` to it and `model.test.ts` holds `model.ts` to it, so the
two copies of the model's rules cannot drift apart. To record a fresh one after the rules or the messages change:

    mvn -pl forge-gui-web -am test -Dtest=TraceRecordingTest -Dsurefire.failIfNoSpecifiedTests=false -Dforge.web.writeTraces=true

Tests that play whole games are skipped unless `-Drun.stress.tests=true` is given.

### In a browser

`e2e/` plays the client in a real browser against a real server: naming yourself, hosting a game against the
computer, a guest joining by link and following the host into the match, and each player's settings staying their
own. Each test starts a server of its own on a port of its own, with a throwaway home folder, so it never touches
your Forge preferences. They take a minute or two and are not part of `mvn test`; run them after building the jar:

    mvn -pl forge-gui-web -am package -DskipTests
    cd forge-gui-web/e2e
    npm ci
    npx playwright install chromium   # once
    npx playwright test

## Layout

- `src/main/java/forge/web/ToBrowser.java` and `FromBrowser.java` define the protocol; `Wire.java` writes and
  reads it.
- `src/main/ts/protocol.gen.ts` is generated from them; `src/main/ts/protocol.ts` adds the views the client reads
  game objects through.
- `src/main/ts/model.ts` is the browser's copy of the game's object table; `forge.web.BrowserModel` applies
  the same rules on the Java side for the tests.
- `src/main/ts/app.ts` is the controller: it receives every message, is the only place that sends one, and
  draws at most one frame per animation frame. Everything else acts through `actions.ts`.
- `WebSession` is one browser. Where it is (the start page, taking a seat, match setup, a match) is one `Stage`,
  and every move between stages goes through `move`, which also tells the browser. A reconnecting browser is put
  back by its stage.
- Every browser shares the process's Forge preferences, which are the host's. `PlayerSettings` is one player's
  settings: the host's are those preferences, a guest's start from Forge's defaults and live in its session (its
  browser remembers them). Nothing a player can set is read from the preferences directly.
- The board (`board.ts` and what it calls) is drawn by hand, because it is placed by measuring and animated card
  by card. Everything around it (the start page, match setup, the options, the game's questions, the chat) is
  Preact components in the `.tsx` files, which `screens.tsx` draws from the model on every frame.
