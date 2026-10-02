// Who is on this server and what they are saying: a panel at the bottom edge before a match, a widget under the log during one

import { useEffect, useRef, useState } from 'preact/hooks';
import { avatarUrl } from './looks';
import { LookPicker } from './lookpicker';
import { rememberAvatar } from './menu';
import { logTints } from './log';
import { players, type ChatEntry, type Model } from './model';
import type { Actions } from './actions';
import type { Person } from './protocol';
import { t, type TextKey } from './text';

/** What each person is doing, in the words the roster shows. A seat at a table is worth naming; waiting is not. */
const DOING: Record<string, TextKey> = {
  waiting: 'lblWebDockWaiting', joining: 'lblWebDockJoining', table: 'lblWebDockAtTheTable', playing: 'lblWebDockPlaying',
  watching: 'lblWebDockWatching',
};

function Crown() {
  return (
    <svg class="crown" role="img" aria-label={t('lblHost')} viewBox="0 0 24 24">
      <path d="M4.5 18h15M5 7.2l4.2 3.4L12 5.2l2.8 5.4L19 7.2l-1.1 8.3H6.1z" />
    </svg>
  );
}

/** rename, when given, puts an edit button on your own row: the name is changed from the menu only. */
export function Dock({ model, actions, rename }: { model: Model; actions: Actions; rename?: () => void }) {
  const [open, setOpen] = useState(false);
  const [closing, setClosing] = useState(false);
  const [listed, setListed] = useState(false);
  const [text, setText] = useState('');
  const [picking, setPicking] = useState(false);
  const log = useRef<HTMLDivElement>(null);
  // A new line is only worth reading if you can see it
  useEffect(() => {
    if (log.current) log.current.scrollTop = log.current.scrollHeight;
  }, [model.chat.length, open]);
  // The last line you could have read, held by the line itself so a chat replayed after a reconnect is counted from its start
  const lastSeen = useRef<ChatEntry | undefined>(model.chat[model.chat.length - 1]);
  if (open || model.inMatch) {
    lastSeen.current = model.chat[model.chat.length - 1];
  }

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
  const tints = chatTints(model);
  if (!inMatch && !open) {
    const unread = unreadSince(model.chat, lastSeen.current, model.playerName);
    return (
      <button class={unread ? 'dock folded unread' : 'dock folded'} onClick={() => setOpen(true)}>
        <span class="faces" aria-hidden="true">
          {people.slice(0, 3).map(p => <img key={p.name} alt="" src={avatarUrl(p.avatar)} />)}
        </span>
        <span class="dock-count">{t('lblWebDockHere', people.length)}</span>
        {/* Keyed on the count, so each new line redraws it and it pulses again */}
        {unread > 0 && <span key={unread} class="dock-new">{t('lblWebDockNew', unread > 9 ? '9+' : unread)}</span>}
      </button>
    );
  }
  // Before a match your own face in the roster changes it; in one, the seat's face is already fixed
  const picker = picking && (
    <LookPicker title={t('lblWebMenuChooseYourAvatar')} count={model.looks?.avatarCount ?? 0} urlOf={avatarUrl}
      current={people.find(p => p.name === model.playerName)?.avatar ?? 0} close={chosen => {
        setPicking(false);
        if (chosen !== null) {
          rememberAvatar(chosen);
          actions.setName(model.playerName, chosen);
        }
      }} />
  );
  return (<>
    <section class={inMatch ? 'dock open in-match' : closing ? 'dock open closing' : 'dock open'} aria-label={t('lblWebDockWhoIsHere')}
      onAnimationEnd={e => {
        // The panel folds once it has slid down, so the bar takes its place without a jump
        if (e.animationName === 'dock-sink') {
          setClosing(false);
          setOpen(false);
        }
      }}>
      {inMatch ? (
        <button class="dock-head" aria-expanded={listed} onClick={() => setListed(!listed)}>
          <span class="faces" aria-hidden="true">
            {seated.slice(0, 4).map(p => <img key={p.name} alt="" src={avatarUrl(p.avatar)} />)}
          </span>
          <span class="dock-sum">
            {host ? t('lblWebDockHosts', host.name) : t('lblWebDockAtThisTable')}{watching ? ` · ${t('lblWebDockNumWatching', watching)}` : ''}
          </span>
          <Chevron up={!listed} />
        </button>
      ) : (
        <button class="dock-head" aria-expanded onClick={() => setClosing(true)}>
          <span class="live" aria-hidden="true" />
          <b>{t('lblWebDockOnThisServer')}</b>
          <span class="dock-count">{people.length}</span>
          <Chevron up={false} />
        </button>
      )}
      {showList && (
        <ul class="roster">
          {people.map(p => (
            <li key={p.name} class={p.doing === 'watching' ? 'watching' : ''}>
              {!inMatch && p.name === model.playerName
                ? <button class="face-change" title={t('lblWebMenuSelectAvatar')} aria-label={t('lblWebMenuSelectAvatar')} onClick={() => setPicking(true)}>
                    <img class="face-small" alt="" src={avatarUrl(p.avatar)} /></button>
                : <img class="face-small" alt="" src={avatarUrl(p.avatar)} />}
              <span class="who">{p.name}</span>
              {p.host && <Crown />}
              {rename && p.name === model.playerName && (
                <button class="rename" title={t('lblWebDockRename')} aria-label={t('lblWebDockRename')} onClick={rename}>
                  <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4.5 19.5h5L20 9a2.6 2.6 0 0 0-3.7-3.7L5.8 15.8z" /></svg>
                </button>
              )}
              <span class="spacer" />
              <span class="doing">{DOING[p.doing] ? t(DOING[p.doing]) : p.doing}</span>
            </li>
          ))}
        </ul>
      )}
      <div class="dock-log" ref={log}>
        {model.chat.map((line, i) => (
          !line.from
            ? <p key={i} class="said note">{line.text}</p>
            : <p key={i} class="said"><img class="face-small" alt="" src={avatarUrl(faceOf(people, line.from))} /><span><b style={{ color: tints.get(line.from) }}>{line.from}</b> {line.text}</span></p>
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
        <input value={text} maxLength={240} placeholder={t('lblWebDockSay')} aria-label={t('lblWebDockSay')}
          onInput={e => setText(e.currentTarget.value)} />
      </form>
    </section>
    {picker}
  </>);
}

function Chevron({ up }: { up: boolean }) {
  return (
    <svg class="chev" aria-hidden="true" viewBox="0 0 24 24">
      <path d={up ? 'M6.5 14.5l5.5-5.5 5.5 5.5' : 'M6.5 9.5l5.5 5.5 5.5-5.5'} />
    </svg>
  );
}

/** Lines someone else said since the last one you could have read, leaving out the server's own and any said before you arrived. */
export function unreadSince(chat: readonly ChatEntry[], lastSeen: ChatEntry | undefined, me: string): number {
  const from = lastSeen ? chat.lastIndexOf(lastSeen) + 1 : 0;
  return chat.slice(from).filter(l => l.from && !l.earlier && l.from !== me).length;
}

/** Each name's colour: in a match the log's, taken in seat order, then everyone watching after them. */
function chatTints(model: Model): Map<string, string> {
  const names = new Set([...players(model).map(p => p.Name ?? ''), ...model.presence.map(p => p.name)]);
  return new Map(logTints([...names].map(name => ({ name, local: name === model.playerName }))).map(n => [n.name, n.colour]));
}

/** The face a line was said under. Someone who has since left keeps the first avatar rather than none. */
function faceOf(people: readonly Person[], name: string): number {
  return people.find(p => p.name === name)?.avatar ?? 0;
}
