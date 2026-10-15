# forge-gui-web

Forge played in a web browser. One Java process runs Forge and serves a page. Each browser that opens the page is one player.

This file is for contributors. It covers how the module is built, how the server and the page fit together, and the steps for the changes people make most often.

## Building and running

    mvn -Pweb -pl forge-gui-web -am install -DskipTests
    java -jar forge-gui-web/target/forge-gui-web.jar

Forge's default build leaves this module out, so `-Pweb` is needed. Maven downloads its own copy of Node and runs npm, so no Node install is needed. The jar needs `target/lib/` beside it. It looks for Forge's `res` folder in the folders above where it runs. If it cannot find it, pass `-Dforge.assets.dir=<folder containing res>`.

| Option | Effect |
|---|---|
| `-Dforge.web.port=<n>` | Port to serve on. The default, 36743, is desktop Forge's network port too. |
| `-Dforge.web.noBrowser=true` | Don't open the game in a browser. |
| `-Dforge.web.noConsole=true` | Don't open the server window. The host's link is printed instead. |
| `-Dforge.web.keepOpen=true` | Keep Forge running with no browser connected, for a server left on between games. |
| `-Dforge.web.pageDir=forge-gui-web/src/main/resources/web` | Serve the page from the source folder, so a change to the page needs only a reload. |

