# forge-gui-web

Forge played in a web browser. One Java process runs Forge and serves a page, and each browser that opens the page is one player.

This file describes the module's architecture and the rules it is built on. Class and package Javadoc, and the header of each TypeScript file, describe the parts.

## Building and running

    mvn -Pweb -pl forge-gui-web -am install -DskipTests
    java -jar forge-gui-web/target/forge-gui-web.jar

Forge's default build leaves this module out, so `-Pweb` is needed. Maven downloads its own Node and runs npm. The jar needs the `lib/` folder Maven writes beside it, and looks for Forge's `res` folder in the folders above where it runs. It takes options as `-Dforge.web.*` system properties, such as `forge.web.port`.

To work on the page, start the jar with `-Dforge.web.pageDir=forge-gui-web/src/main/resources/web`, so the page is served from the source folder, and run `npm run watch` in this folder. A change to the page then needs only a reload.

## Architecture

### Every seat is a netplay client

The web GUI adds no game logic and no game protocol of its own. Every player, the host included, plays through Forge's existing netplay.

The host's browser starts Forge's netplay server inside the process, on a Netty local channel with no socket of its own, and joins it as a client. Each guest's browser joins the same server the same way. Desktop and mobile Forge can join too, through a WebSocket tunnel on the web port that the netplay server adopts as an ordinary client. The lobby, drafts, state updates and `IGuiGame` calls are the ones desktop netplay uses, so a fix to netplay reaches the web, and the web cannot drift from the other clients' rules.

```
browser ──WebSocket──▶ WebServer ─▶ WebSession ─▶ WebGuiGame (IGuiGame) ◀─ netplay client ─┐
browser ──WebSocket──▶ WebServer ─▶ WebSession ─▶ WebGuiGame (IGuiGame) ◀─ netplay client ─┤ in-process
desktop / mobile Forge ──WebSocket tunnel──▶ WebServer ─────────────────────────────────────┤
                                                                         netplay server ◀─┘ (FServerManager)
                                                                         and the game
```

### The server side

`WebServer` serves the page, images, sounds and sockets on one port. Every request must carry one of two link tokens, the host's or the guests', and only the host's can take the host's seat.

Each browser has one `WebSession`, which survives reloads. A session is always at one stage (the menu, a table, a match, an event, a campaign), and it routes the browser's messages to the class that owns that stage. During a match the browser's seat is a `WebGuiGame`, the web's `IGuiGame`, which turns game state, events and questions into messages.

The game's questions block. When the engine asks a seat something, the netplay client's call waits in `WebGuiGame` until the browser answers. A browser that reloads is sent the whole table again with every question still open, so a reload never loses a game.

### Who can reach it

The server listens on every network interface, over plain HTTP, on Forge's netplay port 36743 by default. Hosting from desktop Forge at the same time needs another `forge.web.port`. Each launch makes two random link tokens, one for the host and one for guests. A request without one is refused, the tokens are compared in constant time, the cookie that holds one is `HttpOnly` and `SameSite=Strict`, and a WebSocket must come from the page's own origin. Desktop and mobile Forge join with the guests' token, through the same port, so the netplay tunnel is open to no one a guest link does not already admit.

A small Swing window shows the links, who is connected and the server's traffic. From it the host can forward the port by UPnP or open a Cloudflare tunnel, for players outside the network. Both are off until the host turns them on.

### The host's profile

The web host is Forge itself, so it reads and writes the same user profile as desktop Forge: preferences, decks, saved games and the card image cache. A deck saved in the browser is there in desktop Forge, and the other way round.

### Threads

Forge's models were written for a single UI thread, and most have no locks. The module keeps that true rather than adding locks to Forge:

- Forge's UI calls (`invokeInEdt`) run on one thread that stands in for Swing's.
- Each part that drives an unguarded Forge model has its own single thread, such as a campaign session or an offline draft.
- Browser messages arrive on the web server's threads. A handler that is slow, or that touches one of the models above, hands its work to the owning thread instead of running it there.

Each seat's netplay client runs its calls on a thread of its own, so a game question waiting on a browser holds only that seat's thread, never a web server thread.

### The page

The page is TypeScript, bundled by esbuild. Only `main.ts` talks to the server. Components never send messages themselves. They call the functions in `actions.ts`.

State lives in one of three places, and each change belongs in one of them:

- `model` holds what the server said. Only the message handlers write it.
- `ui` holds what this browser has arranged and the server never hears of, such as an open pile or a pinned preview. It is changed through `changeUi` and its variants in `ui.ts`.
- A component's own state holds what only that component uses.

Nothing draws when it changes. Messages and `ui` changes schedule one redraw for the next animation frame, which draws the whole page from the state. The game's events since the last frame go to that frame, so each is shown once. During a match, game messages can be held back while the page shows something the player should see first, such as a turn's banner. Input that depends on the board is not sent while they are held.

The pages outside a match use Preact. The match screen is drawn by hand, because it is laid out by measuring the table, and its cards move between places in animations that a re-created element would restart. `match/reconcile.ts` keeps each card's element across redraws. A new part of the match screen follows its neighbours.

The card preview is shared by every page. Elements join it through data attributes rather than code, as `preview.ts` describes.

## The protocol

The messages are Java records in `ToBrowser` and `FromBrowser`. Each build generates the page's TypeScript types from them, so a change to a record fails the build wherever the page uses it. A field may be null only when it is marked `@Nullable`. The tests check that every `IGuiGame` method is answered and that every command the page can send is handled.

Game state needs no protocol work. It travels as Forge's own `TrackableProperty` values, so a new property reaches the page without a change here.

## Text and languages

All text a player sees comes from Forge's language files. The page's own keys sit at the end of `en-US.properties` under `#forge-gui-web`, and new keys go into `en-US.properties` only, because Forge falls back to English. The build fails if the page uses a key the file lacks. The texts follow `java.text.MessageFormat`. Write whole sentences with arguments in them, so a translation can change the word order.

## Logging

The module logs through `netLog` from Forge's `IHasForgeLog`, whatever the subject, so every line it writes reaches the network log file as well as the console.

## Generated files

Git ignores the page bundle (`src/main/resources/web/js/`) and the two generated TypeScript files (`*.gen.ts`). Maven writes them during the build. After changing a message record or adding a language key, `mvn -Pweb -pl forge-gui-web -am process-classes` writes the generated files again. The bundle is written into `src/` so that a server run from the source folder serves it.

## Tests

The Java tests and Vitest run with `mvn -Pweb -pl forge-gui-web -am test`, and CI runs them. They catch changes elsewhere in Forge that would break this module without anyone noticing. `-Drun.stress.tests=true` adds whole games and events played over netplay.

The browser tests in `e2e/` drive the page against a built jar. CI does not run them, so run them by hand before changing the page:

    cd forge-gui-web/e2e
    npm ci
    npx playwright install chromium   # first time only
    npx playwright test
