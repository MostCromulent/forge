import { useEffect, useRef, useState } from 'preact/hooks';
import { CampaignBar, CampaignShell } from '../campaign/campaign';
import { Backdrop } from '../dialog';
import { Icon } from '../icons';
import { imageUrl, skinIconUrl } from '../images';
import { isPortrait } from '../form';
import { playSound } from '../audio';
import { useEscapeBack } from '../header';
import { changeUi, ui } from '../ui';
import { t, type TextKey } from '../text';
import { ROGUE_ICONS } from './icons';
import { Landing } from './landing';
import { DescensionSheet, NO_CHOICE, NewRun, StartDeck, backFrom, type Choice } from './newrun';
import type { RogueTab } from './register';
import type { Actions } from '../actions';
import type { Model } from '../model';
import type { AskChoice, RogueAnnounce, Tell } from '../protocol';

const TABS: [RogueTab, TextKey][] = [['runs', 'lblWebRogueRuns'], ['aether', 'lblRogueAether'], ['codex', 'lblRogueCodex'], ['history', 'lblRogueHistory']];

/** Rogue Commander's page. The mode has no shelf of saved games, so the shell is always open. */
export function RogueHub({ model, actions }: { model: Model; actions: Actions }) {
  return <CampaignShell model={model} actions={actions} icons={[]} always class="rg-shell" open={() => <Pages model={model} actions={actions} />} />;
}

function Pages({ model, actions }: { model: Model; actions: Actions }) {
  const [abandoning, setAbandoning] = useState(false);
  const [choice, setChoice] = useState<Choice>(NO_CHOICE);
  const home = model.rogueHome;
  const roster = model.rogueRoster;
  const run = home?.run;
  const inRun = model.campaignSave !== null;
  const tab = inRun ? 'runs' : ui.rogueTab;
  const tell = model.rogueQuestion?.tell;
  const replace = model.rogueQuestion?.kind === 'replace' ? model.rogueQuestion.choice : undefined;
  const announced = model.rogueAnnounced[0];
  const fresh = !inRun && tab === 'runs' && (!run || ui.rogueNewRun) && roster ? roster : null;
  const setTab = (to: RogueTab) => changeUi(u => { u.rogueTab = to; u.rogueNewRun = false; });
  const answer = (value: unknown) => actions.rogueAnswer(model.rogueQuestion!.id, value);
  // Back goes from the run to Runs, from another tab to Runs, from the commanders to the unfinished run, and from Runs to the play menu
  const back = () => {
    const step = fresh && backFrom(fresh, choice);
    if (abandoning) setAbandoning(false);
    else if (replace) answer(replace.options.length - 1);
    else if (tell || announced) return;
    else if (step) setChoice(step);
    else if (!inRun && tab !== 'runs') setTab('runs');
    else if (!inRun && ui.rogueNewRun && run) changeUi(u => { u.rogueNewRun = false; });
    else actions.campaignLeave();
  };
  useEscapeBack(back);
  useEffect(() => { if (choice.deck) actions.rogueStartDeck(choice.deck); }, [choice.deck]);
  if (!home) return null;
  const locked = home.unlocked ? {} : { aether: t('lblRogueUnlockAetherHint'), codex: t('lblRogueUnlockCodexHint'), history: t('lblRogueUnlockHistoryHint') };
  const page = !inRun && tab === 'runs' && run && !ui.rogueNewRun ? (
    <Landing home={home} run={run} go={() => actions.campaignLoad('run')}
      deck={() => { ui.rogueRunTab = 'deck'; actions.campaignLoad('run'); }}
      abandon={() => setAbandoning(true)} fresh={() => changeUi(u => { u.rogueNewRun = true; })} />
  ) : fresh && <NewRun roster={fresh} choice={choice} set={setChoice} runs={run ? () => changeUi(u => { u.rogueNewRun = false; }) : null}
    begin={(deck, level) => actions.rogueBegin(fresh.snapshot, deck, choice.custom, choice.custom ? choice.mods : [], level)} />;
  const details = choice.deck && model.deckDetails?.key === `rogue:start:${choice.deck}` ? model.deckDetails : null;
  const dialog = abandoning || !!tell || !!replace || !!announced || !!fresh && (choice.sheet || !!details);
  const main = <div class="camp-main" inert={dialog}>{page}</div>;
  // On a phone the commanders reached from a run, and Custom Run, are pages of their own with no tabs, as in the mockups
  const alone = !!fresh && isPortrait() && (choice.customOpen || !!run);
  const bar = alone ? { ...home.bar, name: t(choice.customOpen ? 'lblRogueCustomRun' : 'lblRogueStartNewRun') } : home.bar;
  return <>
    <CampaignBar bar={bar} tabs={inRun || alone ? [] : TABS} tab={tab} setTab={setTab} disabledTabs={locked} held={{}} leave={back} under={dialog} />
    {isPortrait() ? main : <div class="rg-body">{main}</div>}
    {abandoning && run && (
      <Backdrop layer="rg-dialog" dismiss={() => setAbandoning(false)}>
        <div class="dialog" role="alertdialog" aria-label={t('lblRogueAbandonRun')}>
          <h3>{t('lblRogueAbandonRun')}</h3>
          <p>{t('lblRogueAbandonRunConfirm')}</p>
          <div class="actions">
            <button onClick={() => setAbandoning(false)}>{t('lblCancel')}</button>
            <button class="danger" style={{ color: 'var(--concede)' }} onClick={() => {
              setAbandoning(false);
              playSound({ t: 'sound', name: 'lose_duel', sync: false });
              actions.rogueAbandon(home.snapshot);
            }}>{t('lblRogueAbandonRun')}</button>
          </div>
        </div>
      </Backdrop>
    )}
    {fresh && choice.sheet && <DescensionSheet roster={fresh} choice={choice} set={setChoice} />}
    {fresh && details && <StartDeck details={details} close={() => setChoice(c => ({ ...c, deck: null }))} />}
    {replace && <Replace choice={replace} answer={answer} />}
    {tell && model.rogueQuestion && <Message tell={tell} ok={() => actions.rogueAnswer(model.rogueQuestion!.id, null)} />}
    {!tell && announced && <Announcement key={`${announced.title}
${announced.text}`} announced={announced} ok={actions.rogueAnnounced} />}
  </>;
}

