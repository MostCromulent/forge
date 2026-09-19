// The cog dialog: one scrolling list of settings with a search box. Settings marked server:true are Forge
// preferences shared with the desktop client; the rest live in this browser.

const LOCAL_KEY = 'forge.settings';

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
  { section: 'Cards', key: 'cardSize', label: 'Card size', type: 'slider', min: 70, max: 130, def: 100 },
  { section: 'Cards', key: 'handSize', label: 'Hand size', type: 'slider', min: 70, max: 130, def: 100 },
  {
    section: 'Arrows', key: 'arrows', label: 'Target and combat arrows', type: 'choice', server: true,
    options: [['0', 'Off'], ['1', 'On hover'], ['2', 'Always']], def: '2',
  },
];

const byKey = new Map(SETTINGS.map(s => [s.key, s]));
let local = {};
let server = {};
let send = () => {};
let onConcede = () => {};

export function initSettings(sendFn, concede) {
  send = sendFn;
  onConcede = concede;
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
  apply();
  if (document.getElementById('options')) drawRows();
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
}

// Card and hand size scale the shared card variables; the rest is read where it is used
function apply() {
  const root = document.documentElement;
  const card = setting('cardSize') / 100;
  root.style.setProperty('--card-w', `${Math.round(88 * card)}px`);
  root.style.setProperty('--card-h', `${Math.round(123 * card)}px`);
  const hand = setting('handSize') / 100;
  root.style.setProperty('--hand-w', `${Math.round(88 * hand)}px`);
  root.style.setProperty('--hand-h', `${Math.round(123 * hand)}px`);
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
  el.className = 'setting';
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
