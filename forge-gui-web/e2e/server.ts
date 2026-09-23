// A Forge web server for one test: the built jar, serving the page from the source folder, on a port of its own,
// with a home folder of its own so the tests never touch the preferences of whoever runs them.

import { spawn, type ChildProcess } from 'node:child_process';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const module = resolve(here, '..');
const repo = resolve(module, '..');

let nextPort = 36900;

export interface Server {
  /** The page's address, with the token that lets a browser in. */
  url: string;
  stop(): Promise<void>;
}

export async function startServer(): Promise<Server> {
  const home = mkdtempSync(join(tmpdir(), 'forge-e2e-'));
  const port = nextPort++;
  const java: ChildProcess = spawn('java', [
    '-Djava.awt.headless=true',
    '-Dforge.web.noBrowser=true',
    `-Dforge.web.port=${port}`,
    `-Dforge.web.pageDir=${join(module, 'src/main/resources/web')}`,
    `-Duser.home=${home}`,
    '-jar', join(module, 'target/forge-gui-web.jar'),
  ], { cwd: repo, stdio: ['ignore', 'pipe', 'pipe'] });
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
    async stop() {
      const exited = new Promise(done => java.once('exit', done));
      java.kill();
      await exited;
      rmSync(home, { recursive: true, force: true });
    },
  };
}
