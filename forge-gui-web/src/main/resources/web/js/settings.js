// Every setting the options dialog offers. Settings marked server:true are Forge preferences shared with the
// desktop client; the rest live in this browser.

import { cssUrl } from './looks.js';

const LOCAL_KEY = 'forge.settings';
const DEFAULTS_KEY = 'forge.defaults';

export const SETTINGS = [
  {
    section: 'Priority', key: 'autoPassNoActions', label: 'Auto-pass when I have nothing to play',
    hint: 'The same setting as the auto-pass button.', type: 'toggle', server: true, def: false,
  },
  {
    section: 'Priority', key: 'interruptAttackers', label: 'Stop auto-passing when attackers are declared',
    type: 'toggle', server: true, def: true,
  },
  {
    section: 'Priority', key: 'interruptOpponentSpell', label: 'Stop auto-passing when an opponent casts a spell',
    type: 'toggle', server: true, def: true,
  },
  {
    section: 'Priority', key: 'interruptTargeting', label: 'Stop auto-passing when something targets me',
    type: 'toggle', server: true, def: false,
  },
  {
    section: 'Priority', key: 'interruptTriggers', label: 'Stop auto-passing on triggered abilities',
    type: 'toggle', server: true, def: false,
  },
  {
    section: 'Priority', key: 'interruptMassRemoval', label: 'Stop auto-passing on mass removal',
    type: 'toggle', server: true, def: false,
  },
  {
    section: 'Priority', key: 'autoYieldMode', label: 'Remember auto-yields', type: 'choice', server: true,
    options: [['ability', 'Per ability'], ['card', 'Per card']], def: 'ability',
  },
  {
    section: 'Game log', key: 'logDetail', label: 'Detail', type: 'choice', server: true,
    options: [['LOW', 'Low'], ['MEDIUM', 'Medium'], ['HIGH', 'High']], def: 'MEDIUM',
  },
  { section: 'Game log', key: 'logImages', label: 'Card thumbnails in the log', type: 'toggle', def: true },
  {
    section: 'Cards', key: 'highlightPlayable', label: 'Highlight cards I can play', type: 'toggle', server: true, def: true,
  },
  {
    section: 'Cards', key: 'autoTapPreview', label: 'Highlight the lands Auto would tap', type: 'toggle', server: true, def: false,
  },
  {
    section: 'Cards', key: 'handSort', label: 'Sort hand', type: 'choice',
    options: [['mana', 'By mana value'], ['draw', 'As drawn']], def: 'mana',
  },
  { section: 'Cards', key: 'cardSize', label: 'Card size', type: 'slider', min: 70, max: 130, def: 100 },
  { section: 'Cards', key: 'handSize', label: 'Hand size', type: 'slider', min: 70, max: 130, def: 100 },
  {
    section: 'Arrows', key: 'arrows', label: 'Target and combat arrows', type: 'choice', server: true,
    options: [['0', 'Off'], ['1', 'On hover'], ['2', 'Always']], def: '2',
  },
  {
    section: 'Sound', key: 'soundVolume', label: 'Sound effects', hint: 'Zero turns them off.',
    type: 'slider', server: true, min: 0, max: 100, def: 100,
  },
  {
    section: 'Sound', key: 'musicVolume', label: 'Music', hint: 'Zero turns it off.',
    type: 'slider', server: true, min: 0, max: 100, def: 100,
  },
  {
    section: 'Theme', key: 'playmat', label: 'Playmat', hint: 'The table the board is played on.',
    type: 'playmat', def: '',
  },
  {
    section: 'Theme', key: 'customCss', label: 'Custom CSS',
    hint: 'Applied to the match screen as you type, and kept in this browser.', type: 'css', def: '',
  },
];

const byKey = new Map(SETTINGS.map(s => [s.key, s]));
let local = {};
let server = {};
let send = () => {};
let redraw = () => {};

export function initSettings(sendFn, schedule) {
  send = sendFn;
  redraw = schedule;
  try {
    local = JSON.parse(localStorage.getItem(LOCAL_KEY) ?? '{}');
  } catch {
    local = {};
  }
  apply();
}

let playmats = [];

export function setPlaymats(list) {
  playmats = list ?? [];
  apply();
}

export const playmatList = () => playmats;

export function setting(key) {
  const def = byKey.get(key);
  const value = def.server ? server[key] : local[key];
  return value === undefined ? def.def : value;
}

// The server sends its preference values with the rest of the turn controls
export function onServerSettings(values) {
  server = values ?? {};
  applyWebDefaults();
  apply();
}

// Forge ships with playable-card highlighting off; this UI wants it on. A browser turns it on once, and after
// that the setting belongs to the player.
function applyWebDefaults() {
  try {
    if (localStorage.getItem(DEFAULTS_KEY)) {
      return;
    }
    localStorage.setItem(DEFAULTS_KEY, '1');
  } catch {
    return;
  }
  if (!setting('highlightPlayable')) {
    set('highlightPlayable', true);
  }
}

export function set(key, value) {
  const def = byKey.get(key);
  if (def.server) {
    server[key] = value;
    send({ t: 'setSetting', key, value: String(value) });
  } else {
    local[key] = value;
    try {
      localStorage.setItem(LOCAL_KEY, JSON.stringify(local));
    } catch {
      // A browser with storage blocked keeps the setting for this session only
    }
  }
  apply();
  redraw();
}

// Card and hand size scale the shared card variables; the rest is read where it is used
function apply() {
  const root = document.documentElement;
  // Desktop keeps the highlight colour as a preference of its own, with no control in this dialog
  root.style.setProperty('--playable', server.highlightColor ?? '#66ccff');
  const card = setting('cardSize') / 100;
  root.style.setProperty('--card-w', `${Math.round(88 * card)}px`);
  root.style.setProperty('--card-h', `${Math.round(123 * card)}px`);
  const hand = setting('handSize') / 100;
  root.style.setProperty('--hand-w', `${Math.round(88 * hand)}px`);
  root.style.setProperty('--hand-h', `${Math.round(123 * hand)}px`);
  customStyle().textContent = String(setting('customCss') ?? '');
  // A url() inside a custom property resolves against the stylesheet that uses it, not the page, so a
  // relative one asks board.css's own folder for it. cssUrl makes it absolute, as it does for sleeves.
  const mat = setting('playmat');
  root.style.setProperty('--playmat', cssUrl(mat ? `playmat?id=${encodeURIComponent(mat)}` : ''));
  // Every playmat Forge ships is already dark, so the table's own wash lifts off one rather than burying it
  root.classList.toggle('has-playmat', !!mat);
}

function customStyle() {
  let style = document.getElementById('custom-css');
  if (!style) {
    style = document.createElement('style');
    style.id = 'custom-css';
    document.head.append(style);
  }
  return style;
}
