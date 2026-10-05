// Quest's Tournaments page: the drafts on offer as tiles, or the one entered drawn as its bracket with the player's next match beside it

import { useState } from 'preact/hooks';
import { balance } from './campaign';
import { PricedButton } from './trade';
import { imageUrl } from './images';
import type { Actions } from './actions';
import type { Model } from './model';
import type { QuestBracket, QuestSeat, QuestTournamentRow, QuestTournaments } from './protocol';
import { t, type TextKey } from './text';

const CREDITS = 'ICO_QUEST_COINSTACK';
const PLACES: TextKey[] = ['lblWebQuestFirstPlace', 'lblWebQuestSecondPlace', 'lblWebQuestThirdPlace', 'lblWebQuestFourthPlace'];
const ROUNDS: TextKey[] = ['lblQuarterfinals', 'lblSemifinals', 'lblFinalMatch', 'lblWinner'];
const terms = (): string => [t('lblWebQuestEightPlayers'), t('lblWebQuestSingleElimination'), t('lblWebQuestBestOf', 3)].join(' · ');

function Tile({ row, credits, actions }: { row: QuestTournamentRow; credits: number; actions: Actions }) {
  return (
    <article class="qu-tourney">
      <div class="qu-tourney-packs">
        {row.packImages.map((key, i) => <img key={i} alt={row.packs[i]} src={imageUrl(key)} onError={e => { e.currentTarget.hidden = true; }} />)}
      </div>
      <div class="qu-tourney-t">
        <b>{row.title}</b>
        <span>{row.packs.join(' | ')}</span>
        <span>{terms()}</span>
      </div>
      <div class="qu-tourney-foot">
        <PricedButton label={t('lblWebQuestEnter')} icon={CREDITS} iconLabel={t('lblCredits')} size={16} cost={row.fee} have={credits}
          onClick={() => actions.questEnter(row.title)} />
      </div>
    </article>
  );
}

function Offered({ page, credits, actions }: { page: QuestTournaments; credits: number; actions: Actions }) {
  return (
    <div class="qu-list qu-tourneys">
      <div class="qu-list-head">
        <h2>{t('lblTournaments')}</h2><p>{t('lblSelectaTournament')}</p>
        <span class="sp" />
        <button disabled={page.tokens === 0} title={t('btnSpendTokenTT')} onClick={() => actions.questToken()}>{`${t('btnSpendToken')} (${page.tokens})`}</button>
      </div>
      <div class="qu-status">
        <span class="cq-kicker">{t('lblPastResults')}</span>
        {page.placings.map((n, i) => <span key={i} class="cq-chip">{t(PLACES[i])}<b>{n === 1 ? t('lblWebQuestOneTime') : t('lblWebQuestTimes', n)}</b></span>)}
      </div>
      {page.offered.length === 0 ? <p class="muted">{t('lblNoTournaments')}</p> : (
        <div class="qu-tourney-grid">
          {page.offered.map(row => <Tile key={row.title} row={row} credits={credits} actions={actions} />)}
        </div>
      )}
    </div>
  );
}

/** Fixed geometry, since eight players is the only size: each round's matches sit midway between the two matches feeding them. */
const SEAT = 40;
const COL = 216;
const GAP = 56;
const PAIR = 128;

function pairCentre(round: number, pair: number): number {
  return round === 0 ? pair * PAIR + SEAT + 1 : (pairCentre(round - 1, 2 * pair) + pairCentre(round - 1, 2 * pair + 1)) / 2;
}

function seatTop(round: number, index: number): number {
  return round === 3 ? pairCentre(2, 0) - SEAT / 2 : pairCentre(round, Math.floor(index / 2)) - SEAT - 1 + (index % 2) * (SEAT + 2);
}

function Seat({ seat, round, index, me }: { seat: QuestSeat; round: number; index: number; me: string }) {
  const name = seat.you ? me : seat.name;
  const cls = ['qu-seat', seat.state, seat.you ? 'you' : '', name ? '' : 'empty'].filter(Boolean).join(' ');
  return (
    <div class={cls} style={{ top: `${seatTop(round, index)}px`, left: `${round * (COL + GAP)}px`, width: `${COL}px` }}>
      {name ? <>
        <span class="qu-av" aria-hidden="true">{name.charAt(0)}</span>
        <span class="n">{name}</span>
        {seat.you && <span class="qu-you">{t('lblYou')}</span>}
      </> : <i>{t('lblUndetermined')}</i>}
    </div>
  );
}

