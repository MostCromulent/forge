// The adventure world map, drawn on one canvas. The server draws the ground in chunks with the mobile client's tile code. The
// browser places the trees, points of interest, enemies and the hero on it, and moves the hero itself so walking feels instant

import { useEffect, useRef } from 'preact/hooks';
import { adjustMovement, Grid, type Box } from './collision';
import { Animator, Atlas, directionOf } from './sprites';
import type { Actions } from '../actions';
import type { AdventureEnemy, AdventurePoi, AdventureState } from '../protocol';

/** The part of the world the mobile client shows, in world pixels. The canvas shows it at a whole multiple, so pixel art stays sharp. */
const VIEW_W = 480;
const VIEW_H = 270;
/** How often the browser reports where the hero is. Each report advances the server's map by the time since the last. */
const REPORT_MS = 50;
/** A click-and-hold stops this close to the pointer, as GameStage stops a touch move. */
const STOP_DISTANCE = 2;
const KEYS: Record<string, [number, number]> = {
  ArrowLeft: [-1, 0], KeyA: [-1, 0], ArrowRight: [1, 0], KeyD: [1, 0], ArrowUp: [0, 1], KeyW: [0, 1], ArrowDown: [0, -1], KeyS: [0, -1],
};

/** A tree or rock of a chunk, at a region of its atlas's picture. */
interface Decoration { x: number; y: number; layer: number; atlas: string; rx: number; ry: number; w: number; h: number }

/** One thing drawn in the sprite layer, sorted by its height on the map. */
interface Drawn { y: number; draw: (g: CanvasRenderingContext2D, top: number, left: number) => void }

/** The hero's box for walking, as CharacterSprite.updateBoundingRect makes it: narrower than the sprite and 0.4 of its height. */
export const heroBox = (x: number, y: number, w: number, h: number): Box => ({ x: x + 4, y, w: w - 6, h: h * 0.4 });

/** Where the camera centres, kept inside the world as GameStage.act keeps it. */
export function cameraAt(x: number, y: number, worldW: number, worldH: number): [number, number] {
  return [Math.min(Math.max(VIEW_W / 2, x), worldW - VIEW_W / 2), Math.min(Math.max(VIEW_H / 2, y), worldH - VIEW_H / 2)];
}

/** Whether two boxes overlap, as libgdx Rectangle.overlaps tests them. */
const overlaps = (a: Box, b: Box) => a.x < b.x + b.w && a.x + a.w > b.x && a.y < b.y + b.h && a.y + a.h > b.y;

/** The minimap's width on the canvas, in world pixels of the view. */
const MINIMAP_W = 72;

