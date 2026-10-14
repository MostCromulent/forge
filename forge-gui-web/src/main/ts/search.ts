// Searches a long list by name by the same rules as desktop's ListChooser, so either client finds the same cards in the same order

/** Lower case, accents stripped, and nothing but letters, digits and spaces. */
export function normalize(s: string): string {
  return s.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase().replace(/[^a-z0-9 ]/g, '');
}

/** Where the typed text falls in a name, as start and end in the name itself, which normalizing may have shortened. */
export function matchedSpan(name: string, typed: string): [number, number] | null {
  const text = normalize(typed);
  if (!text) return null;
  let flat = '';
  const from: number[] = [];
  for (let i = 0; i < name.length; i++) {
    for (const c of normalize(name[i])) {
      flat += c;
      from.push(i);
    }
  }
  const at = flat.indexOf(text);
  return at < 0 ? null : [from[at], from[at + text.length - 1] + 1];
}

/** The indexes of the names matching the typed text: those starting with it, shortest first, then those containing it. */
export function rankByName(names: readonly string[], typed: string): number[] {
  const text = normalize(typed);
  if (!text) return names.map((_, i) => i);
  const startsWith: number[] = [];
  const contains: number[] = [];
  names.forEach((name, i) => {
    const n = normalize(name);
    if (n.startsWith(text)) startsWith.push(i);
    else if (n.includes(text)) contains.push(i);
  });
  startsWith.sort((a, b) => names[a].length - names[b].length);
  return [...startsWith, ...contains];
}
