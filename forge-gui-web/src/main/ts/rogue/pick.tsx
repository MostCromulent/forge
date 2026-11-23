// Card picks drawn as a scene: choosing n of a set of cards beside the deck they go into, a battle's card reward, and choosing any commander from the catalogue

import { Fragment } from 'preact';
import { useLayoutEffect, useRef, useState } from 'preact/hooks';
import { Backdrop } from '../dialog';
import { imageUrl, skinIconUrl } from '../images';
import { reducedMotion } from '../dom';
import { avatarUrl } from '../avatarsleeves';
import { isPortrait } from '../form';
import { useDebounced, useSceneKeys } from '../hooks';
import { useCataloguePages } from '../deck/catalogue';
import { t, formatNumber } from '../text';
import { Icon } from '../icons';
import { playSound } from '../audio';
import { ROGUE_ICONS } from './icons';
import { Scene } from './scene';
import { TraitCard } from './traits';
import type { Actions } from '../actions';
import type { Model } from '../model';
import type { AskCards, AskChoice, AskCommander, PickCard, PickDeck } from '../protocol';

/** The catalogue's source that reads the commanders the open question offers. */
const COMMANDERS = 'rogue-commanders';
const COLOURS = ['W', 'U', 'B', 'R', 'G', 'C'];

function Card({ name, image, at, toggle }: { name: string; image: string; at: string | null; toggle: () => void }) {
  return (
    <button class={`rg-card sm ${at ? 'on' : ''}`} aria-pressed={!!at} data-image={image} onClick={toggle}>
      <img alt={name} src={imageUrl(image)} />
      {at && <span class="no">{at}</span>}
    </button>
  );
}

/** The picks merged by name, in the order they were first picked. */
function chosen(picked: PickCard[]): { name: string; count: number; cmc: number }[] {
  const byName = new Map<string, { name: string; count: number; cmc: number }>();
  for (const c of picked) {
    const line = byName.get(c.name);
    if (line) line.count++;
    else byName.set(c.name, { name: c.name, count: 1, cmc: c.cmc });
  }
  return [...byName.values()];
}

/** The deck the picks go into, with them listed first, or come out of, with them crossed out. */
function DeckColumn({ deck, picked, removing }: { deck: PickDeck; picked: PickCard[]; removing: boolean }) {
  const out = new Set(picked.map(c => c.name));
  return (
    <div class="rg-deckcol">
      <header><b>{deck.title}</b><span>{t('lblWebNCards', deck.total + (removing ? -picked.length : picked.length))}</span></header>
      <div class="ls">
        {!removing && picked.length > 0 && <>
          <div class="rg-ls-h">{t('lblWebRogueChosen')}</div>
          {chosen(picked).map(c => <div key={c.name} class="rg-li in"><span>{c.count}</span><span>{c.name}</span><em>{c.cmc}</em></div>)}
        </>}
        {deck.groups.map(g => <Fragment key={g.heading}>
          <div class="rg-ls-h">{g.heading}</div>
          {g.cards.map(c => (
            <div key={c.name} class={`rg-li ${removing && out.has(c.name) ? 'out' : ''}`}><span>{c.count}</span><span>{c.name}</span><em>{c.mv}</em></div>
          ))}
        </Fragment>)}
      </div>
    </div>
  );
}

/**
 * Choosing between min and max of the cards offered. A card is picked by its id, so two equal copies can both be picked.
 * fold, set in a run, offers View Map, and View Deck opens the run's deck. Before a run, View Deck shows the deck column on a phone.
 */
export function CardPick({ asked, answer, fold, viewDeck }: { asked: AskCards; answer: (value: unknown) => void; fold?: () => void; viewDeck?: () => void }) {
  const [picked, setPicked] = useState<number[]>([]);
  const [deckOpen, setDeckOpen] = useState(false);
  const phone = isPortrait();
  const cards = picked.map(id => asked.cards[id]);
  const removing = asked.kind === 'REMOVE';
  const toggle = (id: number) => setPicked(p => (p.includes(id) ? p.filter(x => x !== id) : p.length < asked.max ? [...p, id] : p));
  const n = picked.length;
  const subtitle = asked.min === asked.max ? t('lblRogueFixedCardSelectionCount', asked.subtitle ?? '', n, asked.max)
    : t('lblRogueVariableCardSelectionCount', asked.subtitle ?? '', n, asked.min, asked.max);
  const short = asked.min - n;
  const ask = <div class="rg-ask"><h3>{asked.title}</h3><span>{subtitle}</span></div>;
  const grid = asked.cards.map(c => {
    const at = picked.indexOf(c.id);
    return <Card key={c.id} name={c.name} image={c.image} at={at < 0 ? null : String(at + 1)} toggle={() => toggle(c.id)} />;
  });
  const showDeck = fold ? viewDeck : phone && asked.deck ? () => setDeckOpen(true) : undefined;
  const foot = <>
    {!fold && <button onClick={() => setPicked([])}>{t('lblWebRogueClear')}</button>}
    <button class="primary" disabled={short > 0} onClick={() => answer(picked)}>
      {short > 0 ? t(phone ? 'lblWebRogueMore' : 'lblWebRogueChooseMore', short) : t('lblChoose')}
    </button>
  </>;
  return <>
    <Scene art={asked.art} fold={fold} viewDeck={showDeck} foot={foot}>
      {phone || !asked.deck ? <div class="rg-pick">{ask}<div class="rg-cards rg-pgrid">{grid}</div></div> : (
        <div class="rg-sheet">
          <div class="main">{ask}<div class="grid">{grid}</div></div>
          <DeckColumn deck={asked.deck} picked={cards} removing={removing} />
        </div>
      )}
    </Scene>
    {deckOpen && asked.deck && (
      <Backdrop layer="rg-dialog" dismiss={() => setDeckOpen(false)}>
        <div class="dialog" role="dialog" aria-label={asked.deck.title}>
          <DeckColumn deck={asked.deck} picked={cards} removing={removing} />
          <div class="actions"><button class="primary" onClick={() => setDeckOpen(false)}>{t('lblWebRogueBackTo', asked.title)}</button></div>
        </div>
      </Backdrop>
    )}
  </>;
}

