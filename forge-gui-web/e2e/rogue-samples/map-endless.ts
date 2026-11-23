import { onMap, runBar, type Sample } from '../rogue-sample';

export default {
  messages: onMap(undefined, { bar: runBar({ life: 31, max: 46, gold: 22, echoes: 52, credits: 3 }, 'Descension: Level 1 - Wrathful · Endless Level 2') }),
  save: 'run',
} satisfies Sample;
