import type { ClientMessage, Send, ServerMessage } from './protocol';

// The server replays full state, prompt, zones and open requests on every connect, so a reconnect needs no bookkeeping here.
// The id says which browser this is, so a reload returns to the seat it left instead of taking another one.
const ID_KEY = 'forge.clientId';

function clientId(): string {
  try {
    let id = localStorage.getItem(ID_KEY);
    if (!id) {
      id = crypto.randomUUID();
      localStorage.setItem(ID_KEY, id);
    }
    return id;
  } catch {
    // Storage can be unavailable; the seat then lasts only as long as the socket
    return '';
  }
}

export function connect(onMessage: (msg: ServerMessage) => void, onStatus: (online: boolean) => void): Send {
  let socket: WebSocket | undefined;
  let retry = 250;
  const id = clientId();
  const open = () => {
    socket = new WebSocket(`ws://${location.host}/ws?client=${encodeURIComponent(id)}`);
    socket.onopen = () => { retry = 250; onStatus(true); };
    socket.onmessage = e => onMessage(JSON.parse(e.data as string) as ServerMessage);
    socket.onclose = () => {
      onStatus(false);
      setTimeout(open, retry);
      retry = Math.min(retry * 2, 4000);
    };
  };
  open();
  return (msg: ClientMessage) => {
    if (socket?.readyState === WebSocket.OPEN) socket.send(JSON.stringify(msg));
  };
}
