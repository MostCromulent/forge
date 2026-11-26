// What a node or a battle did, drawn as a scene: the text or the battle's spoils, then the cards lost and gained

import { Fragment, type JSX } from 'preact';
import { useEffect, useRef } from 'preact/hooks';
import { coinsFrom } from '../campaign/campaign';
import { changeUi } from '../ui';
import { Icon } from '../icons';
import { hideBroken, imageUrl } from '../images';
import { t } from '../text';
import { ROGUE_ICONS } from './icons';
import { Sprite, glyph, ringClass, sealGlyph } from './kit';
import { useSceneKeys } from '../hooks';
import { Scene } from './scene';
import { TERM_ICONS, Words } from './terms';
import type { RogueCard, Spoil, Tell, TellGains } from '../protocol';

/** The face over a revive or a defeat at a side node. */
const FACES: Record<string, keyof typeof ROGUE_ICONS> = { REVIVE: 'gem', DEFEAT: 'skull' };

/** A carry card stands large with its type. Another card is drawn small when the result has several sections, with its count when above one. */
function Card({ card, small }: { card: RogueCard; small: boolean }) {
  if (card.type) {
    const icon = TERM_ICONS[card.type.term!];
    return (
      <div class="rg-card" style={{ width: '214px' }} data-image={card.image}>
        <img onError={hideBroken} alt="" src={imageUrl(card.image)} />
        <span class="myth">{icon && <Icon d={icon} cls="rg-ic" />}{card.type.text}</span>
      </div>
    );
  }
  return (
    <button class={small ? 'rg-card sm' : 'rg-card'} type="button" data-image={card.image}>
      <img onError={hideBroken} alt={card.name} src={imageUrl(card.image)} />
      {card.count > 1 && <span class="no" style={{ padding: '0 9px' }}>{t('lblWebRogueCopies', card.count)}</span>}
    </button>
  );
}

/** The run bar's balances a battle's spoils count into, as held back from it until they have landed. */
const heldIcons = (gains: TellGains): [string, number][] => [['ICO_QUEST_COIN', gains.gold], ['ICO_QUEST_GOLD', gains.echoes], ['rogueLife', gains.life]];

/** What the screen showing a battle's spoils has not yet counted into the run's bar, by balance icon. The server's bar already counts all of it. */
export const heldBack = (gains: TellGains | null | undefined, landed: boolean): Record<string, number> =>
  gains && !landed ? Object.fromEntries(heldIcons(gains)) : {};

/** Each spoil's picture by the skin icon the reward script names, and the run bar's balance it counts into. */
const SPOIL_PICTURES: Record<string, () => JSX.Element> = {
  ICO_QUEST_COIN: () => <Sprite k="coin" px={32} />, ICO_QUEST_GOLD: () => <Sprite k="shards" px={32} />, ICO_QUEST_CHARM: () => glyph('heart', 32),
  ICO_QUEST_ELIXIR: () => <Sprite k="spark" px={32} />, ICO_QUEST_MINUS: () => <Sprite k="xmark" px={32} />,
};
const SPOIL_BALANCES: Record<string, string> = { ICO_QUEST_COIN: 'ICO_QUEST_COIN', ICO_QUEST_GOLD: 'ICO_QUEST_GOLD', ICO_QUEST_CHARM: 'rogueLife' };

/**
 * A battle's spoils as mobile lists them: each with its picture under its title, a lost one fainter. Once the screen has risen the bar
 * counts in what they add, its coins flying from their pictures. The question id marks them landed, so a fold and unfold counts nothing twice.
 */
export function Ledger({ spoils, id }: { spoils: Spoil[]; id: number }) {
  const list = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const shown = [...list.current!.children];
    const sources = spoils.map((s, i) => [SPOIL_BALANCES[s.icon ?? ''], shown[i].firstElementChild] as const).filter(([icon]) => icon);
    for (const [icon, el] of sources) coinsFrom(icon, el);
    let open = true;
    const scene = list.current!.closest('.rg-scene');
    Promise.allSettled(scene?.getAnimations().map(a => a.finished) ?? []).then(() => {
      if (open) changeUi(u => { u.rogueLanded = id; });
    });
    return () => {
      open = false;
      for (const [icon] of sources) coinsFrom(icon, null);
    };
  }, []);
  return (
    <div class="rg-ledger" ref={list}>
      {spoils.map((s, i) => (
        <div key={i} class={s.icon === 'ICO_QUEST_MINUS' ? 'rg-gain rg-spoil lost' : 'rg-gain rg-spoil'}>
          {s.icon && SPOIL_PICTURES[s.icon] ? SPOIL_PICTURES[s.icon]() : <span />}
          <div><small>{s.title}</small><p>{s.text}</p></div>
        </div>
      ))}
    </div>
  );
}

/** A result, or a battle's spoils that no card reward showed. When its one section has no label, the result's text heads it in place of the title. */
export function Gains({ tell, id, ok }: { tell: Tell; id: number; ok: () => void }) {
  useSceneKeys(() => {}, ok);
  const led = tell.sections.length === 1 && !tell.sections[0].label;
  const small = tell.sections.length > 1;
  const face = FACES[tell.kind];
  return (
    <Scene art={tell.art} foot={<button class="primary" onClick={ok}>{t(tell.kind === 'DEFEAT' ? 'lblRogueEndRun' : 'lblContinue')}</button>}>
      <div class="rg-gains">
        {face && <span class="rg-face"><span class={`pic icon${ringClass(face)}`}>{sealGlyph(face, 64)}</span></span>}
        <h3>{led ? <Words parts={tell.text} /> : tell.title}</h3>
        {tell.spoils.length > 0 && <Ledger spoils={tell.spoils} id={id} />}
        {!led && tell.text.length > 0 && <p><Words parts={tell.text} /></p>}
        {tell.sections.map((s, i) => <Fragment key={i}>
          {s.label && <span class="rg-sub">{s.label}</span>}
          {s.text.length > 0 && <p><Words parts={s.text} /></p>}
          {s.cards.length > 0 && (
            <div class={s.removed ? 'rg-cards rg-removed' : 'rg-cards'}>{s.cards.map(c => <Card key={c.name} card={c} small={small} />)}</div>
          )}
        </Fragment>)}
      </div>
    </Scene>
  );
}
