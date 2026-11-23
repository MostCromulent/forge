// The run's map: the trail of nodes climbing to the boss, and the panel of the node chosen. On a phone the panel is a bar under the trail

import { useLayoutEffect, useRef } from 'preact/hooks';
import { Icon } from '../icons';
import { artUrl } from '../images';
import { isPortrait } from '../form';
import { playSound } from '../audio';
import { reducedMotion } from '../dom';
import { changeUi, ui } from '../ui';
import { t } from '../text';
import { ROGUE_ICONS } from './icons';
import { fit, layout, type Link } from './maplayout';
import { MapSide, SheetBar } from './mapside';
import type { Actions } from '../actions';
import type { Model } from '../model';
import type { MapNode, Part, RogueMap } from '../protocol';

/** The attributes of a term the tooltip explains. */
export const explains = (term?: Part) => (term ? { 'data-term': term.term, 'data-tip': term.tip, tabIndex: 0 } : {});

/** A side node's icon, by its NodeType name. */
export function sideIcon(kind: string): string {
  switch (kind) {
    case 'SANCTUM': return ROGUE_ICONS.sanctum;
    case 'BAZAAR': return ROGUE_ICONS.bazaar;
    case 'EVENT': return ROGUE_ICONS.event;
    default: return ROGUE_ICONS.chest;
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

/** A face-up plane can be chosen to read it, ahead or behind. */
const readable = (n: MapNode) => n.kind === 'PLANEBOUND' && !n.faceDown;

/** Choosing an open node makes it the current node. Choosing any other that can be read only shows its panel. */
export function choose(map: RogueMap, n: MapNode, actions: Actions): void {
  changeUi(u => { u.rogueViewing = n.state === 'open' || n.state === 'selected' ? null : n.index; });
  if (n.state === 'open') actions.rogueSelect(map.snapshot, n.index);
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
    return <button class={`rg-n side ${state}`} style={place} data-node={n.index} aria-label={n.name} disabled={off} onClick={pick}><Icon d={sideIcon(n.kind)} cls="rg-ic" />{ok}</button>;
  }
  if (n.faceDown) {
    return (
      <button class={`rg-n hid ${n.boss ? 'boss' : ''} ${state}`} style={place} data-node={n.index} disabled={off} onClick={pick}>
        <span class="q">?</span><span class="mk"><Marks n={n} /></span><span class="nm">{life}</span>
      </button>
    );
  }
  return (
    <button class={`rg-n ${state}`} style={{ ...place, backgroundImage: n.planeImage ? `url("${artUrl(n.planeImage)}")` : undefined }} data-node={n.index}
      data-image={n.planeImage} disabled={off} onClick={pick}>
      <i class="opp" style={n.art ? { backgroundImage: `url("${artUrl(n.art)}")` } : undefined} />
      <span class="mk"><Marks n={n} /></span>
      {ok}
      <span class="nm"><b>{phone ? n.name!.split(',')[0] : n.name}</b>{life}</span>
    </button>
  );
}

/** The trail of nodes. It opens with the current row at 56% of its height, and planes revealed since it was last drawn turn over. */
function Trail({ map, phone, whole, actions }: { map: RogueMap; phone: boolean; whole: boolean; actions: Actions }) {
  const scroller = useRef<HTMLDivElement>(null);
  const shown = useRef<Map<number, boolean> | null>(null);
  const at = new Map<number, [number, number]>();
  map.rows.forEach((row, r) => row.forEach((n, i) => at.set(n.index, [r, i])));
  const links = map.links.map(l => [...at.get(l.from)!, at.get(l.to)![1]] as Link);
  const plan = layout(map.rows.map(row => row.map(n => ({ side: n.kind !== 'PLANEBOUND', boss: n.boss }))), links, phone);
  const [sr, si] = at.get(map.selected) ?? [0, 0];
  const here = plan.boxes[sr][si];
  useLayoutEffect(() => {
    const el = scroller.current!;
    el.scrollTop = Math.round(here.y + here.h / 2) - el.clientHeight * .56;
  }, [map.selected, whole]);
  useLayoutEffect(() => {
    const before = shown.current;
    shown.current = new Map(map.rows.flat().map(n => [n.index, n.faceDown]));
    const turned = before ? map.rows.flat().filter(n => before.get(n.index) && !n.faceDown) : [];
    if (!turned.length) return;
    playSound({ t: 'sound', name: 'flip_card', sync: false });
    if (reducedMotion()) return;
    // A reveal turns each plane over in turn, 150 ms apart, as desktop does
    turned.forEach((n, k) => scroller.current!.querySelector(`[data-node="${n.index}"]`)?.animate(
      [{ transform: 'perspective(700px) rotateY(90deg)' }, { transform: 'none' }], { duration: 260, delay: k * 150, easing: 'ease-out', fill: 'backwards' }));
  }, [map]);
  return (
    <div class={phone ? 'rg-trail rg-vtrail' : 'rg-trail'} style={phone ? { padding: '8px 12px' } : undefined} ref={scroller}>
      <div class="rg-trail-in" style={{ width: `${plan.width}px`, height: `${plan.height}px`, margin: '0 auto' }}>
        <svg width={plan.width} height={plan.height}>
          {map.links.map((l, k) => {
            const [r, i] = at.get(l.from)!, j = at.get(l.to)![1];
            return <path key={k} class={`rg-e ${edge(map.rows[r][i], map.rows[r + 1][j])}`} d={plan.paths[k]} />;
          })}
        </svg>
        {map.rows.map((row, r) => row.map((n, i) => <Node key={n.index} n={n} box={plan.boxes[r][i]} phone={phone} pick={() => choose(map, n, actions)} />))}
      </div>
    </div>
  );
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

export function MapPage({ model, actions }: { model: Model; actions: Actions }) {
  const map = model.rogueMap!;
  const phone = isPortrait();
  const whole = ui.rogueWhole;
  const box = useRef<HTMLDivElement>(null);
  useFit(box, map, phone, whole);
  const toggle = () => changeUi(u => { u.rogueWhole = !u.rogueWhole; });
  if (phone) {
    return (
      <div style={{ display: 'grid', gridTemplateRows: 'minmax(0,1fr) auto', minHeight: 0 }}>
        {whole ? <div class="rg-pfit" ref={box}><Trail map={map} phone whole actions={actions} /></div> : <Trail map={map} phone whole={false} actions={actions} />}
        {whole ? <div class="rg-sheetbar"><div class="go" style={{ gridTemplateColumns: '1fr' }}><button onClick={toggle}>{t('lblWebRogueBackToPlace')}</button></div></div>
          : <SheetBar model={model} actions={actions} />}
      </div>
    );
  }
  return (
    <div class="rg-vmap">
      <div class={whole ? 'rg-vstage fit' : 'rg-vstage'} style={whole ? undefined : { '--z': 0 }} ref={box}>
        <Trail map={map} phone={false} whole={whole} actions={actions} />
        <button class="rg-vbtn" onClick={toggle}>{t(whole ? 'lblWebRogueBackToPlace' : 'lblWebRogueWholeMap')}</button>
      </div>
      <MapSide model={model} actions={actions} />
    </div>
  );
}
