// Whether the player has asked for less motion, in the client's own setting or the system's.

export const reducedMotion = (): boolean => document.documentElement.dataset.motion === 'reduced'
  || matchMedia('(prefers-reduced-motion: reduce)').matches;
