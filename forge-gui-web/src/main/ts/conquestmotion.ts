// Whether the player has asked for less motion; the Motion option decides, and follows the system only when set to.

export const reducedMotion = (): boolean => document.documentElement.dataset.motion === 'reduced';
