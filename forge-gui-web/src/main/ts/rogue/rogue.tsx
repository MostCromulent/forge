import { useEffect, useLayoutEffect, useRef, useState } from 'preact/hooks';
import { CampaignBar, CampaignShell, forgetTallies, slideTab } from '../campaign/campaign';
import { Backdrop } from '../dialog';
import { Icon } from '../icons';
import { hideBroken, imageUrl } from '../images';
import { isPortrait } from '../form';
import { playSound } from '../audio';
import { useEscapeBack } from '../header';
import { useDismiss } from '../hooks';
import { setting } from '../settings';
import { inspectCard } from '../preview';
import { isTouch } from '../press';
import { avatarUrl } from '../avatarsleeves';
import { changeUi, eased, easedLocal, ui } from '../ui';
import { t, type TextKey } from '../text';
import { ROGUE_ICONS } from './icons';
import { Sprite, barGlyph, glyph, tabSprite } from './kit';
import { Landing } from './landing';
import { DescensionSheet, NO_CHOICE, NewRun, backFrom, chosenDeck, type Choice, type SetChoice } from './newrun';
import { entrance, screenWay } from './motion';
import { isScene } from './questions';
import { DeckView, Question } from './scene';
import { MapPage, forgetFaces } from './map';
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
import type { AskNpcs, RogueAnnounce, RogueHome, Tell } from '../protocol';

const TABS: [RogueTab, TextKey][] = [['runs', 'lblRogueRuns'], ['aether', 'lblRogueAether'], ['codex', 'lblRogueCodex'], ['history', 'lblRogueHistory']];
/** The run's tabs. Deck opens the deck editor over the map, so the map stays the tab shown. */
const RUN_TABS: [RunTab, TextKey][] = [['map', 'lblRogueMap'], ['traits', 'lblRogueEffects'], ['deck', 'lblDeck'], ['codex', 'lblRogueCodex']];
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

/** Rogue Commander's page. The mode has no shelf of saved games, so the shell is always open. */
export function RogueHub({ model, actions }: { model: Model; actions: Actions }) {
  // Rogue is drawn in Adventure's look (rogue-adventure.css) while it is open. The deck editor over it keeps the editor's own look, dialogs included
  const editing = !!model.editor;
  useEffect(() => {
    if (editing) return;
    document.documentElement.classList.add('kit');
    return () => document.documentElement.classList.remove('kit');
  }, [editing]);
  useEffect(tapToZoom, []);
  useEffect(scrollHints, []);
  return <CampaignShell model={model} actions={actions} icons={[]} always class="rg-shell" open={() => <Pages model={model} actions={actions} />} />;
}

