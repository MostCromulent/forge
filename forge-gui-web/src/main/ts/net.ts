import type { ClientMessage, Send, ServerMessage } from './protocol';

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

// The server replays full state, prompt, zones and open requests on every connect, so a reconnect needs no bookkeeping here.
export function connect(onMessage: (msg: ServerMessage) => void, onStatus: (online: boolean) => void): Send {
  let socket: WebSocket | undefined;
  let retry = 250;
  let waiting: ReturnType<typeof setTimeout> | undefined;
  let everOpen = false;
  const id = clientId();
  const open = () => {
    // A page served over https may only open a secure socket, because ws:// is blocked there as mixed content
    const scheme = location.protocol === 'https:' ? 'wss:' : 'ws:';
    socket = new WebSocket(`${scheme}//${location.host}/ws?client=${encodeURIComponent(id)}`);
    socket.onopen = () => {
      retry = 250;
      everOpen = true;
      onStatus(true);
    };
    socket.onmessage = e => onMessage(JSON.parse(e.data as string) as ServerMessage);
    socket.onclose = () => {
      onStatus(false);
      waiting = setTimeout(open, retry);
      retry = Math.min(retry * 2, 4000);
    };
  };
  open();
  // A phone back from sleep finds its socket dead, and should not wait out the backoff to try again
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState !== 'visible' || socket?.readyState === WebSocket.OPEN || socket?.readyState === WebSocket.CONNECTING) return;
    clearTimeout(waiting);
    retry = 250;
    open();
  });
  return (msg: ClientMessage) => {
    if (socket?.readyState === WebSocket.OPEN) socket.send(JSON.stringify(msg));
    // Said, not swallowed: a tap that went nowhere looks like a game that has stopped
    else if (everOpen) onStatus(false);
  };
}
