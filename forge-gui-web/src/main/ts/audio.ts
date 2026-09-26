import { setting } from './settings';
import type { Sound } from './protocol';

// Sound effects come from the host as names, and both they and the music are files the server serves from the
// player's own Forge sound set.

const clips = new Map<string, HTMLAudioElement>();
let music: HTMLAudioElement | null = null;

export function playSound(msg: Sound): void {
  if (volume('soundVolume') <= 0) {
    return;
  }
  let clip = clips.get(msg.name);
  if (!clip) {
    clip = new Audio(`sound?name=${encodeURIComponent(msg.name)}`);
    clips.set(msg.name, clip);
  }
  // A synchronised effect never overlaps itself, as on desktop
  if (msg.sync && !clip.paused && clip.currentTime > 0) {
    return;
  }
  clip.volume = volume('soundVolume');
  clip.currentTime = 0;
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