function Pages({ model, actions }: { model: Model; actions: Actions }) {
  const [abandoning, setAbandoning] = useState(false);
  const [resetting, setResetting] = useState<string | null>(null);
  const [devOpen, setDevOpen] = useState(false);
  const devMenu = useRef<HTMLDivElement>(null);
  useDismiss(devOpen, devMenu, () => setDevOpen(false));
  const [historyDeck, setHistoryDeck] = useState<number | null>(null);
  const [runDeck, setRunDeck] = useState(false);
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
  const npcs = model.rogueQuestion?.npcs;
  const announced = model.rogueAnnounced[0];
  const fresh = !inRun && tab === 'runs' && (!run || ui.rogueNewRun) && roster ? roster : null;
  const setTab = (to: RogueTab) => slideTab(TABS, tab, to, u => { u.rogueTab = to; u.rogueNewRun = false; });
  // The commanders, Custom Run and a run are screens of their own, which rise over the one they open from and drop away back to it
  const newRun = (on: boolean) => eased(u => { u.rogueNewRun = on; }, screenWay(!on));
  const enterRun = (go: () => void) => eased(go, screenWay(false), () => model.campaignSave !== null && !!model.rogueMap);
  const moveChoice: SetChoice = change => {
    const next = change(choice);
    if (next.customOpen !== choice.customOpen) easedLocal(() => setChoice(next), screenWay(!next.customOpen));
    else setChoice(change);
  };
  const answer = (value: unknown) => actions.rogueAnswer(model.rogueQuestion!.id, value);
  // Back goes from the run to Runs, from another tab to Runs, from the commanders to the unfinished run, and from Runs to the play menu
  const back = () => {
    const step = fresh && backFrom(fresh, choice);
    if (devOpen) setDevOpen(false);
    else if (historyDeck !== null) setHistoryDeck(null);
    else if (runDeck) setRunDeck(false);
    else if (abandoning) setAbandoning(false);
    else if (resetting) setResetting(null);
    else if (inRun && ui.rogueSheet) changeUi(u => { u.rogueSheet = false; });
    else if (codex && isPortrait() && ui.rogueCodexId) changeUi(u => { u.rogueCodexId = null; });
    else if (aside !== 'map') changeUi(u => { u.roguePage = 'map'; });
    else if (replace) answer(replace.options.length - 1);
    else if (npcs) answer(null);
    else if (tell || announced) return;
    else if (step) moveChoice(() => step);
    else if (!inRun && tab !== 'runs') setTab('runs');
    else if (!inRun && ui.rogueNewRun && run) newRun(false);
    else if (inRun) eased(() => actions.campaignLeave(), screenWay(true), () => model.campaignSave === null);
    else actions.campaignLeave();
  };
  useEscapeBack(back);
  const editDeck = () => { if (home) actions.rogueEditDeck(home.snapshot); };
  useEffect(() => { if (choice.deck) actions.rogueStartDeck(choice.deck); }, [choice.deck]);
  useEffect(() => {
    if (inRun) return;
    ui.roguePage = 'map';
    forgetTallies();
    forgetFaces();
  }, [inRun]);
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
    ) : inRun ? model.rogueMap && <MapPage model={model} actions={actions} covered={scene && !folded} /> : tab === 'runs' && run && !ui.rogueNewRun ? (
    <Landing home={home} run={run} go={() => enterRun(() => { ui.rogueIntro = false; actions.campaignLoad('run'); })}
      deck={() => setRunDeck(true)}
      abandon={() => setAbandoning(true)} fresh={() => newRun(true)} />
  ) : tab === 'history' ? (
    <HistoryPage page={model.rogueHistoryPage} reset={() => setResetting('history')} deck={id => { setHistoryDeck(id); actions.rogueHistoryDeck(id); }} />
  ) : tab === 'aether' ? model.rogueAether && (
    <AetherPage aether={model.rogueAether} raise={id => actions.rogueAetherUpgrade(home.snapshot, id)} toggle={(id, on) => actions.rogueAetherToggle(home.snapshot, id, on)}
      expand={() => actions.rogueAetherExpand(home.snapshot)} reset={() => setResetting('aether')} />
  ) : fresh && <NewRun roster={fresh} choice={choice} set={moveChoice} runs={run ? () => newRun(false) : null}
    begin={(deck, level) => enterRun(() => actions.rogueBegin(home.snapshot, deck, choice.custom, choice.custom ? choice.mods : [], level))}
    answer={model.rogueChoice} toggle={id => actions.rogueChoose(deck, choice.mods, choice.level, id)} unlocked={home.unlocked} />;
  const details = choice.deck && model.deckDetails?.key === `rogue:start:${choice.deck}` ? model.deckDetails : null;
  const pastDeck = historyDeck !== null && model.deckDetails?.key === `rogue:history:${historyDeck}` ? model.deckDetails : null;
  const shownRunDeck = runDeck && !inRun && model.rogueRunDeck ? model.rogueRunDeck.deck : null;
  const dialog = abandoning || !!resetting || !!tell || !!replace || !!npcs || !!announced || !!fresh && (choice.sheet || !!details) || !!pastDeck || !!shownRunDeck
    || inRun && ui.rogueSheet && isPortrait();
  const main = <div class="camp-main" inert={dialog || scene && !folded}>{page}</div>;
  const question = scene && <Question model={model} actions={actions} />;
  // On a phone the commanders reached from a run, and Custom Run, are pages of their own with no tabs, as in the mockups
  const alone = !!fresh && isPortrait() && (choice.customOpen || !!run);
  const bar = alone ? { ...home.bar, name: t(choice.customOpen ? 'lblRogueCustomRun' : 'lblRogueStartNewRun') } : home.bar;
  const runBar = model.rogueMap?.bar;
  const asked = model.rogueQuestion;
  const held = heldBack(asked?.tell?.gains ?? asked?.cards?.gains, asked?.id === ui.rogueLanded);
  // Life is written out of its maximum, so an amount the spoils hold back is taken off that text too
  const lifeHeld = held.rogueLife;
  const runBalances = runBar && run && lifeHeld
    ? { ...runBar.bar, balances: runBar.bar.balances.map(b => (b.icon === 'rogueLife' ? { ...b, text: `${b.amount - lifeHeld} / ${run.maxLife}` } : b)) } : runBar?.bar;
  // A phone's header has room for the balances only, as the phone mockups draw it
  const runShown = runBalances && isPortrait() ? { ...runBalances, line: '' } : runBalances;
  const abandon = (close: () => void) => { close(); setAbandoning(true); };
  const resetRow = (close: () => void, kind: string, label: TextKey) => <button role="menuitem" onClick={() => { close(); setResetting(kind); }}>{t(label)}</button>;
  // A phone has the resets and the Aether Upgrade in the header's menu, in place of the wide page's buttons
  const phoneMenu = tab === 'aether' && model.rogueAether
    ? (close: () => void) => aetherMenu(close, () => setResetting('aether'))
    : tab === 'codex' && ui.rogueCodexTab === 'stats'
      ? (close: () => void) => <>{resetRow(close, 'tutorials', 'lblRogueResetTutorials')}{resetRow(close, 'progress', 'lblRogueResetProgress')}</>
      : tab === 'history' && model.rogueHistoryPage?.runs.length ? (close: () => void) => resetRow(close, 'history', 'lblRogueResetHistory') : undefined;
  const dev = (tool: string) => actions.rogueDev(home.snapshot, tool);
  const tools = inRun ? RUN_TOOLS : devTools(home);
  // A wide screen opens the hub's tools from a button beside the balances, and a run's from its menu, as mobile does. A phone lists them at the end of its header's menu
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
      else slideTab(RUN_TABS, aside, to, u => { u.roguePage = to; });
    }} lead={tabSprite} disabledTabs={home.unlocked ? {} : { codex: t('lblRogueUnlockCodexHint') }}
      held={held} counted leave={back} under={dialog} lineTerm={isPortrait() ? undefined : runBar?.tip} glyph={barGlyph} moreIcon={glyph('dots')} more={more} extra={isPortrait() ? devTool : undefined} />
      : <CampaignBar bar={bar} tabs={alone ? [] : TABS} tab={tab} setTab={setTab} lead={tabSprite} disabledTabs={locked} held={ui.rogueEchoesOwed ? { ICO_QUEST_GOLD: -ui.rogueEchoesOwed } : {}} leave={back} under={dialog} glyph={barGlyph}
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
    {shownRunDeck && <DeckView details={shownRunDeck} line={t('lblWebNCards', shownRunDeck.stats.main)} button={t('lblClose')} close={() => setRunDeck(false)} />}
    {npcs && <NpcLevelsPage asked={npcs} answer={answer} />}
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

