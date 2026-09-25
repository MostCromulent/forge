# Forge Web GUI

Plays Forge in a web browser. One computer runs Forge as a small web server, and every player, including the person
running it, plays in a browser window. Other players need only a browser and a link: they do not install Forge.

The person running the server is the **host**. Everyone who joins by link is a **guest**.

## Building

You need Java 17 or later and Maven. From the repository root:

    mvn -Pweb -pl forge-gui-web -am install -DskipTests

`-Pweb` is needed because the web module is left out of Forge's normal build. Maven downloads its own copy of Node to build the browser code, so nothing else needs installing. The build makes
`forge-gui-web/target/forge-gui-web.jar`, with the libraries it needs in `forge-gui-web/target/lib/`.

## Starting

From the repository root:

    java -jar forge-gui-web/target/forge-gui-web.jar

Start it from the repository root, or from anywhere inside a Forge install. Forge's files (the `res` folder) are
found from there. To start it somewhere else, add `-Dforge.assets.dir=<the folder that holds res>`.

Two windows open:

- **The game**, in a browser window without tabs or an address bar. It uses Edge or Chrome if either is installed,
  otherwise your default browser.
- **The Forge server window.** It shows whether the server is running and lists the links to join. It also has a
  button to open the game again, and the server's log. Closing it quits Forge.

Forge quits on its own 15 seconds after the last browser closes. To keep it running, untick **Quit when the last
player leaves** in the server window.

Your decks, settings and card images are the same ones desktop Forge uses.

### Options

Add these before `-jar`, for example `java -Dforge.web.port=36800 -jar ...`.

| Option | What it does |
|---|---|
| `-Dforge.web.port=<number>` | Uses another port. The default is 36743, the same as desktop Forge's network games, so change it if you also host from desktop Forge at the same time. |
| `-Dforge.web.noBrowser=true` | Does not open the game window. Open it from the server window instead. |
| `-Dforge.web.noConsole=true` | Does not open the server window. The link to open the game is printed in the terminal instead, and Forge quits if no browser connects within 15 seconds. |
| `-Dforge.assets.dir=<folder>` | Where to find Forge's `res` folder. |

## Playing with other people

1. On the start page, choose **Play with friends**. This opens a table with up to four seats.
2. Send a guest one of the links listed under **Others join at**. The server window lists the same links.
   - A link with a local address (such as `192.168.…`) works for people on the same home network.
   - The **Over the internet** link works only after you forward the port (36743 unless you changed it) to this
     computer in your router's settings. Forge does not do this for you.
3. The guest opens the link in any browser, enters a name, and takes a seat.

Each time Forge starts, it makes new links. Links from an earlier run stop working.

If a player reloads the page or loses the connection, opening the page again returns them to their seat. A match
carries on where it was.

## Decks

Choose **Decks** on the start page to build, change or import decks. You can also do this from your seat at a table.

- **The host's decks** are saved in Forge's deck folders, the same ones desktop Forge uses.
- **A guest's decks** are saved in the guest's own browser, not on the host's computer. They show again only when
  the guest uses the same browser and the same link address. To keep a copy, use **Copy as text** in the deck
  editor.

To import a deck, choose **Import**. Paste a list, drop a deck file, or give a link from Moxfield, Archidekt,
TappedOut or MTGGoldfish. The importer shows each line it read and marks any card it could not find or that is not
allowed.

## Settings

The host's settings are desktop Forge's settings, so a change made in one shows in the other. A guest's settings
start from Forge's defaults and are remembered by the guest's browser.

To change the keys, choose ⋯ → **Keys…** during a match.

To change how the game looks, write CSS in Options → **Custom CSS**. It applies as you type and is kept in your
browser. **Export** saves it as a `.css` file, and **Import** loads one, so a theme can be shared with other
players.

---

## For developers