/** Asked when a new run would abandon the unfinished one. The first option starts the new run and the last cancels. */
function Replace({ choice, answer }: { choice: AskChoice; answer: (value: number) => void }) {
  const lines = choice.paragraphs.flat().map(p => p.text).join('').split('\n');
  const last = choice.options.length - 1;
  return (
    <Backdrop layer="rg-dialog">
      <div class="dialog" role="alertdialog" aria-label={choice.title}>
        <h3>{choice.title}</h3>
        <p>{lines.flatMap((line, i) => (i ? [<br key={i} />, line] : [line]))}</p>
        <div class="actions">
          <button onClick={() => answer(last)}>{choice.options[last].label}</button>
          <button style={{ color: 'var(--concede)' }} onClick={() => answer(0)}>{choice.options[0].label}</button>
        </div>
      </div>
    </Backdrop>
  );
}

/** Focuses the dialog's button, so Enter and Space continue. */
function useFocus() {
  const button = useRef<HTMLButtonElement>(null);
  useEffect(() => button.current?.focus({ preventScroll: true }), []);
  return button;
}

/** A plain message from the flow, such as a deck that could not be generated. Other kinds of result are drawn by their own pages. */
function Message({ tell, ok }: { tell: Tell; ok: () => void }) {
  const button = useFocus();
  return (
    <Backdrop layer="rg-dialog">
      <div class="dialog" role="alertdialog" aria-label={tell.title}>
        <h3>{tell.title}</h3>
        <p>{tell.text.map(p => p.text).join('')}</p>
        <div class="actions"><button class="primary" ref={button} onClick={ok}>{t('lblOK')}</button></div>
      </div>
    </Backdrop>
  );
}

/** Something won or unlocked. The first line of its text is its name. */
function Announcement({ announced, ok }: { announced: RogueAnnounce; ok: () => void }) {
  const button = useFocus();
  const [name, ...rest] = announced.text.split('\n');
  useEffect(() => { if (announced.kind === 'unlock') playSound({ t: 'sound', name: 'enchant', sync: false }); }, []);
  return (
    <Backdrop layer="rg-dialog">
      <div class="dialog trophy" role="alertdialog" aria-label={announced.title}>
        <span class="camp-chip brass"><Icon d={ROGUE_ICONS.trophy} cls="rg-ic" /> {announced.title}</span>
        {announced.kind === 'spark' ? <img class="sparkpic" alt="" src={skinIconUrl('ICO_QUEST_ELIXIR')} />
          : announced.image && <img alt="" data-image={announced.image} src={imageUrl(announced.image)} />}
        <h3>{name}</h3>
        {rest.length > 0 && <p>{rest.join('\n')}</p>}
        <div class="actions"><button class="primary" ref={button} onClick={ok}>{t('lblContinue')}</button></div>
      </div>
    </Backdrop>
  );
}
