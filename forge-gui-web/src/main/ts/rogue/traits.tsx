// The run's traits, each marked by where it came from, beside its carry cards

import { Icon } from '../icons';
import { artUrl, hideBroken, imageUrl } from '../images';
import { avatarUrl } from '../avatarsleeves';
import { t } from '../text';
import { ROGUE_ICONS } from './icons';
import { Sprite, sealGlyph } from './kit';
import { Words } from './terms';
import type { RogueCard, Trait } from '../protocol';

/** The icon of each source drawn with one. An NPC's trait shows its face and a Descension level its number; any other source has an empty mark. */
const SEALS: Record<string, keyof typeof ROGUE_ICONS> = {
  AETHER: 'gem', CUSTOM: 'cog', EVENT: 'event', CHEST: 'chest', WOUND: 'wound', WRATHFUL: 'flame', CURSED: 'curse', ENDLESS: 'reroll',
};

/** A trait's mark. explained gives it the tooltip that says where the trait came from. */
export function Seal({ trait, explained }: { trait: Trait; explained?: boolean }) {
  const face = trait.avatar != null;
  const icon = SEALS[trait.source];
  // A Descension level's number sits on Adventure's status sprite
  const level = !face && trait.mark != null;
  return (
    <span class={face ? 'rgt-seal face' : level ? 'rgt-seal lvl' : 'rgt-seal'} style={face ? { backgroundImage: `url("${avatarUrl(trait.avatar!)}")` } : undefined}
      {...(explained ? { 'data-term': trait.term, 'data-tip': trait.tip, tabIndex: 0 } : {})}>
      {face ? null : level ? <><Sprite k="status" px={32} /><i class="ks-n">{trait.mark}</i></> : trait.source === 'TREASURE' ? <Sprite k="coin" px={32} /> : icon && sealGlyph(icon)}
    </span>
  );
}

/** A trait or a reward to take, drawn as a card, as a chest and a bazaar offer them. */
export function TraitCard({ trait, small }: { trait: Trait; small?: boolean }) {
  return (
    <div class={['rg-tcard', small && 'sm', trait.source === 'WOUND' && 'wound'].filter(Boolean).join(' ')}>
      <Seal trait={trait} />
      <b>{trait.name}</b>
      <small>{trait.loot ? t('lblWebRogueLoot') : <><Icon d={ROGUE_ICONS.trait} cls="rg-ic" />{t('lblWebRogueTrait')}</>}</small>
      <p><Words parts={trait.text} /></p>
    </div>
  );
}

/** Your Traits as a page of the run. haze is the commander's picture behind it. */
export function TraitsPage({ traits, carry, haze }: { traits: Trait[]; carry: RogueCard[]; haze: string }) {
  return (
    <div class="rgt">
      <span class="haze" style={{ backgroundImage: `url("${artUrl(haze)}")` }} />
      <div class="rgt-in">
        <section class="rgt-main">
          <header>
            <h1>{t('lblRogueYourEffects')}</h1>
            <p>{t('lblWebRogueTraitsHint')}</p>
          </header>
          <div class="rgt-tiles">
            {traits.map((trait, i) => (
              <article key={i} class={trait.source === 'WOUND' ? 'rgt-tile wound' : 'rgt-tile'}>
                <Seal trait={trait} explained />
                <div><h3>{trait.name}</h3><p><Words parts={trait.text} /></p></div>
              </article>
            ))}
          </div>
        </section>
        <aside class="rgt-carry">
          <header><h2>{t('lblWebRogueCarryHeading')}</h2><p>{t('lblWebRogueCarryHint')}</p></header>
          <div class="pile">
            {carry.map((c, i) => (
              <figure key={i} style={{ '--i': i }}>
                <img onError={hideBroken} alt={c.name} data-image={c.image} src={imageUrl(c.image)} />
                <figcaption>{c.type && <Words parts={[c.type]} />}</figcaption>
              </figure>
            ))}
          </div>
        </aside>
      </div>
    </div>
  );
}
