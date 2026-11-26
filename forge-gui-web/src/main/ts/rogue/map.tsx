// The run's map: the trail of nodes climbing to the boss, and the panel of the node chosen. On a phone the panel is a bar under the trail

import { useLayoutEffect, useRef, useState } from 'preact/hooks';
import { Icon } from '../icons';
import { artUrl } from '../images';
import { isPortrait } from '../form';
import { playSound } from '../audio';
import { reducedMotion } from '../dom';
import { changeUi, ui } from '../ui';
import { t } from '../text';
import { ROGUE_ICONS } from './icons';
import { fit, layout, type Link } from './maplayout';
import { POW3_OUT } from './motion';
import { isScene } from './questions';
import { MapSide, SheetBar } from './mapside';
import type { Actions } from '../actions';
import type { Model } from '../model';
import type { MapNode, Part, RogueMap } from '../protocol';

/** The attributes of a term the tooltip explains. */
export const explains = (term?: Part) => (term ? { 'data-term': term.term, 'data-tip': term.tip, tabIndex: 0 } : {});

/** A side node's icon, by its NodeType name. */
export function sideIcon(kind: string): 'sanctum' | 'bazaar' | 'event' | 'chest' {
  switch (kind) {
    case 'SANCTUM': return 'sanctum';
    case 'BAZAAR': return 'bazaar';
    case 'EVENT': return 'event';
    default: return 'chest';
  }
}

/** Whether the node is entered from here: the current node, or another the player may choose in its row. */
export const enterable = (n: MapNode) => n.state === 'open' || n.state === 'selected';

/** The node whose panel is shown: one the player looks at, while it can still be read, or else the current node. */
export function shownNode(map: RogueMap): MapNode {
  const nodes = map.rows.flat();
  const looked = nodes.find(n => n.index === ui.rogueViewing && (enterable(n) || readable(n)));
  return looked ?? nodes.find(n => n.index === map.selected) ?? nodes[0];
}

/** A face-up plane can be chosen to read it, ahead or behind, and any node still ahead on the path to see what it is; neither can be entered. */
const readable = (n: MapNode) => (n.kind === 'PLANEBOUND' && !n.faceDown) || n.state === 'far';

/** While a question is folded to read the map, the map can only be looked at, as on mobile. */
export const mapLocked = (model: Model) => isScene(model.rogueQuestion) && ui.rogueFolded === model.rogueQuestion!.id;

/** Choosing an open node makes it the current node. Choosing any other that can be read only shows its panel. */
export function choose(n: MapNode, select: (node: number) => void): void {
  changeUi(u => { u.rogueViewing = n.state === 'open' || n.state === 'selected' ? null : n.index; });
  if (n.state === 'open') select(n.index);
}

const edge = (from: MapNode, to: MapNode): string => {
  if (from.state === 'walked' && to.state === 'walked') return 'walked';
  if (from.state === 'walked' && (to.state === 'open' || to.state === 'selected')) return 'can';
  if (from.state === 'selected') return 'next';
  return [from.state, to.state].some(s => s === 'dead' || s === 'past') ? 'dead' : '';
};

function Marks({ n }: { n: MapNode }) {
  return <>
    {n.elite && <span class="rg-mark star"><Icon d={ROGUE_ICONS.star} cls="rg-ic" /></span>}
    {n.boss && <span class="rg-mark star"><Icon d={ROGUE_ICONS.crown} cls="rg-ic" /></span>}
    {n.wrathful > 0 && <span class="rg-mark"><Icon d={ROGUE_ICONS.flame} cls="rg-ic" />{n.wrathful > 1 && n.wrathful}</span>}
    {n.cursed > 0 && <span class="rg-mark"><Icon d={ROGUE_ICONS.curse} cls="rg-ic" />{n.cursed > 1 && n.cursed}</span>}
  </>;
}

