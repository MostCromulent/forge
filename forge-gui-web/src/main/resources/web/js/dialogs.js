import { createCard, updateCard } from './cards.js';
import { imageUrl, noImageOnError, setSymbolText } from './images.js';
import { hoverable } from './detail.js';

let shownId = null;

// Shows the oldest open request; its answer goes back as {t:'reply'} and the request leaves the local model at once
export function renderDialogs(model, send, schedule) {
  const layer = document.getElementById('dialog-layer');
  const req = [...model.requests.values()].sort((a, b) => a.id - b.id)[0];
  if (!req) {
    layer.replaceChildren();
    shownId = null;
    return;
  }
  if (req.id === shownId) {
    for (const c of layer.querySelectorAll('.card[data-key]')) {
      const card = model.objects.get(Number(c.dataset.key));
      if (card) updateCard(c, model, card);
    }
    return;
  }
  shownId = req.id;
  const answer = value => {
    model.requests.delete(req.id);
    send({ t: 'reply', id: req.id, value });
    shownId = null;
    schedule();
  };
  layer.replaceChildren(build(req, model, answer));
}

function el(tag, cls = '', text) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text !== undefined) e.textContent = text;
  return e;
}

function button(label, primary, onClick) {
  const b = el('button', primary ? 'primary' : '', label);
  b.onclick = onClick;
  return b;
}

function actions(...buttons) {
  const a = el('div', 'actions');
  a.append(...buttons);
  return a;
}

function wrap(dlg) {
  const backdrop = el('div', 'backdrop');
  backdrop.append(dlg);
  return backdrop;
}

// A deck list arrives with its sections marked out as entries of their own, which read as headings, not choices
const SECTION = /^=+\s*(.*?)\s*=+$/;

function optionElement(model, opt, onClick) {
  const section = SECTION.exec(opt.label ?? '');
  if (section && !opt.card && !opt.imageKey) {
    return el('div', 'section', section[1]);
  }
  const card = opt.card ? model.objects.get(opt.card.ref) : null;
  if (card) {
    const c = createCard(onClick);
    c.dataset.key = card.$key;
    c.title = opt.label;
    updateCard(c, model, card);
    return c;
  }
  if (opt.imageKey || opt.name) {
    const c = el('div', 'card inline');
    const img = el('img');
    img.alt = '';
    img.loading = 'lazy';
    if (opt.imageKey) {
      img.src = imageUrl(opt.imageKey);
      c.dataset.zoom = img.src;
    } else {
      c.classList.add('noimg');
    }
    noImageOnError(c, img);
    const frame = el('div', 'frame', opt.name ?? opt.label);
    c.append(img, frame);
    c.title = opt.label ?? opt.name ?? '';
    hoverable(c);
    c.onclick = onClick;
    return c;
  }
  const b = el('button', 'text-option');
  setSymbolText(b, opt.label);
  b.onclick = onClick;
  return b;
}

function build(req, model, answer) {
  const dlg = el('div', 'dialog');
  const title = el('h3');
  setSymbolText(title, req.title || req.message || '');
  dlg.append(title);
  if (req.title && req.message) {
    const text = el('p');
    setSymbolText(text, req.message);
    dlg.append(text);
  }
  switch (req.kind) {
    case 'choices':
    case 'reveal': return choices(dlg, req, model, answer);
    case 'order': return order(dlg, req, model, answer);
    case 'manipulate': return manipulate(dlg, req, model, answer);
    case 'option': return option(dlg, req, model, answer);
    case 'text': return text(dlg, req, answer);
    case 'distribute': return distribute(dlg, req, model, answer);
    case 'sideboard': return sideboard(dlg, req, model, answer);
    default:
      dlg.append(actions(button('OK', true, () => answer(req.default))));
      return wrap(dlg);
  }
}

// Naming a card offers every card face, so long lists get a search box and draw only the first matches
const SEARCH_FROM = 20;
const SHOW_AT_MOST = 200;

// Drawing a long list costs an image request per option, so typing waits for a pause
const SEARCH_DELAY_MS = 200;
let searchTimer = 0;

