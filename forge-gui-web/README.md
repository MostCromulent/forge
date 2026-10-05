# Forge Web GUI

Forge in a web browser with a modern interface. The host runs a small server on their computer. Players join by
web link with no further install required. You can also play alone against the AI.

The interface aims to be easy to pick up, and leaves out much of desktop Forge's detailed customisation. It is
built first for a mouse and keyboard. A mobile layout covers setting up and playing a match but is less tested;
the deck editor, Draft, Sealed and Conquest have none yet.

<p>
  <img src="docs/table.webp" width="49%" alt="Setting up a game against two AI opponents">
  <img src="docs/cast.webp" width="49%" alt="A spell cast from the hand at two creatures">
</p>
<p>
  <img src="docs/attack.webp" width="49%" alt="Three creatures attacking">
  <img src="docs/victory.webp" width="49%" alt="A winning spell and the end of a game">
</p>
<p>
  <img src="docs/hover.webp" width="49%" alt="Cards raised from the hand as the pointer passes over them">
  <img src="docs/conquest.webp" width="49%" alt="The map of a Planar Conquest">
</p>

## For players

### What you can play

- **Constructed**, Commander, Brawl, Oathbreaker, Tiny Leaders, Momir Basic and MoJhoSto, for up to four players.
- The variants **Vanguard**, **Planechase**, **Archenemy** and **Archenemy Rumble**.
- **Draft** and **Sealed** for up to eight. The computer fills empty seats.
- **Planar Conquest**, on the same saves as the mobile app.

Not yet: Quest, Adventure, puzzles, constructed gauntlets and Winston draft.

### Starting it

