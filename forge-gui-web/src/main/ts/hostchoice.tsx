// A question the host asks outside a match, such as which net deck category to fetch. The engine thread
// waits for the answer, so this always replies — cancelling sends an empty choice rather than nothing.

import { useEffect, useRef } from 'preact/hooks';
import type { Actions } from './actions';
import type { HostChoice as Question } from './protocol';

export function HostChoice({ question, actions }: { question: Question; actions: Actions }) {
  const first = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    first.current?.focus();
  }, []);
  const answer = (value: number[]) => actions.answerHostChoice(question.id, value);
  return (
    <div class="host-back" onKeyDown={e => {
      if (e.key === 'Escape') {
        e.stopPropagation();
        answer([]);
      }
    }}>
      <div class="host-choice">
        <h2>{question.message || 'Choose'}</h2>
        <div class="host-options">
          {question.options.map((name, i) => (
            <button key={i} ref={i === 0 ? first : undefined} class="host-option" onClick={() => answer([i])}>{name}</button>
          ))}
        </div>
        <footer><button class="host-cancel" onClick={() => answer([])}>Cancel</button></footer>
      </div>
    </div>
  );
}
