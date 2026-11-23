// The ring of commanders on the New Run page: where each card stands, and how Random rolls it

/** Where the card at index stands when centre is in front: o places away, on the shorter side of a ring of n. */
export function place(index: number, centre: number, n: number): { o: number; abs: number; sign: number } {
  let o = index - centre;
  if (o > n / 2) o -= n;
  if (o < -n / 2) o += n;
  return { o, abs: Math.abs(o), sign: Math.sign(o) };
}

/**
 * Random steps forward one card at a time from from and stops on to, after at least eight steps.
 * waits are the times between the steps: 70 ms, slowing by 1.32 over the last seven.
 */
export function roll(from: number, to: number, n: number): { steps: number; waits: number[] } {
  let steps = (((to - from) % n) + n) % n;
  while (steps < 8) steps += n;
  const waits: number[] = [];
  let wait = 70;
  for (let left = steps - 1; left > 0; left--) {
    if (left < 7) wait *= 1.32;
    waits.push(wait);
  }
  return { steps, waits };
}

/** Plays a roll, showing each card as it steps onto it. The returned function ends the roll at once on to. */
export function rolling(from: number, to: number, n: number, show: (index: number) => void, done: () => void): () => void {
  const { steps, waits } = roll(from, to, n);
  let at = from, step = 0, over = false;
  let timer: ReturnType<typeof setTimeout> | undefined;
  const end = () => {
    over = true;
    clearTimeout(timer);
    done();
  };
  const next = () => {
    at = (at + 1) % n;
    show(at);
    if (++step >= steps) end();
    else timer = setTimeout(next, waits[step - 1]);
  };
  next();
  return () => {
    if (over) return;
    show(to);
    end();
  };
}