export function World({ state, enemies, speed, flying, frozen, navAngle, encounter, actions }: {
  state: AdventureState; enemies: AdventureEnemy[]; speed: number; flying: boolean; frozen: boolean; navAngle: number | null; encounter: number | null;
  actions: Actions;
}) {
  const canvas = useRef<HTMLCanvasElement>(null);
  // What the frame loop reads. Props change on every server message, so the loop reads them from here instead of restarting
  const live = useRef({ enemies, speed, flying, frozen, navAngle, encounter });
  live.current = { enemies, speed, flying, frozen, navAngle, encounter };

  useEffect(() => {
    const el = canvas.current!;
    const g = el.getContext('2d')!;
    const tile = state.tileSize;
    const chunkPx = state.chunkSize * tile;
    const worldW = state.width * tile;
    const worldH = state.height * tile;
    const hero = { x: state.x, y: state.y, w: 16, h: 16 };
    const held = new Set<string>();
    let pointer: { x: number; y: number } | null = null;
    let grid: Grid | null = null;
    let heroAnim: Animator | null = null;
    const atlases = new Map<string, Atlas>();
    const loading = new Set<string>();
    const chunks = new Map<string, HTMLImageElement>();
    const decorations = new Map<string, Decoration[] | null>();
    const enemyAnims = new Map<number, Animator>();
    const enemyLast = new Map<number, [number, number]>();
    // The last two enemy lists and when each came. Enemies are drawn between them, a tick behind, so they move smoothly
    let ticks: { at: number; list: AdventureEnemy[] }[] = [];
    // The point of interest the hero stands in. It is entered again only after the hero has left it, as WorldStage.collidingPoint does
    let lockedPoi: string | null = null;
    let fought: number | null = null;
    const minimap = new Image();
    minimap.src = `/adv/minimap?w=${state.world}`;
    let stopped = false;
    let lastFrame = performance.now();
    let lastReport = 0;
    let movingReported = false;

    /** An atlas once it has loaded. The first ask starts loading it. */
    const atlas = (path: string): Atlas | null => {
      const loaded = atlases.get(path);
      if (!loaded && !loading.has(path)) {
        loading.add(path);
        Atlas.load(path).then(a => atlases.set(path, a), () => loading.delete(path));
      }
      return loaded ?? null;
    };
    void Grid.load(`/adv/grid?w=${state.world}`, state.width, state.height, tile).then(loaded => { grid = loaded; });
    void Atlas.load(state.heroAtlas).then(a => {
      atlases.set(state.heroAtlas, a);
      heroAnim = new Animator(a);
      const f = heroAnim.frame();
      if (f) { hero.w = f.w; hero.h = f.h; }
    });

    const chunk = (cx: number, cy: number): HTMLImageElement => {
      const key = `${cx},${cy}`;
      let img = chunks.get(key);
      if (!img) {
        img = new Image();
        img.src = `/adv/chunk?w=${state.world}&x=${cx}&y=${cy}`;
        chunks.set(key, img);
      }
      return img;
    };
    const chunkDecorations = (cx: number, cy: number): Decoration[] => {
      const key = `${cx},${cy}`;
      const known = decorations.get(key);
      if (known) return known;
      if (!decorations.has(key)) {
        decorations.set(key, null);
        fetch(`/adv/decorations?w=${state.world}&x=${cx}&y=${cy}`).then(r => r.json()).then((list: Decoration[]) => decorations.set(key, list),
          () => decorations.delete(key));
      }
      return [];
    };

    const onKey = (down: boolean) => (e: KeyboardEvent) => {
      if (!(e.code in KEYS) || (e.target as HTMLElement)?.closest('input, textarea')) return;
      e.preventDefault();
      if (down) held.add(e.code); else held.delete(e.code);
    };
    const keyDown = onKey(true);
    const keyUp = onKey(false);
    const scale = () => Math.max(1, Math.floor(Math.min((el.parentElement!.clientWidth) / VIEW_W, (el.parentElement!.clientHeight) / VIEW_H)));
    /** The world point under a pointer event. */
    const worldPoint = (e: PointerEvent) => {
      const r = el.getBoundingClientRect();
      const s = r.width / VIEW_W;
      const [camX, camY] = cameraAt(hero.x, hero.y, worldW, worldH);
      return { x: camX - VIEW_W / 2 + (e.clientX - r.left) / s, y: camY + VIEW_H / 2 - (e.clientY - r.top) / s };
    };
    const down = (e: PointerEvent) => { el.setPointerCapture(e.pointerId); pointer = worldPoint(e); };
    const drag = (e: PointerEvent) => { if (pointer) pointer = worldPoint(e); };
    const up = () => { pointer = null; };
    window.addEventListener('keydown', keyDown);
    window.addEventListener('keyup', keyUp);
    el.addEventListener('pointerdown', down);
    el.addEventListener('pointermove', drag);
    el.addEventListener('pointerup', up);
    el.addEventListener('pointercancel', up);

    const frame = (now: number) => {
      if (stopped) return;
      const dt = Math.min(0.1, (now - lastFrame) / 1000);
      lastFrame = now;
      const { enemies: latest, speed: walk, flying: flies, frozen: still, navAngle: nav, encounter: caught } = live.current;
      if (!ticks.length || ticks[ticks.length - 1].list !== latest) ticks = [...ticks.slice(-1), { at: now, list: latest }];
      const between = Math.min(1, (now - ticks[ticks.length - 1].at) / REPORT_MS);
      const before = ticks.length > 1 ? ticks[0].list : latest;
      const foes = latest.map(e => {
        const was = before.find(b => b.id === e.id);
        return was ? { ...e, x: was.x + (e.x - was.x) * between, y: was.y + (e.y - was.y) * between } : e;
      });

      // Ported from GameStage.act: the pointer wins over the keys, and the move is the walking speed in the chosen direction
      let dx = 0;
      let dy = 0;
      if (!still) {
        if (pointer) {
          const tx = pointer.x - hero.w / 2 - hero.x;
          const ty = pointer.y - hero.y;
          if (Math.hypot(tx, ty) >= STOP_DISTANCE) { dx = tx; dy = ty; }
        } else {
          for (const code of held) { dx += KEYS[code][0]; dy += KEYS[code][1]; }
        }
      }
      const moving = dx !== 0 || dy !== 0;
      if (moving && grid) {
        const len = Math.hypot(dx, dy);
        const step = walk * dt;
        const [mx, my] = adjustMovement(grid, dx / len * step, dy / len * step, heroBox(hero.x, hero.y, hero.w, hero.h), flies);
        hero.x += mx;
        hero.y += my;
        heroAnim?.set('Walk');
        heroAnim?.face(directionOf(dx, dy));
      } else if (caught === null) {
        heroAnim?.set('Idle');
      }
      // Ported from WorldStage.onActing: both sides play their attack when an enemy catches the hero
      if (caught !== null && fought !== caught) {
        fought = caught;
        heroAnim?.set('Attack');
        enemyAnims.get(caught)?.set('Attack');
      }
      heroAnim?.tick(dt);
      // Ported from WorldStage.handlePointsOfInterestCollision
      const box = heroBox(hero.x, hero.y, hero.w, hero.h);
      const touching = state.pois.find(p => p.active && overlaps(box, { x: p.x, y: p.y, w: p.w, h: p.h }));
      if (lockedPoi !== null && touching?.id !== lockedPoi) lockedPoi = null;
      if (!still && touching && touching.id !== lockedPoi) {
        lockedPoi = touching.id;
        actions.adventureEnterPoi(touching.id, hero.x, hero.y);
      }
      el.dataset.x = String(Math.round(hero.x));
      el.dataset.y = String(Math.round(hero.y));
      if (now - lastReport >= REPORT_MS || moving !== movingReported) {
        lastReport = now;
        movingReported = moving;
        actions.adventureMove(hero.x, hero.y, moving);
      }

      const s = scale();
      if (el.width !== VIEW_W * s) { el.width = VIEW_W * s; el.height = VIEW_H * s; }
      g.setTransform(s, 0, 0, s, 0, 0);
      g.imageSmoothingEnabled = false;
      g.fillStyle = '#000';
      g.fillRect(0, 0, VIEW_W, VIEW_H);
      const [camX, camY] = cameraAt(hero.x, hero.y, worldW, worldH);
      const left = camX - VIEW_W / 2;
      const top = camY + VIEW_H / 2;
      // The visible chunks and a ring round them, so the next ones are loading before they scroll in
      const minCx = Math.floor(left / chunkPx) - 1;
      const maxCx = Math.floor((left + VIEW_W) / chunkPx) + 1;
      const minCy = Math.floor((top - VIEW_H) / chunkPx) - 1;
      const maxCy = Math.floor(top / chunkPx) + 1;
      const drawn: Drawn[] = [];
      for (let cx = Math.max(0, minCx); cx <= maxCx && cx * chunkPx < worldW; cx++) {
        for (let cy = Math.max(0, minCy); cy <= maxCy && cy * chunkPx < worldH; cy++) {
          const img = chunk(cx, cy);
          if (img.complete && img.naturalWidth) g.drawImage(img, Math.round(cx * chunkPx - left), Math.round(top - (cy + 1) * chunkPx));
          for (const d of chunkDecorations(cx, cy)) {
            const a = atlas(d.atlas);
            if (!a?.picture) continue;
            const draw = (c: CanvasRenderingContext2D, t: number, l: number) =>
              c.drawImage(a.picture!, d.rx, d.ry, d.w, d.h, Math.round(d.x - l), Math.round(t - d.y - d.h), d.w, d.h);
            // MapSprite's background layer lies under everything; the sprite layer is sorted with the characters
            if (d.layer < 0) draw(g, top, left); else drawn.push({ y: d.y, draw });
          }
        }
      }
      for (const p of state.pois) {
        if (!p.active) continue;
        const a = atlas(p.atlas);
        if (a?.picture) drawn.push({ y: p.y, draw: (c, t, l) => drawPoi(c, a.picture!, p, t, l) });
      }
      for (const e of foes) {
        const a = atlas(e.atlas);
        if (!a?.picture) continue;
        let anim = enemyAnims.get(e.id);
        if (!anim) { anim = new Animator(a); enemyAnims.set(e.id, anim); }
        const last = enemyLast.get(e.id);
        if (last && (last[0] !== e.x || last[1] !== e.y)) anim.face(directionOf(e.x - last[0], e.y - last[1]));
        enemyLast.set(e.id, [e.x, e.y]);
        if (e.id !== caught) anim.set(e.animation === 'Walk' ? 'Walk' : 'Idle');
        anim.tick(dt);
        const f = anim.frame();
        if (f) drawn.push({ y: e.y, draw: (c, t, l) => drawFrame(c, a.picture!, f, e.x - l, t - e.y - f.h) });
      }
      const heroFrame = heroAnim?.frame();
      const heroAtlas = atlases.get(state.heroAtlas);
      if (heroFrame && heroAtlas?.picture) drawn.push({ y: hero.y, draw: (c, t, l) => drawFrame(c, heroAtlas.picture!, heroFrame, hero.x - l, t - hero.y - heroFrame.h) });
      // SpriteGroup draws the highest first, so what stands lower on the map covers it
      drawn.sort((a, b) => b.y - a.y);
      for (const d of drawn) d.draw(g, top, left);
      if (nav !== null) drawArrow(g, hero.x + hero.w / 2 - left, top - hero.y - hero.h / 2, nav);
      if (minimap.complete && minimap.naturalWidth) drawMinimap(g, minimap, hero.x / worldW, hero.y / worldH);
      requestAnimationFrame(frame);
    };
    // A hero already standing in a point of interest, as after leaving a town, does not enter it again until it walks out
    lockedPoi = state.pois.find(p => p.active && overlaps(heroBox(hero.x, hero.y, hero.w, hero.h), { x: p.x, y: p.y, w: p.w, h: p.h }))?.id ?? null;
    requestAnimationFrame(frame);
    return () => {
      stopped = true;
      window.removeEventListener('keydown', keyDown);
      window.removeEventListener('keyup', keyUp);
      actions.adventureMove(hero.x, hero.y, false);
    };
  }, [state]);

  return <div class="adv-stage"><canvas ref={canvas} class="adv-canvas" data-testid="adventure-world" /></div>;
}

