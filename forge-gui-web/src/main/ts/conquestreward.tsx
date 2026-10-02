// The reward is already in the save and its steps are the server's, so this only shows them and tells the bar what is yet to be shown

import { useEffect, useLayoutEffect, useRef, useState } from 'preact/hooks';
import { skinIconUrl, imageUrl } from './images';
import { mountPack, type Pack } from './conquestpack';
import { mountWheel, spotFor, type Wheel, type WheelOutcome } from './conquestwheel';
import { pendingAmounts } from './conquestpending';
import { reducedMotion } from './conquestmotion';
import type { Reward, RewardStep } from './protocol';
import { t } from './text';

/** What the reveal has yet to show, by the icon of the balance it was paid into. */
export type Owed = Record<string, number>;

export const wheelLabels = (): Record<WheelOutcome, string> => ({
  CHAOS: t('lblChaos'), BOOSTER: t('lblWebConquestWheelBooster'), DOUBLE_BOOSTER: t('lblWebConquestWheelBoosters'),
  SHARDS: t('lblWebConquestWheelShards'), DOUBLE_SHARDS: t('lblWebConquestWheelDoubleShards'), PLANESWALK: t('lblWebConquestWheelEmblems'),
});

/** A step with nothing of its own to show: the packs before it have already said what their duplicates became. */
const silent = (step: RewardStep): boolean => step.kind === 'DUPLICATE_SHARDS';

export function Reveal({ reward, onOwed, done }: { reward: Reward; onOwed: (owed: Owed) => void; done: () => void }) {
  const steps = reward.steps;
  // The emblem of a first conquest is shown over the wheel that follows it, so the two are one stop
  const first = steps[0]?.kind === 'CONQUER_EMBLEMS' && steps[1]?.kind === 'WHEEL' ? 1 : 0;
  const [at, setAt] = useState(first);
  /** The current pack's duplicate shards that have reached the bar. */
  const [released, setReleased] = useState(0);
  const [zoom, setZoom] = useState<string | null>(null);
  const finished = useRef(false);
  const step = steps[at];

  const next = () => {
    let i = at + 1;
    while (i < steps.length && silent(steps[i])) i++;
    setReleased(0);
    setAt(i);
    // Said at once, not after the next paint: a reload in between would be sent the same reward again
    if (i >= steps.length) finish();
  };
  const finish = () => {
    if (!finished.current) {
      finished.current = true;
      done();
    }
  };
  // A reward with nothing to show is over as soon as it arrives
  useEffect(() => { if (at >= steps.length) finish(); }, []);

  // The step on show has paid, except a pack, which pays card by card
  const packShards = step?.kind === 'BOOSTER' ? (step.cards ?? []).reduce((sum, c) => sum + c.shards, 0) : 0;
  const owed = pendingAmounts(steps, at + 1);
  if (step?.kind === 'BOOSTER' && step.icon) owed[step.icon] = (owed[step.icon] ?? 0) + packShards - released;
  // Before the first paint, or the bar would show for a frame everything the reveal is about to give
  useLayoutEffect(() => onOwed(owed), [JSON.stringify(owed)]);

  if (!step) return null;
  return (
    <div class="backdrop cq-reveal">
      {step.kind === 'WHEEL' && <WheelStop key={at} step={step} emblem={first === 1 && at === 1 ? steps[0].amount : 0} next={next} />}
      {step.kind === 'BOOSTER' && <PackStop key={at} step={step} release={n => setReleased(r => r + n)} zoom={setZoom} next={next} />}
      {step.kind === 'CONQUER_EMBLEMS' && <Gift icon="IMG_PW_BADGE_COMMON" amount={step.amount} title={t('lblWebConquestFirstConquest')} button={t('lblGreat')} next={next} />}
      {step.kind === 'SHARDS' && <Gift icon="IMG_AETHER_SHARD" amount={step.amount} title={t('lblReceivedAetherShards')} button={t('lblGreat')} next={next} />}
      {step.kind === 'EMBLEMS' && <Gift icon="IMG_PW_BADGE_COMMON" amount={step.amount} title={t('lblReceivedBonusPlaneswalkEmblems')} button={t('lblGreat')} next={next} />}
      {step.kind === 'ALL_PLANES_UNLOCKED' && <Gift icon="IMG_PW_BADGE_COMMON" title={t('lblAllPlanesUnlocked')} text={t('lblAllPlanesUnlockedNotify')} button={t('lblOK')} next={next} />}
      {step.kind === 'CHAOS_BATTLE' && <Gift icon="IMG_MULTIVERSE" title={t('lblChaosApproaching')} text={t('lblWebConquestNoRefusal')} button={t('lblOK')} next={next} />}
      {zoom && <div class="backdrop cq-zoom-layer" onClick={e => { e.stopPropagation(); setZoom(null); }}><img class="cq-zoom" alt="" src={imageUrl(zoom)} /></div>}
    </div>
  );
}

