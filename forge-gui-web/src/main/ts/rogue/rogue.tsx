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
import { DescensionSheet, NO_CHOICE, NewRun, backFrom, chosenDeck, type Choice } from './newrun';
import { isScene } from './questions';
import { DeckView, Question } from './scene';
import { MapPage } from './map';
import { PlaneSheet } from './mapside';
import { Terms } from './terms';
import { TraitsPage } from './traits';
import { AetherPage, aetherMenu } from './aether';
import type { RogueTab } from './register';
import type { Actions } from '../actions';
import type { Model } from '../model';
import type { RogueAnnounce, Tell } from '../protocol';

const TABS: [RogueTab, TextKey][] = [['runs', 'lblWebRogueRuns'], ['aether', 'lblRogueAether'], ['codex', 'lblRogueCodex'], ['history', 'lblRogueHistory']];
/** The run's tabs. Deck opens the deck editor over the map, so the map stays the tab shown. */
const RUN_TABS: [RunTab, TextKey][] = [['map', 'lblWebRogueMap'], ['traits', 'lblRogueTraits'], ['deck', 'lblDeck'], ['codex', 'lblRogueCodex']];
type RunTab = 'map' | 'traits' | 'deck' | 'codex';
/** The title and question of each reset, by kind. */
const RESETS: Record<string, [TextKey, TextKey]> = {
  aether: ['lblRogueResetAetherworks', 'lblRogueResetAetherworksConfirm'],
};
const glyph = (name: string) => <Icon d={ROGUE_ICONS[name as keyof typeof ROGUE_ICONS]} cls="rg-ic" />;

/** Rogue Commander's page. The mode has no shelf of saved games, so the shell is always open. */
export function RogueHub({ model, actions }: { model: Model; actions: Actions }) {
  return <CampaignShell model={model} actions={actions} icons={[]} always class="rg-shell" open={() => <Pages model={model} actions={actions} />} />;
}