/** On a touch screen a tap on a card in the Codex, or on a card's name in Rogue's text, zooms the card, as mobile does. */
function tapToZoom() {
  const tap = (e: MouseEvent) => {
    if (!isTouch(e) || !(e.target instanceof Element)) return;
    const el = e.target.closest<HTMLElement>('.rg-cn[data-image], .rg-cx [data-image]');
    if (!el) return;
    e.preventDefault();
    e.stopPropagation();
    inspectCard(el);
  };
  document.addEventListener('click', tap, true);
  return () => document.removeEventListener('click', tap, true);
}

/** Marks each of Rogue's scroll panes that has more below, which rogue.css draws an arrow on, as mobile's RogueScrollPane does. */
function scrollHints() {
  const mark = () => {
    for (const pane of document.querySelectorAll<HTMLElement>('.rg-scroll')) {
      pane.classList.toggle('rg-more', pane.scrollTop + pane.clientHeight < pane.scrollHeight - 2);
    }
  };
  let queued = 0;
  const soon = () => {
    if (!queued) queued = requestAnimationFrame(() => { queued = 0; mark(); });
  };
  // Pages change under the panes without a scroll, so changes to the page are watched too
  const watch = new MutationObserver(soon);
  watch.observe(document.body, { childList: true, subtree: true });
  document.addEventListener('scroll', soon, true);
  document.addEventListener('load', soon, true);
  addEventListener('resize', soon);
  return () => {
    watch.disconnect();
    cancelAnimationFrame(queued);
    document.removeEventListener('scroll', soon, true);
    document.removeEventListener('load', soon, true);
    removeEventListener('resize', soon);
  };
}

