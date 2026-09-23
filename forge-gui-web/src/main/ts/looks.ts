import { imageUrl } from './images';
import type { PlayerView } from './protocol';

// Avatars and sleeves are numbered cells of the skin's sprite sheets, the same numbers desktop stores
export const avatarUrl = (index: number): string => `avatar?i=${index}`;
export const sleeveUrl = (index: number): string => `sleeve?i=${index}`;

// Packets leave out values still at their default, so a missing index means 0; -1 means none.
// A card-art avatar, when the player has one, wins over the sprite
export function playerAvatarUrl(player: PlayerView): string {
  if (player.AvatarCardImageKey) return imageUrl(player.AvatarCardImageKey);
  const index = player.AvatarIndex ?? 0;
  return index >= 0 ? avatarUrl(index) : '';
}

export function playerSleeveUrl(player: PlayerView | undefined): string {
  const index = player?.SleeveIndex ?? 0;
  return index >= 0 ? sleeveUrl(index) : '';
}

// For a CSS variable: a relative url() there resolves against the stylesheet that uses it, not the page
export function cssUrl(url: string): string {
  return url ? `url("${new URL(url, document.baseURI).href}")` : 'none';
}

export const ROBOT_ICON = '<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="5" y="8" width="14" height="11" rx="3"/><rect x="11" y="3" width="2" height="5"/><circle cx="12" cy="3" r="1.6"/><circle cx="9.5" cy="13" r="1.6" class="eye"/><circle cx="14.5" cy="13" r="1.6" class="eye"/><rect x="2" y="11" width="2.5" height="5" rx="1"/><rect x="19.5" y="11" width="2.5" height="5" rx="1"/></svg>';
