import { HOME, MEETING_TEFERI, story, type Sample } from '../rogue-sample';

// The phone mockup shows one paragraph of the part
export default {
  messages: [HOME, story([MEETING_TEFERI[1].split('\n')[0]])],
  under: true,
} satisfies Sample;
