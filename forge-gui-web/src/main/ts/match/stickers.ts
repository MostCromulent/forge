// Stickers drawn on a card as pieces cut from the picture of the sheet they were peeled from

import { cardImageSrc } from '../images';
import { deref, derefAll, type Model } from '../model';
import type { CardView, PlayerView } from '../protocol';

type Kind = 'name' | 'art' | 'ability' | 'pt';

/** A sticker on a card, as the host describes it: a word, "art sticker 4", the ability's text, or "1/4". */
export interface Applied {
  text: string;
  kind: Kind;
}

/** Where each sticker lies on a printed sheet, as x, y, width and height in its 488 × 680 picture, by its place in the sheet's list. Every sheet shares the frame; an ability's box grows with its text, so abilities are drawn as words. */
const SHEET_W = 488;
const SHEET_H = 680;
const CUTS: Record<number, readonly [number, number, number, number]> = {
  0: [40, 32, 142, 40], 1: [182, 32, 130, 40], 2: [312, 32, 146, 40],
  3: [165, 88, 145, 170], 4: [45, 215, 155, 145], 5: [280, 205, 170, 160],
  8: [232, 597, 103, 56], 9: [367, 597, 103, 56],
};
/** A sheet lists three names, three pieces of art, two abilities and two power/toughness boxes, in that order. */
const KIND_AT: Kind[] = ['name', 'name', 'name', 'art', 'art', 'art', 'ability', 'ability', 'pt', 'pt'];
/** Where art lands on the card it is stuck to, by the sheet slot it came from: left and top, as percentages of the card. */
const ART_AT: Record<number, readonly [number, number]> = { 3: [52, 13], 4: [10, 22], 5: [32, 32] };

export function applied(text: string | undefined): Applied[] {
  if (!text) return [];
  return text.split(/\r?\n/).slice(1).map(line => /^\s*(.*) \((name|art|ability|pt)\)\s*$/.exec(line))
    .filter((m): m is RegExpExecArray => !!m).map(m => ({ text: m[1], kind: m[2] as Kind }));
}

/** A sheet's own list: each sticker's text, and whether it has been used. */
export function sheetLines(text: string | undefined): { text: string; used: boolean }[] {
  if (!text) return [];
  return text.split(/\r?\n/).slice(1).map(line => {
    const used = / - used\s*$/.test(line);
    return { text: line.replace(/ - used\s*$/, '').replace(/\s*\((?:\{TK\})+\)\s*$/, '').trim(), used };
  });
}

/** The sheet a sticker came from and its place on it. Art reads the same on every sheet, so a sheet whose copy is used is preferred. */
function source(model: Model, owner: PlayerView | undefined, sticker: Applied): { src: string; slot: number } | null {
  let fallback: { src: string; slot: number } | null = null;
  for (const sheet of derefAll(model, owner?.StickerSheets) as CardView[]) {
    const lines = sheetLines(sheet.Stickers);
    for (let slot = 0; slot < lines.length; slot++) {
      if (KIND_AT[slot] !== sticker.kind || lines[slot].text !== sticker.text) continue;
      const found = { src: cardImageSrc(model, sheet), slot };
      if (lines[slot].used) return found;
      fallback ??= found;
    }
  }
  return fallback;
}

/** The background that shows one sticker's piece of its sheet's picture. */
function cut(src: string, slot: number): string {
  const [x, y, w, h] = CUTS[slot];
  const size = `${((SHEET_W / w) * 100).toFixed(1)}% ${((SHEET_H / h) * 100).toFixed(1)}%`;
  const at = `${((x / (SHEET_W - w)) * 100).toFixed(1)}% ${((y / (SHEET_H - h)) * 100).toFixed(1)}%`;
  return `background-image:url('${src.replace(/'/g, '%27')}');background-size:${size};background-position:${at}`;
}

const escape = (s: string) => s.replace(/[&<>"]/g, c => `&#${c.charCodeAt(0)};`);

/** The stickers on a card as markup for a layer over its picture: names along the name line, art on the art, abilities across the text box and numbers on the corner. */
export function stickersHtml(model: Model, card: CardView | undefined): string {
  // A sheet shows where each sticker it has given away was, as the peeled paper does
  if (card?.Stickers?.startsWith('Stickers on this sheet')) {
    return sheetLines(card.Stickers).map((line, slot) => {
      if (!line.used || !CUTS[slot]) return '';
      const [x, y, w, h] = CUTS[slot];
      const at = (n: number, of: number) => ((n / of) * 100).toFixed(1);
      return `<span class="stk peeled" style="left:${at(x, SHEET_W)}%;top:${at(y, SHEET_H)}%;width:${at(w, SHEET_W)}%;height:${at(h, SHEET_H)}%"></span>`;
    }).join('');
  }
  const stickers = applied(card?.Stickers);
  if (!card || !stickers.length) return '';
  const owner = deref(model, card.Owner) as PlayerView | undefined;
  // A card's height is 88/63 of its width, so a box a share of the height tall is this many widths wide per unit of its shape
  const tall = 88 / 63;
  let nameLeft = 8;
  let abilities = 0;
  const out: string[] = [];
  for (const sticker of stickers) {
    const from = source(model, owner, sticker);
    if (sticker.kind === 'ability' || !from || !CUTS[from.slot]) {
      const top = sticker.kind === 'ability' ? 64 + 12 * abilities++ : 64;
      out.push(`<span class="stk words" style="top:${top}%" title="${escape(sticker.text)}">${escape(sticker.text)}</span>`);
      continue;
    }
    const [, , w, h] = CUTS[from.slot];
    const style = cut(from.src, from.slot);
    if (sticker.kind === 'name') {
      const width = 5.4 * tall * (w / h);
      out.push(`<span class="stk" style="left:${nameLeft.toFixed(1)}%;top:4.4%;width:${width.toFixed(1)}%;aspect-ratio:${w}/${h};${style}"></span>`);
      nameLeft += width;
    } else if (sticker.kind === 'art') {
      const [left, top] = ART_AT[from.slot];
      out.push(`<span class="stk art" style="left:${left}%;top:${top}%;width:36%;aspect-ratio:${w}/${h};${style}"></span>`);
    } else {
      out.push(`<span class="stk" style="right:4%;bottom:2.6%;width:27%;aspect-ratio:${w}/${h};${style}"></span>`);
    }
  }
  return out.join('');
}

/** The picture of the sticker a choice names, as "{TK}{TK} - 1/4" or "Ancestral" name it, cut from the sheet in play that holds it; null for any other choice. */
export function cutFor(model: Model, label: string | undefined): string | null {
  const text = (label ?? '').replace(/^(?:\{TK\})+ - /, '').trim();
  if (!text) return null;
  for (const object of model.objects.values()) {
    for (const sheet of derefAll(model, (object as PlayerView).StickerSheets) as CardView[]) {
      const slot = sheetLines(sheet.Stickers).findIndex((line, i) => CUTS[i] && line.text === text);
      if (slot >= 0) return `aspect-ratio:${CUTS[slot][2]}/${CUTS[slot][3]};${cut(cardImageSrc(model, sheet), slot)}`;
    }
  }
  return null;
}

/** Fills a card's sticker layer, touching the page only when the stickers change. */
export function drawStickers(layer: HTMLElement, model: Model, card: CardView | undefined): void {
  const html = stickersHtml(model, card);
  if (layer.dataset.html === html) return;
  layer.dataset.html = html;
  layer.innerHTML = html;
}
