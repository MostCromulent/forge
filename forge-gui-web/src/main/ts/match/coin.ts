// The coin tossed for a match's first game, alone over the board, with mobile's art and its motion (Coin3D.pose)

import { byId, make, q, reducedMotion } from '../dom';
import { leave } from '../closing';

/** How long the coin is in the air, and how long it then rests before it is put away. */
const FLIGHT_S = 1.5;
const REST_S = 1.2;
/** Discs between the two faces, a pixel apart, which make the coin's edge. */
const EDGE = Array.from({ length: 13 }, (_, k) => `<i class="coin-edge" style="transform: translateZ(${k - 6}px)"></i>`).join('');

let inAir = false;

/** Whether the coin is up, so what follows the toss waits for it. */
export const coinInAir = (): boolean => inAir;

/** Fetches the coin's two faces ahead of the toss, so it does not start blank. */
export function readyCoin(): void {
  for (const side of ['heads', 'tails']) new Image().src = `coin?side=${side}`;
}

/** Tosses the coin, which lands heads for a toss the player won, and calls `then` as it is put away. A press puts it away early. */
export function tossCoin(heads: boolean, caption: string, then: () => void): void {
  if (reducedMotion()) return;
  inAir = true;
  const root = make('div', '');
  root.id = 'coin-toss';
  root.setAttribute('role', 'status');
  root.innerHTML = `<div class="coin-scene"><span class="coin-shadow"></span><div class="coin"><i class="coin-side tails"></i>${EDGE}<i class="coin-side"></i></div></div><p></p>`;
  q(root, 'p').textContent = caption;
  byId('match').append(root);
  const coin = q(root, '.coin');
  const shadow = q(root, '.coin-shadow');
  const turns = 3 + (heads ? 0 : .5);
  const start = performance.now();
  const end = () => {
    if (!inAir) return;
    inAir = false;
    leave(root);
    then();
  };
  root.onclick = end;
  const frame = (now: number) => {
    if (!inAir) return;
    const s = (now - start) / 1000;
    const t = Math.min(1, s / FLIGHT_S);
    const rested = Math.max(0, s - FLIGHT_S);
    const spun = 1 - (1 - t) ** 3;
    // A precession that fades as the coin comes down, the lean it rests at, and a rocking once it has landed
    const fade = 1 - t;
    const lean = Math.min(1, Math.max(0, (t - .55) / .45));
    const leaned = lean * lean * (3 - 2 * lean);
    const rock = rested > 0 ? Math.exp(-5 * rested) : 0;
    // Thrown towards the viewer at the top of its arc
    const lift = 4 * t * (1 - t) * 236;
    coin.style.transform = `translateZ(${lift}px) rotateX(${Math.sin(rested * 20) * 13 * rock}deg) rotateY(${Math.sin(rested * 17 + 1) * 6 * rock}deg)`
      + ` rotateX(${-20 * leaned}deg) rotateY(${14 * leaned}deg)`
      + ` rotateZ(${Math.sin(t * Math.PI * 3) * 9 * fade}deg) rotateY(${Math.cos(t * Math.PI * 3) * 7 * fade}deg)`
      + ` rotateX(${spun * turns * 360}deg)`;
    shadow.style.scale = String(1 - lift / 520);
    shadow.style.opacity = String(.5 - lift / 700);
    root.classList.toggle('landed', t === 1);
    if (rested >= REST_S) end();
    else requestAnimationFrame(frame);
  };
  requestAnimationFrame(frame);
}