/** The bracket: each match's line runs from its winner, or from between its two seats while it is open, to the seat it feeds. */
function Bracket({ bracket, me }: { bracket: QuestBracket; me: string }) {
  const lines = [];
  for (let round = 0; round < 3; round++) {
    const seats = bracket.rounds[round];
    for (let pair = 0; pair < seats.length / 2; pair++) {
      const won = [2 * pair, 2 * pair + 1].find(i => seats[i].state === 'won');
      const from = won === undefined ? pairCentre(round, pair) : seatTop(round, won) + SEAT / 2;
      const to = seatTop(round + 1, pair) + SEAT / 2;
      const x = round * (COL + GAP) + COL;
      const cls = won === undefined ? 'open' : seats[won].you ? 'you' : 'won';
      lines.push(<path key={`${round}-${pair}`} class={cls} d={`M${x} ${from}H${x + GAP / 2}V${to}H${x + GAP}`} />);
    }
  }
  const height = pairCentre(0, 3) + SEAT + 2;
  return (
    <div class="qu-bracket-wrap">
      <div class="qu-bracket-heads">
        {ROUNDS.map(k => <span key={k} style={{ width: `${COL}px` }}>{t(k)}</span>)}
      </div>
      <div class="qu-bracket" style={{ height: `${height}px`, width: `${4 * COL + 3 * GAP}px` }}>
        <svg class="qu-lines" width={4 * COL + 3 * GAP} height={height} aria-hidden="true">{lines}</svg>
        {bracket.rounds.map((seats, round) => seats.map((seat, i) => <Seat key={`${round}-${i}`} seat={seat} round={round} index={i} me={me} />))}
      </div>
    </div>
  );
}

function Panel({ bracket, actions }: { bracket: QuestBracket; actions: Actions }) {
  const [leaving, setLeaving] = useState(false);
  const playing = bracket.started && bracket.next !== null && bracket.next !== undefined;
  const over = bracket.started && !playing;
  return (
    <aside class="cq-side">
      <div class="cq-panel">
        <div class="cq-where"><span class="cq-kicker">{bracket.title}</span></div>
        <h3>{!bracket.started ? t('lblWebQuestBuildDeck') : playing ? t('lblWebQuestNextMatch') : t('lblCollectPrizes')}</h3>
        <div class="cq-chips"><span class="cq-chip">{t('lblWebQuestBestOf', 3)}</span></div>
        {!bracket.started && <p class="cq-desc">{t('lblWebQuestBuildDeckLine')}</p>}
        <dl class="qu-facts">
          {playing && <><dt>{t('lblWebQuestOpponent')}</dt><dd>{bracket.next || t('lblUndetermined')}</dd></>}
          {bracket.started && <><dt>{t('lblWebQuestPlacing')}</dt><dd>{bracket.placing}</dd></>}
        </dl>
      </div>
      <div class="cq-foot">
        <button onClick={() => actions.questTournamentDeck()}>{t('btnEditDeck')}</button>
        {!bracket.started ? <button class="primary cq-big" onClick={() => actions.questTournamentStart()}>{t('lblWebQuestStartTournament')}</button>
          : playing ? <button class="primary cq-big" onClick={() => actions.questTournamentNext()}>{t('btnStartMatchSmall')}</button>
          : <button class="primary cq-big" onClick={() => actions.questTournamentLeave()}>{t('lblCollectPrizes')}</button>}
        {!over && <button onClick={() => setLeaving(true)}>{t('btnLeaveTournament')}</button>}
      </div>
      {leaving && (
        <div class="backdrop" onClick={e => { if (e.target === e.currentTarget) setLeaving(false); }}>
          <div class="dialog" role="alertdialog" aria-label={t('btnLeaveTournament')}>
            <h3>{t('btnLeaveTournament')}</h3>
            <p class="hint qu-lines-text">{t(bracket.started ? 'lblLeaveTournamentDraftWarning2' : 'lblLeaveTournamentDraftWarning1')}</p>
            <div class="actions">
              <button onClick={() => setLeaving(false)}>{t('lblCancel')}</button>
              <button class="danger" onClick={() => { setLeaving(false); actions.questTournamentLeave(); }}>{t('lblLeave')}</button>
            </div>
          </div>
        </div>
      )}
    </aside>
  );
}

export function Tournaments({ model, page, actions }: { model: Model; page: QuestTournaments; actions: Actions }) {
  if (!page.bracket) return <Offered page={page} credits={balance(model.campaignBar, CREDITS)} actions={actions} />;
  return (
    <div class="cq-map">
      <Bracket bracket={page.bracket} me={model.playerName} />
      <Panel bracket={page.bracket} actions={actions} />
    </div>
  );
}