For work on the page, run the jar with `pageDir` set and run `npm run watch` in `forge-gui-web`. esbuild then rebuilds the bundle on each save. `npm run watch` does not type-check, and it does not regenerate the protocol types (see [Generated files](#generated-files)).

## How it fits together

```
browser (src/main/ts)                server (src/main/java/forge/web)                   Forge
─────────────────────                ────────────────────────────────                   ─────
main.ts ◀── WebSocket /ws ──▶ WebServer ─▶ WebSessions ─▶ WebSession   (one per browser)
  │                                                         │
  ├─ model.ts   ◀─ state, prompt,  ── WebGuiGame (IGuiGame) ◀─┼─ LocalGame: a netplay client ◀──┐
  ├─ match/board.ts  questions                              │                                  │ in-process
  ├─ screens.tsx                                            ├─ Lobby (ServerGameLobby)          │ netplay
  └─ actions.ts ── what the player did ────────────────────▶└─ DeckSession, OnlineDraft …      │
                                                               FServerManager ◀── the game ─────┘
desktop or mobile Forge ── WebSocket tunnel ─▶ WebServer ─────▶ FServerManager.adopt
```

Every seat is a netplay client, the host's included. The host's `LocalGame` starts Forge's netplay server (`FServerManager.startLoopbackServer`) and joins it. That server listens on a Netty local channel inside the process, with no socket of its own. Each guest's session joins the same server the same way. The lobby, drafts, delta packets and `IGuiGame` calls are the ones desktop netplay uses, so a fix to netplay reaches the web too.

Only the web port is reachable from outside. A desktop or mobile Forge joins a web table through a WebSocket tunnel on that port, and `FServerManager.adopt` takes the connection over as an ordinary netplay client. Browser players and app players sit at the same table.

`WebServer` (Netty) serves the page, card images, sounds and the socket. It refuses any request without one of the two link tokens. Only the host's token can take the host's seat. There is one `WebSession` per browser. It knows which screen the browser is on, and passes each message to `Lobby`, `DeckSession`, the draft classes, `ConquestGame` and `QuestGame`, or `WebGuiGame` during a match.

`WebGuiGame` is the web's `IGuiGame`. It sends the browser what changed, as JSON. When the game needs an answer, the call becomes a request, and `PendingRequests` holds the calling thread until the browser replies. A browser that reloads is sent the whole table again and every question that is still open.

### Where things are

| Java package | Holds |
|---|---|
| `forge.web` | Start-up (`WebMain`), the HTTP and socket server (`WebServer`), the browser sessions (`WebSessions`, `WebSession`), settings, dev mode and the host's own questions (`HostRequests`). |
| `.protocol` | The message records (`ToBrowser`, `FromBrowser`), their encoding, and the generator of the page's types. |
| `.match` | A match as a browser plays it: `WebGuiGame`, `LocalGame`, and the classes that turn game state, events and questions into messages. |
| `.lobby` | The table before a match: seats, decks, formats, variants and teams. |
| `.deck` | The deck finder, the editor, the card catalogue, importing, legality and deck storage. |
| `.limited` | Sealed and draft, offline against the computer or online at a netplay table. |
| `.campaign` | Quest and Planar Conquest. |
| `.images` | Card pictures, thumbnails, foil and the skin's sprite sheets. |
| `.console` | The server window on the host's desktop, and the tools that open the server to the internet. |

| TypeScript | Holds |
|---|---|
| `app.ts`, `main.ts` | The entry point and the controller: the socket, the model, the frame. |
| `model.ts`, `ui.ts` | What the server said, and what this browser has arranged (see [The page](#the-page)). |
| `actions.ts` | Every message the page can send. Components call these. They never send messages themselves. |
| `screens.tsx` | Which page shows, and drawing the Preact pages. |
| `match/` | The match screen. Mostly drawn by hand, from `match/board.ts` down. |
| `lobby/`, `deck/`, `limited/`, `campaign/`, `conquest/`, `quest/` | One folder per part of the page. |
| `preview.ts` | The card preview every page shares (see [Giving cards the preview](#giving-cards-the-preview)). |
| `dialog.tsx`, `dom.ts`, `press.ts`, `sheet.ts`, `hooks.ts` | Shared building blocks: dialogs and dropdowns, element helpers, pointer gestures, phone sheets, Preact hooks. |
| `text.ts`, `settings.ts`, `storage.ts` | Text and languages, the options, and this browser's saved state. |

## Threads

| Thread | What runs on it |
|---|---|
| `WebServer` (Netty, two threads) | HTTP requests and the page's socket. Every browser message arrives here, and `WebSession.onMessage` handles it on this thread. |
| `WebUI` | Stands in for Swing's event thread. Forge's `invokeInEdt` calls run here, and so do opening a table, starting a match and leaving one. |
| `WebBackground` | `runBackgroundTask`. One short-lived thread per task, for slow work such as loading net decks or setting up an event. |
| `WebClient` (one per `WebGuiGame`) | The netplay client's protocol calls. A question that waits on the browser holds only this thread. |
| `WebCampaign` (one per session) | Everything a Quest or Conquest page does. Forge's campaign models have no locks, so only this thread touches them. |
| `Draft` (one per offline draft) | Forge's `BoosterDraft`, which has no locks either. |
| `CardThumbnails` (two threads) | Shrinking card pictures for the deck lists. |
| Forge's game thread | The engine, inside the netplay server. It waits for a web seat's answer over netplay, as it waits for any remote player. |

A question goes round like this. The engine on its game thread asks a seat over netplay. The seat's `LocalGame` receives the call and runs it on `WebClient`. `WebGuiGame` sends the browser a request and waits in `PendingRequests`. The browser's answer arrives on a `WebServer` thread and completes the request. `WebClient` then replies over netplay and the game goes on.

Do not block a `WebServer` thread. A message whose handling is slow, or that needs Forge's UI thread, is handed to `WebUI`, `WebBackground` or `WebCampaign`, as the existing cases in `WebSession.onMessage` show.

## The page

`app.ts` loads the page's text from the server first, then loads `main.ts`. Only `main.ts` talks to the server. The page is TypeScript, with Preact for most screens, bundled by esbuild.

### State

The page keeps three kinds of state, and each change belongs in one of them.

- `model` (`model.ts`) holds what the server said: the game, the prompt, the lobby, the open page's data. Only `main.ts` writes it, from incoming messages.
- `ui` (`ui.ts`) holds what this browser has arranged and the server never hears of: open piles, the zone search, a pinned preview, the open menu. Change it with `changeUi(u => …)`, which draws on the next frame. Use `changeUiNow` when the change must be drawn before the call returns, and `eased` to animate the change with a view transition.
- A Preact component's own `useState`, for what only that component uses, such as a text field being typed into.

A new message's handler in `main.ts` updates `model`. A new control calls a function in `actions.ts`, and the server's answer comes back as a message.

### The frame

Messages and `changeUi` calls do not draw anything themselves. They schedule one `render()` in `main.ts` for the next animation frame, which draws everything from the model in this order:

1. The page that shows (`screenOf` and `pageUnder` in `screens.tsx`), with the other `<main>` elements hidden.
2. The Preact pages (`renderScreens`).
3. During a match: the side column, the board (`match/board.ts`), the prompt, the card preview, block dragging and the overlay that draws targets and attacks.

The game's events since the last frame (`model.events`) are handed to that frame's board, so each event is shown once.

During a match `match/pacer.ts` can hold messages back. While a turn's banner shows, or while another player's play stays on screen a moment, the game's messages wait and are applied in order afterwards. Input that depends on the board (selecting a card, OK, cancel) is not sent while messages are held, because the player would be answering a board the server has moved past. `main.ts` logs "Not sent while the page catches up" when this happens.

### Preact or drawn by hand

The pages outside a match use Preact. The match screen is drawn by hand: `match/board.ts` and most of the modules in `match/`, plus `preview.ts`. The few match dialogs and menus (`match/dialogs.tsx`, `match/gamemenu.tsx`) are Preact.

The board is drawn by hand because it is laid out by measuring, and its cards move between places in animations that must not restart. `match/reconcile.ts` keeps each card's element across redraws and moves it with `moveBefore` where the browser has it, so an animation that is running keeps running. A new piece of the match screen should follow its neighbours: hand-drawn beside the board, Preact for a dialog.

## The protocol

Messages are Java records in `ToBrowser` and `FromBrowser`. Each build generates `src/main/ts/protocol.gen.ts` from them and type-checks the TypeScript against it, so changing a record breaks the build wherever the page uses it. A field may be null only if it is marked `@Nullable`.

- To add a question the game asks, implement the `IGuiGame` method in `WebGuiGame` with `ask(request, check)`, add a `@Request` record to `ToBrowser`, and draw it in `match/dialogs.tsx`. `WebGuiGameTest` fails while any such method is still left to Forge's default.
- To add a message to the page, write a `@Message` record in `ToBrowser` and handle it in `main.ts`.
- To add a command from the page, write a `@Command` record in `FromBrowser`, add its function to `actions.ts`, and handle it where `WebSession.onMessage` routes it. `CommandRoutingTest` fails if a command is routed nowhere.
- A new game property needs no work. It travels with the rest of `TrackableProperty`.

## Text and languages

All the text a player sees comes from Forge's language files (`forge-gui/res/languages`). The page's own keys are grouped at the end of `en-US.properties`, under `#forge-gui-web`. Add new keys to `en-US.properties` only. Forge falls back to English for a key a language lacks.

In TypeScript, `t('lblWebSomething', arg…)` returns the text, and `tNodes` does the same when a sentence has elements inside it. The build fails if the page uses a `lblWeb…` key that `en-US.properties` does not have. On the Java side, text sent for display goes through `Localizer`.

The texts follow `java.text.MessageFormat`. `{0}` is an argument, and an apostrophe is written twice (`can''t`). Write whole sentences with arguments in them, rather than joining pieces together, so a translation can change the word order. Forge loads the language files as Latin-1, so write any other character as a `\u` escape.

## Recipes

### Adding a page

A page is a `<main>` element that `render()` shows or hides. Adding one touches:

1. `src/main/resources/web/index.html`: a `<main id="…" hidden>` beside the others.
2. `screens.tsx`: the page's name in `DEPTH`, which orders pages so the page-change animation knows forward from back. The rule that picks it in `pageUnder`. Its `render(…)` call in `renderScreens`.
3. `main.ts`: its id in the list `render()` shows and hides.
4. The model state it reads, and the server message that fills it (see the next recipe).

### Adding a mode's state

The server sends a `hello` message whenever the session moves to another stage (the menu, a table, a match, an event), and its handler in `main.ts` clears what belonged to the last one. A mode with several fields in `model` keeps their empty values in one object in `model.ts`, such as `NO_QUEST` or `NO_CONQUEST`. `createModel` spreads it in, and the `hello` handler assigns it back when the mode ends. A new field goes into its mode's object, so the reset needs no new line.

For hand-drawn match modules, state kept in module variables is cleared by a `reset…` function that `resetTable` in `match/board.ts` calls. That runs when a new game's first full state arrives and when the match is left.

### Giving cards the preview

Every page shares one card preview: a large picture beside the pointer on a desktop, and a sheet on a phone after a long press. Any element joins it with data attributes, and no code:

- `data-image`: the card's image key. Hovering previews it, and a long press pins it.
- `data-act`: the label of the phone sheet's button, which clicks the element. Leave it out when a tap does nothing.
- `data-menu`: present when the element has a right-click menu, so the phone sheet offers it.
- `data-list`: on a container, so the phone sheet's arrows step through the cards inside it.

## Generated files

| File | Made by | When |
|---|---|---|
| `src/main/ts/protocol.gen.ts` | `forge.web.protocol.ProtocolTypes`, from the records in `ToBrowser` and `FromBrowser` | Maven's `process-classes` phase |
| `src/main/ts/text.gen.ts` | `forge.web.protocol.PageTextKeys`, from the keys the page uses and their English text | Maven's `process-classes` phase |
| `src/main/resources/web/js/` | esbuild (`npm run build`) | Maven's `generate-resources` phase |

All three are ignored by git. After changing a record or adding a language key, run `mvn -Pweb -pl forge-gui-web -am process-classes` to write the generated files again. `npm run watch` picks the change up from there.

The bundle goes into `src/main/resources/web/js/`, beside the page's other files, rather than into `target/`. A server run with `-Dforge.web.pageDir` then serves the latest bundle from the source folder.

## Tests

The tests catch changes elsewhere in Forge that would break this module without anyone noticing. They cover the wire format, `IGuiGame` methods left unanswered, a web seat answering the engine's prompts, netplay between host and guest, and the server's tokens and paths.

- `mvn -Pweb -pl forge-gui-web -am test` runs the Java tests and Vitest. CI runs these on any change to Java, a pom, `en-US.properties` or this module.
- `npm test` runs Vitest alone. Its tests are in `src/test/ts` and run on Node, so they cover logic that does not touch the page.
- Adding `-Drun.stress.tests=true` also plays whole games and events over netplay.

The tests in `e2e/` drive the page in a real browser against a built jar. CI does not run them, so run them by hand before changing the page:

    cd forge-gui-web/e2e
    npm ci
    npx playwright install chromium   # first time only
    npx playwright test

Each test starts its own server, with its own Forge home folder, and serves the page from the source folder. Rebuild the jar after a Java change. A page change needs only `npm run build`.
