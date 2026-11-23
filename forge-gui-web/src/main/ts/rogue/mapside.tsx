// The panel of the node chosen on the map, with the buttons that enter it and reroll its plane. On a phone it is a bar under the trail,
// and a sheet that opens from the bar

import { useState } from 'preact/hooks';
import { DeckRow, SidePanel } from '../campaign/campaign';
import { Icon } from '../icons';
import { artUrl, imageUrl, skinIconUrl } from '../images';
import { changeUi } from '../ui';
import { t } from '../text';
import { ROGUE_ICONS } from './icons';
import { enterable, explains, shownNode, sideIcon } from './map';
import type { Actions } from '../actions';
import type { Model } from '../model';
import type { MapNode, RogueMap } from '../protocol';

const glyph = (name: keyof typeof ROGUE_ICONS) => <Icon d={ROGUE_ICONS[name]} cls="rg-ic" />;

/** The planebound's kind, as the panel's kicker names it. */
const kind = (map: RogueMap, n: MapNode) => (n.boss ? t('lblRogueBossPlanebound') : n.elite ? t('lblRogueElitePlanebound') : map.terms.PLANEBOUND?.text ?? '');

/** What the buttons under the panel do with the node shown. Only the current node is entered, and only its plane rerolled. */
function useNode(model: Model, actions: Actions) {
  const map = model.rogueMap!;
  const snapshot = model.rogueHome!.snapshot;
  const n = shownNode(map);
  const current = n.index === map.selected && enterable(n);
  return {
    map, n,
    enter: current ? () => actions.rogueEnter(snapshot, n.index) : undefined,
    reroll: current && n.kind === 'PLANEBOUND' && map.rerolls > 0 ? () => actions.rogueReroll(snapshot, n.index) : undefined,
  };
}

function Chips({ map, n, cls }: { map: RogueMap; n: MapNode; cls: string }) {
  const chip = (c: string) => (cls ? `${cls} ${c}`.trim() : undefined);
  return <>
    <span class={chip('')}>{glyph('heart')}{` ${t('lblWebRogueLife', n.life)}`}</span>
    {n.elite && <span class={chip('brass')}>{glyph('star')}{` ${t('lblElite')}`}</span>}
    {n.boss && <span class={chip('brass')}>{glyph('crown')}{` ${t('lblWebRogueBoss')}`}</span>}
    {n.wrathful > 0 && <span class={chip('')} {...explains(map.terms.WRATHFUL)}>{glyph('flame')}{` ${map.terms.WRATHFUL?.text}`}</span>}
    {n.cursed > 0 && <span class={chip('')} {...explains(map.terms.CURSED)}>{glyph('curse')}{` ${map.terms.CURSED?.text}`}</span>}
  </>;
}

/** Gold and Echoes of the node's kind, and the card reward every plane but the boss's gives. */
function Loot({ map, n }: { map: RogueMap; n: MapNode }) {
  return <>
    {n.gold > 0 && <span><img alt={t('lblWebRogueGold')} src={skinIconUrl('ICO_QUEST_COIN')} />{n.gold}</span>}
    <span><img alt="" src={skinIconUrl('ICO_QUEST_GOLD')} />{n.echoes}</span>
    {!n.boss && <span {...explains(map.terms.CARD_REWARD)}>{glyph('deck')}{` ${map.terms.CARD_REWARD?.text}`}</span>}
    {!n.boss && <span>{glyph('star')}{` ${t('lblMythic')}`}</span>}
  </>;
}

const paragraphs = (text?: string) => (text ?? '').split('\n').filter(Boolean);