1. Install Java 17 or newer.
2. Download `forge-web.zip` from the [pre-release](https://github.com/MostCromulent/forge/releases/tag/web-snapshot)
   and unzip it anywhere.
3. Open `forge-gui-web.jar`. If nothing happens, run `java -jar forge-gui-web.jar` in that folder.

The game opens in your browser, along with the **server window**. Keep that window open: closing it quits Forge.
Forge also quits when the last browser leaves, unless you untick **Close Forge when everyone has left, including
you**. To update, replace the folder with the new zip.

<img src="docs/server.webp" width="49%" alt="The server window">

### Playing with friends

Click **Invite** at the top of the table, or **Copy link** in the server window.

- The link for **players on your network** works on your home network.
- The link for **players on the internet** works once your router forwards the port to this computer. **Forward
  the port automatically (UPnP)** asks the router to do that. If it refuses, see the
  [network setup guide](https://github.com/Card-Forge/forge/wiki/Network-Play#network-configuration).

Links aren't encrypted and anyone with one can join, so only send them to people you're playing with. Never share
the host's own link: it controls the table and the server. Links change each time Forge starts. A player who
reloads or drops out goes straight back to their seat.

### Decks and settings

Build decks in the deck editor, or import them from a pasted list, a deck file, or a link from Moxfield,
Archidekt, TappedOut or MTGGoldfish.

- **The host's decks and game settings** are kept in the Forge user folder, which desktop Forge also uses, so a
  change in one shows in the other. If you already play desktop Forge, back that folder up first:
  `%APPDATA%\Forge` on Windows, `~/Library/Application Support/Forge` on macOS, `~/.forge` on Linux.
- **A guest's decks** are kept in their browser, for the exact link they joined by. A private window keeps
  nothing.
- **Display settings, keys and custom CSS** are kept in each browser, the host's included.

---

## For developers

The Java side (`src/main/java`) runs the engine and serves the page. The browser side (`src/main/ts`) is
TypeScript and Preact, bundled by the build into `src/main/resources/web/js/`, which git ignores.

### Building

    mvn -Pweb -pl forge-gui-web -am install -DskipTests
    java -jar forge-gui-web/target/forge-gui-web.jar

`-Pweb` is needed because Forge's default build leaves this module out. Maven fetches its own Node. The jar needs
`target/lib/` beside it and finds Forge's `res` folder by searching up from where it runs; otherwise pass
`-Dforge.assets.dir=<folder containing res>`.

| Option | Effect |
|---|---|
| `-Dforge.web.port=<n>` | Port to serve on. The default, 36743, is desktop Forge's network port too. |
| `-Dforge.web.noBrowser=true` | Don't open the game in a browser. |
| `-Dforge.web.noConsole=true` | Don't open the server window; the host's link is printed instead. |
| `-Dforge.web.pageDir=forge-gui-web/src/main/resources/web` | Serve the page from source, so a change needs only a reload. |

With `pageDir` set, `npm run watch` in this folder rebuilds the TypeScript on save.

### How it fits together

One Java process runs Forge and serves the page on one port. Each browser is a player, over one WebSocket.

```
browser (src/main/ts)                server (src/main/java/forge/web)              Forge
─────────────────────                ────────────────────────────────              ─────
main.ts ◀── WebSocket /ws ──▶ WebServer ─▶ WebSessions ─▶ WebSession   (one per browser)
  │                                                           │
  ├─ model.ts   ◀─ state, prompt,  ── WebGuiGame (IGuiGame) ◀─┤─ LocalGame: a netplay client ◀─┐
  ├─ board.ts      questions                                  │                                 │ loopback
  ├─ screens.tsx                                              ├─ Lobby  (ServerGameLobby)       │ netplay
  └─ actions.ts ── what the player did ──────────────────────▶└─ DeckSession, OnlineDraft …     │
                                                                  FServerManager ◀── the game ──┘
```

- **Every seat is a netplay client.** The host's `LocalGame` starts Forge's netplay server (`FServerManager`) on
  loopback and joins it, and each guest's session joins the same server. The lobby, drafts, delta packets and
  `IGuiGame` calls are desktop netplay's own. Only the web port is reachable from outside.
- **`WebServer`** (Netty) serves the page, card images, sounds and the socket, and turns away any request without
  one of the two link tokens. Only the host's token can take the host's seat.
- **`WebSession`**, one per browser, knows which screen it is on and routes its messages to `Lobby`,
  `DeckSession`, the draft classes, or `WebGuiGame` in a match.
- **`WebGuiGame`** is the web's `IGuiGame`. It sends the browser what changed as JSON. A call that needs an answer
  becomes a request, and `PendingRequests` holds the game's thread until the reply. A browser that reloads is sent
  the whole table and every open question again.

In the page, `main.ts` is the only module that talks to the server. Messages update `model.ts`, and the page is
drawn from the model once a frame. The board is drawn by hand (`board.ts`); everything else is Preact. Nothing
that draws sends anything: it calls `actions.ts`.

### The protocol

Messages are Java records in `ToBrowser` and `FromBrowser`. Each build writes `src/main/ts/protocol.gen.ts` from
them and type-checks the TypeScript against it, so a changed record fails the build where the browser uses it. A
field may be null only if marked `@Nullable`.

- **A new question from the game:** implement the `IGuiGame` method in `WebGuiGame` with `ask(request, check)`,
  add a `@Request` record to `ToBrowser.REQUESTS`, and draw it in `dialogs.tsx`. `WebGuiGameTest` fails while any
  such method is left to Forge's default.
- **A new message:** a `@Message` record in `ToBrowser.MESSAGES`, handled in `main.ts`; or a `@Command` record in
  `FromBrowser.COMMANDS`, handled where `WebSession` routes it.
- **A new game property** needs nothing: it travels with the rest of `TrackableProperty`.

### Text and languages

Everything a player reads comes from Forge's language files (`forge-gui/res/languages`). The page's keys are
grouped at the end of `en-US.properties`, under `#forge-gui-web`.

- In TypeScript, `t('lblWebSomething', arg…)` gives the text, and `tNodes` the same with elements inside a
  sentence. The build fails on a `lblWeb…` key that `en-US.properties` lacks.
- Patterns follow `java.text.MessageFormat`: `{0}` is an argument and an apostrophe is written twice (`can''t`).
  Write whole sentences with arguments rather than joining pieces.
- In Java, text the server sends for display uses `Localizer`.

### Tests

The tests guard what a change elsewhere in Forge could break without the module noticing: the wire format,
unanswered `IGuiGame` calls, a web seat answering the engine's prompts, netplay between host and guest, and the
server's tokens and paths.

- `mvn -Pweb -pl forge-gui-web -am test` runs the Java tests and Vitest; `npm test` runs only Vitest.
- `-Drun.stress.tests=true` adds whole games and events played over netplay. The snapshot build runs these.
- `e2e/` drives the page in a real browser against a built jar. It runs only locally:

      cd forge-gui-web/e2e
      npm ci
      npx playwright install chromium   # first time only
      npx playwright test