The Java side (`src/main/java`) runs the engine and serves the page. The browser side (`src/main/ts`) is TypeScript,
bundled into `src/main/resources/web/js/app.js`. The bundle is a build output and is not committed. Code needed only
now and then (three.js, for a portrait breaking when a player loses) is split into `js/chunks/` and loaded when
first used.

### Running from IntelliJ

The **Forge Web** run configuration builds the bundle and starts `forge.web.WebMain`. It sets
`-Dforge.web.pageDir=forge-gui-web/src/main/resources/web`, so the server reads the page from disk and a change
needs only a browser reload:

- CSS and `index.html` are served as they are.
- TypeScript needs rebuilding. In this folder, `npm run watch` rebuilds on every save, and `npm run build`
  type-checks and builds once.

Use the Node that Maven installed (`node/node`, `node/npm`) or any Node 22.

### The protocol

Every message between the server and the browser is a Java record in `ToBrowser` (server to browser) or
`FromBrowser` (browser to server). `src/main/ts/protocol.gen.ts` is generated from those records, and from Forge's
`TrackableProperty` for the game objects, so each field is named in one place only.

After changing a record, regenerate the TypeScript. The compiler then shows what the client must change:

    mvn -Pweb -pl forge-gui-web -am test -Dtest=ProtocolTypesTest -Dsurefire.failIfNoSpecifiedTests=false -Dforge.web.writeProtocol=true

`ProtocolTypesTest` fails whenever the committed file is out of date. That also catches a Forge update that renames
or retypes a game property.

A record field may be null only when it is marked `@Nullable`. It is then left out of the JSON and optional in the
TypeScript. The tests run with assertions on, so a null anywhere else fails them.

### Tests

`mvn -Pweb -pl forge-gui-web -am test` runs the Java tests and the browser client's Vitest tests (`src/test/ts`). `npm test` runs only the
Vitest tests. Tests that play whole games are skipped unless you add `-Drun.stress.tests=true`.

`src/test/resources/traces/whole-game.json` is a recorded game: every state message the browser received, and the
table they build. `SharedTraceTest` checks `BrowserModel` against it and `model.test.ts` checks `model.ts` against
it, so the Java and TypeScript copies of the model cannot drift apart. To record a new one after the model or the
messages change:

    mvn -Pweb -pl forge-gui-web -am test -Dtest=TraceRecordingTest -Dsurefire.failIfNoSpecifiedTests=false -Dforge.web.writeTraces=true

`e2e/` drives the client in a real browser against a real server. Each test starts its own server on its own port,
with a throwaway home folder, so it never touches your Forge settings. These tests take a few minutes and are not
part of the Maven tests. Build the jar first, then:

    cd forge-gui-web/e2e
    npm ci
    npx playwright install chromium   # once
    npx playwright test

### Layout

- `ToBrowser.java` and `FromBrowser.java` define the protocol, and `Wire.java` writes and reads it.
  `protocol.gen.ts` is generated from them, and `protocol.ts` adds the views the client reads game objects through.
- `model.ts` is the browser's copy of the game's object table. `BrowserModel` applies the same rules on the Java
  side, for the tests.
- `app.ts` is the controller. It receives every message, is the only place that sends one, and draws at most once
  per animation frame. Everything else acts through `actions.ts`.
- `WebSession` is one browser. Where it is (the start page, a seat, match setup, a match) is one `Stage`. Every move
  between stages goes through `move`, which also tells the browser. A reconnecting browser is put back by its
  stage.
- `PlayerSettings` is one player's settings. The host's are Forge's preferences. A guest's start from Forge's
  defaults and live in its session. Nothing a player can set is read from the preferences directly.
- `DeckSession` handles the deck editor and the importer. `DeckEditor` holds the deck being edited and saves every
  change. `DeckImport` reads a pasted list, and `Legality` checks a deck against a format.
- The board (`board.ts` and what it calls) is drawn by hand, because it is placed by measuring and animated card by
  card. Everything around it (the start page, match setup, the options, the deck editor, the chat) is Preact
  components in the `.tsx` files, which `screens.tsx` draws from the model on every frame.
