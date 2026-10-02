import { setting } from './settings';
import type { Sound } from './protocol';

// Sound effects come from the host as names, and the server serves them and the music from the player's own Forge sound set

const clips = new Map<string, HTMLAudioElement>();
let music: HTMLAudioElement | null = null;
/** When each sound last started. The host sends a sound for every card of a deal at once, which is heard once. */
const started = new Map<string, number>();
const BURST_MS = 100;
/** Until when the host's copies of a sound are not played, while the page plays it for each card of a deal itself. */
const echoing = new Map<string, number>();

export function playSound(msg: Sound, echo = false): void {
  if (volume('soundVolume') <= 0) {
    return;
  }
  const now = performance.now();
  if (!echo && (now - (started.get(msg.name) ?? -Infinity) < BURST_MS || now < (echoing.get(msg.name) ?? 0))) {
    return;
  }
  started.set(msg.name, now);
  let clip = clips.get(msg.name);
  if (!clip) {
    clip = new Audio(`sound?name=${encodeURIComponent(msg.name)}`);
    clips.set(msg.name, clip);
  }
  const playing = !clip.paused && clip.currentTime > 0;
  // A synchronised effect never overlaps itself, as on desktop
  if (msg.sync && playing) {
    return;
  }
  // Any other plays again over itself, so a run of them, such as a deal, is heard as a run
  const voice = playing ? clip.cloneNode() as HTMLAudioElement : clip;
  voice.volume = volume('soundVolume');
  voice.currentTime = 0;
  voice.play().catch(() => {});
}

/** Only a sound the host sent a moment ago is echoed, so sounds turned off on the host stay off. */
export function echoSound(name: string, delays: number[]): void {
  const now = performance.now();
  if (!delays.length || now - (started.get(name) ?? -Infinity) > 1500) {
    return;
  }
  echoing.set(name, now + Math.max(...delays) + BURST_MS);
  for (const delay of delays) {
    window.setTimeout(() => playSound({ t: 'sound', name, sync: false }, true), delay);
  }
}

/** A sound the page ships itself rather than one from the host's sound set, played from `from` seconds in. */
export function playEffect(clip: HTMLAudioElement, from = 0): void {
  if (volume('soundVolume') <= 0) {
    return;
  }
  clip.volume = volume('soundVolume');
  clip.currentTime = from;
  clip.play().catch(() => {});
}

/** The music a screen plays: desktop's menu playlist, its match playlist, or none. */
export type Playlist = 'menu' | 'match';

let playing: Playlist | null = null;
let wanted: Playlist | null = null;
let awaitingGesture = false;

export function playMusic(list: Playlist | null): void {
  if (list !== wanted) {
    wanted = list;
    applyAudioSettings();
  }
}

export function applyAudioSettings(): void {
  const level = volume('musicVolume');
  if (!wanted || level <= 0) {
    stopMusic();
  } else if (music && playing === wanted) {
    music.volume = level;
  } else {
    stopMusic();
    const track = new Audio();
    music = track;
    playing = wanted;
    track.addEventListener('ended', nextTrack);
    // A playlist with no tracks answers 404; it stays silent until the screen asks for another list
    track.addEventListener('error', () => { if (music === track) stopMusic(); });
    nextTrack();
  }
}

function stopMusic(): void {
  music?.pause();
  music = null;
  playing = null;
}

// Each request returns another track from the playlist, so the server's shuffle does the choosing
function nextTrack(): void {
  if (!music) {
    return;
  }
  music.src = `music?name=${playing}&t=${Date.now()}`;
  music.volume = volume('musicVolume');
  music.play().catch(e => {
    stopMusic();
    // A browser plays nothing until the page is first clicked or typed into, so the menu's music waits for that
    if (e instanceof DOMException && e.name === 'NotAllowedError' && !awaitingGesture) {
      awaitingGesture = true;
      const retry = () => {
        if (awaitingGesture) {
          awaitingGesture = false;
          applyAudioSettings();
        }
      };
      window.addEventListener('pointerdown', retry, { once: true, capture: true });
      window.addEventListener('keydown', retry, { once: true, capture: true });
    }
  });
}

function volume(key: string): number {
  return Math.min(1, Math.max(0, Number(setting(key)) / 100));
}
