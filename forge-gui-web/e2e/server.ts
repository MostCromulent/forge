// A Forge web server for one test: the built jar, serving the page from the source folder, on a port of its own,
// with a home folder of its own so the tests never touch the preferences of whoever runs them.

import { execFileSync, spawn, type ChildProcess } from 'node:child_process';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const module = resolve(here, '..');
const repo = resolve(module, '..');

// Tests run side by side in separate workers, each counting from a block of ports of its own. E2E_PORT_BASE moves
// every block, so two runs on one machine do not take the same ports
let nextPort = Number(process.env.E2E_PORT_BASE ?? 36900) + Number(process.env.TEST_WORKER_INDEX ?? 0) * 50;

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
  const port = onPort ?? nextPort++;
  const java: ChildProcess = spawn('java', [
    '-Djava.awt.headless=true',
    '-Dforge.web.noBrowser=true',
    `-Dforge.web.port=${port}`,
    // FORGE_PAGE_DIR serves another build of the page, so two builds can be compared against the same server
    `-Dforge.web.pageDir=${process.env.FORGE_PAGE_DIR ?? join(module, 'src/main/resources/web')}`,
    `-Duser.home=${home}`,
    '-jar', join(module, 'target/forge-gui-web.jar'),
  // On Windows Forge keeps its preferences under APPDATA rather than the home folder
  ], { cwd: repo, stdio: ['ignore', 'pipe', 'pipe'], env: { ...process.env, APPDATA: home, LOCALAPPDATA: home } });
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
      // A server that has already exited has nothing left to stop, and taskkill fails on a process that is gone
      if (java.exitCode === null && java.signalCode === null) {
        const exited = new Promise(done => java.once('exit', done));
        // On Windows `java` can be a launcher that starts the real JVM as a child, which must go too or it keeps the port
        if (process.platform === 'win32') {
          try {
            execFileSync('taskkill', ['/pid', String(java.pid), '/T', '/F'], { stdio: 'ignore' });
          } catch {
            // It exited between the check and the kill; the exit event still comes
          }
        } else {
          java.kill();
        }
        await exited;
      }
      rmSync(home, { recursive: true, force: true });
    },
  };
}
