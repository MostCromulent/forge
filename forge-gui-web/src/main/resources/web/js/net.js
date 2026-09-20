// The server replays full state, prompt, zones and open requests on every connect, so a reconnect needs no bookkeeping here.
// The id says which browser this is, so a reload returns to the seat it left instead of taking another one.
const ID_KEY = 'forge.clientId';

function clientId() {
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

export function connect(onMessage, onStatus) {
  let socket;
  let retry = 250;
  const id = clientId();
  const open = () => {
    socket = new WebSocket(`ws://${location.host}/ws?client=${encodeURIComponent(id)}`);
    socket.onopen = () => { retry = 250; onStatus(true); };
    socket.onmessage = e => onMessage(JSON.parse(e.data));
    socket.onclose = () => {
      onStatus(false);
      setTimeout(open, retry);
      retry = Math.min(retry * 2, 4000);
    };
  };
  open();
  return msg => {
    if (socket?.readyState === WebSocket.OPEN) socket.send(JSON.stringify(msg));
  };
}
