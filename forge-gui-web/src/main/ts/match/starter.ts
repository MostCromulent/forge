// Who goes first. This covers the coin toss, the choice made by the toss's winner or the last game's loser, and the reveal over the board.

import { isLocal, me, players, type Model } from '../model';
import { coinInAir, readyCoin, tossCoin } from './coin';
import { playerAvatarUrl } from '../looks';
import { byId, make } from '../dom';
import { redrawUi } from '../ui';
import { t } from '../text';
import type { Actions } from '../actions';
import type { GameEvent } from '../protocol';

/** The player the game chose to take the first turn. It is kept from when that is decided until the next game. */
let firstPlayer: number | null = null;
/** The game has said who starts, but the page has not shown it yet. Showing it waits for the coin. */
let firstDue = false;
/** Whether this viewer was asked who starts, because they won the toss or lost the last game. */
let choseStarter = false;
/** The first game's toss has been shown. */
let tossed = false;

/** Tracks the toss and the game's start through these events, and shows or asks who goes first. */
export function showStarter(model: Model, actions: Actions, events: readonly GameEvent[]): void {
  for (const e of events) {
    if (e.kind === 'coinTossed') toss(model, model.localPlayers.includes(e.winner.ref));
    if (e.kind === 'gameStarted') {
      firstPlayer = e.first.ref;
      firstDue = true;
    }
  }
  const choice = model.prompt?.starterChoice;
  // The winner is asked who starts when the toss is made. That prompt can reach the page before the toss's own event
  if (choice === 'toss') toss(model, true);
  // Who goes first is shown or asked once the coin has landed
  if (!coinInAir()) {
    if (firstDue) {
      firstDue = false;
      // The player who chose the starter already knows who starts
      if (!choseStarter) revealFirst(model, firstPlayer);
    }
    if (choice && !choseStarter) {
      choseStarter = true;
      chooseFirst(model, actions, choice);
    }
  }
  // Remove the choice if it was answered with the prompt's own buttons, or if the game was conceded before anyone chose
  if (!choice || model.gameOver) document.querySelector('#first-reveal.choosing')?.remove();
}

/** Whether the player goes first and the reveal has already shown it. */
export function wentFirst(key: number): boolean {
  return firstPlayer === key && !firstDue;
}

export function resetStarter(): void {
  firstPlayer = null;
  firstDue = false;
  tossed = false;
  readyCoin();
  choseStarter = false;
}

/** Shows the toss once, only to a player at the table. It is shown on the toss event or the winner's prompt, whichever arrives first. */
function toss(model: Model, heads: boolean): void {
  const name = me(model)?.Name;
  if (tossed || name === undefined) return;
  tossed = true;
  // The page is redrawn when the coin is removed. That is when who goes first is shown or asked
  tossCoin(heads, t(heads ? 'lblYouHaveWonTheCoinToss' : 'lblYouLostTheCoinToss', name), redrawUi);
}

/** With two players the choice is play or draw, so your own face answers OK and the other face cancels. With more players a face selects that player. */
function chooseFirst(model: Model, actions: Actions, choice: string): void {
  const mine = me(model)?.$key;
  const two = players(model).length === 2;
  revealFirst(model, null, t(choice === 'toss' ? 'lblWebBoardWonCoinToss' : 'lblWebBoardLostLastGame'), key => {
    if (!two) actions.selectPlayer(key);
    else if (key === mine) actions.ok();
    else actions.cancel();
    document.getElementById('first-reveal')?.remove();
  });
}

/** Shows who goes first over the board. With `pick` it asks instead. The faces are then buttons, and the reveal stays until one is chosen. */
function revealFirst(model: Model, first: number | null, said?: string, pick?: (key: number) => void): void {
  const everyone = players(model);
  const starter = everyone.find(p => p.$key === first);
  if (!starter && !pick) return;
  document.getElementById('first-reveal')?.remove();
  const reveal = document.createElement('div');
  reveal.id = 'first-reveal';
  reveal.setAttribute('role', pick ? 'dialog' : 'status');
  reveal.classList.toggle('choosing', !!pick);
  const faces = make('div', 'reveal-faces');
  for (const p of everyone) {
    const face = document.createElement(pick ? 'button' : 'div');
    face.className = p.$key === first ? 'reveal-face first' : 'reveal-face';
    if (pick) face.onclick = () => pick(p.$key);
    const url = playerAvatarUrl(p);
    const picture = document.createElement(url ? 'img' : 'span');
    if (picture instanceof HTMLImageElement) {
      picture.alt = '';
      picture.src = url;
    } else {
      picture.textContent = (p.Name ?? '?').slice(0, 1).toUpperCase();
    }
    const name = make('span', 'reveal-name');
    name.textContent = p.Name ?? '';
    face.append(picture, name);
    faces.append(face);
  }
  const line = document.createElement('p');
  line.textContent = said ?? (starter && isLocal(model, starter) ? t('lblWebBoardYouGoFirst') : t('lblWebBoardPlayerGoesFirst', starter?.Name ?? ''));
  reveal.append(faces, line);
  byId('match').append(reveal);
  reveal.addEventListener('animationend', e => {
    if (e.animationName === 'first-reveal') reveal.remove();
  });
}