/**
 * A battle's card reward: up to max of the cards dealt, none allowed, or a reroll at the cost the server names. The cards are dealt face down
 * and turn over one by one, as desktop turns them. Mythic is marked only among other cards, so a reward of mythics alone has no marks.
 */
export function RewardPick({ asked, answer, fold, viewDeck }: { asked: AskCards; answer: (value: unknown) => void; fold?: () => void; viewDeck?: () => void }) {
  const [picked, setPicked] = useState<number[]>([]);
  const dealt = useRef<HTMLDivElement>(null);
  const phone = isPortrait();
  const n = picked.length;
  const marked = !asked.cards.every(c => c.mythic);
  const toggle = (id: number) => setPicked(p => (p.includes(id) ? p.filter(x => x !== id) : p.length < asked.max ? [...p, id] : p));
  useLayoutEffect(() => {
    playSound({ t: 'sound', name: 'flip_card', sync: false });
    if (reducedMotion()) return;
    // Desktop turns the first card 200 ms after the dialog opens, then one every 100 ms
    dealt.current!.querySelectorAll('.rg-card').forEach((card, k) => card.animate(
      [{ opacity: 0, transform: 'perspective(700px) rotateY(90deg)' }, { opacity: 1, transform: 'none' }],
      { duration: 260, delay: 200 + k * 100, easing: 'ease-out', fill: 'backwards' }));
  }, []);
  const foot = <>
    {asked.rerollCost != null && (
      <button disabled={!asked.rerollEnabled} onClick={() => answer('reroll')}>
        <Icon d={ROGUE_ICONS.reroll} cls="rg-ic" />
        {phone ? ` ${asked.rerollCost}` : <>{` ${t('lblWebRogueReroll')} `}<img class="camp-shard" alt="" src={skinIconUrl('ICO_QUEST_COIN')} />{asked.rerollCost}</>}
      </button>
    )}
    <button class="primary" onClick={() => answer(picked)}>
      {!n ? t('lblWebRogueTakeNone') : phone ? t('lblWebRogueAdd', n) : t(n === 1 ? 'lblWebRogueAddCard' : 'lblWebRogueAddCards', n)}
    </button>
  </>;
  return (
    <Scene art={asked.art} fold={fold} viewDeck={viewDeck} foot={foot}>
      <div class="rg-pick">
        <div class="rg-ask"><h3>{t('lblRogueVictoryReward', asked.title)}</h3><span>{t('lblRogueCardRewardSelectionCount', asked.max, n)}</span></div>
        <div class="rg-cards seven" ref={dealt}>
          {asked.cards.map(c => {
            const at = picked.indexOf(c.id);
            return (
              <button key={c.id} class={at < 0 ? 'rg-card' : 'rg-card on'} aria-pressed={at >= 0} data-image={c.image} onClick={() => toggle(c.id)}>
                <img alt={c.name} src={imageUrl(c.image)} />
                {marked && c.mythic && <span class="myth">{t('lblMythic')}</span>}
                {at >= 0 && <span class="no">{at + 1}</span>}
              </button>
            );
          })}
        </div>
      </div>
    </Scene>
  );
}

