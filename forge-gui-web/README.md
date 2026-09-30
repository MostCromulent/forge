# Forge Web GUI

Play Forge in a web browser. One computer runs Forge as a small server; everyone, including the host, plays in a
browser. Friends install nothing: you send them a link.

The interface aims to be modern and easy to pick up, and leaves out much of desktop Forge's detailed customisation.
It is built for a computer with a mouse and keyboard; phones and tablets aren't supported yet.

<p>
  <img src="docs/table.webp" width="49%" alt="Setting up a game against two AI opponents">
  <img src="docs/target.webp" width="49%" alt="A spell's targets while it is paid for">
</p>
<p>
  <img src="docs/four.webp" width="49%" alt="A four-player game, attacking two opponents">
  <img src="docs/victory.webp" width="49%" alt="The end of a game">
</p>

## For players

The person running the server is the **host**; anyone who joins by link is a **guest**.

### What you can play

- **Constructed**, Commander, Brawl, Oathbreaker, Tiny Leaders, Momir Basic and MoJhoSto, for up to four players.
- The variants **Vanguard**, **Planechase**, **Archenemy** and **Archenemy Rumble**.
- **Draft** and **Sealed**, for up to eight, against the computer or with friends. The computer fills empty seats.

Not yet: Quest, Adventure, Planar Conquest, puzzles, constructed gauntlets and Winston draft.

### Starting it

You need Java 17 or newer and Maven. From the top folder of the Forge repository:

    mvn -Pweb -pl forge-gui-web -am install -DskipTests
    java -jar forge-gui-web/target/forge-gui-web.jar

This opens the game in your browser, and the **server window**, which starts and stops the server and shows the
links people join by. Closing it quits Forge, as does the last browser closing, unless you untick **Quit when the
last player leaves**.

<img src="docs/server.webp" width="49%" alt="The server window">

### Playing with friends

Click **Invite** at the top of the table, or an address in the server window, to copy a link.

- A local address (often `192.168.…`) works on your home network.
- The internet address works for everyone else if your router lets them in. Tick **Open the port on the router**
  in the server window; if that fails, turn on UPnP on your router or forward the port by hand.

Anyone with a guest link can join, and the links aren't encrypted, so only send them to people you're playing with.
Never share the host's own link: it controls the table and the server. Links change each time Forge starts.

Only the host sets up the match, in the bar along the top of the table. A player who reloads or drops out goes
straight back to their seat.

### Decks and settings

**Decks** builds, edits and imports decks: a pasted list, a deck file, or a Moxfield, Archidekt, TappedOut or
MTGGoldfish link.

- The host's decks and game settings are desktop Forge's own, so a change in one shows in the other.
- A guest's decks are kept in their browser, for the exact link they joined by, and a private window keeps
  nothing. **Copy as text** in the deck editor keeps a copy elsewhere.
- Display settings, keys and custom CSS are kept in each browser, the host's too.

The cog opens **Options**, and the **⋯** button in a game offers a draw, auto-pass stops and conceding.

---

## For developers

The Java side (`src/main/java`) runs the engine and serves the page. The browser side (`src/main/ts`) is TypeScript
and Preact. The build bundles it into `src/main/resources/web/js/`, which git ignores, so a fresh checkout has no
page to serve until it is built.

### Building

`-Pweb` is needed because Forge's default build leaves this module out. Maven fetches its own Node for the browser
build. The jar finds Forge's `res` folder by searching up from where it runs; if it can't, pass
`-Dforge.assets.dir=<folder containing res>`. It needs `target/lib/` beside it.

| Option | Effect |
|---|---|
| `-Dforge.web.port=<n>` | Port to serve on. The default, 36743, is desktop Forge's network port too. |
| `-Dforge.web.noBrowser=true` | Don't open the game in a browser. |
| `-Dforge.web.noConsole=true` | Don't open the server window; the host's link is printed instead. |
| `-Dforge.web.pageDir=forge-gui-web/src/main/resources/web` | Serve the page from source, so a change needs only a reload. |

With `pageDir` set, run `npm run watch` in this folder to rebuild the TypeScript on save. Use the Node Maven
installed (`node/`) or any Node 22.

### How it fits together

One Java process runs Forge and serves the page on one port. Each browser is a player; the page draws what the server
sends and sends back what the player does, over one WebSocket.

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

**Starting up.** `WebMain` loads Forge through `WebGuiBase` (Forge's `IGuiBase` for the web: where files are, which
thread is the interface thread), opens `ServerConsole` (the server window) and starts `WebServer`. `WebServer` is
Netty: it serves the page, card images and sounds, `/text` (below) and the socket, and turns away any request without
one of the two tokens in the links. The host's token can take the host's seat; the guests' cannot.

**Every seat is a netplay client.** The host's `LocalGame` starts Forge's own netplay server (`FServerManager`) on
loopback and joins it, and each guest's session joins the same server. So the web module reuses desktop netplay
whole: the lobby is a `ServerGameLobby`, a draft is run by `BoosterDraftHost`, the game's state arrives as netplay's
delta packets, and the game's questions arrive as `IGuiGame` calls. Only the web port is reachable from outside.

