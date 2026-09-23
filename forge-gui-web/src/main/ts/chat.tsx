// The conversation with the other players, shown in match setup and again beside the board. Both places read the
// model's one list, so a line said in the lobby is still there when the game starts.

import { useLayoutEffect, useRef } from 'preact/hooks';
import type { Model } from './model';

export function ChatLog({ model, id, class: cls }: { model: Model; id?: string; class?: string }) {
  const log = useRef<HTMLDivElement>(null);
  const lines = model.chat.length;
  // The newest line is the one worth reading, so a new one scrolls into view
  useLayoutEffect(() => {
    const el = log.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [lines]);
  return (
    <div class={cls} id={id} ref={log}>
      {model.chat.map((l, i) => <p key={i} class="chat-line"><b>{l.from}</b> <span>{l.text}</span></p>)}
    </div>
  );
}

/** Sends on Enter. The field is left empty whether or not anything was said. */
export function ChatInput({ say, id }: { say: (text: string) => void; id?: string }) {
  return (
    <input id={id} type="text" placeholder="Say something" maxLength={240} onKeyDown={e => {
      if (e.key !== 'Enter') {
        return;
      }
      // The board reads single keys as commands, so a message being typed must not reach it
      e.stopPropagation();
      const input = e.currentTarget;
      const text = input.value.trim();
      input.value = '';
      if (text) {
        say(text);
      }
    }} />
  );
}
