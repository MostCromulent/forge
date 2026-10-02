// Conquest's preferences: mobile's twenty numbers in its four groups. Each is saved as it is changed, since the
// server checks one against the others as they stand, and every conquest shares them.

import { useEffect, useState } from 'preact/hooks';
import type { Actions } from './actions';
import type { Model } from './model';
import { t } from './text';

export function Prefs({ model, actions, close }: { model: Model; actions: Actions; close: () => void }) {
  const p = model.conquestPrefs;
  /** What has been typed and not yet accepted, by key. A refused value stays in its field for the player to mend. */
  const [typed, setTyped] = useState<Record<string, string>>({});
  const [last, setLast] = useState<string | null>(null);
  useEffect(() => { actions.conquestPrefs(); }, []);
  useEffect(() => { if (p && !p.problem) setTyped({}); }, [p]);
  if (!p) return null;
  const groups = [...new Set(p.rows.map(r => r.group))];
  const save = (key: string) => {
    const text = typed[key];
    if (text === undefined || text.trim() === '' || !Number.isInteger(Number(text))) return;
    setLast(key);
    actions.conquestPref(key, Number(text));
  };
  return (
    <div class="backdrop" onMouseDown={e => { if (e.target === e.currentTarget) close(); }}>
      <div class="dialog cq-prefs">
        <header><h3>{t('lblConquestPreference')}</h3><button aria-label={t('lblClose')} onClick={close}>×</button></header>
        <div class="cq-prefs-body">
          {groups.map(g => (
            <section key={g}>
              <h4>{g}</h4>
              {p.rows.filter(r => r.group === g).map(r => (
                <label key={r.key} class={p.problem && last === r.key ? 'cq-pref bad' : 'cq-pref'}>
                  <span>{r.label}</span>
                  <input type="number" min={0} step={1} value={typed[r.key] ?? String(r.value)}
                    onInput={e => setTyped({ ...typed, [r.key]: e.currentTarget.value })}
                    onBlur={() => save(r.key)}
                    onKeyDown={e => { if (e.key === 'Enter') save(r.key); }} />
                </label>
              ))}
            </section>
          ))}
        </div>
        <footer>
          <span class={p.problem ? 'cq-warn' : 'muted'}>{p.problem ?? t('lblWebConquestPrefsShared')}</span>
          <button onClick={() => { setTyped({}); setLast(null); actions.conquestPrefsReset(); }}>{t('lblReset')}</button>
          <button class="primary" onClick={close}>{t('lblOK')}</button>
        </footer>
      </div>
    </div>
  );
}
