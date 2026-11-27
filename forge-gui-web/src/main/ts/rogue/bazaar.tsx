// A bazaar: cards, and traits and carry cards on a tab of their own, priced in gold. Picked items are added up in the foot

import { useEffect, useLayoutEffect, useRef, useState } from 'preact/hooks';
import { Icon } from '../icons';
import { hideBroken, imageUrl } from '../images';
import { isPortrait } from '../form';
import { playSound } from '../audio';
import { t } from '../text';
import { Sprite, glyph } from './kit';
import { Scene } from './scene';
import { TERM_ICONS, Words } from './terms';
import { Seal, TraitCard } from './traits';
import { shopTotal } from './shop';
import { deal } from './motion';
import type { AskShop, ShopItem } from '../protocol';

const coin = (alt: string, cls?: string) => <Sprite k="coin" alt={alt} cls={cls} />;

/** An item as a tile: a card with its carry type or Mythic over it, or a trait as a card, and its price. A struck price is a discount. */
function Item({ item, on, no, toggle }: { item: ShopItem; on: boolean; no: boolean; toggle: () => void }) {
  const icon = item.type && TERM_ICONS[item.type.term!];
  return (
    <button class={['rg-item', on && 'on', no && 'no'].filter(Boolean).join(' ')} aria-pressed={on} aria-label={item.name} onClick={toggle}>
      {item.type ? <span class="ty">{icon && <Icon d={icon} cls="rg-ic" />}{item.type.text}</span> : item.mythic && <span class="ty">{t('lblMythic')}</span>}
      {item.trait ? <TraitCard trait={item.trait} small /> : <img onError={hideBroken} class="c" alt="" data-image={item.image} src={imageUrl(item.image!)} />}
      <span class="pr">{coin('Gold')}{item.was > item.price && <s>{item.was}</s>}{item.price}</span>
    </button>
  );
}

/** A trait as a row, as a phone lists them. */
function Row({ item, on, no, toggle }: { item: ShopItem; on: boolean; no: boolean; toggle: () => void }) {
  return (
    <button class={['rg-prow', on && 'on', no && 'no'].filter(Boolean).join(' ')} aria-pressed={on} onClick={toggle}>
      <Seal trait={item.trait!} />
      <span><b>{item.name}</b><em><Words parts={item.trait!.text} /></em></span>
      <span class="pr">{coin('')}{item.price}</span>
    </button>
  );
}

/**
 * The bazaar. With both cards and special offers each has its tab, and the tab shown is told with the answer, so a reroll keeps it.
 * A picked item can always be put back, and one that no longer fits the gold left is dimmed and cannot be picked.
 */
export function Bazaar({ id, asked, answer, fold, viewDeck }: { id: number; asked: AskShop; answer: (value: unknown) => void; fold?: () => void; viewDeck?: () => void }) {
  const cards = asked.items.filter(i => !i.special);
  const special = asked.items.filter(i => i.special);
  const tabbed = cards.length > 0 && special.length > 0;
  const [tab, setTab] = useState(tabbed ? Math.min(asked.tab, 1) : cards.length ? 0 : 1);
  const [picked, setPicked] = useState<number[]>([]);
  const stock = useRef<HTMLDivElement>(null);
  const traits = useRef<HTMLDivElement>(null);
  const phone = isPortrait();
  useEffect(() => playSound({ t: 'sound', name: 'coins_drop', sync: false }), []);
  const { cost, left, dimmed } = shopTotal(asked.items, picked, asked.gold);
  const toggle = (id: number) => setPicked(p => (p.includes(id) ? p.filter(x => x !== id) : dimmed.has(id) ? p : [...p, id]));
  const tile = (item: ShopItem) => <Item key={item.id} item={item} on={picked.includes(item.id)} no={dimmed.has(item.id)} toggle={() => toggle(item.id)} />;
  const shown = tab ? special : cards;
  const send = (selected: number[], reroll: boolean) => answer({ selected, reroll, tab });
  const reroll = asked.rerollCost != null && (
    <button disabled={!asked.rerollEnabled} onClick={() => send([], true)}>
      {glyph('reroll')}{phone ? ` ${asked.rerollCost}` : <>{` ${t('lblWebRogueReroll')} `}{coin('', 'camp-shard')}{asked.rerollCost}</>}
    </button>
  );
  const foot = <>
    {reroll}
    {!phone && (
      <span class="rg-sum">
        <span>{t('lblRogueBazaarCost', '')}<b>{cost}</b></span>
        <span>{t('lblRogueRemainingGold', '')}{coin('Gold')}<b>{left}</b></span>
      </span>
    )}
    <button onClick={() => send([], false)}>{t('lblSkip')}</button>
    <button class="primary" disabled={!picked.length} onClick={() => send(picked, false)}>
      {phone ? t('lblWebRogueBuyCost', cost) : t(special.length ? 'lblRogueBuySelected' : 'lblRogueBuySelectedCards')}
    </button>
  </>;
  // A phone lists the traits as rows above the carry cards
  const rows = phone && tab ? shown.filter(i => i.trait) : [];
  // Each tab is dealt the first time it shows, the special offers each dropping onto a halo, as mobile deals its one list
  useLayoutEffect(() => {
    const tiles = [...traits.current?.children ?? [], ...stock.current!.children] as HTMLElement[];
    deal(id, tab, tiles, () => tab === 1);
  }, [tab]);
  return (
    // A phone's foot has room for four buttons, so View Map is left out there
    <Scene art={asked.art} fold={phone ? undefined : fold} viewDeck={viewDeck} foot={foot}>
      <div class="rg-baz">
        <div class="rg-baz-head">
          <h3>{asked.title}</h3>
          <p>{t(tab ? 'lblRogueSelectOffersToBuy' : 'lblRogueSelectCardsToBuy')}</p>
          {tabbed && (
            <span class="seg">
              <button aria-pressed={tab === 0} onClick={() => setTab(0)}>{t('lblCards')}</button>
              <button aria-pressed={tab === 1} onClick={() => setTab(1)}>{t('lblRogueTraitsAndCarryCards')}</button>
            </span>
          )}
        </div>
        {rows.length > 0 && (
          <div class="rg-pstock" ref={traits}>{rows.map(i => <Row key={i.id} item={i} on={picked.includes(i.id)} no={dimmed.has(i.id)} toggle={() => toggle(i.id)} />)}</div>
        )}
        <div class="rg-stock" ref={stock}>{shown.filter(i => !rows.includes(i)).map(tile)}</div>
      </div>
    </Scene>
  );
}
