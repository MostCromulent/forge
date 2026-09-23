import type { Send } from './protocol';

// The conversation with the other players, shown in match setup and again beside the board. Both places
// read the same list, so a line said in the lobby is still there when the game starts.

let send: Send = () => {};
let lines: { from: string; text: string }[] = [];

export function initChat(sendFn: Send): void {
  send = sendFn;
}

export function addChat(from: string, text: string): void {
  lines = [...lines, { from, text }];
}

export function clearChat(): void {
  lines = [];
}

/** Sends on Enter. The field is left empty whether or not anything was said. */
export function wireChatInput(input: HTMLInputElement): void {
  input.onkeydown = e => {
    if (e.key !== 'Enter') {
      return;
    }
    // The board reads single keys as commands, so a message being typed must not reach it
    e.stopPropagation();
    const text = input.value.trim();
    input.value = '';
    if (text) {
      send({ t: 'chat', text });
    }
  };
}

export function paintChat(log: HTMLElement): void {
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