function choices(dlg, req, model, answer) {
  const reveal = req.kind === 'reveal';
  const picked = new Set(reveal ? [] : req.selected);
  const list = el('div', reveal ? 'options grid' : 'options');
  const note = el('p', 'hint');
  const confirm = button(reveal ? 'OK' : 'Confirm', true, () => answer(reveal ? [] : [...picked]));
  const search = req.options.length > SEARCH_FROM ? el('input', 'choice-search') : null;
  let shown = [];
  const sync = () => {
    [...list.children].forEach((o, pos) => o.classList.toggle('picked', picked.has(shown[pos])));
    confirm.disabled = !reveal && (picked.size < req.min || (req.max >= 0 && picked.size > req.max));
  };
  const toggle = i => {
    if (reveal) return;
    if (picked.has(i)) picked.delete(i);
    else {
      if (req.max === 1) picked.clear();
      picked.add(i);
    }
    sync();
  };
  const draw = () => {
    const q = search ? search.value.trim().toLowerCase() : '';
    const matches = [];
    for (let i = 0; i < req.options.length && matches.length <= SHOW_AT_MOST; i++) {
      const o = req.options[i];
      if (!q || String(o.label ?? o.name ?? '').toLowerCase().includes(q)) matches.push(i);
    }
    shown = matches.slice(0, SHOW_AT_MOST);
    list.replaceChildren(...shown.map(i => optionElement(model, req.options[i], () => toggle(i))));
    note.textContent = matches.length > SHOW_AT_MOST ? `Showing the first ${SHOW_AT_MOST} of ${req.options.length}. Type to narrow the list.`
      : search && picked.size ? `${picked.size} selected` : '';
    sync();
  };
  if (search) {
    search.placeholder = `Search ${req.options.length} options`;
    search.addEventListener('input', () => {
      clearTimeout(searchTimer);
      searchTimer = setTimeout(draw, SEARCH_DELAY_MS);
    });
    dlg.append(search);
  }
  dlg.append(list, note, actions(confirm));
  draw();
  if (search) requestAnimationFrame(() => search.focus());
  return wrap(dlg);
}

function order(dlg, req, model, answer) {
  const chosen = [...req.selected];
  let remember = false;
  const pool = el('div', 'options');
  const picked = el('div', 'options ordered');
  const confirm = button('Confirm', true, () => answer({ indices: chosen, remember }));
  const redraw = () => {
    pool.replaceChildren(...req.options.flatMap((o, i) => chosen.includes(i) ? [] : [optionElement(model, o, () => { chosen.push(i); redraw(); })]));
    picked.replaceChildren(...chosen.map((i, pos) => {
      const row = el('div', 'ordered-item');
      row.append(optionElement(model, req.options[i], () => { chosen.splice(pos, 1); redraw(); }),
        button('↑', false, () => {
          if (pos > 0) [chosen[pos - 1], chosen[pos]] = [chosen[pos], chosen[pos - 1]];
          redraw();
        }));
      return row;
    }));
    confirm.disabled = chosen.length < req.min || chosen.length > req.max;
  };
  dlg.append(el('p', 'hint', req.top || 'Pick in order'), pool, el('p', 'hint', 'Chosen, first to last'), picked);
  if (req.remember) {
    const label = el('label');
    const box = el('input');
    box.type = 'checkbox';
    box.onchange = () => { remember = box.checked; };
    label.append(box, ' Remember this order');
    dlg.append(label);
  }
  dlg.append(actions(confirm));
  redraw();
  return wrap(dlg);
}

// Scry and friends: the host passes the whole library with only the top cards movable.
// Movable cards placed after the untouched middle go to the bottom (PlayerControllerHuman.arrangeForMove).
function manipulate(dlg, req, model, answer) {
  const all = req.options.map((_, i) => i);
  const top = all.filter(i => req.movable.includes(i));
  const rest = all.filter(i => !req.movable.includes(i));
  const bottom = [];
  const topList = el('div', 'options ordered');
  const bottomList = el('div', 'options ordered');
  const row = (i, list, pos) => {
    const r = el('div', 'ordered-item');
    r.append(optionElement(model, req.options[i], () => {}));
    r.append(button('↑', false, () => {
      if (pos > 0) [list[pos - 1], list[pos]] = [list[pos], list[pos - 1]];
      redraw();
    }));
    if (list === top && req.toBottom) r.append(button('To bottom', false, () => { top.splice(pos, 1); bottom.push(i); redraw(); }));
    if (list === bottom && req.toTop) r.append(button('To top', false, () => { bottom.splice(pos, 1); top.push(i); redraw(); }));
    return r;
  };
  const redraw = () => {
    topList.replaceChildren(...top.map((i, p) => row(i, top, p)));
    bottomList.replaceChildren(...bottom.map((i, p) => row(i, bottom, p)));
  };
  dlg.append(el('p', 'hint', 'Top of library, first is on top'), topList,
    el('p', 'hint', `${rest.length} other cards`),
    el('p', 'hint', 'Bottom of library, last is at the bottom'), bottomList,
    actions(button('Confirm', true, () => answer([...top, ...rest, ...bottom]))));
  redraw();
  return wrap(dlg);
}

