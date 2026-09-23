import { byId, q } from './dom';
import type { HostChoice, Send } from './protocol';

// A question the host asks outside a match, such as which net deck category to fetch. The engine thread
// waits for the answer, so this always replies — cancelling sends an empty choice rather than nothing.

let overlay: HTMLElement | null = null;
let send: Send = () => {};

export function initHostChoice(sendFn: Send): void {
  send = sendFn;
}

export function onHostChoice(msg: HostChoice): void {
  close();
  const back = document.createElement('div');
  overlay = back;
  back.className = 'host-back';
  back.innerHTML = `
    <div class="host-choice">
      <h2></h2>
      <div class="host-options"></div>
      <footer><button class="host-cancel">Cancel</button></footer>
    </div>`;
  q(back, 'h2').textContent = msg.message || 'Choose';
  byId('dialog-layer').append(back);
  const list = q(back, '.host-options');
  list.replaceChildren(...msg.options.map((name, i) => {
    const b = document.createElement('button');
    b.className = 'host-option';
    b.textContent = name;
    b.onclick = () => answer(msg.id, [i]);
    return b;
  }));
  q(back, '.host-cancel').onclick = () => answer(msg.id, []);
  back.addEventListener('keydown', e => {
    if (e.key === 'Escape') {
      e.stopPropagation();
      answer(msg.id, []);
    }
  });
  back.querySelector<HTMLElement>('.host-option')?.focus();
}

function answer(id: number, value: number[]): void {
  send({ t: 'hostChoice', id, value });
  close();
}

function close(): void {
  overlay?.remove();
  overlay = null;
}
