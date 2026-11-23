import { HOME, MEETING_TEFERI, press, story, type Sample } from '../rogue-sample';

export default {
  messages: [HOME, story(MEETING_TEFERI, 'Meeting Teferi')],
  steps: page => press(page, '#campaign .rg-scene .ft .primary'),
  under: true,
} satisfies Sample;
