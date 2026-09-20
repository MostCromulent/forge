import { setting } from './settings.js';

// Sound effects come from the host as names, and both they and the music are files the server serves from the
// player's own Forge sound set. A browser refuses to play until the page has been clicked, which the start
// page's Play button covers.

const clips = new Map();
let music = null;

export function playSound(msg) {
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

export function startMusic() {
  if (volume('musicVolume') <= 0 || music) {
    return;
  }
  music = new Audio();
  music.addEventListener('ended', nextTrack);
  music.addEventListener('error', () => stopMusic());
  nextTrack();
}

export function stopMusic() {
  if (!music) {
    return;
  }
  music.pause();
  music = null;
}

// Each request returns another track from the match playlist, so the server's shuffle does the choosing
function nextTrack() {
  if (!music) {
    return;
  }
  music.src = `music?t=${Date.now()}`;
  music.volume = volume('musicVolume');
  music.play().catch(() => stopMusic());
}

export function applyAudioSettings() {
  if (volume('musicVolume') <= 0) {
    stopMusic();
  } else if (music) {
    music.volume = volume('musicVolume');
  } else {
    startMusic();
  }
}

function volume(key) {
  return Math.min(1, Math.max(0, Number(setting(key)) / 100));
}
