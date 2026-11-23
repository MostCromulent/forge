import { useEffect, useRef, useState } from 'preact/hooks';
import { CampaignBar, CampaignShell } from '../campaign/campaign';
import { Backdrop } from '../dialog';
import { Icon } from '../icons';
import { imageUrl, skinIconUrl } from '../images';
import { isPortrait } from '../form';
import { playSound } from '../audio';
import { useEscapeBack } from '../header';
import { useDismiss } from '../hooks';
import { setting } from '../settings';
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
import { CodexPage, type CodexTab } from './codex';
import { HistoryPage } from './history';
import { heldBack } from './gains';
import type { RogueTab } from './register';
import type { Actions } from '../actions';
import type { Model } from '../model';
import type { RogueAnnounce, RogueHome, Tell } from '../protocol';

const TABS: [RogueTab, TextKey][] = [['runs', 'lblWebRogueRuns'], ['aether', 'lblRogueAether'], ['codex', 'lblRogueCodex'], ['history', 'lblRogueHistory']];
/** The run's tabs. Deck opens the deck editor over the map, so the map stays the tab shown. */
const RUN_TABS: [RunTab, TextKey][] = [['map', 'lblWebRogueMap'], ['traits', 'lblRogueTraits'], ['deck', 'lblDeck'], ['codex', 'lblRogueCodex']];
type RunTab = 'map' | 'traits' | 'deck' | 'codex';
/** The title and question of each reset, by kind. */
const RESETS: Record<string, [TextKey, TextKey]> = {
  aether: ['lblRogueResetAetherworks', 'lblRogueResetAetherworksConfirm'],
  history: ['lblRogueResetRunHistory', 'lblRogueResetHistoryConfirm'],
  tutorials: ['lblRogueResetTutorials', 'lblRogueResetTutorialsConfirm'],
  progress: ['lblRogueResetGameProgression', 'lblRogueResetProgressConfirm'],
};
/** The dev mode tools that change the progress, offered between runs as desktop does. Two of them switch, and their names say what the next press does. */
const devTools = (home: RogueHome): [string, TextKey][] => [
  ['unlockAll', home.devUnlockAll ? 'lblRogueDevLockCommanders' : 'lblRogueDevUnlockAll'], ['npcProgress', 'lblRogueDevNpcProgress'],
  ['maxAether', home.aetherMaxed ? 'lblRogueDevResetAether' : 'lblRogueDevMaxAether'], ['echoes', 'lblRogueDevGainEchoes'],
  ['sparks', 'lblRogueDevGainSparks'], ['codex', 'lblRogueDevUnlockCodex'],
];
const RUN_TOOLS: [string, TextKey][] = [['nextNode', 'lblRogueDevNextNode'], ['winRun', 'lblRogueDevWinRun']];
const glyph = (name: string) => <Icon d={ROGUE_ICONS[name as keyof typeof ROGUE_ICONS]} cls="rg-ic" />;

/** Rogue Commander's page. The mode has no shelf of saved games, so the shell is always open. */
export function RogueHub({ model, actions }: { model: Model; actions: Actions }) {
  return <CampaignShell model={model} actions={actions} icons={[]} always class="rg-shell" open={() => <Pages model={model} actions={actions} />} />;
}

