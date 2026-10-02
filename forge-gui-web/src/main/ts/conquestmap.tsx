// The Multiverse: the plane as a road of regions running left to right, and the selected event in a panel beside it.
// A region is a banner of its art over a grid of tiles; a tile is the opponent's picture and its state is carried by
// the picture alone. White is used for where the player stands and what is selected, and for nothing else.

import { useEffect, useLayoutEffect, useRef, useState } from 'preact/hooks';
import { artUrl } from './sleeves';
import { conquestIconUrl, imageUrl } from './images';
import { Pips } from './symbols';
import { BH, G, SPINE, TH, TW, Y0, isFolded, layout } from './conquestlayout';
import { reducedMotion } from './conquestmotion';
import { changeUi, ui } from './ui';
import type { Actions } from './actions';
import type { ConquestCell, ConquestPlace, ConquestState } from './protocol';
import { t, type TextKey } from './text';

/** How long the marker takes over one step, as mobile's token does. */
const STEP_MS = 500;
const HOVER_W = 232;

const key = (p: ConquestPlace): string => `${p.region}:${p.row}:${p.col}`;
const same = (a: ConquestPlace, b: ConquestPlace): boolean => a.region === b.region && a.row === b.row && a.col === b.col;

const VARIANT_NAMES: Record<string, TextKey> = { Commander: 'lblCommander', Vanguard: 'lblVanguard', Planeswalker: 'lblPlaneswalker', Planechase: 'lblPlanechase' };
const VARIANT_DESC: Record<string, TextKey> = { Commander: 'lblCommanderDesc', Vanguard: 'lblVanguardDesc', Planeswalker: 'lblPlaneswalkerDesc', Planechase: 'lblPlanechaseDesc' };
const variantName = (v: string): string => (VARIANT_NAMES[v] ? t(VARIANT_NAMES[v]) : v);

