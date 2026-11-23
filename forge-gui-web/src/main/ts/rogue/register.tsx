import { t } from '../text';
import { registerMode } from '../campaign/modes';
import { RogueHub } from './rogue';

// A winding trail between two points
const ICON = <><circle cx="12" cy="37" r="4" /><circle cx="36" cy="11" r="4" /><path d="M16 37h10a7 7 0 0 0 0-14h-4a7 7 0 0 1 0-12h10" /></>;

registerMode({
  mode: 'rogue',
  fields: () => ({}),
  hello: () => {},
  stored: [],
  actions: () => ({}),
  Page: RogueHub,
  tile: {
    icon: ICON, art: 'c:Jund|PC2|1', name: 'lblRogueCommander', blurb: 'lblRogueCommanderDesc',
    resumeLabel: () => t('lblRogueContinueRun'), order: 3,
  },
});