export function MapSide({ model, actions }: { model: Model; actions: Actions }) {
  const [zoom, setZoom] = useState<string | null>(null);
  const { map, n, enter, reroll } = useNode(model, actions);
  const run = model.rogueHome?.run;
  const foot = <>
    {run && <DeckRow image={run.image} name={run.commander} line={t('lblWebNCards', run.cards)} problem={false} zoom={setZoom}
      change={() => actions.rogueEditDeck(model.rogueHome!.snapshot)} changeLabel={t('lblEdit')} />}
    {reroll && <button disabled={!!map.rerollReason} title={map.rerollReason} onClick={reroll}>{glyph('reroll')}{` ${t('lblRogueRerollPlaneCount', map.rerolls)}`}</button>}
    <button class="primary rg-go" disabled={!enter} onClick={enter}>{n.enter}</button>
  </>;
  if (n.kind !== 'PLANEBOUND') {
    return (
      <SidePanel kicker={t('lblWebRogueSideNode')} title={n.name} noArt zoom={zoom} setZoom={setZoom} foot={foot}>
        <div class="rg-face" style={{ padding: '14px 0 6px' }}><span class="pic icon" style={{ width: '128px', height: '128px', fontSize: '58px' }}><Icon d={sideIcon(n.kind)} cls="rg-ic" /></span></div>
        <div class="rg-sec"><p>{n.text}</p></div>
      </SidePanel>
    );
  }
  const reward = <div class="rg-sec"><h4>{t('lblWebRogueReward')}</h4><div class="rg-loot"><Loot map={map} n={n} /></div></div>;
  if (n.faceDown) {
    return (
      <SidePanel kicker={kind(map, n)} title="???" noArt zoom={zoom} setZoom={setZoom} foot={foot}>
        <div class="camp-chips"><Chips map={map} n={n} cls="camp-chip" /></div>
        {map.hidden && <div class="rg-lock">{glyph('wound')}<span>{map.hidden}</span></div>}
        {reward}
      </SidePanel>
    );
  }
  return (
    <SidePanel kicker={<span {...explains(map.terms.PLANEBOUND)}>{kind(map, n)}</span>} title={n.name} art={n.art} zoom={zoom} setZoom={setZoom} foot={foot}>
      <div class="camp-chips"><Chips map={map} n={n} cls="camp-chip" /></div>
      <div class="rg-sec"><h4>{t('lblPlane')}</h4><span class="rg-plane">{n.planeImage && <img alt={n.plane} data-image={n.planeImage} src={imageUrl(n.planeImage)} />}</span></div>
      <div class="rg-sec">{paragraphs(n.text).map((p, i) => <p key={i}>{p}</p>)}</div>
      {reward}
    </SidePanel>
  );
}

/** The phone's bar under the trail: the node shown, Enter, the sheet of its plane, and the reroll. */
export function SheetBar({ model, actions }: { model: Model; actions: Actions }) {
  const { map, n, enter, reroll } = useNode(model, actions);
  const plane = n.kind === 'PLANEBOUND';
  const line = plane ? [n.plane ?? '???', `♥ ${n.life}`, n.elite && t('lblElite'), n.boss && t('lblWebRogueBoss'),
    n.wrathful > 0 && map.terms.WRATHFUL?.text, n.cursed > 0 && map.terms.CURSED?.text].filter(Boolean).join(' · ') : n.text;
  return (
    <div class="rg-sheetbar">
      <div class="top">
        {n.art && <i style={{ backgroundImage: `url("${artUrl(n.art)}")` }} />}
        <div class="t"><b>{n.faceDown ? '???' : n.name}</b><span>{line}</span></div>
      </div>
      <div class="go">
        <button class="primary" disabled={!enter} onClick={enter}>{t('lblWebRogueEnter')}</button>
        {plane && <button onClick={() => changeUi(u => { u.rogueSheet = true; })}>{t('lblPlane')}</button>}
        {reroll && <button disabled={!!map.rerollReason} title={map.rerollReason} aria-label={t('lblRogueRerollPlane')} onClick={reroll}>{glyph('reroll')}{` ${map.rerolls}`}</button>}
      </div>
    </div>
  );
}

/** The phone's sheet of the plane shown: its planebound, plane, text and reward. */
export function PlaneSheet({ model, actions }: { model: Model; actions: Actions }) {
  const { map, n, enter, reroll } = useNode(model, actions);
  const close = () => changeUi(u => { u.rogueSheet = false; });
  return (
    <div class="rg-psheet" onClick={e => { if (e.target === e.currentTarget) close(); }}>
      <div class="box">
        <i class="grab" />
        <div class="bd">
          <div class="camp-kicker">{kind(map, n)}</div>
          <h3>{n.faceDown ? '???' : n.name}</h3>
          <div class="ln"><Chips map={map} n={n} cls="" /></div>
          {n.planeImage && <span class="rg-plane"><img alt={n.plane} data-image={n.planeImage} src={imageUrl(n.planeImage)} /></span>}
          {n.faceDown && map.hidden && <p>{map.hidden}</p>}
          {paragraphs(n.text).map((p, i) => <p key={i}>{p}</p>)}
          <div class="camp-kicker">{t('lblWebRogueReward')}</div>
          <div class="ln"><Loot map={map} n={n} /></div>
        </div>
        <div class="ft2">
          {reroll && <button disabled={!!map.rerollReason} title={map.rerollReason} onClick={reroll}>{glyph('reroll')}{` ${t('lblRogueRerollPlane')}`}</button>}
          <button class="primary" disabled={!enter} onClick={() => { close(); enter?.(); }}>{t('lblWebRogueEnter')}</button>
        </div>
      </div>
    </div>
  );
}
