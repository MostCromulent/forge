// A quest's Spell Shop: the cards for sale and the player's own on the trade page, in Quest's words, with the packs and decks for sale above the cards

import { TYPES } from '../deck/catalogue';
import { TradePage, pickOf, type Pick, type TradeWords } from '../campaign/trade';
import { imageUrl, skinIconUrl } from '../images';
import type { Actions } from '../actions';
import type { Model } from '../model';
import type { Product } from '../protocol';
import { t } from '../text';

/** Where a sentence puts the credits' coin. */
const COIN = '{CR}';
const CREDITS = 'ICO_QUEST_COINSTACK';

/** The shop's packs, boxes and decks, each picked whole as a card is. */
function Products({ products, picked, toggle }: { products: Product[]; picked: Map<string, Pick>; toggle: (pick: Pick) => void }) {
  if (!products.length) return null;
  return (
    <div class="qu-products">
      <span class="qu-products-head">{t('lblWebQuestProducts')}</span>
      {products.map(p => (
        <button key={p.key} class="qu-product" aria-pressed={picked.has(p.key)}
          onClick={() => toggle({ key: p.key, name: p.name, value: p.price, count: 1, most: 1 })}>
          <span class="qu-product-pic"><img loading="lazy" alt="" src={imageUrl(p.image)} onError={e => { e.currentTarget.hidden = true; }} /></span>
          <span class="qu-product-kind">{p.kind}</span>
          <b>{p.name}</b>
          <span class="qu-price"><img alt={t('lblCredits')} src={skinIconUrl(CREDITS)} />{p.price.toLocaleString('en-GB')}</span>
        </button>
      ))}
    </div>
  );
}

export function Shop({ model, actions }: { model: Model; actions: Actions }) {
  const words: Record<string, TradeWords> = {
    shop: {
      label: t('lblCardsForSale'),
      verb: n => t('lblWebQuestBuyN', n),
      row: price => `${COIN} ${price}`,
      confirm: (_, total) => t('lblWebQuestBuyConfirm', COIN, total),
      count: n => t('lblWebCatalogueLeft', n),
    },
    inventory: {
      label: t('lblYourCards'),
      verb: n => t('lblWebQuestSellN', n),
      row: price => `${COIN} ${price}`,
      confirm: (_, total) => t('lblWebQuestSellConfirm', COIN, total),
      count: n => `×${n}`,
    },
  };
  const extras = model.trading?.extras ?? [];
  return <TradePage model={model} actions={actions} icon={CREDITS} iconLabel={t('lblCredits')} token={COIN} words={words}
    types={TYPES} blocked="" top={(source, picked, toggle, askAbout) => (source === 'shop'
      ? <Products products={model.trading?.products ?? []} picked={picked} toggle={toggle} />
      : (
        <div class="qu-products qu-extras">
          <button disabled={!extras.length} onClick={() => askAbout(extras.map(row => pickOf(row, row.count ?? 1)))}>{t('lblSellAllExtras')}</button>
        </div>
      ))} />;
}