/** A chest's rewards, each a card to take, with Reroll and Skip. A phone words Reroll by its count alone. */
export function LootPick({ choice, answer, fold, viewDeck }: { choice: AskChoice; answer: (value: unknown) => void; fold?: () => void; viewDeck?: () => void }) {
  const phone = isPortrait();
  const pick = (i: number) => {
    playSound({ t: 'sound', name: 'token', sync: true });
    answer(i);
  };
  useSceneKeys(i => { if (choice.options[i]?.enabled) pick(i); }, () => {});
  const foot = <>
    {choice.rerolls > 0 && (
      <button onClick={() => answer('reroll')}><Icon d={ROGUE_ICONS.reroll} cls="rg-ic" />{` ${phone ? choice.rerolls : t('lblRogueRerollsRemaining', choice.rerolls)}`}</button>
    )}
    {choice.skip && <button onClick={() => answer('skip')}>{t(phone ? 'lblSkip' : 'lblRogueSkipLoot')}</button>}
  </>;
  return (
    <Scene art={choice.art} fold={fold} viewDeck={viewDeck} foot={choice.rerolls > 0 || choice.skip ? foot : null}>
      <div class="rg-pick">
        <div class="rg-ask"><h3>{choice.heading ?? choice.title}</h3></div>
        <div class={phone ? 'rg-cards rg-ploot' : 'rg-cards'} style={phone ? undefined : { gap: '26px' }}>
          {choice.options.map((o, i) => (
            <button key={i} class="rg-card" style={phone ? undefined : { width: '232px' }} disabled={!o.enabled} onClick={() => pick(i)}>
              <TraitCard trait={o.trait!} />
            </button>
          ))}
        </div>
      </div>
    </Scene>
  );
}

/**
 * Choosing any commander, a page of the catalogue at a time, with its portrait beside it; or its partner, which can be skipped.
 * Cancel cancels the run's start.
 */
export function CommanderPick({ asked, model, actions, answer }: { asked: AskCommander; model: Model; actions: Actions; answer: (value: unknown) => void }) {
  const [typed, setTyped] = useState('');
  const text = useDebounced(typed);
  const [colours, setColours] = useState('');
  const [pick, setPick] = useState<{ key: string; name: string; image: string } | null>(null);
  const [avatar, setAvatar] = useState(asked.avatars[0] ?? null);
  const phone = isPortrait();
  const partner = asked.skip;
  const page = model.catalogue?.source === COMMANDERS ? model.catalogue : null;
  const query = { text, colours, type: 'any', filters: '', sort: 'name', showAll: true, source: COMMANDERS };
  const more = useCataloguePages(actions, query, page, [text, colours]);
  const grid = (page?.rows ?? []).map(r => (
    <Card key={r.key} name={r.name} image={r.image} at={pick?.key === r.key ? '✓' : null} toggle={() => setPick(pick?.key === r.key ? null : { key: r.key!, name: r.name, image: r.image })} />
  ));
  const head = partner ? <div class="rg-ask"><h3>{asked.title}</h3></div> : (
    <div class="rg-head" style={{ padding: '0 6px' }}>
      <input placeholder={t('lblSearch')} value={typed} style={{ width: '240px' }} onInput={e => setTyped(e.currentTarget.value)} />
      <span class="seg" role="group">
        {COLOURS.map(c => (
          <button key={c} aria-pressed={colours.includes(c)} onClick={() => setColours(colours.includes(c) ? colours.replace(c, '') : colours + c)}>{c}</button>
        ))}
      </span>
      <span class="sp" />
      <span style={{ color: 'var(--muted)' }}>{t('lblWebRogueCommanderCount', formatNumber(page?.total ?? asked.total))}</span>
    </div>
  );
  const portraits = asked.avatars.length > 0 && (
    <div class="rg-sec">
      <h4>{t('lblWebRoguePortrait')}</h4>
      <div class="rg-avs">
        {asked.avatars.map(a => <img key={a} class={a === avatar ? 'on' : ''} alt="" src={avatarUrl(a)} tabIndex={0} aria-pressed={a === avatar}
          onClick={() => setAvatar(a)} onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') setAvatar(a); }} />)}
      </div>
    </div>
  );
  const foot = <>
    <button onClick={() => answer(null)}>{t('lblCancel')}</button>
    {partner && <button onClick={() => answer('skip')}>{t('lblSkip')}</button>}
    <button class="primary" disabled={!pick} onClick={() => answer({ commander: Number(pick!.key), avatar: asked.avatars.length ? avatar : null })}>
      {t('lblRogueChooseCommander')}
    </button>
  </>;
  return (
    <Scene art={asked.art ?? pick?.image} foot={foot} onScroll={phone ? more : undefined}>
      {phone ? <div class="rg-pick">{head}<div class="rg-cards rg-pgrid">{grid}</div>{portraits}</div> : (
        <div class={partner ? 'rg-sheet one' : 'rg-sheet'}>
          <div class="main">{head}<div class="grid" onScroll={more}>{grid}</div></div>
          {!partner && (
            <div class="rg-deckcol">
              <header><b>{pick?.name ?? asked.title}</b></header>
              <div class="ls" style={{ padding: '14px', display: 'grid', gap: '14px', alignContent: 'start' }}>{portraits}</div>
            </div>
          )}
        </div>
      )}
    </Scene>
  );
}