export function ConquestMap({ actions, state }: { actions: Actions; state: ConquestState }) {
  const cells = new Map(state.cells.map(c => [key(c), c]));
  // The marker walks the move just made a step at a time, and nothing on the map answers while it does. It is first
  // put at the walk's start with no movement, since the map that brings the path already has the player at its end.
  const [walk, setWalk] = useState<{ at: ConquestPlace; moving: boolean } | null>(null);
  const battleWanted = useRef(false);
  const pathKey = state.path.map(key).join('>');
  useLayoutEffect(() => {
    if (state.path.length < 2) {
      // A map with no path, arriving mid-walk, ends the walk; whatever was to follow it is forgotten
      battleWanted.current = false;
      setWalk(null);
      return;
    }
    const done = () => {
      setWalk(null);
      if (battleWanted.current) {
        battleWanted.current = false;
        actions.conquestBattle();
      }
    };
    if (reducedMotion()) {
      done();
      return;
    }
    let i = 0;
    let timer = 0;
    setWalk({ at: state.path[0], moving: false });
    // Two frames: one for the marker to be drawn at the start, and the next to set it moving from there
    let frame = requestAnimationFrame(() => {
      frame = requestAnimationFrame(() => {
        const step = () => {
          i += 1;
          if (i < state.path.length) setWalk({ at: state.path[i], moving: true });
          else { clearInterval(timer); done(); }
        };
        step();
        timer = window.setInterval(step, STEP_MS);
      });
    });
    return () => { cancelAnimationFrame(frame); clearInterval(timer); };
  }, [pathKey]);

  // A region nobody has reached, or one that is finished, stands folded until the player opens it. The one the player
  // stands in is always open, and so is every one the marker is walking through.
  // What the player chose is kept by plane, and outlives a visit to another page
  const opened: Record<number, boolean> = {};
  state.regions.forEach((_, r) => {
    const chosen = ui.conquestOpened[`${state.plane}:${r}`];
    if (chosen !== undefined) opened[r] = chosen;
  });
  const keepOpen = new Set([state.at.region, ...(walk ? state.path.map(p => p.region) : [])]);
  const folded = (r: number): boolean => isFolded(state, r, opened, keepOpen);
  const l = layout(state, folded);

  // The view follows the player, as mobile's does
  const trail = useRef<HTMLDivElement>(null);
  const marker = walk?.at ?? state.at;
  const markerAt = l.at(marker.region, marker.row, marker.col);
  useEffect(() => {
    const el = trail.current;
    if (el) el.scrollTo({ left: Math.max(0, markerAt.x + TW / 2 - el.clientWidth * 0.55), behavior: reducedMotion() ? 'auto' : 'smooth' });
  }, [markerAt.x, state.plane]);

  const [hover, setHover] = useState<string | null>(null);
  const hovered = hover ? cells.get(hover) : undefined;
  const selected = cells.get(key(state.selected));

  // Every two places that touch are tied where at least one is conquered: along the road to the next step, crossing
  // into the next region at a region's last step, and down to the next lane
  const ties: { a: ConquestCell; b: ConquestCell }[] = [];
  for (const c of state.cells) {
    const next = c.row + 1 < state.rows ? cells.get(key({ region: c.region, row: c.row + 1, col: c.col }))
      : cells.get(key({ region: c.region + 1, row: 0, col: c.col }));
    const below = cells.get(key({ region: c.region, row: c.row, col: c.col + 1 }));
    for (const other of [next, below]) {
      if (other && (c.state === 'won' || other.state === 'won') && !folded(c.region) && !folded(other.region)) ties.push({ a: c, b: other });
    }
  }

  const battle = () => {
    if (state.steps > 0) {
      battleWanted.current = true;
      actions.conquestMove();
    } else {
      actions.conquestBattle();
    }
  };

  return (
    <div class="cq-map">
      <div class="cq-stage">
        <div class="cq-map-head"><h2>{state.plane}</h2><span class="muted">{state.cells.filter(c => c.state === 'won').length} / {state.cells.length}</span></div>
        <div class="cq-trail" ref={trail}>
          <div class={walk?.moving ? 'cq-trail-in walking' : 'cq-trail-in'} style={{ width: `${l.width}px`, height: `${l.height}px` }}>
            {state.regions.map((region, r) => {
              const at = l.regions[r];
              const done = region.conquered === region.total;
              const count = `${region.conquered} / ${region.total}`;
              const toggle = r === state.at.region ? undefined : () => { const open = folded(r); changeUi(u => { u.conquestOpened[`${state.plane}:${r}`] = open; }); };
              if (at.folded) {
                return (
                  <button key={`s${r}`} class={done ? 'cq-spine done' : 'cq-spine'} title={region.name} onClick={toggle}
                    style={{ left: `${at.x}px`, top: `${Y0}px`, width: `${SPINE}px`, height: `${l.height - Y0 - 20}px` }}>
                    <i style={{ backgroundImage: `url("${artUrl(region.art)}")` }} />
                    <b>{region.name}</b><span>{count}</span>
                  </button>
                );
              }
              return (
                <button key={`b${r}`} class={done ? 'cq-banner done' : 'cq-banner'} disabled={!toggle || !done} onClick={toggle}
                  style={{ left: `${at.x}px`, top: `${Y0}px`, width: `${at.w}px`, height: `${BH}px`, backgroundImage: `url("${artUrl(region.art)}")` }}>
                  <b>{region.name}</b><span>{done && <img alt={t('lblWebConquestConquered')} src={conquestIconUrl('IMG_PW_BADGE_COMMON')} />}{count}</span>
                </button>
              );
            })}
            {state.cells.filter(c => !folded(c.region)).map(c => {
              const p = l.at(c.region, c.row, c.col);
              const here = same(c, state.at) && !walk;
              const sel = same(c, state.selected) && !same(c, state.at);
              const cls = ['cq-tile', c.state, here && 'here', sel && 'sel', hover === key(c) && 'hover'].filter(Boolean).join(' ');
              return (
                <button key={key(c)} class={cls} disabled={c.state === 'fog'} aria-label={c.name ?? undefined}
                  style={{ left: `${p.x}px`, top: `${p.y}px`, width: `${TW}px`, height: `${TH}px`, backgroundImage: c.avatar ? `url("${artUrl(c.avatar)}")` : undefined }}
                  onPointerEnter={() => setHover(key(c))} onPointerLeave={() => setHover(null)}
                  onClick={() => { if (!walk) actions.conquestSelect(c.region, c.row, c.col); }}>
                  {(c.state === 'open' || here) && <span>{c.name}</span>}
                </button>
              );
            })}
            {ties.map(({ a, b }) => {
              const p = l.at(a.region, a.row, a.col), q = l.at(b.region, b.row, b.col);
              const kind = a.state === 'won' && b.state === 'won' ? 'won' : 'open';
              return p.y === q.y
                ? <i key={`${key(a)}-${key(b)}`} class={`cq-tie ${kind}`} style={{ left: `${p.x + TW}px`, top: `${p.y + TH / 2}px`, width: `${q.x - p.x - TW}px` }} />
                : <i key={`${key(a)}-${key(b)}`} class={`cq-tie down ${kind}`} style={{ left: `${p.x + TW / 2}px`, top: `${p.y + TH}px`, height: `${G}px` }} />;
            })}
            <div class="cq-you" style={{ left: `${markerAt.x + TW / 2}px`, top: `${markerAt.y + 54}px` }}>
              <i style={{ backgroundImage: `url("${artUrl(state.walkerImage)}")` }} /><b>{t('lblWebConquestYou')}</b>
            </div>
            {hovered && hovered.state !== 'fog' && !walk && <HoverCard cell={hovered} at={l.at(hovered.region, hovered.row, hovered.col)} width={l.width} />}
          </div>
        </div>
      </div>
      {selected && <EventPanel state={state} cell={selected} busy={!!walk} battle={battle} />}
    </div>
  );
}

