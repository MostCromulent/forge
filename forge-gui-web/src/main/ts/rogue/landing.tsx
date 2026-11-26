// Runs with an unfinished run: the run, which continues from here, beside Start New Run

import { artUrl, hideBroken, imageUrl } from '../images';
import { t } from '../text';
import { Sprite, glyph } from './kit';
import type { RogueHome, RogueRunCard } from '../protocol';

const art = (key: string) => ({ backgroundImage: `url("${artUrl(key)}")` });
const card = (key: string) => ({ backgroundImage: `url("${imageUrl(key)}")` });

export function Landing({ home, run, go, deck, abandon, fresh }: {
  home: RogueHome; run: RogueRunCard; go: () => void; deck: () => void; abandon: () => void; fresh: () => void;
}) {
  return (
    <div class="rgl">
      <div class="rgl-top">
        <article class="rgl-run">
          <span class="haze" style={art(run.image)} />
          <div class="in">
            <span class="rgl-card" data-image={run.image} style={card(run.image)} />
            <div class="t">
              <div>
                <span class="camp-kicker">{t('lblWebRogueCurrentRun')}</span>
                <h1>{run.commander}</h1>
                {run.line && <p>{run.line}</p>}
              </div>
              <div class="figs">
                <div>{glyph('heart')}<b>{`${run.life} / ${run.maxLife}`}</b><span>{t('lblLife')}</span></div>
                <div><Sprite k="coin" /><b>{run.gold}</b><span>{t('lblWebRogueGold')}</span></div>
                <div>{glyph('deck')}<b>{run.cards}</b><span>{t('lblCards')}</span></div>
                <div>{glyph('cut')}<b>{run.credits}</b><span>{t('lblWebRogueRemovalCredits')}</span></div>
              </div>
              {run.carry.length > 0 && (
                <div class="sec">
                  <span class="camp-kicker">{t('lblWebRogueCarryCards')}</span>
                  <div class="held">{run.carry.map(c => <img onError={hideBroken} key={c.name} alt={c.name} data-image={c.image} src={imageUrl(c.image)} />)}</div>
                </div>
              )}
              <div class="go">
                <button class="primary" onClick={go}>{t('lblRogueContinueRun')}</button>
                <button onClick={deck}>{t('lblDeck')}</button>
                <button onClick={abandon}>{t('lblRogueAbandonRun')}</button>
              </div>
            </div>
          </div>
        </article>
        <button class="rgl-new" onClick={fresh}>
          {home.fan.length > 0 && <span class="haze" style={art(home.fan[home.fan.length - 1])} />}
          <span class="fan">{home.fan.map((key, i) => <i key={key} style={{ '--k': i - 1, ...card(key) }} />)}</span>
          <b>{t('lblRogueStartNewRun')}</b>
          <span class="d">{t('lblRoguePickYourRogueCommander')}</span>
        </button>
      </div>
    </div>
  );
}