function option(dlg, req, model, answer) {
  if (req.card) {
    const card = model.objects.get(req.card.ref);
    if (card) {
      const c = createCard(() => {});
      updateCard(c, model, card);
      dlg.append(c);
    }
  }
  dlg.append(actions(...req.labels.map((label, i) => button(label, i === req.default, () => answer(i)))));
  return wrap(dlg);
}

function text(dlg, req, answer) {
  const input = el('input');
  input.type = req.numeric ? 'number' : 'text';
  input.value = req.initial ?? '';
  dlg.append(input, actions(button('OK', true, () => answer(input.value))));
  setTimeout(() => input.focus());
  return wrap(dlg);
}

function distribute(dlg, req, model, answer) {
  const values = [...req.default];
  const remaining = el('p', 'hint');
  const confirm = button('Confirm', true, () => answer(values));
  const rows = req.options.map((opt, i) => {
    const r = el('div', 'row');
    const count = el('b', '', String(values[i]));
    const step = delta => () => {
      const next = values[i] + delta;
      if (next < req.perMin) return;
      values[i] = next;
      count.textContent = String(next);
      sync();
    };
    r.append(optionElement(model, opt, () => {}), button('−', false, step(-1)), count, button('+', false, step(1)));
    return r;
  });
  const sync = () => {
    const left = req.amount - values.reduce((a, b) => a + b, 0);
    remaining.textContent = `${left} left to assign`;
    confirm.disabled = left !== 0;
  };
  dlg.append(...rows, remaining, actions(...(req.maySkip ? [button('Skip', false, () => answer(null))] : []), confirm));
  sync();
  return wrap(dlg);
}

// Between games: move copies between the main deck and the sideboard. The host re-asks if the deck is illegal
function sideboard(dlg, req, model, answer) {
  const inMain = [...req.main];
  const mainList = el('div', 'sb-list');
  const sideList = el('div', 'sb-list');
  const mainHead = el('h4');
  const sideHead = el('h4');
  const row = (i, count, arrow, move) => {
    const r = el('div', 'sb-row');
    const card = optionElement(model, req.entries[i], move);
    const n = el('span', 'sb-count', `×${count}`);
    r.append(card, el('span', 'sb-name', req.entries[i].name), n, button(arrow, false, move));
    return r;
  };
  const redraw = () => {
    const mainTotal = inMain.reduce((a, b) => a + b, 0);
    const sideTotal = req.entries.reduce((a, e, i) => a + e.total - inMain[i], 0);
    mainHead.textContent = `Main deck (${mainTotal})`;
    sideHead.textContent = `Sideboard (${sideTotal})`;
    mainList.replaceChildren(...req.entries.flatMap((e, i) => inMain[i] > 0
      ? [row(i, inMain[i], '→', () => { inMain[i]--; redraw(); })] : []));
    sideList.replaceChildren(...req.entries.flatMap((e, i) => e.total - inMain[i] > 0
      ? [row(i, e.total - inMain[i], '←', () => { inMain[i]++; redraw(); })] : []));
  };
  const columns = el('div', 'sb-columns');
  const left = el('div');
  left.append(mainHead, mainList);
  const right = el('div');
  right.append(sideHead, sideList);
  columns.append(left, right);
  dlg.append(el('p', 'hint', 'Click a card or its arrow to move one copy.'), columns,
    actions(button('Reset', false, () => { req.main.forEach((n, i) => { inMain[i] = n; }); redraw(); }), button('Done', true, () => answer(inMain))));
  redraw();
  return wrap(dlg);
}
