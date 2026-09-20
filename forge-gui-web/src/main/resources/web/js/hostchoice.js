// A question the host asks outside a match, such as which net deck category to fetch. The engine thread
// waits for the answer, so this always replies — cancelling sends an empty choice rather than nothing.

let overlay = null;
let send = null;

export function initHostChoice(sendFn) {
  send = sendFn;
}

export function onHostChoice(msg) {
  close();
  overlay = document.createElement('div');
  overlay.className = 'host-back';
  overlay.innerHTML = `
    <div class="host-choice">
      <h2></h2>
      <div class="host-options"></div>
      <footer><button class="host-cancel">Cancel</button></footer>
    </div>`;
  overlay.querySelector('h2').textContent = msg.message || 'Choose';
  document.getElementById('dialog-layer').append(overlay);
  const list = overlay.querySelector('.host-options');
  list.replaceChildren(...msg.options.map((name, i) => {
    const b = document.createElement('button');
    b.className = 'host-option';
    b.textContent = name;
    b.onclick = () => answer(msg.id, [i]);
    return b;
  }));
  overlay.querySelector('.host-cancel').onclick = () => answer(msg.id, []);
  overlay.addEventListener('keydown', e => {
    if (e.key === 'Escape') {
      e.stopPropagation();
      answer(msg.id, []);
    }
  });
  overlay.querySelector('.host-option')?.focus();
}

function answer(id, value) {
  send({ t: 'hostChoice', id, value });
  close();
}

function close() {
  overlay?.remove();
  overlay = null;
}
