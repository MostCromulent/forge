// What sits over the adventure world map: the player's balances, notices, the quest dialog, the town notice and the menu

import { useEffect, useState } from 'preact/hooks';
import { Backdrop } from '../dialog';
import { plain } from './adventure';
import type { Actions } from '../actions';
import type { AdventureDialog, AdventureHud } from '../protocol';
import { t, formatNumber } from '../text';

/** How long a notice stays, as GameHUD keeps its notifications. */
const NOTICE_MS = 4000;

export function Hud({ hud, actions, openInventory }: { hud: AdventureHud; actions: Actions; openInventory?: () => void }) {
  const [notices, setNotices] = useState<{ id: number; text: string }[]>([]);
  const [announcement, setAnnouncement] = useState<string | null>(null);
  const [menu, setMenu] = useState(false);
  // The server sends each notice once, so they are kept here until they time out
  useEffect(() => {
    if (!hud.notifications.length) return;
    const added = hud.notifications.map(text => ({ id: Math.random(), text: plain(text) }));
    setNotices(n => [...n, ...added]);
    const timer = setTimeout(() => setNotices(n => n.filter(x => !added.includes(x))), NOTICE_MS);
    return () => clearTimeout(timer);
  }, [hud.notifications]);
  useEffect(() => { if (hud.announcement) setAnnouncement(plain(hud.announcement)); }, [hud.announcement]);
  return <>
    <div class="adv-hud" data-testid="adventure-hud">
      <span class="adv-chip" title={t('lblLife')}>♥ {hud.life}/{hud.maxLife}</span>
      <span class="adv-chip" title={t('lblWebAdventureGold')}>● {formatNumber(hud.gold)}</span>
      <span class="adv-chip" title={t('lblWebAdventureShards')}>◆ {formatNumber(hud.shards)}</span>
      <span class="sp" />
      {openInventory && <button onClick={openInventory}>{t('lblInventory')}</button>}
      <button onClick={() => setMenu(!menu)} aria-expanded={menu}>{t('lblMenu')}</button>
      {menu && (
        <div class="adv-menu" role="menu">
          <button role="menuitem" onClick={() => { setMenu(false); actions.adventureQuickSave(); }}>{t('lblWebAdventureQuickSave')}</button>
          <button role="menuitem" onClick={() => { setMenu(false); actions.adventureQuickSave(); actions.campaignLeave(); }}>{t('lblWebAdventureLeave')}</button>
        </div>
      )}
    </div>
    {notices.length > 0 && <div class="adv-notices">{notices.map(n => <p key={n.id}>{n.text}</p>)}</div>}
    {announcement && (
      <Backdrop>
        <div class="dialog" role="alertdialog">
          <p class="adv-dialog-text">{announcement}</p>
          <div class="actions"><button class="primary" onClick={() => setAnnouncement(null)}>{t('lblOK')}</button></div>
        </div>
      </Backdrop>
    )}
  </>;
}

/** A quest dialog page. The keys 1 to 9 pick the offered choices in order. */
export function DialogBox({ dialog, actions }: { dialog: AdventureDialog; actions: Actions }) {
  const offered = dialog.choices.filter(c => !c.disabled);
  useEffect(() => {
    const key = (e: KeyboardEvent) => {
      const n = Number(e.key);
      if (n >= 1 && n <= offered.length) actions.adventureDialogChoice(dialog.id, offered[n - 1].index);
    };
    window.addEventListener('keydown', key);
    return () => window.removeEventListener('keydown', key);
  }, [dialog.id]);
  return (
    <Backdrop cls="adv-backdrop">
      <div class="dialog adv-dialog" role="dialog" data-testid="adventure-dialog">
        <p class="adv-dialog-text">{plain(dialog.text)}</p>
        <div class="adv-choices">
          {dialog.choices.map(c => (
            <button key={c.index} disabled={c.disabled} onClick={() => actions.adventureDialogChoice(dialog.id, c.index)}>{plain(c.label)}</button>
          ))}
        </div>
      </div>
    </Backdrop>
  );
}

/** The notice for a town the browser cannot show yet. */
export function TownNotice({ name, actions }: { name: string; actions: Actions }) {
  return (
    <Backdrop cls="adv-backdrop">
      <div class="dialog" role="alertdialog" data-testid="adventure-town">
        <h3>{plain(name)}</h3>
        <p class="hint">{t('lblWebAdventureTownNotYet')}</p>
        <div class="actions"><button class="primary" onClick={() => actions.adventurePlaceholderDone()}>{t('lblContinue')}</button></div>
      </div>
    </Backdrop>
  );
}