/** The dev tool that sets every NPC's level on one page, as mobile's NPC progress screen does. Cancel changes none. */
function NpcLevelsPage({ asked, answer }: { asked: AskNpcs; answer: (value: unknown) => void }) {
  const [picked, setPicked] = useState(() => asked.npcs.map(() => 0));
  return (
    <Backdrop layer="rg-dialog" dismiss={() => answer(null)}>
      <div class="dialog rg-npcs" role="dialog" aria-label={asked.title}>
        <h3>{asked.title}</h3>
        <p>{asked.text}</p>
        <div class="rows">
          {asked.npcs.map((npc, i) => (
            <label key={npc.name}>
              <span class="pic" style={{ backgroundImage: `url("${avatarUrl(npc.avatar)}")` }} />
              <b>{npc.name}</b>
              <select value={picked[i]} onChange={e => {
                const at = Number((e.target as HTMLSelectElement).value);
                setPicked(p => p.map((x, j) => (j === i ? at : x)));
              }}>
                {npc.levels.map((level, j) => <option key={j} value={j}>{level}</option>)}
              </select>
            </label>
          ))}
        </div>
        <div class="actions">
          <button onClick={() => answer(null)}>{t('lblCancel')}</button>
          <button class="primary" onClick={() => answer(picked)}>{t('lblOK')}</button>
        </div>
      </div>
    </Backdrop>
  );
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

/**
 * Something won or unlocked. The first line of its text is its name. An unlock's picture and an Aether Upgrade's Spark arrive in turning light,
 * and a Spark won drops onto a halo, as on mobile.
 */
function Announcement({ announced, ok }: { announced: RogueAnnounce; ok: () => void }) {
  const button = useFocus();
  const lit = useRef<HTMLSpanElement>(null);
  const [name, ...rest] = announced.text.split('\n');
  useEffect(() => { if (announced.kind === 'unlock') playSound({ t: 'sound', name: 'enchant', sync: false }); }, []);
  useLayoutEffect(() => { if (lit.current) entrance(lit.current, announced.kind === 'spark' ? 'drop' : 'rays'); }, []);
  return (
    <Backdrop layer="rg-dialog">
      <div class="dialog trophy" role="alertdialog" aria-label={announced.title}>
        <span class="camp-chip brass"><Icon d={ROGUE_ICONS.trophy} cls="rg-ic" /> {announced.title}</span>
        {announced.kind !== 'unlock' ? <span class="rg-lit-pic" ref={lit}><Sprite k="spark" px={32} cls="sparkpic" /></span>
          : announced.image && <span class="rg-lit-pic" ref={lit}><img onError={hideBroken} alt="" data-image={announced.image} src={imageUrl(announced.image)} /></span>}
        <h3>{name}</h3>
        {rest.length > 0 && <p>{rest.join('\n')}</p>}
        <div class="actions"><button class="primary" ref={button} onClick={ok}>{t('lblContinue')}</button></div>
      </div>
    </Backdrop>
  );
}