function Node({ n, box, phone, pick }: { n: MapNode; box: { x: number; y: number; w: number; h: number }; phone: boolean; pick: () => void }) {
  const place = { left: `${box.x}px`, top: `${box.y}px`, width: `${box.w}px`, height: `${box.h}px` };
  const state = n.state === 'selected' ? 'sel' : n.state;
  const off = !enterable(n) && !readable(n);
  const ok = n.state === 'walked' && <span class="ok"><Icon d={ROGUE_ICONS.check} cls="rg-ic" /></span>;
  const life = <span><Icon d={ROGUE_ICONS.heart} cls="rg-ic" />{` ${n.life}`}</span>;
  if (n.kind !== 'PLANEBOUND') {
    return <button class={`rg-n side ${state}`} style={place} data-node={n.index} data-kind={n.kind} aria-label={n.name} disabled={off} onClick={pick}><Icon d={ROGUE_ICONS[sideIcon(n.kind)]} cls="rg-ic" />{ok}</button>;
  }
  if (n.faceDown) {
    return (
      <button class={`rg-n hid ${n.boss ? 'boss kit-boss' : ''} ${state}`} style={place} data-node={n.index} disabled={off} onClick={pick}>
        <span class="q">?</span><span class="mk"><Marks n={n} /></span><span class="nm">{life}</span>
      </button>
    );
  }
  return (
    <button class={`rg-n ${n.boss ? 'kit-boss ' : ''}${state}`} style={{ ...place, backgroundImage: n.planeImage ? `url("${artUrl(n.planeImage)}")` : undefined }} data-node={n.index}
      data-image={n.planeImage} data-sideways={n.planeImage ? '' : undefined} disabled={off} onClick={pick}>
      <i class="opp" style={n.art ? { backgroundImage: `url("${artUrl(n.art)}")` } : undefined} />
      <span class="mk"><Marks n={n} /></span>
      {ok}
      <span class="nm"><b>{phone ? n.name!.split(',')[0] : n.name}</b>{life}</span>
      <i class="kit-back" aria-hidden="true">?</i>
    </button>
  );
}

/** Adventure's plates are drawn larger than the layout plans them, and the boss's a fifth larger again, as mobile draws them. */
const PLATE = [164, 118];
const BOSS = 1.2;

/**
 * The wide map fitted to its stage's width, as mobile fits it. The layout is scaled by k, never above its own size: plates and paths scale with it,
 * and side nodes keep their size, so their sprites stay at a whole multiple. The width is measured from the plates, with room for their marks.
 */
function fitted(map: RogueMap, plan: ReturnType<typeof layout>, k: number) {
  const side = (n: MapNode) => n.kind !== 'PLANEBOUND';
  const size = (n: MapNode, box: { w: number; h: number }): [number, number] =>
    (side(n) ? [box.w, box.h] : [PLATE[0] * (n.boss ? BOSS : 1), PLATE[1] * (n.boss ? BOSS : 1)]);
  const nodes = map.rows.flatMap((row, r) => row.map((n, i) => ({ n, box: plan.boxes[r][i] })));
  const centre = (box: { x: number; w: number }) => box.x + box.w / 2;
  const x0 = Math.min(0, ...nodes.map(({ n, box }) => centre(box) - size(n, box)[0] / 2 - (side(n) ? 0 : 8)));
  const span = Math.max(plan.width, ...nodes.map(({ n, box }) => centre(box) + size(n, box)[0] / 2 + (side(n) ? 0 : 8))) - x0;
  const boxes = plan.boxes.map((row, r) => row.map((box, i) => {
    const n = map.rows[r][i];
    const [w, h] = side(n) ? size(n, box) : size(n, box).map(d => Math.round(d * k));
    return { x: Math.round((centre(box) - x0) * k - w / 2), y: Math.round((box.y + box.h / 2) * k - h / 2), w, h };
  }));
  return { span, boxes, width: Math.round(span * k), height: Math.round(plan.height * k), left: Math.round(-x0 * k), svgWidth: Math.round(plan.width * k) };
}

/** Which planes the map last showed face down in this run, or null before its first look, which shows the planes as they are. */
let faces: Map<number, boolean> | null = null;
export const forgetFaces = () => { faces = null; };

/** Two taps on one node this close together, in ms, are a double tap, as libgdx counts them. */
const DOUBLE_TAP = 400;

