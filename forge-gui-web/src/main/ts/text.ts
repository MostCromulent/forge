// The page's words. Every piece of text the player reads comes from Forge's language files through t(), in the
// language Forge is set to, so the page and the game it shows speak the same language. The server sends the patterns
// before anything is drawn (app.ts); a key the page uses must be in en-US.properties, which the build checks.

import type { ComponentChild } from 'preact';
import type { TextKey } from './text.gen';

export type { TextKey };

type Arg = string | number;

let patterns: Record<string, string> | undefined;
let language = 'en-US';
let numbers = new Intl.NumberFormat('en-US');

/** Fetches the page's text from the server. Nothing may be drawn before it arrives. */
export async function loadText(): Promise<void> {
  const answer = await fetch('/text');
  const { lang, text } = await answer.json() as { lang: string; text: Record<string, string> };
  setText(lang, text);
  document.documentElement.lang = lang;
  for (const el of document.querySelectorAll<HTMLElement>('[data-text]')) {
    el.textContent = t(el.dataset.text as TextKey);
  }
}

/** Sets the page's text directly, as the tests do. */
export function setText(lang: string, text: Record<string, string>): void {
  patterns = text;
  language = lang;
  numbers = new Intl.NumberFormat(lang);
}

/** The language the page's text is in, as a BCP 47 tag, for sorting and dates. */
export function textLanguage(): string {
  return language;
}

/** The text for a key, with {0}, {1}… filled from the arguments as Forge's Localizer fills them. */
export function t(key: TextKey, ...args: Arg[]): string {
  return pieces(key, args).join('');
}

/** The same, with markup for the arguments: an element can sit inside a sentence without splitting its words. */
export function tNodes(key: TextKey, ...args: ComponentChild[]): ComponentChild[] {
  return pieces(key, args);
}

function pieces<T>(key: TextKey, args: readonly T[]): (string | T)[] {
  if (!patterns) throw new Error(`The page's text is not loaded yet, so ${key} cannot be shown`);
  const pattern = patterns[key];
  return pattern === undefined ? [key] : format(pattern, args);
}

/**
 * Splits a pattern at its arguments, following java.text.MessageFormat's quoting, which Forge's language files are
 * written for: '' is an apostrophe, and a run between single quotes is taken as it stands.
 */
export function format<T>(pattern: string, args: readonly T[]): (string | T)[] {
  const out: (string | T)[] = [];
  let text = '';
  let quoted = false;
  for (let i = 0; i < pattern.length; i++) {
    const c = pattern[i];
    if (c === '\'') {
      if (pattern[i + 1] === '\'') {
        text += '\'';
        i++;
      } else {
        quoted = !quoted;
      }
    } else if (c === '{' && !quoted) {
      const end = pattern.indexOf('}', i);
      const arg = args[Number(pattern.slice(i + 1, end))];
      if (typeof arg === 'number') {
        text += numbers.format(arg);
      } else if (typeof arg === 'string' || arg === undefined || arg === null) {
        text += arg ?? '';
      } else {
        if (text) out.push(text);
        text = '';
        out.push(arg);
      }
      i = end;
    } else {
      text += c;
    }
  }
  if (text || !out.length) out.push(text);
  return out;
}
