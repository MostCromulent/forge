// What a node did, drawn as a scene: the text, then the cards lost and gained

import { Fragment } from 'preact';
import { Icon } from '../icons';
import { imageUrl } from '../images';
import { t } from '../text';
import { Scene, useSceneKeys } from './scene';
import { TERM_ICONS, Words } from './terms';
import type { RogueCard, Tell } from '../protocol';

/** A carry card stands large with its type. Another card is drawn small when the result has several sections, with its count when above one. */
function Card({ card, small }: { card: RogueCard; small: boolean }) {
  if (card.type) {
    const icon = TERM_ICONS[card.type.term!];
    return (
      <div class="rg-card" style={{ width: '214px' }} data-image={card.image}>
        <img alt="" src={imageUrl(card.image)} />
        <span class="myth">{icon && <Icon d={icon} cls="rg-ic" />}{card.type.text}</span>
      </div>
    );
  }
  return (
    <button class={small ? 'rg-card sm' : 'rg-card'} type="button" data-image={card.image}>
      <img alt={card.name} src={imageUrl(card.image)} />
      {card.count > 1 && <span class="no" style={{ padding: '0 9px' }}>{t('lblWebRogueCopies', card.count)}</span>}
    </button>
  );
}

/** A result. When its one section has no label, the result's text heads it in place of the title. */
export function Gains({ tell, ok }: { tell: Tell; ok: () => void }) {
  useSceneKeys(() => {}, ok);
  const led = tell.sections.length === 1 && !tell.sections[0].label;
  const small = tell.sections.length > 1;
  return (
    <Scene art={tell.art} foot={<button class="primary" onClick={ok}>{t('lblContinue')}</button>}>
      <div class="rg-gains">
        <h3>{led ? <Words parts={tell.text} /> : tell.title}</h3>
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
