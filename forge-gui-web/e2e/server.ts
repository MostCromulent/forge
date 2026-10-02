// A Forge web server for one test, with a home folder of its own so tests never touch the preferences of whoever runs them

import { execFileSync, spawn, type ChildProcess } from 'node:child_process';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { createServer, type AddressInfo } from 'node:net';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const module = resolve(here, '..');
const repo = resolve(module, '..');

export interface Server {
  /** The page's address, with the token that lets a browser in. */
  url: string;
  port: number;
  stop(): Promise<void>;
}

/** What the server's home folder holds before it starts. */
export interface Seed {
  /** Forge preferences, as the keys forge.preferences takes. */
  prefs?: Record<string, string>;
  /** Constructed decks saved as the player's own, by name, in .dck text. */
  decks?: Record<string, string>;
  /** Any other files, by their path under Forge's user folder, with their text. */
  files?: Record<string, string>;
}

/** The player's own deck every probe server starts with: sixty basics, legal in Constructed. */
export const PROBE_DECK = 'Probe deck';

/** Card-based deck generation is off, because loading its data is over half of starting up and a probe plays real decks. */
export const PROBE_SEED: Seed = {
  prefs: { DEV_MODE_ENABLED: 'true', DECKGEN_CARDBASED: 'false' },
  decks: { [PROBE_DECK]: `[metadata]\nName=${PROBE_DECK}\n[Main]\n30 Mountain\n30 Forest\n` },
};

/** A port nothing is listening on, from the system, so runs side by side, from any session, never take the same one. */
function freePort(): Promise<number> {
  return new Promise((done, fail) => {
    const probe = createServer();
    probe.once('error', fail);
    probe.listen(0, '127.0.0.1', () => {
      const { port } = probe.address() as AddressInfo;
      probe.close(() => done(port));
    });
  });
}

/** Ends the process and, on Windows, the JVM a `java` launcher starts under it, which would otherwise keep the port. */
function killTree(pid: number): void {
  try {
    if (process.platform === 'win32') execFileSync('taskkill', ['/pid', String(pid), '/T', '/F'], { stdio: 'ignore' });
    else process.kill(pid);
  } catch {
    // Already gone
  }
}

/** Starts a server; on a given port, to stand in for the same server restarted, which a browser treats as the same site. */
export async function startServer(onPort?: number, seed: Seed = {}): Promise<Server> {
  const home = mkdtempSync(join(tmpdir(), 'forge-e2e-'));
  // Forge's user folder: under APPDATA on Windows (set to home below), a dot folder in the home folder elsewhere
  const user = process.platform === 'win32' ? join(home, 'Forge') : join(home, '.forge');
  if (seed.prefs) {
    mkdirSync(join(user, 'preferences'), { recursive: true });
    writeFileSync(join(user, 'preferences', 'forge.preferences'),
      Object.entries(seed.prefs).map(([k, v]) => `${k}=${v}\n`).join(''));
  }
  for (const [name, text] of Object.entries(seed.decks ?? {})) {
    mkdirSync(join(user, 'decks', 'constructed'), { recursive: true });
    writeFileSync(join(user, 'decks', 'constructed', `${name}.dck`), text);
  }
  for (const [path, text] of Object.entries(seed.files ?? {})) {
    mkdirSync(dirname(join(user, path)), { recursive: true });
    writeFileSync(join(user, path), text);
  }
  const port = onPort ?? await freePort();
  const java: ChildProcess = spawn('java', [
    // FORGE_E2E_JVM adds JVM options, such as a flight recording to profile the server under a test
    ...(process.env.FORGE_E2E_JVM ?? '').split(' ').filter(Boolean),
    '-Djava.awt.headless=true',
    '-Dforge.web.noBrowser=true',
    `-Dforge.web.port=${port}`,
    // FORGE_PAGE_DIR serves another build of the page, so two builds can be compared against the same server
    `-Dforge.web.pageDir=${process.env.FORGE_PAGE_DIR ?? join(module, 'src/main/resources/web')}`,
    `-Duser.home=${home}`,
    '-jar', join(module, 'target/forge-gui-web.jar'),
  // On Windows Forge keeps its preferences under APPDATA rather than the home folder
  ], { cwd: repo, stdio: ['ignore', 'pipe', 'pipe'], env: { ...process.env, APPDATA: home, LOCALAPPDATA: home } });
  const running = () => java.exitCode === null && java.signalCode === null;
  // A run stopped part way (a timeout, Ctrl+C) never reaches stop(), and the server would keep its port for good
  const orphaned = () => { if (running()) killTree(java.pid!); };
  process.once('exit', orphaned);
  let log = '';
  const url = await new Promise<string>((resolveUrl, reject) => {
    const timer = setTimeout(() => reject(new Error(`Forge did not start in time:\n${log.slice(-4000)}`)), 180_000);
    const read = (chunk: Buffer) => {
      log += chunk;
      const found = /Forge web UI: (\S+)/.exec(log);
      if (found) {
        clearTimeout(timer);
        resolveUrl(found[1]);
      }
    };
    java.stdout?.on('data', read);
    java.stderr?.on('data', read);
    java.on('exit', code => reject(new Error(`Forge exited with ${code} before it started:\n${log.slice(-4000)}`)));
  });
  return {
    url,
    port,
    async stop() {
      process.off('exit', orphaned);
      if (running()) {
        const exited = new Promise(done => java.once('exit', done));
        killTree(java.pid!);
        await exited;
      }
      rmSync(home, { recursive: true, force: true });
    },
  };
}
