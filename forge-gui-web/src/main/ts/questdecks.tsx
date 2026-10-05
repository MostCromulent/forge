// Quest's Decks page: every deck of the quest as a plate, and beside them the one being looked at with its curve, its list and what can be done with it

import { useEffect, useState } from 'preact/hooks';
import { DeckPanel, Plate } from './deckplate';
import { Curve } from './deckhalf';
import { CardGroup } from './importer';
import type { Actions } from './actions';
import type { Model } from './model';
import type { QuestDecks } from './protocol';
import { t } from './text';

/** A deck's name being typed: for a new deck, or a new name for one. */
function NameForm({ initial, submit, done, cancel }: { initial: string; submit: string; done: (name: string) => void; cancel: () => void }) {
  return (
    <form class="qu-name" onSubmit={e => {
      e.preventDefault();
      const name = new FormData(e.currentTarget).get('name')?.toString().trim();
      if (name) done(name);
    }}>
      <input name="name" defaultValue={initial} maxLength={60} placeholder={t('lblDeckName')} aria-label={t('lblDeckName')} autoFocus
        onKeyDown={e => { if (e.key === 'Escape') cancel(); }} />
      <button class="primary" type="submit">{submit}</button>
      <button type="button" onClick={cancel}>{t('lblCancel')}</button>
    </form>
  );
}

export function Decks({ page, model, actions }: { page: QuestDecks; model: Model; actions: Actions }) {
  const [chosen, setChosen] = useState<string | null>(null);
  const [naming, setNaming] = useState(false);
  /** What the panel's foot is doing instead of offering its buttons. */
  const [asking, setAsking] = useState<'rename' | 'delete' | null>(null);
  const deck = page.decks.find(d => d.name === chosen) ?? page.decks.find(d => d.current) ?? page.decks[0];
  // The curve and list are the deck's own details, asked for whenever the deck shown or its size changes
  useEffect(() => {
    if (deck) actions.questDeckView(deck.name);
    setAsking(null);
  }, [deck?.name, deck?.size, deck?.sideboard]);
  const details = deck && model.deckDetails?.key === `quest:${deck.name}` ? model.deckDetails : null;
  return (
    <div class="cq-party">
      <div class="cq-party-list">
        <section>
          <h4>{t('lblQuestDecks')}<span>{page.decks.length}</span></h4>
          <div class="cq-cmd-grid">
            {naming
              ? <div class="qu-new"><NameForm initial="" submit={t('lblCreate')} done={name => { setNaming(false); setChosen(name); actions.questDeckNew(name); }}
                  cancel={() => setNaming(false)} /></div>
              : <button class="qu-new" onClick={() => setNaming(true)}><span class="plus" aria-hidden="true">+</span><b>{t('lblNewDeck')}</b></button>}
            {page.decks.map(d => (
              <Plate key={d.name} name={d.name} image={d.image} colors={d.colors} selected={d.current} tag={t('lblCurrentDeck2')}
                pressed={d === deck} pick={() => setChosen(d.name)}
                lines={[d.problem ? <span class="cq-warn" title={d.problem}>{t('lblInvalidDeck')} · {d.size}</span> : t('lblWebNCards', d.size)]} />
            ))}
          </div>
        </section>
      </div>
      {deck && (
        <DeckPanel image={deck.image} title={deck.name} problem={deck.problem}
          chips={<>
            {deck.current && <span class="cq-chip brass">{t('lblCurrentDeck2')}</span>}
            <span class="cq-chip">{t('lblWebNCards', deck.size)}</span>
            {deck.sideboard > 0 && <span class="cq-chip">{t('lblSideboard')}: {deck.sideboard}</span>}
          </>}
          details={details && deck.size > 0 && <>
            <Curve curve={details.stats.curve} creatures={details.stats.creatures} px={44} />
            <div class="dk-cards">{details.main.map(g => <CardGroup key={g.heading} heading={g.heading} cards={g.cards} />)}</div>
          </>}>
          {asking === 'rename'
            ? <NameForm initial={deck.name} submit={t('lblRename')} cancel={() => setAsking(null)}
                done={to => { setAsking(null); setChosen(to); actions.questDeckRename(deck.name, to); }} />
            : asking === 'delete'
              ? <div class="qu-ask">
                  <span>{t('lblConfirmDelete')} "{deck.name}"?</span>
                  <div class="cq-two">
                    <button onClick={() => setAsking(null)}>{t('lblCancel')}</button>
                    <button class="danger" onClick={() => { setAsking(null); setChosen(null); actions.questDeckDelete(deck.name); }}>{t('lblDelete')}</button>
                  </div>
                </div>
              : <>
                  <div class="cq-two">
                    <button onClick={() => actions.questDeckEdit(deck.name)}>{t('btnEditDeck')}</button>
                    <button class="primary" disabled={deck.current} onClick={() => actions.questDeckCurrent(deck.name)}>{t('lblCurrentDeck2')}</button>
                  </div>
                  <div class="cq-two">
                    <button onClick={() => setAsking('rename')}>{t('lblRename')}</button>
                    <button class="danger" onClick={() => setAsking('delete')}>{t('lblDelete')}</button>
                  </div>
                </>}
        </DeckPanel>
      )}
    </div>
  );
}
