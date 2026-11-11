// Adventure's animated sprites, read from the mobile client's atlases. The names, timings and the stand-ins for a missing
// direction follow CharacterSprite, so a character moves and faces as it does on mobile

export type AnimationType = 'Idle' | 'Walk' | 'Death' | 'Attack' | 'Hit' | 'Hidden' | 'Wake';
export type Direction = 'None' | 'Right' | 'RightDown' | 'Down' | 'LeftDown' | 'Left' | 'LeftUp' | 'Up' | 'RightUp';

const DIRECTIONS: Direction[] = ['None', 'Right', 'RightDown', 'Down', 'LeftDown', 'Left', 'LeftUp', 'Up', 'RightUp'];
const FRAME_SECONDS = 0.2;
const MOST_DEATH_SECONDS = 3;
const ONE_SHOT: AnimationType[] = ['Attack', 'Death', 'Hit'];

export interface AtlasFrame { name: string; index: number; x: number; y: number; w: number; h: number }
export interface Frame { x: number; y: number; w: number; h: number; flip: boolean }

export class Atlas {
  private readonly byName = new Map<string, AtlasFrame[]>();
  picture: HTMLImageElement | null = null;

  constructor(readonly image: string, frames: AtlasFrame[]) {
    for (const f of frames) {
      const list = this.byName.get(f.name) ?? [];
      list.push(f);
      this.byName.set(f.name, list);
    }
    for (const list of this.byName.values()) list.sort((a, b) => a.index - b.index);
  }

  frames(name: string): AtlasFrame[] {
    return this.byName.get(name) ?? [];
  }

  private static readonly loaded = new Map<string, Promise<Atlas>>();

  /** An atlas from the adventure folders, with its picture loaded. Each is fetched once per page. */
  static load(path: string): Promise<Atlas> {
    let atlas = Atlas.loaded.get(path);
    if (!atlas) {
      atlas = (async () => {
        const json = await (await fetch(`/adv/atlas?path=${encodeURIComponent(path)}`)).json() as { image: string; frames: AtlasFrame[] };
        const loaded = new Atlas(json.image, json.frames);
        const picture = new Image();
        picture.src = `/adv/res?path=${encodeURIComponent(json.image)}`;
        await picture.decode();
        loaded.picture = picture;
        return loaded;
      })();
      Atlas.loaded.set(path, atlas);
    }
    return atlas;
  }
}

/** The facing for a move, by eighths of the circle, as CharacterSprite.moveBy picks it. */
export function directionOf(dx: number, dy: number): Direction {
  let degrees = Math.atan2(dy, dx) * 180 / Math.PI;
  if (degrees < 0) degrees += 360;
  const order: Direction[] = ['Right', 'RightUp', 'Up', 'LeftUp', 'Left', 'LeftDown', 'Down', 'RightDown'];
  for (let i = 0; i < order.length; i++) {
    if (degrees < 22.5 + 45 * i) return order[i];
  }
  return 'Right';
}

interface Animation { frames: AtlasFrame[]; flip: boolean; seconds: number }

/** One character's animations in each direction, and which frame shows now. */
export class Animator {
  private readonly animations = new Map<AnimationType, Map<Direction, Animation>>();
  private type: AnimationType = 'Idle';
  private direction: Direction = 'Right';
  private current: Animation | null = null;
  private timer = 0;

  constructor(atlas: Atlas) {
    for (const type of ['Idle', 'Walk', 'Death', 'Attack', 'Hit', 'Hidden', 'Wake'] as AnimationType[]) {
      const dirs = new Map<Direction, Animation>();
      for (const dir of DIRECTIONS) {
        const frames = atlas.frames(dir === 'None' ? type : type + dir);
        if (frames.length) {
          const seconds = type === 'Death' ? Math.min(FRAME_SECONDS, MOST_DEATH_SECONDS / frames.length) : FRAME_SECONDS;
          dirs.set(dir, { frames, flip: false, seconds });
        }
      }
      // Ported from CharacterSprite.load
      const flipped = (a: Animation): Animation => ({ ...a, flip: !a.flip });
      if (!dirs.has('None') && dirs.has('Right')) dirs.set('None', dirs.get('Right')!);
      if (!dirs.has('Right') && dirs.has('None')) dirs.set('Right', dirs.get('None')!);
      if (!dirs.has('Left') && dirs.has('Right')) dirs.set('Left', flipped(dirs.get('Right')!));
      if (dirs.has('Left') && !dirs.has('Right')) dirs.set('Right', flipped(dirs.get('Left')!));
      if (!dirs.has('LeftUp') && dirs.has('Left')) dirs.set('LeftUp', dirs.get('Left')!);
      if (!dirs.has('LeftDown') && dirs.has('Left')) dirs.set('LeftDown', dirs.get('Left')!);
      if (!dirs.has('RightDown') && dirs.has('Right')) dirs.set('RightDown', dirs.get('Right')!);
      if (!dirs.has('RightUp') && dirs.has('Right')) dirs.set('RightUp', dirs.get('Right')!);
      if (!dirs.has('Up') && dirs.has('Right')) dirs.set('Up', dirs.get('Right')!);
      if (!dirs.has('Down') && dirs.has('Left')) dirs.set('Down', dirs.get('Left')!);
      this.animations.set(type, dirs);
    }
    this.set('Idle');
  }

  /** Switches to an animation. One the atlas does not have leaves the current one playing. Attack, Death and Hit start over each time. */
  set(type: AnimationType): void {
    const animation = this.animations.get(type)?.get(this.direction);
    if (!animation) return;
    const oneShot = ONE_SHOT.includes(type);
    if (this.type !== type || this.current !== animation || oneShot) {
      this.type = type;
      this.current = animation;
      if (oneShot) this.timer = 0;
    }
  }

  face(direction: Direction): void {
    if (this.direction === direction) return;
    this.direction = direction;
    const animation = this.animations.get(this.type)?.get(direction);
    if (animation) this.current = animation;
  }

  get animation(): AnimationType {
    return this.type;
  }

  tick(seconds: number): void {
    this.timer += seconds;
  }

  /** How long the current animation runs once through. */
  duration(): number {
    return this.current ? this.current.frames.length * this.current.seconds : 0;
  }

  frame(): Frame | null {
    const a = this.current;
    if (!a) return null;
    const step = Math.floor(this.timer / a.seconds);
    const index = ONE_SHOT.includes(this.type) ? Math.min(a.frames.length - 1, step) : step % a.frames.length;
    const f = a.frames[index];
    return { x: f.x, y: f.y, w: f.w, h: f.h, flip: a.flip };
  }
}
