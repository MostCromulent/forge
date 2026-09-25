// The pack dial: the draft's seats on a ring, you at the bottom, each pack a token on the track inside it. A seat's
// waiting packs queue on the side they arrive from, so a slow seat shows a line of packs behind it. When every pack
// passes a pack on, that pack's token slides along the track to its next seat before settling.

import { useEffect, useRef, useState } from 'preact/hooks';
import { dialTokens, nextFrom, seatAngle } from './dial';
import type { DraftState } from './protocol';

const SIZE = 264;
const CENTRE = SIZE / 2;
const SEAT_R = 104;
const TRACK_R = 70;
const SLIDE_MS = 650;
const CLOCK_R = SEAT_R + 22;
/** Under this much time the clock turns amber and thick. */
const CLOCK_LOW_MS = 15_000;

const at = (angle: number, r: number) => ({ x: CENTRE + r * Math.cos(angle), y: CENTRE + r * Math.sin(angle) });
const reducedMotion = () => typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches;

export function Dial({ state }: { state: DraftState }) {
  const n = state.seats.length;
  const direction = (state.direction < 0 ? -1 : 1) as 1 | -1;
  const depths = state.seats.map(s => s.packs);
  const next = nextFrom(depths, direction);
  const gap = (2 * Math.PI) / n;
  // How far the passed packs have slid towards their next seat, from 0 to 1; only a pass animates
  const [slide, setSlide] = useState(0);
  const shown = useRef<DraftState | null>(null);
  useEffect(() => {
    const before = shown.current;
    shown.current = state;
    if (state.moved.length === 0 || !before || reducedMotion()) {
      setSlide(0);
      return;
    }
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
  // The pick clock counts down from what the state said was left when it stamp
  const stamp = useRef(Date.now());
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    stamp.current = Date.now();
    setNow(stamp.current);
    if (!state.clockSeconds || !state.clockLeftMillis) return;
    const tick = setInterval(() => setNow(Date.now()), 250);
    return () => clearInterval(tick);
  }, [state]);
  const left = state.clockSeconds ? Math.max(0, state.clockLeftMillis - (now - stamp.current)) : 0;
  const around = 2 * Math.PI * CLOCK_R;
  // While sliding, a passed pack, the newest at the seat after the one that passed it, starts a gap back and eases into its place
  const back = slide > 0 ? direction * gap * (1 - ease(slide)) : 0;
  const arrived = new Set(state.moved.map(s => (((s + direction) % n) + n) % n));
  const layout = dialTokens(depths, direction).map(t =>
    arrived.has(t.seat) && t.slot === depths[t.seat] - 1 ? { ...t, angle: t.angle + back } : t);
  const feeder = next === null ? null : seatAngle(next, n);
  return (
    <div class="dial" style={{ width: `${SIZE}px`, height: `${SIZE}px` }} role="img"
      aria-label={`Pack ${state.pack}, pick ${state.pick}. ${next === null ? '' : `Next pack from ${state.seats[next].name}.`}`}>
      <svg viewBox={`0 0 ${SIZE} ${SIZE}`} width={SIZE} height={SIZE}>
        <circle class="dial-track" cx={CENTRE} cy={CENTRE} r={TRACK_R} />
        {left > 0 && (
          <circle class={left < CLOCK_LOW_MS ? 'dial-clock low' : 'dial-clock'} cx={CENTRE} cy={CENTRE} r={CLOCK_R}
            stroke-dasharray={`${(around * left) / (state.clockSeconds * 1000)} ${around}`}
            transform={`rotate(-90 ${CENTRE} ${CENTRE})`} />
        )}
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
            <span class={seat.ai ? 'dial-face ai' : 'dial-face'} title={i === 0 ? 'You' : seat.name}>{i === 0 ? 'You' : initials(seat.name)}</span>
            {/* The face already says who you are, and a numbered seat's number */}
            {i !== 0 && !/^Seat \d+$/.test(seat.name) && <span class="dial-name">{seat.name}</span>}
            {seat.held && <span class="dial-held" title="Away: the draft holds or picks for this seat">❚❚</span>}
            {waiting > 1 && <span class="dial-count" title={`${seat.packs} packs`}>{waiting}</span>}
          </div>
        );
      })}
      <div class="dial-centre"><b>Pack {state.pack}</b><span>pick {state.pick}</span>
        {left > 0 && <span class={left < CLOCK_LOW_MS ? 'dial-time low' : 'dial-time'}>{Math.ceil(left / 1000)} s</span>}
      </div>
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
