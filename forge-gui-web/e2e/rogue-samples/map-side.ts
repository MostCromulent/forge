import { copyRows, onMap, runBar, type Sample } from '../rogue-sample';

// After the battle of row 4 the marker moves on to the bazaar of row 5
const rows = copyRows();
rows[4] = rows[4].map((n, i) => ({ ...n, st: i === 1 ? 'walked' : 'past' }));
rows[5] = [{ kind: 'sanctum', st: 'open' }, { kind: 'bazaar', st: 'sel' }, { kind: 'chest', st: 'dead' }];

export default { messages: onMap(rows, { bar: runBar({ life: 11, max: 23, gold: 12, echoes: 56, credits: 6 }), cards: 67 }), save: 'run' } satisfies Sample;
