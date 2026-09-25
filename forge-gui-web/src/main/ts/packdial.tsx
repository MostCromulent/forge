// The pack dial: the draft's seats on a ring, you at the bottom, each pack a token on the track inside it. A seat's
// waiting packs queue on the side they arrive from, so a slow seat shows a line of packs behind it. When every pack
// moves on one seat, the tokens slide along the track to their next seat before settling.

import { useEffect, useRef, useState } from 'preact/hooks';
import { dialTokens, nextFrom, seatAngle } from './dial';
import type { DraftState } from './protocol';

const SIZE = 264;
const CENTRE = SIZE / 2;
const SEAT_R = 104;
const TRACK_R = 70;
const SLIDE_MS = 650;

const at = (angle: number, r: number) => ({ x: CENTRE + r * Math.cos(angle), y: CENTRE + r * Math.sin(angle) });
const reducedMotion = () => typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches;

export function Dial({ state }: { state: DraftState }) {
  const n = state.seats.length;
  const direction = (state.direction < 0 ? -1 : 1) as 1 | -1;
  const depths = state.seats.map(s => s.packs);
  const next = nextFrom(depths, direction);
  const gap = (2 * Math.PI) / n;
  // How far the tokens have slid towards their next seat, from 0 to 1; only a pass animates
  const [slide, setSlide] = useState(0);
  const shown = useRef<DraftState | null>(null);
  useEffect(() => {
    const before = shown.current;
    shown.current = state;
    if (!state.passed || !before || reducedMotion()) return;
    let frame = 0;
    const start = performance.now();
    const step = (now: number) => {
      const t = Math.min(1, (now - start) / SLIDE_MS);
      setSlide(t < 1 ? t : 0);
      if (t < 1) frame = requestAnimationFrame(step);
    };
    frame = requestAnimationFrame(step);
    return () => cancelAnimationFrame(frame);
  }, [state]);
  // While sliding, each token starts a gap back, at the seat that passed it, and eases into its place
  const back = slide > 0 ? direction * gap * (1 - ease(slide)) : 0;
  const layout = dialTokens(depths, direction).map(t => ({ ...t, angle: t.angle + back }));
  const feeder = next === null ? null : seatAngle(next, n);
  return (
    <div class="dial" style={{ width: `${SIZE}px`, height: `${SIZE}px` }} role="img"
      aria-label={`Pack ${state.pack}, pick ${state.pick}. ${next === null ? '' : `Next pack from ${state.seats[next].name}.`}`}>
      <svg viewBox={`0 0 ${SIZE} ${SIZE}`} width={SIZE} height={SIZE}>
        <circle class="dial-track" cx={CENTRE} cy={CENTRE} r={TRACK_R} />
        {feeder !== null && <path class="dial-feed" d={arc(feeder, seatAngle(0, n), TRACK_R + 13, direction)} />}
        {state.seats.map((_, i) => {
          const mid = seatAngle(i, n) - (direction * gap) / 2;
          const p = at(mid, TRACK_R + 13);
          const heading = (Math.atan2(-direction * Math.cos(mid), direction * Math.sin(mid)) * 180) / Math.PI;
          return <path key={i} class={i === next ? 'dial-chevron hot' : 'dial-chevron'} d="M-4 -4 L3 0 L-4 4"
            transform={`translate(${p.x.toFixed(1)} ${p.y.toFixed(1)}) rotate(${heading.toFixed(0)})`} />;
        })}
      </svg>
      {layout.map(t => {
        const p = at(t.angle, TRACK_R);
        const turn = (t.angle * 180) / Math.PI + 90;
        return <span key={`${t.seat}-${t.slot}`} class={t.inHand ? (t.seat === 0 ? 'dial-pack hand me' : 'dial-pack hand') : 'dial-pack'}
          style={{ transform: `translate(${p.x.toFixed(1)}px, ${p.y.toFixed(1)}px) rotate(${turn.toFixed(0)}deg)` }} />;
      })}
      {state.seats.map((seat, i) => {
        const p = at(seatAngle(i, n), SEAT_R);
        const cls = ['dial-seat', i === 0 ? 'you' : '', i === next ? 'next' : '', seat.held ? 'held' : ''].join(' ');
        const waiting = seat.packs - 1;
        return (
          <div key={i} class={cls} style={{ left: `${p.x}px`, top: `${p.y}px` }}>
            <span class={seat.ai ? 'dial-face ai' : 'dial-face'}>{initials(i === 0 ? 'You' : seat.name)}</span>
            <span class="dial-name">{i === 0 ? 'You' : seat.name}</span>
            {waiting > 1 && <span class="dial-count" title={`${seat.packs} packs`}>{waiting}</span>}
          </div>
        );
      })}
      <div class="dial-centre"><b>Pack {state.pack}</b><span>pick {state.pick}</span></div>
    </div>
  );
}

function ease(t: number): number {
  return t < 0.5 ? 2 * t * t : 1 - ((-2 * t + 2) ** 2) / 2;
}

/** The arc of the track from one seat's angle to another's, the way packs travel. */
function arc(from: number, to: number, r: number, direction: 1 | -1): string {
  const a = at(from, r);
  const b = at(to, r);
  return `M${a.x.toFixed(1)} ${a.y.toFixed(1)} A ${r} ${r} 0 0 ${direction > 0 ? 0 : 1} ${b.x.toFixed(1)} ${b.y.toFixed(1)}`;
}

function initials(name: string): string {
  const words = name.split(/\s+/).filter(Boolean);
  return words.length > 1 && /\d/.test(words[1]) ? `${words[0][0]}${words[1]}` : name.slice(0, 2);
}