**A browser's session.** `WebSessions` gives each browser (known by an id it keeps in `localStorage`) a
`WebSession`, which knows which screen it is on (start page, table, deck editor, draft, match) and routes its
messages: to `Lobby` for match setup, `DeckSession` for decks, `OnlineDraft`/`OfflineDraft` for drafts, and
`WebGuiGame` in a match.

**A match.** `WebGuiGame` is the web's `IGuiGame`, the interface desktop draws a match through. It keeps the game's
objects in a `BrowserModel` and sends the browser what changed as JSON (`JsonCodec` writes each `TrackableProperty`),
along with the prompt (`PromptState`), the zones on show, the log and sounds. A call that needs an answer (choose
cards, order them, pick a number) becomes a request the browser answers with a `reply`; `PendingRequests` holds the
game's thread until it arrives. A browser that reloads is sent the whole table and every open question again, so it
carries on where it was.

**The page.** `app.ts` fetches the page's text, then loads `main.ts`, the controller: the only module that talks to
the server. Messages update `model.ts`; once a frame, the page is drawn from the model and `ui.ts` (what the player has
opened or collapsed, which the server never sees). The board is drawn by hand (`board.ts` and the modules it places,
with `motion.ts` animating cards between zones) because it is laid out by measuring; everything else is Preact
(`screens.tsx` and the screens it picks). Nothing that draws sends anything: it calls `actions.ts`, which `main.ts`
turns into messages.

### The protocol

Messages are Java records in `ToBrowser` and `FromBrowser`. Each build writes `src/main/ts/protocol.gen.ts` from them
and from Forge's `TrackableProperty`, then type-checks the TypeScript against it, so a changed record fails the build
where the browser uses it. A field may be null only if marked `@Nullable`.

- **A new question from the game** (an `IGuiGame` method that returns something): implement it in `WebGuiGame` with
  `ask(request, check)`, add the request as a `@Request` record in `ToBrowser.REQUESTS`, and draw it in `dialogs.tsx`,
  answering with `actions.answer`. `WebGuiGameTest` fails while any such method is left to Forge's default.
- **A new message**: a `@Message` record in `ToBrowser.MESSAGES`, handled in `main.ts`; or a `@Command` record in
  `FromBrowser.COMMANDS`, handled where `WebSession` routes it.
- **A new game property** needs nothing: it travels with the rest of `TrackableProperty` and appears in
  `protocol.gen.ts`.

### Text and languages

Everything a player reads comes from Forge's language files (`forge-gui/res/languages`), in the language Forge is set
to, so the page and the game's own prompts read in one language; a key a translation lacks falls back to English. The
page's keys are grouped at the end of `en-US.properties`, under `#forge-gui-web`.

- In TypeScript, `t('lblWebSomething', arg…)` gives the text, and `tNodes` the same with elements inside a sentence
  (`text.ts`). Before anything is drawn, the page fetches `/text` (`PageText`): the patterns of the keys it uses.
- The build finds those keys by scanning the TypeScript and `index.html` for quoted keys (`PageTextKeys`), writes them
  to `text.gen.ts` so `t()` accepts only real keys, and fails on a `lblWeb…` key that `en-US.properties` lacks.
- Patterns follow `java.text.MessageFormat`, as all of Forge's do: `{0}` is an argument and an apostrophe is written
  twice (`can''t`). Write whole sentences with arguments rather than joining pieces, and a key for one and a key for
  many where a count changes the words.
- In Java, text the server sends for display uses `Localizer`, as the rest of Forge does.

### Keeping up with Forge

The module relies on changes to Forge's shared modules (`forge-game`, `forge-gui`, `forge-core`) and the root
`pom.xml`, so merging upstream Forge can conflict there. After a merge, build the module: the type check catches a game
property renamed or retyped upstream. CI doesn't build with `-Pweb`, so run the module's tests and the e2e suite
locally before pushing.

### Tests

The tests guard what a change elsewhere in Forge could break without the module noticing: the wire format of the game's
views, `IGuiGame` calls the web seat does not answer, a web seat answering the engine's prompts, netplay between host
and guest, the draft and sealed products, and the server's tokens and paths. The page's own behaviour is left to the
e2e suite and to playing it.

- `mvn -Pweb -pl forge-gui-web -am test` runs the Java tests and the Vitest tests; `npm test` runs only Vitest.
- The slow ones need `-Drun.stress.tests=true`, the switch Forge's other network tests use: whole games and events
  played over netplay (`LoopbackGameTest`, `OnlineEventTest`, `GuestSeatTest`). Run them before pushing a change to
  sessions or netplay.
- `e2e/` drives the page in a real browser against a real server. Build the jar, then:

      cd forge-gui-web/e2e
      npm ci
      npx playwright install chromium   # first time only
      npx playwright test

---

🤖 Generated with [Claude Code](https://claude.com/claude-code)
