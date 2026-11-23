import { copyRows, onMap, runBar, type Sample } from '../rogue-sample';

// The last row reached: one node walked in each row before it
const rows = copyRows().map((r, i) => r.map((n, j) => ({ ...n, st: i === 10 ? 'sel' : j === (i === 7 ? 2 : Math.min(1, r.length - 1)) ? 'walked' : 'past' })));
rows[6] = [{ hid: true, life: 20, st: 'past' }, { opp: 'teysa-karlov', plane: 'orzhova', on: 'Teysa Karlov', pn: 'Orzhova', life: 20, st: 'walked' }, { hid: true, life: 20, st: 'past' }];
rows[4][0] = { hid: true, life: 15, st: 'past' };
rows[8] = [{ hid: true, life: 25, st: 'past' }, { opp: 'ygra-eater-of-all', plane: 'the-wilds', on: 'Ygra, Eater of All', pn: 'The Wilds', life: 25, st: 'walked' }];
rows[10] = [{ opp: 'korvold-fae-cursed-king', plane: 'jund', on: 'Korvold, Fae-Cursed King', pn: 'Jund', life: 30, st: 'sel', boss: true, w: 1, cu: 1 }];

export default { messages: onMap(rows, { bar: runBar({ life: 9, max: 23, gold: 2, echoes: 71, credits: 1 }), rerolls: 0 }), save: 'run' } satisfies Sample;