/** One of the wheel's plain outcomes, or a notice, as a small dialog of one shape. */
function Gift({ icon, amount, title, text, button, next }: { icon: string; amount?: number; title: string; text?: string; button: string; next: () => void }) {
  return (
    <div class="dialog cq-gift">
      <h3>{title}</h3>
      <div class="cq-gift-what"><img alt="" src={skinIconUrl(icon)} />{amount !== undefined && <b>{amount.toLocaleString('en-GB')}</b>}</div>
      {text && <p>{text}</p>}
      <button class="primary" onClick={next}>{button}</button>
    </div>
  );
}

function WheelStop({ step, emblem, next }: { step: RewardStep; emblem: number; next: () => void }) {
  const host = useRef<HTMLDivElement>(null);
  const wheel = useRef<Wheel | null>(null);
  const spot = useRef(spotFor(step.outcome ?? ''));
  const [phase, setPhase] = useState<'ready' | 'spinning' | 'stopped'>('ready');
  useEffect(() => { wheel.current = mountWheel(host.current!, wheelLabels()); }, []);
  const spin = () => {
    setPhase('spinning');
    void wheel.current!.spin(spot.current).then(() => setPhase('stopped'));
  };
  return (
    <div class="cq-wheel-stop">
      {emblem > 0 && <p class="cq-wheel-first"><img alt="" src={skinIconUrl('IMG_PW_BADGE_COMMON')} />{t('lblWebConquestFirstConquest')}</p>}
      <div class="cq-wheel-host" ref={host} />
      <div class="cq-reveal-foot">
        {phase === 'stopped'
          ? <button class="primary cq-big" onClick={next}>{t('lblGreat')}</button>
          : <button class="primary cq-big" disabled={phase === 'spinning'} onClick={spin}>{t('lblWebConquestSpin')}</button>}
      </div>
    </div>
  );
}

/** A duplicate's shards leave the card for the bar's balance, and are counted there when they arrive. */
function flyShards(from: DOMRect, arrived: () => void): void {
  const to = document.querySelector('.cq-purse .cq-coin')?.getBoundingClientRect();
  if (!to || reducedMotion()) {
    arrived();
    return;
  }
  const mote = document.createElement('img');
  mote.className = 'cq-mote';
  mote.alt = '';
  mote.src = skinIconUrl('IMG_AETHER_SHARD');
  document.body.append(mote);
  const x0 = from.left + from.width / 2, y0 = from.top + from.height * .4;
  mote.animate([{ transform: `translate(${x0}px, ${y0}px) scale(1.4)`, opacity: 1 },
    { transform: `translate(${to.left + 14}px, ${to.top + to.height / 2}px) scale(.8)`, opacity: .9 }],
    { duration: 600, easing: 'cubic-bezier(.5,0,.3,1)' }).finished.then(() => { mote.remove(); arrived(); }, () => mote.remove());
}

function PackStop({ step, release, zoom, next }: { step: RewardStep; release: (shards: number) => void; zoom: (image: string) => void; next: () => void }) {
  const host = useRef<HTMLDivElement>(null);
  const pack = useRef<Pack | null>(null);
  const [phase, setPhase] = useState<'sealed' | 'opening' | 'shown'>('sealed');
  // Shards still in flight when the pack is left have been counted already, and are not the next pack's
  const here = useRef(true);
  useEffect(() => () => { here.current = false; }, []);
  const cards = step.cards ?? [];
  const duplicates = cards.reduce((sum, c) => sum + c.shards, 0);
  useEffect(() => {
    pack.current = mountPack(host.current!, {
      name: step.chaos ? t('lblChaos') : step.pack ?? '', sub: step.chaos ? step.pack ?? '' : t('lblBoosterPack'),
      art: step.chaos ? null : step.art ?? null, chaos: step.chaos, cards, hint: t('lblWebConquestClickToOpen'),
    }, {
      flipped: (card, rect) => { if (card.shards) flyShards(rect, () => { if (here.current) release(card.shards); }); },
      zoom: card => zoom(card.image),
      opening: () => setPhase('opening'),
      shown: () => setPhase('shown'),
    });
  }, []);
  const title = step.total > 1 ? t('lblReceivedBoosterPackNOfTotal', step.number, step.total).replace('\n', ' ') : t('lblReceivedBoosterPack');
  return (
    // A click anywhere while the cards are travelling or turning shows them all at once
    <div class="cq-pack-stop" onClick={() => { if (phase === 'opening') pack.current?.finish(); }}>
      <h3>{title}</h3>
      <div class="cq-pack-host" ref={host} />
      <div class="cq-reveal-foot">
        {phase === 'shown' && duplicates > 0 && (
          <span class="cq-dup-line">{t('lblReceivedAetherShardsForDuplicateCards')}<img alt="" src={skinIconUrl('IMG_AETHER_SHARD')} /><b>{duplicates.toLocaleString('en-GB')}</b></span>
        )}
        {phase === 'shown'
          ? <button class="primary cq-big" onClick={e => { e.stopPropagation(); next(); }}>{t('lblGreat')}</button>
          : <button onClick={e => { e.stopPropagation(); pack.current?.finish(); }}>{t('lblSkip')}</button>}
      </div>
    </div>
  );
}
