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
import type { RogueTab } from './register';
import type { Actions } from '../actions';
import type { Model } from '../model';
import type { RogueAnnounce, Tell } from '../protocol';

const TABS: [RogueTab, TextKey][] = [['runs', 'lblWebRogueRuns'], ['aether', 'lblRogueAether'], ['codex', 'lblRogueCodex'], ['history', 'lblRogueHistory']];

/** Rogue Commander's page. The mode has no shelf of saved games, so the shell is always open. */
export function RogueHub({ model, actions }: { model: Model; actions: Actions }) {
  return <CampaignShell model={model} actions={actions} icons={[]} always class="rg-shell" open={() => <Pages model={model} actions={actions} />} />;
}

function Pages({ model, actions }: { model: Model; actions: Actions }) {
  const [abandoning, setAbandoning] = useState(false);
  const home = model.rogueHome;
  const run = home?.run;
  const inRun = model.campaignSave !== null;
  const tab = inRun ? 'runs' : ui.rogueTab;
  const tell = model.rogueQuestion?.tell;
  const announced = model.rogueAnnounced[0];
  const setTab = (to: RogueTab) => changeUi(u => { u.rogueTab = to; u.rogueNewRun = false; });
  // Back goes from the run to Runs, from another tab to Runs, from the commanders to the unfinished run, and from Runs to the play menu
  const back = () => {
    if (abandoning) setAbandoning(false);
    else if (tell || announced) return;
    else if (!inRun && tab !== 'runs') setTab('runs');
    else if (!inRun && ui.rogueNewRun && run) changeUi(u => { u.rogueNewRun = false; });
    else actions.campaignLeave();
  };
  useEscapeBack(back);
  if (!home) return null;
  const locked = home.unlocked ? {} : { aether: t('lblRogueUnlockAetherHint'), codex: t('lblRogueUnlockCodexHint'), history: t('lblRogueUnlockHistoryHint') };
  const page = !inRun && tab === 'runs' && run && !ui.rogueNewRun ? (
    <Landing home={home} run={run} go={() => actions.campaignLoad('run')}
      deck={() => { ui.rogueRunTab = 'deck'; actions.campaignLoad('run'); }}
      abandon={() => setAbandoning(true)} fresh={() => changeUi(u => { u.rogueNewRun = true; })} />
  ) : null;
  const dialog = abandoning || !!tell || !!announced;
  const main = <div class="camp-main" inert={dialog}>{page}</div>;
  return <>
    <CampaignBar bar={home.bar} tabs={inRun ? [] : TABS} tab={tab} setTab={setTab} disabledTabs={locked} held={{}} leave={back} under={dialog} />
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
    {tell && model.rogueQuestion && <Message tell={tell} ok={() => actions.rogueAnswer(model.rogueQuestion!.id, null)} />}
    {!tell && announced && <Announcement key={`${announced.title}
${announced.text}`} announced={announced} ok={actions.rogueAnnounced} />}
  </>;
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