/**
 * The trail of nodes. It opens with the current row at 56% of its height, and planes revealed since the map last showed turn over.
 * covered is true while a scene lies over it, so the intro and the turns wait to be seen. open shows the chosen plane's sheet.
 */
function Trail({ map, phone, whole, covered, locked, select, open }: {
  map: RogueMap; phone: boolean; whole: boolean; covered: boolean; locked: boolean; select: (node: number) => void; open?: () => void;
}) {
  const scroller = useRef<HTMLDivElement>(null);
  const intro = useRef<{ target: number; frame: number } | null>(null);
  const tapped = useRef<{ node: number; at: number } | null>(null);
  const at = new Map<number, [number, number]>();
  map.rows.forEach((row, r) => row.forEach((n, i) => at.set(n.index, [r, i])));
  const links = map.links.map(l => [...at.get(l.from)!, at.get(l.to)![1]] as Link);
  const plan = layout(map.rows.map(row => row.map(n => ({ side: n.kind !== 'PLANEBOUND', boss: n.boss }))), links, phone);
  const fits = !phone && !whole;
  const [k, setK] = useState(1);
  const fit = fits ? fitted(map, plan, k) : null;
  const span = fit?.span ?? 0;
  useLayoutEffect(() => {
    if (!fits) return;
    const el = scroller.current!;
    const measure = () => {
      const style = getComputedStyle(el);
      setK(Math.min(1, (el.clientWidth - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight) - 2) / span));
    };
    measure();
    const watch = new ResizeObserver(measure);
    watch.observe(el);
    return () => watch.disconnect();
  }, [fits, span]);
  const boxes = fit?.boxes ?? plan.boxes;
  const [sr, si] = at.get(map.selected) ?? [0, 0];
  const here = boxes[sr][si];
  // Mobile's boss light: dark red rays turning slowly behind the act's final boss until it is beaten
  const boss = map.rows.flatMap((row, r) => row.map((n, i) => ({ n, box: boxes[r][i] }))).find(({ n }) => n.boss && n.state !== 'walked' && n.state !== 'past');
  const ray = boss && { r: boss.box.w * 1.3, x: boss.box.x + boss.box.w / 2, y: boss.box.y + boss.box.h / 2, w: boss.box.w, h: boss.box.h };
  useLayoutEffect(() => {
    const el = scroller.current!;
    const target = Math.max(0, Math.min(el.scrollHeight - el.clientHeight, Math.round(here.y + here.h / 2) - el.clientHeight * .56));
    const running = intro.current;
    if (running && !covered) {
      running.target = target;
      return;
    }
    // Cut short by a scene, the intro starts again when the map shows, as mobile's does
    if (running) cancelAnimationFrame(running.frame);
    intro.current = null;
    if (!ui.rogueIntro || whole || covered || reducedMotion()) {
      if (!whole && !covered) ui.rogueIntro = false;
      el.scrollTop = target;
      return;
    }
    // A new run's first look starts at the boss, waits half a second, then eases down to the current row over 1.2 s. It holds the scroll meanwhile, as mobile's does
    el.scrollTop = 0;
    const start = performance.now();
    const step = (now: number) => {
      const p = Math.min(1, Math.max(0, (now - start - 500) / 1200));
      el.scrollTop = intro.current!.target * p * p * (3 - 2 * p);
      if (p < 1) intro.current!.frame = requestAnimationFrame(step);
      else {
        intro.current = null;
        ui.rogueIntro = false;
      }
    };
    intro.current = { target, frame: requestAnimationFrame(step) };
  }, [map.selected, whole, k, covered]);
  useLayoutEffect(() => () => { if (intro.current) cancelAnimationFrame(intro.current.frame); }, []);
  useLayoutEffect(() => {
    if (covered) return;
    const nodes = map.rows.flat();
    const before = faces;
    faces = new Map(nodes.map(n => [n.index, n.faceDown]));
    const turned = before ? nodes.filter(n => before.get(n.index) && !n.faceDown) : [];
    if (!turned.length) return;
    playSound({ t: 'sound', name: 'flip_card', sync: false });
    if (reducedMotion()) return;
    // Each revealed plane turns as mobile turns it, 150 ms apart: its back narrows to an edge, then its face widens
    turned.forEach((n, i) => {
      const plate = scroller.current!.querySelector<HTMLElement>(`[data-node="${n.index}"]`);
      const turn = { duration: 500, delay: i * 150, fill: 'backwards' } as const;
      plate?.animate([{ transform: 'scaleX(1)' }, { transform: 'scaleX(0)' }, { transform: 'scaleX(1)' }], turn);
      plate?.querySelector('.kit-back')?.animate([{ opacity: 1 }, { opacity: 1, offset: 0.5 }, { opacity: 0, offset: 0.5 }, { opacity: 0 }], turn);
    });
  }, [map, covered]);
  // A double tap on a plane of the current row chooses it and opens its sheet, as on mobile
  const tap = (n: MapNode) => {
    if (locked) return;
    choose(n, select);
    const now = performance.now();
    const again = tapped.current?.node === n.index && now - tapped.current.at < DOUBLE_TAP;
    tapped.current = again ? null : { node: n.index, at: now };
    if (again && enterable(n) && n.kind === 'PLANEBOUND') open?.();
  };
  return (
    <div class={phone ? 'rg-trail rg-vtrail' : 'rg-trail'} style={phone ? { padding: '8px 12px' } : undefined} ref={scroller}>
      <div class="rg-trail-in" style={{ width: `${fit?.width ?? plan.width}px`, height: `${fit?.height ?? plan.height}px`, margin: '0 auto' }}>
        <Fades />
        {ray && <i class="kit-rays" aria-hidden="true" style={{ left: `${ray.x - ray.r}px`, top: `${ray.y - ray.r}px`, width: `${2 * ray.r}px`, height: `${2 * ray.r}px`, '--pw': `${ray.w}px`, '--ph': `${ray.h}px` }} />}
        <svg width={fit?.svgWidth ?? plan.width} height={fit?.height ?? plan.height} viewBox={`0 0 ${plan.width} ${plan.height}`} style={fit ? { left: `${fit.left}px` } : undefined}>
          {map.links.map((l, k) => {
            const [r, i] = at.get(l.from)!, j = at.get(l.to)![1];
            return <path key={k} class={`rg-e ${edge(map.rows[r][i], map.rows[r + 1][j])}`} d={plan.paths[k]} />;
          })}
        </svg>
        {map.rows.map((row, r) => row.map((n, i) => <Node key={n.index} n={n} box={boxes[r][i]} phone={phone} pick={() => tap(n)} />))}
      </div>
    </div>
  );
}

