import { onMap, press, type Sample } from '../rogue-sample';

export default {
  messages: onMap(),
  save: 'run',
  steps: page => press(page, '#campaign .camp-purse button[title^="More"]'),
  owned: ['.rg-menu'],
} satisfies Sample;
