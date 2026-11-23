import { FIRST, HOME, type Sample } from '../rogue-sample';

export default {
  messages: [{
    ...HOME, unlocked: false, run: undefined,
    bar: { ...HOME.bar, line: 'No runs yet', balances: HOME.bar.balances.map(b => ({ ...b, amount: 0 })) },
  }, FIRST],
  owned: ['.icon-button'],
} satisfies Sample;
