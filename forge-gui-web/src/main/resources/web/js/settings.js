// The cog dialog: one scrolling list of settings with a search box. Settings marked server:true are Forge
// preferences shared with the desktop client; the rest live in this browser.

const LOCAL_KEY = 'forge.settings';
const DEFAULTS_KEY = 'forge.defaults';

const SETTINGS = [
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
  { section: 'Sound', key: 'sounds', label: 'Sound effects', type: 'toggle', server: true, def: true },
  {
    section: 'Sound', key: 'soundVolume', label: 'Effect volume', type: 'slider', server: true, min: 0, max: 100, def: 100,
  },
  { section: 'Sound', key: 'music', label: 'Music', type: 'toggle', server: true, def: true },
  {
    section: 'Sound', key: 'musicVolume', label: 'Music volume', type: 'slider', server: true, min: 0, max: 100, def: 100,
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
let onConcede = () => {};
let redraw = () => {};

export function initSettings(sendFn, concede, schedule) {
  send = sendFn;
  onConcede = concede;
  redraw = schedule;
  try {
    local = JSON.parse(localStorage.getItem(LOCAL_KEY) ?? '{}');
  } catch {
    local = {};
  }
  apply();
}

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
  if (document.getElementById('options')) drawRows();
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

function set(key, value) {
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

export function openOptions() {
  if (document.getElementById('options')) return;
  const back = document.createElement('div');
  back.id = 'options';
  back.className = 'backdrop';
  back.innerHTML = `
    <div class="options-dialog" role="dialog" aria-label="Options">
      <header>
        <b>Options</b>
        <input class="search" type="search" placeholder="Search settings" aria-label="Search settings">
        <button class="close" title="Close (Esc)"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M18 6 6 18"/><path d="m6 6 12 12"/></svg></button>
      </header>
      <div class="rows"></div>
      <footer><span class="hint">Changes apply at once.</span><button class="concede"></button></footer>
    </div>`;
  back.querySelector('.close').onclick = closeOptions;
  back.querySelector('.search').addEventListener('input', drawRows);
  back.onmousedown = e => {
    if (e.target === back) closeOptions();
  };
  const concede = back.querySelector('.concede');
  concede.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 22V4a1 1 0 0 1 .4-.8A6 6 0 0 1 8 2c3 0 5 2 7.333 2q2 0 3.067-.8A1 1 0 0 1 20 4v10a1 1 0 0 1-.4.8A6 6 0 0 1 16 16c-3 0-5-2-8-2a6 6 0 0 0-4 1.528"/></svg>Concede game';
  concede.onclick = () => {
    if (concede.classList.contains('armed')) {
      onConcede();
      closeOptions();
      return;
    }
    concede.classList.add('armed');
    concede.lastChild.textContent = 'Confirm concede';
  };
  document.body.append(back);
  drawRows();
  back.querySelector('.search').focus();
}

export function closeOptions() {
  document.getElementById('options')?.remove();
}

function drawRows() {
  const dialog = document.getElementById('options');
  if (!dialog) return;
  const query = dialog.querySelector('.search').value.trim().toLowerCase();
  const rows = dialog.querySelector('.rows');
  rows.replaceChildren();
  let section = '';
  for (const def of SETTINGS) {
    if (query && !`${def.section} ${def.label} ${def.hint ?? ''}`.toLowerCase().includes(query)) continue;
    if (def.section !== section) {
      section = def.section;
      const h = document.createElement('h4');
      h.textContent = section;
      rows.append(h);
    }
    rows.append(row(def));
  }
  if (!rows.firstChild) {
    const empty = document.createElement('p');
    empty.className = 'hint';
    empty.textContent = 'No setting matches that.';
    rows.append(empty);
  }
}

function row(def) {
  const el = document.createElement('div');
  el.className = def.type === 'css' ? 'setting wide' : 'setting';
  const text = document.createElement('div');
  const label = document.createElement('div');
  label.textContent = def.label;
  text.append(label);
  if (def.hint) {
    const hint = document.createElement('div');
    hint.className = 'hint';
    hint.textContent = def.hint;
    text.append(hint);
  }
  el.append(text, control(def));
  return el;
}

// A theme is a plain CSS file: load one, save the current one, or edit it here
function cssControl(def, value) {
  const wrap = document.createElement('div');
  wrap.className = 'css-editor';
  const area = document.createElement('textarea');
  area.className = 'css';
  area.spellcheck = false;
  area.rows = 5;
  area.placeholder = '#prompt { border-color: #7c3aed; }';
  area.value = value;
  // Typed CSS lands at once; the dialog keeps its rows so the caret does not jump
  area.addEventListener('input', () => set(def.key, area.value));
  const file = document.createElement('input');
  file.type = 'file';
  file.accept = '.css,text/css';
  file.hidden = true;
  file.addEventListener('change', async () => {
    const chosen = file.files?.[0];
    if (chosen) {
      area.value = await chosen.text();
      set(def.key, area.value);
    }
    file.value = '';
  });
  const buttons = document.createElement('div');
  buttons.className = 'css-buttons';
  buttons.append(
    button('Import', () => file.click()),
    button('Export', () => saveCss(area.value)),
    button('Clear', () => {
      area.value = '';
      set(def.key, '');
    }),
  );
  wrap.append(area, buttons, file);
  return wrap;
}

function button(label, onClick) {
  const b = document.createElement('button');
  b.textContent = label;
  b.onclick = onClick;
  return b;
}

function saveCss(text) {
  const url = URL.createObjectURL(new Blob([text], { type: 'text/css' }));
  const link = document.createElement('a');
  link.href = url;
  link.download = 'forge-theme.css';
  link.click();
  URL.revokeObjectURL(url);
}

function control(def) {
  const value = setting(def.key);
  if (def.type === 'toggle') {
    const b = document.createElement('button');
    b.className = `switch${value ? ' on' : ''}`;
    b.role = 'switch';
    b.ariaChecked = String(!!value);
    b.onclick = () => {
      set(def.key, !setting(def.key));
      drawRows();
    };
    return b;
  }
  if (def.type === 'choice') {
    const group = document.createElement('div');
    group.className = 'choice';
    for (const [v, label] of def.options) {
      const b = document.createElement('button');
      b.textContent = label;
      b.className = String(value) === v ? 'on' : '';
      b.onclick = () => {
        set(def.key, v);
        drawRows();
      };
      group.append(b);
    }
    return group;
  }
  if (def.type === 'css') {
    return cssControl(def, String(value ?? ''));
  }
  const wrap = document.createElement('div');
  wrap.className = 'slider';
  const input = document.createElement('input');
  input.type = 'range';
  input.min = String(def.min);
  input.max = String(def.max);
  input.step = '5';
  input.value = String(value);
  const out = document.createElement('span');
  out.textContent = `${value}%`;
  input.oninput = () => {
    out.textContent = `${input.value}%`;
    set(def.key, Number(input.value));
  };
  wrap.append(input, out);
  return wrap;
}
