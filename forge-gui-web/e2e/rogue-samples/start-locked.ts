import { HOME, ROSTER, choose, type Sample } from '../rogue-sample';

export default { messages: [HOME, ROSTER], steps: page => choose(page, 4, true), owned: ['.icon-button'] } satisfies Sample;
