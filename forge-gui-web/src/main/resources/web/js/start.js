let built = false;

export function renderStart(model, send) {
  const root = document.getElementById('start');
  if (!built) {
    root.innerHTML = `
      <div class="start-card">
        <h1>Forge</h1>
        <label>Your name <input id="player-name" maxlength="40"></label>
        <label>Your deck <select id="player-deck"></select></label>
        <label>Opponent deck <select id="ai-deck"></select></label>
        <p id="start-error" class="error" hidden></p>
        <div class="actions"><button id="quit">Quit</button><button id="play" class="primary">Play</button></div>
      </div>`;
    root.querySelector('#play').onclick = () => send({
      t: 'start',
      playerName: root.querySelector('#player-name').value,
      playerDeck: root.querySelector('#player-deck').value,
      aiDeck: root.querySelector('#ai-deck').value,
    });
    root.querySelector('#quit').onclick = () => send({ t: 'quit' });
    built = true;
  }
  const name = root.querySelector('#player-name');
  if (!name.value) name.value = model.playerName;
  fillDecks(root.querySelector('#player-deck'), model.decks);
  fillDecks(root.querySelector('#ai-deck'), model.decks);
  const err = root.querySelector('#start-error');
  err.hidden = !model.error;
  err.textContent = model.error ?? '';
}

function fillDecks(select, decks) {
  if (select.options.length === decks.length) return;
  const chosen = select.value;
  select.replaceChildren(...decks.map(d => {
    const o = new Option(d.problem ? `${d.name} (not legal)` : d.name, d.key);
    o.title = d.problem ?? '';
    return o;
  }));
  if (chosen) select.value = chosen;
}
