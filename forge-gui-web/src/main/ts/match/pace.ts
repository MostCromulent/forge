// Paces what the page shows: a board with another player's play on it stays up a moment before the next replaces it

import type { GameEvent, Prompt, Ref } from '../protocol';

/** Zones everyone can see into, where a card arriving or leaving is something to look at. */
const OPEN = new Set(['Battlefield', 'Stack', 'Graveyard', 'Exile', 'Command']);

/** Whether events show something the player would want to see before the game moves on: another player acting in the open, or a change to a player or a permanent. */
export function worthSeeing(events: GameEvent[], localPlayers: number[]): boolean {
  const mine = (player?: Ref) => !!player && localPlayers.includes(player.ref);
  return events.some(e => {
    switch (e.kind) {
      case 'cardMoved':
        return [e.from, e.to].some(place => place && OPEN.has(place.zone)) && !mine(e.caster ?? e.to?.player ?? e.from?.player);
      case 'attackersDeclared':
        return e.attacks.length > 0 && !mine(e.player);
      case 'blockersDeclared':
        return e.blocks.length > 0 && !mine(e.player);
      case 'stackAdded':
      case 'stackResolved':
        return !mine(e.player);
      case 'cardDamaged':
      case 'playerDamaged':
      case 'lifeChanged':
      case 'playerCounters':
      case 'cardCounters':
      case 'cardAttached':
        return true;
      default:
        return false;
    }
  });
}

/** Whether a prompt asks the player for something, so they are looking at the board and their answer must not wait. */
export function asksPlayer(prompt: Prompt): boolean {
  return prompt.priority || prompt.ok.enabled || prompt.cancel.enabled || prompt.selectable.length > 0 || prompt.selectablePlayers.length > 0;
}
