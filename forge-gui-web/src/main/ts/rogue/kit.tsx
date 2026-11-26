import { Icon } from '../icons';
import { ROGUE_ICONS } from './icons';

/** Adventure's item and building sprites, as mobile's Rogue screens draw them: sheet, x, y and the square's side. The server cuts them (rogue-adventure.css). */
const SPRITES = {
  life: ['items', 48, 16, 16], coin: ['items', 48, 0, 16], shards: ['items', 32, 768, 16], deck: ['items', 48, 48, 16],
  refresh: ['items', 0, 80, 16], menu: ['items', 32, 64, 16], mag: ['items', 448, 63, 16], star: ['items', 464, 16, 16],
  rune: ['items', 352, 80, 16], toolbox: ['items', 448, 79, 16], blood: ['items', 80, 528, 16], status: ['items', 16, 64, 16],
  award: ['items', 144, 496, 16], skull: ['items', 0, 496, 16], xmark: ['items', 16, 80, 16], spark: ['items', 320, 160, 16],
  dmap: ['items', 176, 848, 16], vs: ['items', 208, 928, 16], spellbook: ['items', 144, 688, 16], logbook: ['items', 240, 912, 16],
  fire: ['buildings', 304, 288, 16], event: ['buildings', 240, 272, 16], chest: ['buildings', 432, 272, 16],
  sanctum: ['buildings', 160, 240, 32], bazaar: ['buildings', 192, 240, 32],
} as const;
export type SpriteName = keyof typeof SPRITES;
type IconName = keyof typeof ROGUE_ICONS;

/** A sprite at a whole multiple of its own size nearest px, and never above twice it, so one texel is one size across the screen. */
export function Sprite({ k, px = 16, cls, alt = '' }: { k: SpriteName; px?: number; cls?: string; alt?: string }) {
  const [sheet, x, y, n] = SPRITES[k];
  const s = n * Math.min(2, Math.max(1, Math.round(px / n)));
  return <img class={cls ? `ks ks-${k} ${cls}` : `ks ks-${k}`} alt={alt} width={s} height={s} style={{ width: `${s}px`, height: `${s}px` }}
    src={`adventure?s=${sheet}&r=${x},${y},${n},${n}`} />;
}

/** The line icons Adventure has a sprite for. The buildings stand in only where the icon is drawn large. */
const LINE: Partial<Record<IconName, SpriteName>> = { heart: 'life', star: 'star', flame: 'fire', curse: 'rune', reroll: 'refresh', dots: 'menu', cut: 'deck' };
const LARGE: Partial<Record<IconName, SpriteName>> = { event: 'event', bazaar: 'bazaar', chest: 'chest', sanctum: 'sanctum' };
/** What a seal holds: the sprite for the line icon in it. */
const SEAL: Partial<Record<IconName, SpriteName>> = { chest: 'chest', gem: 'shards', wound: 'blood', event: 'event', cog: 'toolbox', reroll: 'status', sanctum: 'sanctum', bazaar: 'bazaar' };

/** An icon by name: Adventure's sprite where it has one, otherwise the line icon. px is the size it is drawn at. */
export function glyph(name: IconName, px = 16) {
  const k = LINE[name] ?? (px >= 36 ? LARGE[name] : undefined);
  return k ? <Sprite k={k} px={px} cls="rg-ic" /> : <Icon d={ROGUE_ICONS[name]} cls="rg-ic" />;
}

/** A balance on the bar: the sprite it names, or its line icon. */
export const barGlyph = (name: string, px = 16) => (name in SPRITES ? <Sprite k={name as SpriteName} px={px} cls="rg-ic" /> : glyph(name as IconName, px));

/** Each tab's sprite, beside its word. */
export const TAB_SPRITES: Record<string, SpriteName> = { runs: 'vs', aether: 'shards', codex: 'spellbook', history: 'logbook', map: 'dmap', traits: 'status', deck: 'deck' };
export const tabSprite = (tab: string) => TAB_SPRITES[tab] && <Sprite k={TAB_SPRITES[tab]} />;

/** A ring's class, which tints it by the sprite it holds. */
export const ringClass = (name: IconName) => (SEAL[name] ? ` ring-${SEAL[name]}` : '');

/** The icon inside a seal or a ring. */
export function sealGlyph(name: IconName, px = 32) {
  const k = SEAL[name];
  return k ? <Sprite k={k} px={px} /> : <Icon d={ROGUE_ICONS[name]} cls="rg-ic" />;
}
