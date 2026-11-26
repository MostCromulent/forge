// What a node or a won battle did, drawn as a scene: the text or the figures, then the cards lost and gained

import { Fragment } from 'preact';
import { useEffect, useRef } from 'preact/hooks';
import { coinsFrom } from '../campaign/campaign';
import { changeUi } from '../ui';
import { Icon } from '../icons';
import { hideBroken, imageUrl } from '../images';
import { isPortrait } from '../form';
import { t } from '../text';
import { ROGUE_ICONS } from './icons';
import { Sprite, glyph, ringClass, sealGlyph } from './kit';
import { useSceneKeys } from '../hooks';
import { Scene } from './scene';
import { TERM_ICONS, Words } from './terms';
import type { RogueCard, Tell, TellGains } from '../protocol';

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

/** The run bar's balance each figure counts into, by icon. */
const figureIcons = (gains: TellGains): [string, number][] =>
  [['ICO_QUEST_COIN', gains.gold], ['ICO_QUEST_GOLD', gains.echoes], ['rogueLife', gains.life], ['rogueCredits', gains.credits]];

/** What a battle's summary has not yet counted into the run's bar, by balance icon. The server's bar already counts all of it. */
export const heldBack = (gains: TellGains | null | undefined, landed: string[]): Record<string, number> =>
  gains ? Object.fromEntries(figureIcons(gains).filter(([icon]) => !landed.includes(icon))) : {};

/**
 * The figures a won battle gave. One of 0 is left out, and a phone words removal credits shorter. Once the scene has risen,
 * the bar counts them in, its coins flying from the figures.
 */
function Figures({ gains }: { gains: TellGains }) {
  const row = useRef<HTMLDivElement>(null);
  const pictures = [<Sprite k="coin" px={32} />, <Sprite k="shards" px={32} />, glyph('heart', 32), glyph('cut', 32)];
  const labels = [t('lblWebRogueGold'), t('lblWebRogueEchoes'), t('lblLife'), t(isPortrait() ? 'lblCredits' : 'lblWebRogueRemovalCredits')];
  const figures = figureIcons(gains).map(([icon, n], i) => ({ icon, n, picture: pictures[i], label: labels[i] })).filter(f => f.n > 0);
  useEffect(() => {
    row.current!.querySelectorAll('.rg-gain').forEach((figure, i) => coinsFrom(figures[i].icon, figure.firstElementChild));
    let open = true;
    const scene = row.current!.closest('.rg-scene');
    Promise.allSettled(scene?.getAnimations().map(a => a.finished) ?? []).then(() => {
      if (open) changeUi(u => { u.rogueLanded = figureIcons(gains).map(([icon]) => icon); });
    });
    return () => {
      open = false;
      for (const f of figures) coinsFrom(f.icon, null);
      changeUi(u => { u.rogueLanded = []; });
    };
  }, []);
  return (
    <div class="rg-gain-row" ref={row}>
      {figures.map(f => <div key={f.icon} class="rg-gain">{f.picture}<div><b>{`+${f.n}`}</b><span>{f.label}</span></div></div>)}
    </div>
  );
}

/** A result, or what a won battle gave. When its one section has no label, the result's text heads it in place of the title. */
export function Gains({ tell, ok }: { tell: Tell; ok: () => void }) {
  useSceneKeys(() => {}, ok);
  const led = tell.sections.length === 1 && !tell.sections[0].label;
  const small = tell.sections.length > 1;
  const face = FACES[tell.kind];
  return (
    <Scene art={tell.art} foot={<button class="primary" onClick={ok}>{t(tell.kind === 'DEFEAT' ? 'lblRogueEndRun' : 'lblContinue')}</button>}>
      <div class="rg-gains">
        {face && <span class="rg-face"><span class={`pic icon${ringClass(face)}`}>{sealGlyph(face, 64)}</span></span>}
        <h3>{led ? <Words parts={tell.text} /> : tell.title}</h3>
        {tell.gains && <Figures gains={tell.gains} />}
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