function Record({ cell }: { cell: ConquestCell }) {
  return cell.wins || cell.losses ? <span class="cq-chip">{cell.wins}W / {cell.losses}L</span> : null;
}

/** What winning here gives: the emblem of a first conquest, and always a spin of the wheel. */
function Reward({ cell }: { cell: ConquestCell }) {
  return (
    <div class="cq-reward-line">
      <span class="cq-kicker">{t('lblWebConquestReward')}</span>
      {cell.state !== 'won' && <span class="cq-chip"><img alt={t('lblPlaneswalkEmblems')} src={conquestIconUrl('IMG_PW_BADGE_COMMON')} />1</span>}
      <span class="cq-chip">{t('lblWebConquestChaosWheel')}</span>
    </div>
  );
}

function Chips({ cell }: { cell: ConquestCell }) {
  return (
    <div class="cq-chips">
      {(cell.variants ?? []).map(v => <span key={v} class="cq-chip">{variantName(v)}</span>)}
      {cell.state === 'won' && <span class="cq-chip brass"><img alt="" src={conquestIconUrl('IMG_PW_BADGE_COMMON')} />{t('lblWebConquestConquered')}</span>}
      <Record cell={cell} />
      {cell.opens && <span class="cq-chip brass">{t('lblWebConquestPortal')}: {cell.opens}</span>}
    </div>
  );
}

/** What a tile says when pointed at. It opens on the side of the tile that has the room. */
function HoverCard({ cell, at, width }: { cell: ConquestCell; at: { x: number; y: number }; width: number }) {
  const left = at.x + TW + 12 + HOVER_W > width;
  return (
    <div class={left ? 'cq-hov left' : 'cq-hov'} style={{ left: `${left ? at.x - 12 - HOVER_W : at.x + TW + 12}px`, top: `${at.y + 8}px`, width: `${HOVER_W}px` }}>
      <b>{cell.name}</b>
      <Chips cell={cell} />
      <Reward cell={cell} />
    </div>
  );
}

function EventPanel({ state, cell, busy, battle }: { state: ConquestState; cell: ConquestCell; busy: boolean; battle: () => void }) {
  const [zoom, setZoom] = useState<string | null>(null);
  const region = state.regions[cell.region];
  const lead = state.commander;
  return (
    <aside class="cq-side">
      <div class="cq-panel">
        <div class="cq-where"><span class="cq-kicker">{state.plane} - {region.name}</span><span class="pips"><Pips colors={region.colors} /></span></div>
        <h3>{cell.name}</h3>
        <button class="cq-art" disabled={!cell.avatar} style={{ backgroundImage: cell.avatar ? `url("${artUrl(cell.avatar)}")` : undefined }}
          onClick={() => setZoom(cell.avatar ?? null)}>
          <b>{cell.opponent}</b>
        </button>
        <Chips cell={cell} />
        {(cell.variants ?? []).filter(v => VARIANT_DESC[v]).map(v => <p key={v} class="cq-desc">{t(VARIANT_DESC[v])}</p>)}
        <Reward cell={cell} />
        {state.steps > 0 && <p class="cq-steps">{state.steps === 1 ? t('lblWebConquestOneStep') : t('lblWebConquestSteps', state.steps)}</p>}
      </div>
      <div class="cq-foot">
        <div class="cq-mine">
          <button class="cq-face2" aria-label={lead.name} style={{ backgroundImage: `url("${artUrl(lead.image)}")` }} onClick={() => setZoom(lead.image)} />
          <div class="t">
            <b>{lead.name}</b>
            {lead.problem ? <span class="cq-warn">{t('lblWebConquestInvalidDeck')}</span> : <span>{t('lblWebConquestDeckCards', lead.deckSize)}</span>}
          </div>
          <button onClick={() => changeUi(u => { u.conquestTab = 'party'; })}>{t('lblWebConquestChange')}</button>
        </div>
        {(cell.variants ?? []).includes('Planeswalker') && (
          <div class="cq-mine">
            <button class="cq-face2 round" aria-label={state.walker} style={{ backgroundImage: `url("${artUrl(state.walkerImage)}")` }} onClick={() => setZoom(state.walkerImage)} />
            <div class="t"><b>{state.walker}</b><span>{t('lblPlaneswalker')}</span></div>
          </div>
        )}
        {lead.problem && <p class="cq-warn">{lead.problem}</p>}
        <button class="primary cq-big" disabled={!!lead.problem || busy} onClick={battle}>{t('lblBattle')}</button>
      </div>
      {zoom && <div class="backdrop" onClick={() => setZoom(null)}><img class="cq-zoom" alt="" src={imageUrl(zoom)} /></div>}
    </aside>
  );
}
