// The run's traits, each marked by where it came from, beside its carry cards

import { Icon } from '../icons';
import { artUrl, imageUrl } from '../images';
import { avatarUrl } from '../avatarsleeves';
import { t } from '../text';
import { ROGUE_ICONS } from './icons';
import { Words } from './terms';
import type { RogueCard, Trait } from '../protocol';

/** The icon of each source drawn with one. An NPC's trait shows its face and a Descension level its number; any other source has an empty mark. */
const SEALS: Record<string, string> = {
  AETHER: ROGUE_ICONS.gem, CUSTOM: ROGUE_ICONS.cog, EVENT: ROGUE_ICONS.event, CHEST: ROGUE_ICONS.chest, WOUND: ROGUE_ICONS.wound,
  WRATHFUL: ROGUE_ICONS.flame, CURSED: ROGUE_ICONS.curse, ENDLESS: ROGUE_ICONS.reroll,
};

/** A trait's mark. explained gives it the tooltip that says where the trait came from. */
export function Seal({ trait, explained }: { trait: Trait; explained?: boolean }) {
  const face = trait.avatar != null;
  const icon = SEALS[trait.source];
  return (
    <span class={face ? 'rgt-seal face' : 'rgt-seal'} style={face ? { backgroundImage: `url("${avatarUrl(trait.avatar!)}")` } : undefined}
      {...(explained ? { 'data-term': trait.term, 'data-tip': trait.tip, tabIndex: 0 } : {})}>
      {face ? null : trait.mark ?? (icon && <Icon d={icon} cls="rg-ic" />)}
    </span>
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
            <h1><Icon d={ROGUE_ICONS.trait} cls="rg-ic" />{t('lblWebRogueYourTraits')}</h1>
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
                <img alt={c.name} data-image={c.image} src={imageUrl(c.image)} />
                <figcaption>{c.type && <Words parts={[c.type]} />}</figcaption>
              </figure>
            ))}
          </div>
        </aside>
      </div>
    </div>
  );
}
