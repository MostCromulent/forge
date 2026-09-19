let built = false;

export function renderPrompt(model, send) {
  const root = document.getElementById('prompt');
  if (!built) {
    root.innerHTML = '<p class="message"></p><div class="buttons"><button class="cancel"></button><button class="ok primary"></button></div>';
    root.querySelector('.ok').onclick = () => send({ t: 'ok' });
    root.querySelector('.cancel').onclick = () => send({ t: 'cancel' });
    document.addEventListener('keydown', e => {
      if (e.target instanceof HTMLInputElement || document.querySelector('#dialog-layer .dialog')) return;
      const ok = root.querySelector('.ok');
      const cancel = root.querySelector('.cancel');
      if ((e.key === ' ' || e.key === 'Enter') && !ok.disabled) {
        e.preventDefault();
        ok.click();
      } else if (e.key === 'Escape' && !cancel.disabled) {
        cancel.click();
      }
    });
    built = true;
  }
  const p = model.prompt;
  if (!p) return;
  root.querySelector('.message').textContent = p.message ?? '';
  setButton(root.querySelector('.ok'), p.ok);
  setButton(root.querySelector('.cancel'), p.cancel);
  root.querySelector('.ok').classList.toggle('focus', !!p.focusOk);
}

function setButton(button, spec) {
  button.textContent = spec?.label ?? '';
  button.disabled = !spec?.enabled;
  button.hidden = !spec?.label;
}

export function flash() {
  const root = document.getElementById('prompt');
  root.classList.remove('flash');
  void root.offsetWidth;
  root.classList.add('flash');
}

export function showNotice(n) {
  const el = document.createElement('div');
  el.className = n.error ? 'notice error' : 'notice';
  const title = document.createElement('b');
  title.textContent = n.title ?? '';
  const body = document.createElement('div');
  body.textContent = n.message ?? '';
  el.append(title, body);
  el.onclick = () => el.remove();
  document.getElementById('notices').append(el);
  if (!n.error) setTimeout(() => el.remove(), 6000);
}
