// Which of Rogue Commander's questions are drawn as a scene over the page, and what its keys answer. The others are dialogs

import type { Option, RogueQuestion } from '../protocol';

/** The options of a question drawn as a scene, empty for one that only continues, or null for a question drawn as a dialog: a plain message or Replace. */
export function sceneOptions(asked: RogueQuestion | null): Option[] | null {
  if (asked?.choice) return asked.choice.kind === 'replace' ? null : asked.choice.options;
  return asked?.omen || asked?.tell && asked.tell.kind !== 'MESSAGE' ? [] : null;
}

/** Whether a question is drawn as a scene over the page: those with options, results and card picks. */
export function isScene(asked: RogueQuestion | null): boolean {
  return sceneOptions(asked) !== null || !!asked?.cards || !!asked?.commander;
}
