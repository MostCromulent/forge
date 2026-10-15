// Who this browser plays as between visits: the name and the face it last played under

import { store, stored } from './storage';

const NAME_KEY = 'forge.playerName';
const AVATAR_KEY = 'forge.avatar';

/** The name this browser last played under, offered for it when it arrives on a server that does not know it. */
export function rememberedName(): string | null {
  return stored(NAME_KEY);
}

export function rememberName(name: string | null): void {
  store(NAME_KEY, name || null);
}

/** The face this browser last played under. The first avatar is as good a default as any. */
export function rememberedAvatar(): number {
  const saved = Number(stored(AVATAR_KEY));
  return Number.isInteger(saved) && saved >= 0 ? saved : 0;
}

export function rememberAvatar(index: number): void {
  store(AVATAR_KEY, String(index));
}
