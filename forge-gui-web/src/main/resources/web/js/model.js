// Browser copy of the game's object table; forge.web.BrowserModel implements the same apply-and-prune rules
export function createModel() {
  return {
    objects: new Map(), root: -1, visible: new Set(), localPlayers: [],
    prompt: null, zones: [], requests: new Map(), gameOver: false, controls: null, playable: null,
    looks: null, spectating: false,
    inMatch: false, playerName: '', decks: [], error: null,
    lobby: null, addresses: null, host: true,
  };
}

export function applyState(model, msg) {
  if (msg.full) {
    model.objects.clear();
    model.root = msg.root;
  }
  for (const [k, props] of Object.entries(msg.newObjects)) {
    model.objects.set(Number(k), { ...props, $key: Number(k) });
  }
  for (const [k, props] of Object.entries(msg.deltas)) {
    const target = model.objects.get(Number(k));
    if (!target) continue;
    for (const [name, value] of Object.entries(props)) {
      if (value === null) delete target[name];
      else target[name] = value;
    }
  }
  prune(model);
  model.visible = new Set(msg.visible);
  model.localPlayers = msg.localPlayers;
}

// Packets carry no removal signal, so anything the game no longer reaches is dropped here
function prune(model) {
  if (!model.objects.has(model.root)) return;
  const reachable = new Set();
  const todo = [model.root];
  while (todo.length) {
    const key = todo.pop();
    if (reachable.has(key)) continue;
    reachable.add(key);
    const o = model.objects.get(key);
    if (o) collectRefs(o, todo);
  }
  for (const key of [...model.objects.keys()]) {
    if (!reachable.has(key)) model.objects.delete(key);
  }
}

function collectRefs(value, out) {
  if (Array.isArray(value)) {
    value.forEach(v => collectRefs(v, out));
  } else if (value && typeof value === 'object') {
    const keys = Object.keys(value);
    if (keys.length === 1 && keys[0] === 'ref') {
      out.push(value.ref);
      return;
    }
    for (const [k, v] of Object.entries(value)) {
      if (k !== '$key') collectRefs(v, out);
    }
  }
}

export const deref = (model, v) => (v && typeof v === 'object' && 'ref' in v) ? model.objects.get(v.ref) : undefined;
export const derefAll = (model, list) => (list ?? []).map(v => deref(model, v)).filter(Boolean);
export const game = model => model.objects.get(model.root);
export const players = model => derefAll(model, game(model)?.Players);
export const isLocal = (model, player) => model.localPlayers.includes(player.$key);
export const me = model => players(model).find(p => isLocal(model, p));
export const opponents = model => players(model).filter(p => !isLocal(model, p));
export const zone = (model, player, name) => derefAll(model, player?.[name]);
export const stateOf = (model, card) => deref(model, card.CurrentState) ?? {};
