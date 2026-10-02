// The engine thread waits for the answer to a question the host is asked, so cancelling sends an empty choice instead of nothing

import { useEffect, useRef } from 'preact/hooks';
import { t } from './text';
import type { Actions } from './actions';
import type { HostChoice as Question } from './protocol';

export function HostChoice({ question, actions }: { question: Question; actions: Actions }) {
  const first = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    first.current?.focus();
  }, []);
  const answer = (value: number[]) => actions.answerHostChoice(question.id, value);
  // Desktop's dialog of a few buttons: its title, its message line by line, and the buttons in a row
  if (question.kind === 'confirm') {
    return (
      <div class="host-back">
        <div class="host-choice confirm" role="alertdialog" aria-label={question.title ?? question.message ?? ''}>
          <h2>{question.title || question.message}</h2>
          {question.title && question.message && <p class="host-message">{question.message}</p>}
          <footer>
            {question.options.map((name, i) => (
              <button key={i} ref={i === 0 ? first : undefined} class={i === 0 ? 'primary' : ''} onClick={() => answer([i])}>{name}</button>
            ))}
          </footer>
        </div>
      </div>
    );
  }
  return (
    <div class="host-back">
      <div class="host-choice">
        <h2>{question.message || t('lblChoose')}</h2>
        <div class="host-options">
          {question.options.map((name, i) => (
            <button key={i} ref={i === 0 ? first : undefined} class="host-option" onClick={() => answer([i])}>{name}</button>
          ))}
        </div>
        <footer><button class="host-cancel" onClick={() => answer([])}>{t('lblCancel')}</button></footer>
      </div>
    </div>
  );
}