// The map paper's colour, 228/210/170, and how much of a faded node shows over it
const PAPER = [228 / 255, 210 / 255, 170 / 255];
const FADES = [['rg-fade70', 0.7], ['rg-fade62', 0.62], ['rg-fade32', 0.32]] as const;

/** Filters that fade a node toward the paper while keeping it opaque, so the trail under it stays hidden, as opacity would show it. */
function Fades() {
  return (
    <svg class="rg-fades" width="0" height="0" aria-hidden="true">
      {FADES.map(([id, a]) => (
        <filter key={id} id={id} color-interpolation-filters="sRGB">
          <feComponentTransfer>
            <feFuncR type="linear" slope={a} intercept={(1 - a) * PAPER[0]} />
            <feFuncG type="linear" slope={a} intercept={(1 - a) * PAPER[1]} />
            <feFuncB type="linear" slope={a} intercept={(1 - a) * PAPER[2]} />
          </feComponentTransfer>
        </filter>
      ))}
    </svg>
  );
}

const ZOOM_MS = 450;

/** Zooms the trail between the place and the whole map: the chosen node grows or shrinks from where it was to where it now is. */
function zoomTrail(page: HTMLElement, selected: number, from: DOMRect): void {
  const trail = page.querySelector<HTMLElement>('.rg-trail');
  const to = page.querySelector(`[data-node="${selected}"]`)?.getBoundingClientRect();
  if (!trail || !to || !to.width || reducedMotion()) return;
  const box = trail.getBoundingClientRect();
  const ox = to.left + to.width / 2 - box.left, oy = to.top + to.height / 2 - box.top;
  const dx = from.left + from.width / 2 - (to.left + to.width / 2), dy = from.top + from.height / 2 - (to.top + to.height / 2);
  // The stage clips the trail while it is larger than its box
  const stage = trail.parentElement!;
  stage.style.overflow = 'hidden';
  const open = () => { stage.style.overflow = ''; };
  trail.animate([{ transformOrigin: `${ox}px ${oy}px`, transform: `translate(${dx}px, ${dy}px) scale(${from.width / to.width})` },
    { transformOrigin: `${ox}px ${oy}px`, transform: 'none' }], { duration: ZOOM_MS, easing: POW3_OUT })
    .finished.then(open, open);
}

