// What a question asks, as one short line: the engine's prompt text carries the source card's rules, its id and its own bookkeeping

/** A question's text split into the card it comes from, when it names one, and what it asks. */
export interface Asked {
  source: string;
  ask: string;
}

// "Gravedigger (121) - When Gravedigger enters, ...": the source card, its id, and its rules text
const SOURCE = /^(.+?) \(\d+\) - (.*)$/;
// The engine's own notes ride along in square brackets, such as the trigger that holds an ability
const BRACKETED = /\s*\[[^\]]*\]/g;
const CARD_ID = / \(\d+\)/g;

/** Splits the engine's text: the source card's name, and the instruction its last line gives, without ids or bracketed notes. */
export function splitQuestion(text: string): Asked {
  const lines = text.split('\n').map(line => line.trim());
  // The targets picked so far follow the instruction, and the board shows them already
  const targeted = lines.findIndex(line => line.startsWith('Targeted:'));
  const kept = (targeted < 0 ? lines : lines.slice(0, targeted)).filter(line => line);
  if (!kept.length) return { source: '', ask: '' };
  // Some prompts break the line after the source's id, before the dash and its rules text
  if (kept.length > 1 && kept[1].startsWith('- ')) kept.splice(0, 2, `${kept[0]} ${kept[1]}`);
  const named = SOURCE.exec(kept[0]);
  const source = named ? named[1] : '';
  // After a source and its rules text, which the stack and the prompt show, the question keeps only what it asks
  const ask = !named ? kept.join(' ') : kept.length > 1 ? kept[kept.length - 1] : named[2];
  return { source, ask: ask.replace(BRACKETED, '').replace(CARD_ID, '').trim() };
}
