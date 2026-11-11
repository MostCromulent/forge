// The adventure inventory: the player's items, which can be put on, taken off or used, and the deck slots, which can be chosen or edited

import { useEffect } from 'preact/hooks';
import { Backdrop } from '../dialog';
import { plain } from './adventure';
import type { Actions } from '../actions';
import type { AdventureInventory, AdventureItem } from '../protocol';
import { t, formatNumber } from '../text';

/** Items are drawn at twice their pixels, sharp. */
const ITEM_SCALE = 2;

/** An item's region of the items atlas, at its own pixels inside a box twice its size, scaled up sharp. */
function ItemPicture({ item }: { item: AdventureItem }) {
  if (!item.w) return <span class="adv-item-pic" />;
  const sheet = `/adv/res?path=${encodeURIComponent(item.atlas.replace(/\.atlas$/, '.png'))}`;
  return (
    <span class="adv-item-pic" style={{ width: `${item.w * ITEM_SCALE}px`, height: `${item.h * ITEM_SCALE}px` }}>
      <span class="adv-item-sprite" style={{
        width: `${item.w}px`, height: `${item.h}px`, backgroundImage: `url(${sheet})`,
        backgroundPosition: `-${item.rx}px -${item.ry}px`, transform: `scale(${ITEM_SCALE})`,
      }} />
    </span>
  );
}

export function Inventory({ page, actions, close }: { page: AdventureInventory | null; actions: Actions; close: () => void }) {
  useEffect(() => { actions.adventureInventory(); }, []);
  useEffect(() => {
    const key = (e: KeyboardEvent) => { if (e.code === 'Escape' || e.code === 'KeyI') { e.preventDefault(); close(); } };
    window.addEventListener('keydown', key);
    return () => window.removeEventListener('keydown', key);
  }, []);
  return (
    <Backdrop cls="adv-backdrop" dismiss={close}>
      <div class="dialog adv-inventory" role="dialog" aria-label={t('lblInventory')} data-testid="adventure-inventory">
        <h3>{t('lblInventory')}</h3>
        {!page ? <p class="muted">{t('lblWebAdventureReading')}</p> : <>
          <p class="adv-balances">♥ {page.life}/{page.maxLife} · ● {formatNumber(page.gold)} · ◆ {formatNumber(page.shards)}</p>
          <h4>{t('lblDeck')}</h4>
          <ul class="adv-decks">
            {page.decks.map((name, slot) => (
              <li key={slot} class={slot === page.selectedDeck ? 'current' : ''}>
                <span>{name}</span>
                {slot !== page.selectedDeck && <button onClick={() => actions.adventureSelectDeck(slot)}>{t('lblWebAdventureUseDeck')}</button>}
                <button onClick={() => { close(); actions.adventureEditDeck(slot); }}>{t('lblWebAdventureEditDeck')}</button>
              </li>
            ))}
          </ul>
          <h4>{t('lblWebAdventureItems')}</h4>
          <ul class="adv-items">
            {page.items.map(item => (
              <li key={item.id} class={item.equipped ? 'equipped' : ''}>
                <ItemPicture item={item} />
                <span class="adv-item-text"><b>{plain(item.name)}</b><span class="dim">{plain(item.description)}</span></span>
                {item.slot && <button onClick={() => actions.adventureEquip(item.id)}>{t(item.equipped ? 'lblWebAdventureTakeOff' : 'lblWebAdventurePutOn')}</button>}
                {item.usable && <button onClick={() => actions.adventureUseItem(item.id)}>{item.shards ? t('lblWebAdventureUseFor', item.shards) : t('lblWebAdventureUse')}</button>}
              </li>
            ))}
          </ul>
        </>}
        <div class="actions"><button onClick={close}>{t('lblClose')}</button></div>
      </div>
    </Backdrop>
  );
}