/** Keeps the whole map's zoom fitted to its box, as the box changes size. */
function useFit(box: { current: HTMLElement | null }, map: RogueMap, phone: boolean, on: boolean): void {
  useLayoutEffect(() => {
    if (!on) return;
    const resize = () => {
      const el = box.current!;
      const inner = el.querySelector<HTMLElement>('.rg-trail-in')!;
      el.style.setProperty('--z', '1');
      const z = fit({ width: parseFloat(inner.style.width), height: parseFloat(inner.style.height) }, { width: el.clientWidth, height: el.clientHeight });
      el.style.setProperty('--z', z.toFixed(4));
    };
    resize();
    addEventListener('resize', resize);
    return () => removeEventListener('resize', resize);
  }, [on, map, phone]);
}

/** covered is true while a scene lies over the map. */
export function MapPage({ model, actions, covered }: { model: Model; actions: Actions; covered: boolean }) {
  const map = model.rogueMap!;
  const phone = isPortrait();
  const whole = ui.rogueWhole;
  const locked = mapLocked(model);
  // Only a phone opens the sheet, because a wide screen's panel already shows the plane chosen
  const sheet = () => changeUi(u => { u.rogueSheet = true; });
  const box = useRef<HTMLDivElement>(null);
  useFit(box, map, phone, whole);
  const page = useRef<HTMLDivElement>(null);
  const zoomFrom = useRef<DOMRect | null>(null);
  const toggle = () => {
    zoomFrom.current = page.current?.querySelector(`[data-node="${map.selected}"]`)?.getBoundingClientRect() ?? null;
    changeUi(u => { u.rogueWhole = !u.rogueWhole; });
  };
  useLayoutEffect(() => {
    const from = zoomFrom.current;
    zoomFrom.current = null;
    if (from) zoomTrail(page.current!, map.selected, from);
  }, [whole]);
  const select = (node: number) => actions.rogueSelect(model.rogueHome!.snapshot, node);
  if (phone) {
    return (
      <div style={{ display: 'grid', gridTemplateRows: 'minmax(0,1fr) auto', minHeight: 0 }} ref={page}>
        {whole ? <div class="rg-pfit" ref={box}><Trail map={map} phone whole covered={covered} locked={locked} select={select} open={sheet} /></div>
          : <Trail map={map} phone whole={false} covered={covered} locked={locked} select={select} open={sheet} />}
        {whole ? <div class="rg-sheetbar"><div class="go" style={{ gridTemplateColumns: '1fr' }}><button onClick={toggle}>{t('lblWebRogueBackToPlace')}</button></div></div>
          : <SheetBar model={model} actions={actions} />}
      </div>
    );
  }
  return (
    <div class="rg-vmap" ref={page}>
      <div class={whole ? 'rg-vstage fit' : 'rg-vstage'} style={whole ? undefined : { '--z': 0 }} ref={box}>
        <Trail map={map} phone={false} whole={whole} covered={covered} locked={locked} select={select} />
        <button class="rg-vbtn" aria-pressed={whole} title={t(whole ? 'lblWebRogueBackToPlace' : 'lblWebRogueWholeMap')} aria-label={t(whole ? 'lblWebRogueBackToPlace' : 'lblWebRogueWholeMap')} onClick={toggle} />
      </div>
      <MapSide model={model} actions={actions} />
    </div>
  );
}
