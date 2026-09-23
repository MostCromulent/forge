// The conversation with the other players, shown in match setup and again beside the board. Both places read the
// model's one list, so a line said in the lobby is still there when the game starts.

import type { Model } from './model';

/** Sends on Enter. The field is left empty whether or not anything was said. */
export function wireChatInput(input: HTMLInputElement, say: (text: string) => void): void {
  input.onkeydown = e => {
    if (e.key !== 'Enter') {
      return;
    }
    // The board reads single keys as commands, so a message being typed must not reach it
    e.stopPropagation();
    const text = input.value.trim();
    input.value = '';
    if (text) {
      say(text);
    }
  };
}

export function paintChat(log: HTMLElement, model: Model): void {
  const lines = model.chat;
  if (log.childElementCount === lines.length) {
    return;
  }
  log.replaceChildren(...lines.map(l => {
    const p = document.createElement('p');
    p.className = 'chat-line';
    const from = document.createElement('b');
    from.textContent = l.from;
    const text = document.createElement('span');
    text.textContent = l.text;
    p.append(from, ' ', text);
    return p;
  }));
  log.scrollTop = log.scrollHeight;
}
