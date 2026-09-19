// The server replays full state, prompt, zones and open requests on every connect, so a reconnect needs no bookkeeping here.
export function connect(onMessage, onStatus) {
  let socket;
  let retry = 250;
  const open = () => {
    socket = new WebSocket(`ws://${location.host}/ws`);
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
