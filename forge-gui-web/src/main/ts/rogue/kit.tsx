import { Icon, SPRITES, Sprite, type SpriteName } from '../icons';
import { ROGUE_ICONS } from './icons';

export { Sprite, type SpriteName } from '../icons';
type IconName = keyof typeof ROGUE_ICONS;

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
