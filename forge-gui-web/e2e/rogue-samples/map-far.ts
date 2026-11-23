import { copyRows, onMap, press, type Sample } from '../rogue-sample';

// Narset's Farsight shows a plane of row 6, which is chosen to read it
const rows = copyRows();
rows[6][1] = { opp: 'teysa-karlov', plane: 'orzhova', on: 'Teysa Karlov', pn: 'Orzhova', life: 25, st: 'far', star: true };

export default {
  messages: onMap(rows, { text: {} }),
  save: 'run',
  steps: page => press(page, '#campaign .rg-n[data-node="21"]'),
} satisfies Sample;
