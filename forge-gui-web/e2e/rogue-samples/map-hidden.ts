import { copyRows, onMap, type Sample } from '../rogue-sample';

// With the Eye wound the planes of row 4 stay face down when they are reached
const rows = copyRows();
rows[4] = [{ hid: true, life: 15, st: 'dead' }, { hid: true, life: 20, st: 'sel', star: true, w: 1 }, { hid: true, life: 15, st: 'open', w: 2, cu: 1 },
  { hid: true, life: 20, st: 'dead', star: true }];

export default { messages: onMap(rows, { rerolls: 0, hidden: 'Planes on the path are not revealed when you reach them.' }), save: 'run' } satisfies Sample;