function Pages({ model, actions }: { model: Model; actions: Actions }) {
  const [abandoning, setAbandoning] = useState(false);
  const [resetting, setResetting] = useState<string | null>(null);
  const [devOpen, setDevOpen] = useState(false);
  const devMenu = useRef<HTMLDivElement>(null);
  useDismiss(devOpen, devMenu, () => setDevOpen(false));
  const [historyDeck, setHistoryDeck] = useState<number | null>(null);
  const [choice, setChoice] = useState<Choice>(NO_CHOICE);
  const home = model.rogueHome;
  const roster = model.rogueRoster;
  const run = home?.run;
  const inRun = model.campaignSave !== null;
  // Aether, Codex and History show only while they are unlocked
  const tab = inRun || home?.unlocked === false ? 'runs' : ui.rogueTab;
  const aside = inRun ? ui.roguePage : 'map';
  const codex = inRun ? aside === 'codex' : tab === 'codex';
  const deciding = isScene(model.rogueQuestion);
  // The Traits and Codex tabs take the decision's place until the Map tab brings it back
  const scene = deciding && aside === 'map';
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
    if (devOpen) setDevOpen(false);
    else if (historyDeck !== null) setHistoryDeck(null);
    else if (abandoning) setAbandoning(false);
    else if (resetting) setResetting(null);
    else if (inRun && ui.rogueSheet) changeUi(u => { u.rogueSheet = false; });
    else if (codex && isPortrait() && ui.rogueCodexId) changeUi(u => { u.rogueCodexId = null; });
    else if (aside !== 'map') changeUi(u => { u.roguePage = 'map'; });
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
  useEffect(() => { if (!inRun) ui.roguePage = 'map'; }, [inRun]);
  // The Codex is asked for again after each step, such as a reset
  useEffect(() => { if (codex) actions.rogueCodex(ui.rogueCodexTab, ui.rogueCodexId); }, [codex, ui.rogueCodexTab, ui.rogueCodexId, home?.snapshot]);
  useEffect(() => { if (!inRun && tab === 'history') actions.rogueHistory(); }, [inRun, tab, home?.snapshot]);
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
  const page = aside === 'traits' ? model.rogueTraits && run && <TraitsPage traits={model.rogueTraits.traits} carry={model.rogueTraits.carry} haze={run.image} />
    : codex ? (
      <CodexPage page={model.rogueCodexPage} tab={ui.rogueCodexTab} id={ui.rogueCodexId}
        setTab={(to: CodexTab) => changeUi(u => { u.rogueCodexTab = to; u.rogueCodexId = null; })}
        choose={id => changeUi(u => { u.rogueCodexId = id; })} reset={inRun ? undefined : setResetting} />
    ) : inRun ? model.rogueMap && <MapPage model={model} actions={actions} /> : tab === 'runs' && run && !ui.rogueNewRun ? (
    <Landing home={home} run={run} go={() => actions.campaignLoad('run')}
      deck={() => { ui.rogueRunTab = 'deck'; actions.campaignLoad('run'); }}
      abandon={() => setAbandoning(true)} fresh={() => changeUi(u => { u.rogueNewRun = true; })} />
  ) : tab === 'history' ? (
    <HistoryPage page={model.rogueHistoryPage} reset={() => setResetting('history')} deck={id => { setHistoryDeck(id); actions.rogueHistoryDeck(id); }} />
  ) : tab === 'aether' ? model.rogueAether && (
    <AetherPage aether={model.rogueAether} raise={id => actions.rogueAetherUpgrade(home.snapshot, id)} toggle={(id, on) => actions.rogueAetherToggle(home.snapshot, id, on)}
      expand={() => actions.rogueAetherExpand(home.snapshot)} reset={() => setResetting('aether')} />
  ) : fresh && <NewRun roster={fresh} choice={choice} set={setChoice} runs={run ? () => changeUi(u => { u.rogueNewRun = false; }) : null}
    begin={(deck, level) => actions.rogueBegin(home.snapshot, deck, choice.custom, choice.custom ? choice.mods : [], level)}
    answer={model.rogueChoice} toggle={id => actions.rogueChoose(deck, choice.mods, choice.level, id)} />;
  const details = choice.deck && model.deckDetails?.key === `rogue:start:${choice.deck}` ? model.deckDetails : null;
  const pastDeck = historyDeck !== null && model.deckDetails?.key === `rogue:history:${historyDeck}` ? model.deckDetails : null;
  const dialog = abandoning || !!resetting || !!tell || !!replace || !!announced || !!fresh && (choice.sheet || !!details) || !!pastDeck
    || inRun && ui.rogueSheet && isPortrait();
  const main = <div class="camp-main" inert={dialog || scene && !folded}>{page}</div>;
  const question = scene && <Question model={model} actions={actions} />;
  // On a phone the commanders reached from a run, and Custom Run, are pages of their own with no tabs, as in the mockups
  const alone = !!fresh && isPortrait() && (choice.customOpen || !!run);
  const bar = alone ? { ...home.bar, name: t(choice.customOpen ? 'lblRogueCustomRun' : 'lblRogueStartNewRun') } : home.bar;
  const runBar = model.rogueMap?.bar;
  const held = heldBack(model.rogueQuestion?.tell?.gains, ui.rogueLanded);
  // Life is written out of its maximum, so an amount the summary holds back is taken off that text too
  const lifeHeld = held.rogueLife;
  const runBalances = runBar && run && lifeHeld
    ? { ...runBar.bar, balances: runBar.bar.balances.map(b => (b.icon === 'rogueLife' ? { ...b, text: `${b.amount - lifeHeld} / ${run.maxLife}` } : b)) } : runBar?.bar;
  // A phone's header has room for the balances only, as the phone mockups draw it
  const runShown = runBalances && isPortrait() ? { ...runBalances, line: '' } : runBalances;
  const abandon = (close: () => void) => { close(); setAbandoning(true); };
  const resetRow = (close: () => void, kind: string, label: TextKey) => <button role="menuitem" onClick={() => { close(); setResetting(kind); }}>{t(label)}</button>;
  // A phone has the resets and the Aether Upgrade in the header's menu, in place of the wide page's buttons
  const phoneMenu = tab === 'aether' && model.rogueAether
    ? (close: () => void) => aetherMenu(model.rogueAether!, close, () => actions.rogueAetherExpand(home.snapshot), () => setResetting('aether'))
    : tab === 'codex' && ui.rogueCodexTab === 'stats'
      ? (close: () => void) => <>{resetRow(close, 'tutorials', 'lblRogueResetTutorials')}{resetRow(close, 'progress', 'lblRogueResetProgress')}</>
      : tab === 'history' && model.rogueHistoryPage?.runs.length ? (close: () => void) => resetRow(close, 'history', 'lblRogueResetHistory') : undefined;
  const dev = (tool: string) => actions.rogueDev(home.snapshot, tool);
  const tools = inRun ? RUN_TOOLS : devTools(home);
  // A wide screen opens the tools from a button beside the balances, and a phone lists them at the end of its header's menu
  const devTool = setting('devMode') && (isPortrait() ? <>
    <hr class="ph-menu-sep" />
    <small class="ph-menu-head">{t('lblDevMode')}</small>
    {tools.map(([tool, label]) => <button key={tool} role="menuitem" onClick={() => dev(tool)}>{t(label)}</button>)}
  </> : (
    <button class="camp-dev" title={t('lblDevMode')} aria-label={t('lblDevMode')} aria-expanded={devOpen} onPointerDown={e => e.stopPropagation()}
      onClick={() => setDevOpen(!devOpen)}><Icon d={ROGUE_ICONS.code} cls="rg-ic" /></button>
  ));
  const more = (close: () => void) => (isPortrait() ? <>
    <button role="menuitem" onClick={() => { close(); changeUi(u => { u.rogueWhole = !u.rogueWhole; }); }}>
      {t(ui.rogueWhole ? 'lblWebRogueBackToPlace' : 'lblWebRogueWholeMap')}
    </button>
    <button role="menuitem" onClick={() => abandon(close)}>{t('lblRogueAbandonRun')}</button>
  </> : (
    <div class="rg-menu">
      <button class="d" onClick={() => abandon(close)}>{t('lblRogueAbandonRun')}</button>
      {setting('devMode') && <>
        <h6>{t('lblDev')}</h6>
        {RUN_TOOLS.map(([tool, label]) => <button key={tool} onClick={() => { close(); dev(tool); }}>{t(label)}</button>)}
      </>}
    </div>
  ));
  return <>
    {inRun ? <CampaignBar bar={runShown ?? bar} tabs={RUN_TABS} tab={aside} setTab={to => {
      // During a decision the deck is read only
      if (to === 'deck' && deciding) changeUi(u => { u.rogueDeckView = true; });
      else if (to === 'deck') editDeck();
      else changeUi(u => { u.roguePage = to; });
    }} mark={id => id === 'traits' && !!runBar?.traits && <sup class="rg-count">{runBar.traits}</sup>} disabledTabs={home.unlocked ? {} : { codex: t('lblRogueUnlockCodexHint') }}
      held={held} leave={back} under={dialog} lineTerm={isPortrait() ? undefined : runBar?.tip} glyph={glyph} moreIcon={glyph('dots')} more={more} extra={devTool} />
      : <CampaignBar bar={bar} tabs={alone ? [] : TABS} tab={tab} setTab={setTab} disabledTabs={locked} held={{}} leave={back} under={dialog}
        more={isPortrait() ? phoneMenu : undefined} extra={devTool} />}
    {!isPortrait() ? <div class="rg-body">{main}{question}</div>
      // On a phone a scene takes the page's place, and the bar it folds to lies over the page
      : scene && !folded ? <div class="camp-main" inert={dialog}><div class="rg-pbody">{question}</div></div> : <>{main}{question}</>}
    {devOpen && !isPortrait() && (
      <div class="rg-menu" ref={devMenu}>
        <h6 style={{ border: 0, paddingTop: '4px' }}>{t('lblDev')}</h6>
        {tools.map(([tool, label]) => <button key={tool} onClick={() => { setDevOpen(false); dev(tool); }}>{t(label)}</button>)}
      </div>
    )}
    {abandoning && run && (
      <Confirm title={t('lblRogueAbandonRun')} text={t('lblRogueAbandonRunConfirm')} back={t('lblCancel')} go={t('lblRogueAbandonRun')} danger={!isPortrait()}
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
    {inRun && isPortrait() && ui.rogueSheet && model.rogueMap && !deciding && aside === 'map' && <PlaneSheet model={model} actions={actions} />}
    {fresh && choice.sheet && <DescensionSheet roster={fresh} choice={choice} set={setChoice} />}
    {pastDeck && <DeckView details={pastDeck} line={t('lblWebNCards', pastDeck.stats.main)} button={t('lblClose')} close={() => setHistoryDeck(null)} />}
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
    {!tell && announced && <Announcement key={announced.id} announced={announced} ok={actions.rogueAnnounced} />}
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