function drawFrame(g: CanvasRenderingContext2D, picture: HTMLImageElement, f: { x: number; y: number; w: number; h: number; flip: boolean }, x: number, y: number) {
  if (!f.flip) {
    g.drawImage(picture, f.x, f.y, f.w, f.h, Math.round(x), Math.round(y), f.w, f.h);
    return;
  }
  g.save();
  g.translate(Math.round(x) + f.w, Math.round(y));
  g.scale(-1, 1);
  g.drawImage(picture, f.x, f.y, f.w, f.h, 0, 0, f.w, f.h);
  g.restore();
}

/** The quest arrow round the hero, pointing at the tracked quest's target, as NavArrowActor draws it. angle is counterclockwise from east. */
function drawArrow(g: CanvasRenderingContext2D, x: number, y: number, angle: number) {
  g.save();
  g.translate(Math.round(x), Math.round(y));
  g.rotate(-angle * Math.PI / 180);
  g.fillStyle = '#ffd34d';
  g.strokeStyle = '#000';
  g.beginPath();
  g.moveTo(22, 0);
  g.lineTo(14, -5);
  g.lineTo(14, 5);
  g.closePath();
  g.fill();
  g.stroke();
  g.restore();
}

/** The whole world small in the top right corner, with the hero's place marked. fx and fy are the hero's place as a fraction of the world. */
function drawMinimap(g: CanvasRenderingContext2D, map: HTMLImageElement, fx: number, fy: number) {
  const h = Math.round(MINIMAP_W * map.naturalHeight / map.naturalWidth);
  const x = VIEW_W - MINIMAP_W - 4;
  const y = 4;
  g.globalAlpha = 0.85;
  g.drawImage(map, x, y, MINIMAP_W, h);
  g.globalAlpha = 1;
  g.strokeStyle = '#000';
  g.strokeRect(x - 0.5, y - 0.5, MINIMAP_W + 1, h + 1);
  // A white mark in a black frame stands out by brightness on any biome
  g.fillStyle = '#000';
  g.fillRect(Math.round(x + fx * MINIMAP_W) - 2, Math.round(y + (1 - fy) * h) - 2, 5, 5);
  g.fillStyle = '#fff';
  g.fillRect(Math.round(x + fx * MINIMAP_W) - 1, Math.round(y + (1 - fy) * h) - 1, 3, 3);
}

function drawPoi(g: CanvasRenderingContext2D, picture: HTMLImageElement, p: AdventurePoi, top: number, left: number) {
  g.drawImage(picture, p.rx, p.ry, p.w, p.h, Math.round(p.x - left), Math.round(top - p.y - p.h), p.w, p.h);
}