function Pages({ model, actions }: { model: Model; actions: Actions }) {
  const [abandoning, setAbandoning] = useState(false);
  const [resetting, setResetting] = useState<string | null>(null);
  const [choice, setChoice] = useState<Choice>(NO_CHOICE);
  const home = model.rogueHome;
  const roster = model.rogueRoster;
  const run = home?.run;
  const inRun = model.campaignSave !== null;
  // Aether, Codex and History show only while they are unlocked
  const tab = inRun || home?.unlocked === false ? 'runs' : ui.rogueTab;
  const traits = inRun && ui.rogueTraits;
  const deciding = isScene(model.rogueQuestion);
  // The Traits tab takes the decision's place until the Map tab brings it back
  const scene = deciding && !traits;
  const folded = scene && ui.rogueFolded === model.rogueQuestion!.id;
  const tell = deciding ? undefined : model.rogueQuestion?.tell;
  const replace = model.rogueQuestion?.choice?.kind === 'replace' ? model.rogueQuestion.choice : undefined;
  const announced = model.rogueAnnounced[0];
  const fresh = !inRun && tab === 'runs' && (!run || ui.rogueNewRun) && roster ? roster : null;
  const setTab = (to: RogueTab) => changeUi(u => { u.rogueTab = to; u.rogueNewRun = false; });
  const answer = (value: unknown) => actions.rogueAnswer(model.rogueQuestion!.id, value);
  // Back goes from the run to Runs, from another tab to Runs, from the commanders to the unfinished run, and from Runs to the play menu
  const back = () => {
    const step = fresh && backFrom(fresh, choice);
    if (abandoning) setAbandoning(false);
    else if (resetting) setResetting(null);
    else if (inRun && ui.rogueSheet) changeUi(u => { u.rogueSheet = false; });
    else if (traits) changeUi(u => { u.rogueTraits = false; });
    else if (replace) answer(replace.options.length - 1);
    else if (tell || announced) return;
    else if (step) setChoice(step);
    else if (!inRun && tab !== 'runs') setTab('runs');
    else if (!inRun && ui.rogueNewRun && run) changeUi(u => { u.rogueNewRun = false; });
    else actions.campaignLeave();
  };
  useEscapeBack(back);
  const editDeck = () => { if (home) actions.rogueEditDeck(home.snapshot); };
  // Deck on Runs opens the run with its deck
  useEffect(() => {
    if (!inRun || ui.rogueRunTab !== 'deck' || !home) return;
    ui.rogueRunTab = null;
    editDeck();
  }, [inRun, home?.snapshot]);
  useEffect(() => { if (choice.deck) actions.rogueStartDeck(choice.deck); }, [choice.deck]);
  useEffect(() => { if (!inRun) ui.rogueTraits = false; }, [inRun]);
  const deck = fresh ? chosenDeck(fresh, choice) : '';
  const asking = fresh && choice.custom ? `${deck}|${choice.level}|${choice.mods.join()}` : null;
  useEffect(() => { if (asking !== null) actions.rogueChoose(deck, choice.mods, choice.level); }, [asking]);
  // The server's ticking can change whether the experimental roster is shown, and then the commander in front is chosen again
  useEffect(() => {
    const a = model.rogueChoice;
    if (!a || !fresh) return;
    setChoice(c => {
      if (!c.custom || a.modifications.join() === c.mods.join()) return c;
      const experimental = (on: string[]) => fresh.modifications.some(m => m.experimental && on.includes(m.id));
      return { ...c, mods: a.modifications, pick: experimental(a.modifications) === experimental(c.mods) ? c.pick : null };
    });
  }, [model.rogueChoice]);
  if (!home) return null;
  const locked = home.unlocked ? {} : { aether: t('lblRogueUnlockAetherHint'), codex: t('lblRogueUnlockCodexHint'), history: t('lblRogueUnlockHistoryHint') };
  const page = traits ? model.rogueTraits && run && <TraitsPage traits={model.rogueTraits.traits} carry={model.rogueTraits.carry} haze={run.image} />
    : inRun ? model.rogueMap && <MapPage model={model} actions={actions} /> : tab === 'runs' && run && !ui.rogueNewRun ? (
    <Landing home={home} run={run} go={() => actions.campaignLoad('run')}
      deck={() => { ui.rogueRunTab = 'deck'; actions.campaignLoad('run'); }}
      abandon={() => setAbandoning(true)} fresh={() => changeUi(u => { u.rogueNewRun = true; })} />
  ) : tab === 'aether' ? model.rogueAether && (
    <AetherPage aether={model.rogueAether} raise={id => actions.rogueAetherUpgrade(home.snapshot, id)} toggle={(id, on) => actions.rogueAetherToggle(home.snapshot, id, on)}
      expand={() => actions.rogueAetherExpand(home.snapshot)} reset={() => setResetting('aether')} />
  ) : fresh && <NewRun roster={fresh} choice={choice} set={setChoice} runs={run ? () => changeUi(u => { u.rogueNewRun = false; }) : null}
    begin={(deck, level) => actions.rogueBegin(home.snapshot, deck, choice.custom, choice.custom ? choice.mods : [], level)}
    answer={model.rogueChoice} toggle={id => actions.rogueChoose(deck, choice.mods, choice.level, id)} />;
  const details = choice.deck && model.deckDetails?.key === `rogue:start:${choice.deck}` ? model.deckDetails : null;
  const dialog = abandoning || !!resetting || !!tell || !!replace || !!announced || !!fresh && (choice.sheet || !!details) || inRun && ui.rogueSheet && isPortrait();
  const main = <div class="camp-main" inert={dialog || scene && !folded}>{page}</div>;
  const question = scene && <Question model={model} actions={actions} />;
  // On a phone the commanders reached from a run, and Custom Run, are pages of their own with no tabs, as in the mockups
  const alone = !!fresh && isPortrait() && (choice.customOpen || !!run);
  const bar = alone ? { ...home.bar, name: t(choice.customOpen ? 'lblRogueCustomRun' : 'lblRogueStartNewRun') } : home.bar;
  const runBar = model.rogueMap?.bar;
  const abandon = (close: () => void) => { close(); setAbandoning(true); };
  const more = (close: () => void) => (isPortrait() ? <>
    <button role="menuitem" onClick={() => { close(); changeUi(u => { u.rogueWhole = !u.rogueWhole; }); }}>
      {t(ui.rogueWhole ? 'lblWebRogueBackToPlace' : 'lblWebRogueWholeMap')}
    </button>
    <button role="menuitem" onClick={() => abandon(close)}>{t('lblRogueAbandonRun')}</button>
  </> : <div class="rg-menu"><button class="d" onClick={() => abandon(close)}>{t('lblRogueAbandonRun')}</button></div>);
  return <>
    {inRun ? <CampaignBar bar={runBar?.bar ?? bar} tabs={RUN_TABS} tab={traits ? 'traits' : 'map'} setTab={to => {
      // During a decision the deck is read only
      if (to === 'deck' && deciding) changeUi(u => { u.rogueDeckView = true; });
      else if (to === 'deck') editDeck();
      else if (to === 'map' || to === 'traits') changeUi(u => { u.rogueTraits = to === 'traits'; });
    }} mark={id => id === 'traits' && runBar && <sup class="rg-count">{runBar.traits}</sup>} disabledTabs={home.unlocked ? {} : { codex: t('lblRogueUnlockCodexHint') }}
      held={{}} leave={back} under={dialog} lineTerm={runBar?.tip} glyph={glyph} moreIcon={glyph('dots')} more={more} />
      : <CampaignBar bar={bar} tabs={alone ? [] : TABS} tab={tab} setTab={setTab} disabledTabs={locked} held={{}} leave={back} under={dialog}
        more={isPortrait() && tab === 'aether' && model.rogueAether
          ? close => aetherMenu(model.rogueAether!, close, () => actions.rogueAetherExpand(home.snapshot), () => setResetting('aether')) : undefined} />}
    {!isPortrait() ? <div class="rg-body">{main}{question}</div>
      // On a phone a scene takes the page's place, and the bar it folds to lies over the page
      : scene && !folded ? <div class="camp-main" inert={dialog}><div class="rg-pbody">{question}</div></div> : <>{main}{question}</>}
    {abandoning && run && (
      <Confirm title={t('lblRogueAbandonRun')} text={t('lblRogueAbandonRunConfirm')} back={t('lblCancel')} go={t('lblRogueAbandonRun')} danger
        onBack={() => setAbandoning(false)} dismiss={() => setAbandoning(false)} onGo={() => {
          setAbandoning(false);
          playSound({ t: 'sound', name: 'lose_duel', sync: false });
          actions.rogueAbandon(home.snapshot);
        }} />
    )}
    {resetting && (
      <Confirm title={t(RESETS[resetting][0])} text={t(RESETS[resetting][1])} back={t('lblCancel')} go={t('lblReset')}
        onBack={() => setResetting(null)} dismiss={() => setResetting(null)} onGo={() => {
          setResetting(null);
          actions.rogueReset(home.snapshot, resetting);
        }} />
    )}
    {inRun && isPortrait() && ui.rogueSheet && model.rogueMap && !deciding && !traits && <PlaneSheet model={model} actions={actions} />}
    {fresh && choice.sheet && <DescensionSheet roster={fresh} choice={choice} set={setChoice} />}
    {fresh && details && (
      <DeckView details={details} line={t('lblRogueStartingDeck')} button={t('lblClose')} close={() => setChoice(c => ({ ...c, deck: null }))} />
    )}
    {replace && (
      <Confirm title={replace.title} text={replace.paragraphs.flat().map(p => p.text).join('')} back={replace.options[replace.options.length - 1].label}
        go={replace.options[0].label} onBack={() => answer(replace.options.length - 1)} onGo={() => answer(0)} />
    )}
    {deciding && ui.rogueDeckView && model.rogueRunDeck && (
      <DeckView details={model.rogueRunDeck.deck} line={t('lblWebNCards', model.rogueRunDeck.deck.stats.main)}
        button={t('lblWebRogueBackTo', (model.rogueQuestion!.cards ?? model.rogueQuestion!.choice)?.title ?? '')}
        close={() => changeUi(u => { u.rogueDeckView = false; })} />
    )}
    {tell && model.rogueQuestion && <Message tell={tell} ok={() => actions.rogueAnswer(model.rogueQuestion!.id, null)} />}
    <Terms />
    {!tell && announced && <Announcement key={`${announced.title}
${announced.text}`} announced={announced} ok={actions.rogueAnnounced} />}
  </>;
}

/** Asks before a step that cannot be taken back: abandoning the run, or abandoning it for a new one. go is drawn red. */
function Confirm({ title, text, back, go, danger, onBack, onGo, dismiss }: {
  title: string; text: string; back: string; go: string; danger?: boolean; onBack: () => void; onGo: () => void; dismiss?: () => void;
}) {
  return (
    <Backdrop layer="rg-dialog" dismiss={dismiss}>
      <div class="dialog" role="alertdialog" aria-label={title}>
        <h3>{title}</h3>
        <p>{text.split('\n').flatMap((line, i) => (i ? [<br key={i} />, line] : [line]))}</p>
        <div class="actions">
          <button onClick={onBack}>{back}</button>
          <button class={danger ? 'danger' : undefined} style={{ color: 'var(--concede)' }} onClick={onGo}>{go}</button>
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
