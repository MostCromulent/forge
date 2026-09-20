// The conversation with the other players, shown in match setup and again beside the board. Both places
// read the same list, so a line said in the lobby is still there when the game starts.

let send = null;
let lines = [];

export function initChat(sendFn) {
  send = sendFn;
}

export function addChat(from, text) {
  lines = [...lines, { from, text }];
}

export function clearChat() {
  lines = [];
}

/** Sends on Enter. The field is left empty whether or not anything was said. */
export function wireChatInput(input) {
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

export function paintChat(log) {
  if (log.childElementCount === lines.length) {
    return;
  }
  log.replaceChildren(...lines.map(l => {
    const p = document.createElement('p');
    p.className = 'chat-line';
    p.innerHTML = '<b></b> <span></span>';
    p.querySelector('b').textContent = l.from;
    p.querySelector('span').textContent = l.text;
    return p;
  }));
  log.scrollTop = log.scrollHeight;
}
