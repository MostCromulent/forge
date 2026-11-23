import { HOME, type Sample } from '../rogue-sample';

export default {
  messages: [HOME, { t: 'rogueAnnounce', kind: 'spark', title: 'Spark Reward', text: 'You won 1 Spark!' }],
  owned: ['.icon-button'],
} satisfies Sample;
