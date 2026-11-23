import { HOME, ROSTER, type Sample } from '../rogue-sample';
import { atraxa } from './p-start';

export default { messages: [HOME, ROSTER], steps: page => atraxa(page, true), owned: ['.ph'] } satisfies Sample;
