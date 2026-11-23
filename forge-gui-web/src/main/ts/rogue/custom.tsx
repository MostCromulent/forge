// Custom Run: the run's modifications by what each changes, beside the commander and the Descension levels

import { SidePanel } from '../campaign/campaign';
import { artUrl } from '../images';
import { isPortrait } from '../form';
import { t } from '../text';
import { Levels, Lock, answerFor, descensionLabel, picked, ready, shown, type Choice, type SetChoice } from './newrun';
import type { RogueChoice, RogueRoster } from '../protocol';

/** The art behind the page. Custom Run has no subject of its own, so it is always the same. */
const HAZE = 'c:Maelstrom Wanderer|PC2|1';

/** answer is the server's latest about the custom run, and toggle asks it to tick or untick a modification. */
export function CustomRun({ roster, choice, set, begin, answer, toggle }: {
  roster: RogueRoster; choice: Choice; set: SetChoice; begin: () => void; answer: RogueChoice | null; toggle: (id: string) => void;
}) {
  const phone = isPortrait();
  const mods = roster.modifications;
  const generated = mods.find(m => m.generatesCommander && choice.mods.includes(m.id));
  const groups = [...new Set(mods.map(m => m.group))];
  const list = shown(roster, choice);
  const commander = list[picked(list, choice)];
  const forced = answerFor(choice, answer)?.forced ?? [];
  const start = <button class={phone ? 'primary' : 'primary rg-go'} disabled={!ready(roster, choice, answer)} onClick={begin}>{t('lblRogueStartRun')}</button>;
  return (
    <div class="rg-start">
      <span class="rg-haze" style={{ backgroundImage: `url("${artUrl(HAZE)}")` }} />
      <div class="rg-scroll">
        {phone ? <p class="rg-pnote">{t('lblRogueCustomRunLimitations')}</p> : (
          <div class="rg-head"><h2>{t('lblRogueCustomRun')}</h2><span class="sp" /><span class="rg-pnote">{t('lblRogueCustomRunLimitations')}</span></div>
        )}
        {groups.map(group => (
          <div key={group} class="rg-group">
            <h4>{group}</h4>
            <div class="rg-mods">
              {mods.filter(m => m.group === group).map(m => {
                const on = choice.mods.includes(m.id);
                return (
                  <button key={m.id} class="rg-mod" aria-pressed={on} disabled={forced.includes(m.id)} onClick={() => toggle(m.id)}>
                    <span class="box">{on ? '✓' : ''}</span><b>{m.name}</b><span>{m.text}</span>
                  </button>
                );
              })}
            </div>
          </div>
        ))}
      </div>
      {phone ? (
        <div class="rg-sheetbar">
          <div class="go" style={{ gridTemplateColumns: 'auto 1fr' }}>
            <button disabled={!roster.descension} onClick={() => set(c => ({ ...c, sheet: true }))}>{descensionLabel(choice.level)}</button>
            {start}
          </div>
        </div>
      ) : (
        <SidePanel kicker={t('lblRogueCustomRun')} title={generated ? generated.name : commander?.name ?? '???'} art={generated ? null : commander?.image}
          noArt={!!generated} zoom={null} setZoom={() => {}} foot={start}>
          {generated ? <p class="camp-desc">{generated.text}</p>
            : <button onClick={() => set(c => ({ ...c, customOpen: false }))}>{t('lblWebRogueChangeCommander')}</button>}
          <div class="rg-sec">
            {roster.customLock ? <Lock text={roster.customLock} /> : <>
              <h4>{t('lblRogueDescensionMode')}</h4>
              <Levels levels={roster.levels} level={choice.level} startable={roster.levels.length} choose={level => set(c => ({ ...c, level }))} />
              {choice.level > 0 && <p>{roster.levels[choice.level - 1].text}</p>}
            </>}
          </div>
        </SidePanel>
      )}
    </div>
  );
}
