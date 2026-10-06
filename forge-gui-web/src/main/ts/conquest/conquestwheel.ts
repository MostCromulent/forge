// The Chaos Wheel as a dial, drawn and turned by hand, and the server decides where it stops

import { skinIconUrl } from '../images';
import { reducedMotion } from '../dom';

/** The wheel's eight spots, clockwise from the needle, as ConquestEvent.ChaosWheelOutcome orders them. */
export const WHEEL_SPOTS = ['CHAOS', 'BOOSTER', 'SHARDS', 'DOUBLE_BOOSTER', 'PLANESWALK', 'BOOSTER', 'DOUBLE_SHARDS', 'BOOSTER'] as const;
export type WheelOutcome = typeof WHEEL_SPOTS[number];

const TONES: Record<WheelOutcome, string> = {
  CHAOS: '#2a1420', BOOSTER: '#121827', SHARDS: '#10232a', DOUBLE_BOOSTER: '#1c2744', PLANESWALK: '#232833', DOUBLE_SHARDS: '#163843',
};
const LINES = {
  chaos: '<svg class="ln" viewBox="0 0 40 40"><path d="M20 20c0-9 12-9 12 0s-12 9-12 18"/><path d="M20 20c0 9-12 9-12 0s12-9 12-18"/></svg>',
  pack: '<svg class="ln" viewBox="0 0 24 34"><path d="M4 2h16v30H4z"/><path d="M4 6.5h16M4 27.5h16"/><path d="M12 12.5l4 4.5-4 4.5-4-4.5z"/></svg>',
  shard: '<svg class="ln" viewBox="0 0 24 34"><path d="M12 2l7 10-7 20-7-20z"/><path d="M5 12h14M12 2l-3 10 3 20 3-20z"/></svg>',
};
const emblem = `<img alt="" src="${skinIconUrl('IMG_PW_BADGE_COMMON')}">`;
const ICONS: Record<WheelOutcome, string> = {
  CHAOS: LINES.chaos, BOOSTER: LINES.pack, SHARDS: LINES.shard, DOUBLE_BOOSTER: LINES.pack + LINES.pack, PLANESWALK: emblem, DOUBLE_SHARDS: LINES.shard + LINES.shard,
};
/** The chaos mark, which the dial's hub and the chaos pack both carry. */
export const CHAOS_MARK = LINES.chaos;

const SPIN_MS = 5600;

/** A spot that holds the outcome. Three hold a booster, and which of them it stops on is nobody's business. */
export function spotFor(outcome: string): number {
  const spots = WHEEL_SPOTS.map((o, i) => (o === outcome ? i : -1)).filter(i => i >= 0);
  return spots.length ? spots[Math.floor(Math.random() * spots.length)] : 0;
}

export interface Wheel {
  /** Turns the dial until the spot is under the needle. */
  spin(spot: number): Promise<void>;
  /** Shows the dial already stopped on the spot. */
  show(spot: number): void;
}

/** Draws the dial into host. labels names each outcome, in the player's language. */
export function mountWheel(host: HTMLElement, labels: Record<WheelOutcome, string>): Wheel {
  const stops = WHEEL_SPOTS.map((o, i) => `${TONES[o]} ${i * 45}deg ${(i + 1) * 45}deg`).join(', ');
  host.innerHTML = `<div class="cq-dl">
    <div class="cq-dl-glow"></div><div class="cq-dl-rim"></div>
    <div class="cq-dl-spin"><div class="cq-dl-disc" style="background: conic-gradient(from -22.5deg, ${stops})"></div>${
      WHEEL_SPOTS.map((o, i) => `<div class="cq-dl-wedge${i > 2 && i < 6 ? ' low' : ''}" style="transform: rotate(${45 * i}deg)"><div class="cq-dl-ic">${ICONS[o]}</div><span></span></div>`).join('')
    }</div>
    <div class="cq-dl-flare"></div><div class="cq-dl-hub">${LINES.chaos}</div><div class="cq-dl-needle"></div>
  </div>`;
  const root = host.firstElementChild as HTMLElement;
  const spin = root.querySelector<HTMLElement>('.cq-dl-spin')!;
  root.querySelectorAll<HTMLElement>('.cq-dl-wedge span').forEach((span, i) => { span.textContent = labels[WHEEL_SPOTS[i]]; });
  const show = (spot: number) => {
    spin.style.transform = `rotate(${-45 * spot}deg)`;
    root.classList.add('done');
  };
  return {
    show,
    async spin(spot) {
      if (reducedMotion()) {
        show(spot);
        return;
      }
      root.classList.remove('done');
      await spin.animate([{ transform: 'rotate(0deg)' }, { transform: `rotate(${-45 * spot - 360 * 5}deg)` }],
        { duration: SPIN_MS, easing: 'cubic-bezier(.16,.6,.1,1)', fill: 'forwards' }).finished;
      root.classList.add('done');
    },
  };
}
