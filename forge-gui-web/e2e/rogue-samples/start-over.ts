import { HOME, ROSTER, choose, type Sample } from '../rogue-sample';

export default {
  messages: [HOME, ROSTER],
  steps: page => choose(page, 2, true),
  later: [{
    t: 'rogueQuestion', id: 1, kind: 'replace',
    choice: {
      kind: 'replace', title: 'Abandon Current Run', rerolls: 0, skip: false,
      paragraphs: [[{ kind: 'text', text: 'You have an active Run in progress.\nStarting a new Run will abandon it. Continue?' }]],
      options: [{ label: 'Start New Run', text: [], enabled: true }, { label: 'Cancel', text: [], enabled: true }],
    },
  }],
  owned: ['.icon-button'],
} satisfies Sample;
