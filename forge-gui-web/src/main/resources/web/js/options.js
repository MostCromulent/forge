import { SETTINGS, playmatList, set, setting } from './settings.js';

// The cog dialog: one scrolling list of settings with a search box, and the concede button under it

export function openOptions(onConcede) {
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
  back.querySelector('.search').addEventListener('input', refreshOptions);
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
  refreshOptions();
  back.querySelector('.search').focus();
}

export function closeOptions() {
  document.getElementById('options')?.remove();
}

export function refreshOptions() {
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

// The playmats an installation has are its skins' own table images, so the picker is built from what the server sends
function playmatControl(def, value) {
  const wrap = document.createElement('div');
  wrap.className = 'mat-grid';
  const choices = [{ id: '', label: 'Plain' }, ...playmatList()];
  for (const mat of choices) {
    const b = document.createElement('button');
    b.className = `mat${mat.id === value ? ' chosen' : ''}`;
    b.title = mat.label;
    b.style.backgroundImage = mat.id ? `url("playmat?id=${encodeURIComponent(mat.id)}")` : 'none';
    b.onclick = () => {
      set(def.key, mat.id);
      refreshOptions();
    };
    wrap.append(b);
  }
  return wrap;
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
      refreshOptions();
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
        refreshOptions();
      };
      group.append(b);
    }
    return group;
  }
  if (def.type === 'playmat') {
    return playmatControl(def, String(value ?? ''));
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
