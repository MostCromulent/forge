const MAX_ENTRIES = 400;

// The server replays the whole log on connect (full) and sends new entries after that
export function appendLog(msg) {
  const root = document.getElementById('log');
  if (msg.full) root.replaceChildren();
  const atBottom = root.scrollHeight - root.scrollTop - root.clientHeight < 20;
  for (const entry of msg.entries) {
    const el = document.createElement('div');
    el.className = `log-entry ${entry.type.toLowerCase()}`;
    el.textContent = entry.message;
    root.append(el);
  }
  while (root.childElementCount > MAX_ENTRIES) root.firstChild.remove();
  if (atBottom || msg.full) root.scrollTop = root.scrollHeight;
}
