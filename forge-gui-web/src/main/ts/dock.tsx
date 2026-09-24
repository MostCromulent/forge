// Who is on this server and what they are saying, in one panel that follows you in. Before a match it rises out
// of the bottom edge; during one it is a widget under the log in the side panel, and the roster collapses to a
// row, because every seated player already carries a name on the board. What the board never says is who is
// watching, who holds the host's seat and who has dropped, so that is what the row keeps.

import { useEffect, useRef, useState } from 'preact/hooks';
import { avatarUrl } from './looks';
import type { Actions } from './actions';
import type { Model } from './model';
import type { Person } from './protocol';

/** What each person is doing, in the words the roster shows. A seat at a table is worth naming; waiting is not. */
const DOING: Record<string, string> = {
  waiting: 'waiting', joining: 'joining', table: 'at the table', playing: 'playing', watching: 'watching',
};

function Crown() {
  return (
    <svg class="crown" role="img" aria-label="Host" viewBox="0 0 24 24">
      <path d="M4.5 18h15M5 7.2l4.2 3.4L12 5.2l2.8 5.4L19 7.2l-1.1 8.3H6.1z" />
    </svg>
  );
}

export function Dock({ model, actions }: { model: Model; actions: Actions }) {
  const [open, setOpen] = useState(false);
  const [listed, setListed] = useState(false);
  const [text, setText] = useState('');
  const log = useRef<HTMLDivElement>(null);
  // A new line is only worth reading if you can see it
  useEffect(() => {
    if (log.current) log.current.scrollTop = log.current.scrollHeight;
  }, [model.chat.length, open]);

  const people = model.presence;
  if (!people.length) {
    return null;
  }
  const inMatch = model.inMatch;
  // In a match the roster is a summary until asked, because the board already names everyone with a seat
  const showList = !inMatch || listed;
  const host = people.find(p => p.host);
  const watching = people.filter(p => p.doing === 'watching').length;
  const seated = people.filter(p => p.doing !== 'watching');
  if (!inMatch && !open) {
    return (
      <button class="dock folded" onClick={() => setOpen(true)}>
        <span class="faces" aria-hidden="true">
          {people.slice(0, 3).map(p => <img key={p.name} alt="" src={avatarUrl(p.avatar)} />)}
        </span>
        <span class="dock-count">{people.length} here</span>
      </button>
    );
  }
  return (
    <section class={inMatch ? 'dock open in-match' : 'dock open'} aria-label="Who is here">
      {inMatch ? (
        <button class="dock-head" aria-expanded={listed} onClick={() => setListed(!listed)}>
          <span class="faces" aria-hidden="true">
            {seated.slice(0, 4).map(p => <img key={p.name} alt="" src={avatarUrl(p.avatar)} />)}
          </span>
          <span class="dock-sum">
            {host ? `${host.name} hosts` : 'At this table'}{watching ? ` · ${watching} watching` : ''}
          </span>
          <Chevron up={!listed} />
        </button>
      ) : (
        <button class="dock-head" aria-expanded onClick={() => setOpen(false)}>
          <span class="live" aria-hidden="true" />
          <b>On this server</b>
          <span class="dock-count">{people.length}</span>
          <Chevron up={false} />
        </button>
      )}
      {showList && (
        <ul class="roster">
          {people.map(p => (
            <li key={p.name} class={p.doing === 'watching' ? 'watching' : ''}>
              <img class="face-small" alt="" src={avatarUrl(p.avatar)} />
              <span class="who">{p.name}</span>
              {p.host && <Crown />}
              <span class="spacer" />
              <span class="doing">{DOING[p.doing] ?? p.doing}</span>
            </li>
          ))}
        </ul>
      )}
      <div class="dock-log" ref={log}>
        {model.chat.map((line, i) => (
          line.from === null || line.from === undefined
            ? <p key={i} class="said note">{line.text}</p>
            : <p key={i} class="said"><img class="face-small" alt="" src={avatarUrl(faceOf(people, line.from))} /><span><b>{line.from}</b> {line.text}</span></p>
        ))}
      </div>
      <form class="dock-say" onSubmit={e => {
        e.preventDefault();
        const said = text.trim();
        if (said) {
          actions.say(said);
          setText('');
        }
      }}>
        <input value={text} maxLength={240} placeholder="Say something" aria-label="Say something"
          onInput={e => setText(e.currentTarget.value)} />
      </form>
    </section>
  );
}

function Chevron({ up }: { up: boolean }) {
  return (
    <svg class="chev" aria-hidden="true" viewBox="0 0 24 24">
      <path d={up ? 'M6.5 14.5l5.5-5.5 5.5 5.5' : 'M6.5 9.5l5.5 5.5 5.5-5.5'} />
    </svg>
  );
}

/** The face a line was said under. Someone who has since left keeps the first avatar rather than none. */
function faceOf(people: readonly Person[], name: string): number {
  return people.find(p => p.name === name)?.avatar ?? 0;
}
