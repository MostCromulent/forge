// A player's portrait breaking apart when they lose. At the end of the game it lifts to the middle of the board,
// strains, cracks and bursts; a player knocked out while the game goes on breaks where they sit. The pieces are lit
// 3D slabs, so this draws with WebGL, and three.js is fetched the first time a portrait breaks rather than with
// the page.

type Three = typeof import('three');

export interface ShatterOptions {
  /** The seat avatar the portrait leaves from. */
  from: DOMRect;
  image: string;
  /** The end of the game: the portrait breaks in the middle of the board, and the title follows. */
  final: boolean;
  /** Where the portrait breaks when final, in viewport pixels. */
  centre: { x: number; y: number };
  /** Called at the moment the title should appear; only when final. */
  onTitle?: () => void;
}

interface Timing {
  fly: number; crack: number; brk: number; burst: number; title: number; end: number;
}

// Tuned on a 1440 by 900 board; a seat's portrait runs the same beats, shorter and without the slow motion
const FINAL: Timing = { fly: 0.9, crack: 1.3, brk: 2.0, burst: 2.12, title: 3.05, end: 5.6 };
const SEAT: Timing = { fly: 0.35, crack: 0.45, brk: 0.95, burst: 1.02, title: Infinity, end: 3.4 };
const GRAVITY = 1500;

let three: Promise<{ THREE: Three; Room: typeof import('three/examples/jsm/environments/RoomEnvironment.js').RoomEnvironment }> | null = null;

function loadThree() {
  three ??= Promise.all([import('three'), import('three/examples/jsm/environments/RoomEnvironment.js')])
    .then(([THREE, room]) => ({ THREE, Room: room.RoomEnvironment }));
  return three;
}

/** Whether a portrait may break here at all; without it, the seat just empties and the title shows at once. */
export function canShatter(): boolean {
  if (window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) return false;
  try {
    return !!document.createElement('canvas').getContext('webgl2');
  } catch {
    return false;
  }
}

const ease = (x: number) => 1 - Math.pow(1 - Math.min(1, Math.max(0, x)), 3);

function mulberry(seed: number) {
  return () => {
    seed |= 0;
    seed = seed + 0x6D2B79F5 | 0;
    let t = Math.imul(seed ^ seed >>> 15, 1 | seed);
    t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t;
    return ((t ^ t >>> 14) >>> 0) / 4294967296;
  };
}

type Point = [number, number];

/** Voronoi cells inside a disc of radius r, seeded densely around the point where it gives way. */
function fracture(rng: () => number, r: number, impact: Point): Point[][] {
  const seeds: Point[] = [];
  for (let i = 0; i < 38; i++) {
    const d = r * 1.05 * Math.pow(rng(), 1.55), a = rng() * Math.PI * 2;
    seeds.push([impact[0] + d * Math.cos(a), impact[1] + d * Math.sin(a)]);
  }
  for (let k = 0; k < 9; k++) {
    const a = (k / 9) * Math.PI * 2 + rng() * 0.4;
    seeds.push([0.82 * r * Math.cos(a), 0.82 * r * Math.sin(a)]);
  }
  const circle: Point[] = [];
  for (let i = 0; i < 64; i++) circle.push([r * Math.cos((i / 64) * Math.PI * 2), r * Math.sin((i / 64) * Math.PI * 2)]);
  const clip = (poly: Point[], n: Point, c: number): Point[] => {
    const out: Point[] = [];
    for (let i = 0; i < poly.length; i++) {
      const a = poly[i], b = poly[(i + 1) % poly.length];
      const da = a[0] * n[0] + a[1] * n[1] - c, db = b[0] * n[0] + b[1] * n[1] - c;
      if (da <= 0) out.push(a);
      if ((da <= 0) !== (db <= 0)) {
        const t = da / (da - db);
        out.push([a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t]);
      }
    }
    return out;
  };
  const cells: Point[][] = [];
  for (const s of seeds) {
    let poly = circle;
    for (const o of seeds) {
      if (o === s) continue;
      const n: Point = [o[0] - s[0], o[1] - s[1]];
      poly = clip(poly, n, n[0] * (s[0] + o[0]) / 2 + n[1] * (s[1] + o[1]) / 2);
      if (poly.length < 3) break;
    }
    if (poly.length < 3) continue;
    let area = 0;
    for (let i = 0; i < poly.length; i++) {
      const a = poly[i], b = poly[(i + 1) % poly.length];
      area += a[0] * b[1] - b[0] * a[1];
    }
    if (Math.abs(area) / 2 > (r / 130) ** 2 * 6) cells.push(poly);
  }
  return cells;
}

/** The portrait in a steel frame with a brass inner rim, drawn once and cut up with the pieces. */
function medalCanvas(img: HTMLImageElement): HTMLCanvasElement {
  const S = 1024, r = S / 2, c = document.createElement('canvas');
  c.width = c.height = S;
  const g = c.getContext('2d')!;
  const steel = g.createRadialGradient(r, r * 0.9, r * 0.78, r, r, r);
  steel.addColorStop(0, '#666d79');
  steel.addColorStop(0.3, '#343a43');
  steel.addColorStop(0.8, '#1b1f25');
  steel.addColorStop(1, '#0b0d11');
  g.fillStyle = steel;
  g.beginPath(); g.arc(r, r, r, 0, Math.PI * 2); g.fill();
  g.lineWidth = S * 0.011;
  g.strokeStyle = '#c9a14a';
  g.beginPath(); g.arc(r, r, r * 0.83, 0, Math.PI * 2); g.stroke();
  g.save();
  g.beginPath(); g.arc(r, r, r * 0.817, 0, Math.PI * 2); g.clip();
  g.imageSmoothingQuality = 'high';
  g.drawImage(img, r - r * 0.817, r - r * 0.817, r * 1.634, r * 1.634);
  const v = g.createRadialGradient(r, r, r * 0.45, r, r, r * 0.817);
  v.addColorStop(0, 'rgba(0,0,0,0)');
  v.addColorStop(1, 'rgba(0,0,0,.42)');
  g.fillStyle = v;
  g.fillRect(0, 0, S, S);
  g.restore();
  return c;
}

function loadImage(src: string): Promise<HTMLImageElement> {
  return new Promise((ok, fail) => {
    const img = new Image();
    img.onload = () => ok(img);
    img.onerror = fail;
    img.src = src;
  });
}

/** Breaks one portrait. Resolves once it is over; rejects if WebGL or the portrait cannot be had, before anything shows. */
export async function shatter(o: ShatterOptions): Promise<void> {
  const [{ THREE, Room }, img] = await Promise.all([loadThree(), loadImage(o.image)]);
  const T = o.final ? FINAL : SEAT;
  const W = window.innerWidth, H = window.innerHeight;
  const seatR = o.from.width / 2;
  // The breaking portrait is sized to the board; a seat's lifts only a little above its own size
  const R = o.final ? Math.min(140, Math.max(95, Math.min(W, H) * 0.145)) : seatR * 1.35;
  const k = R / 130;

  const layer = document.createElement('div');
  layer.className = 'shatter-layer';
  const canvas = document.createElement('canvas');
  layer.append(canvas);
  const glow = (name: string) => {
    const el = document.createElement('div');
    el.className = 'shatter-glow ' + name;
    layer.append(el);
    return el;
  };
  const gBloom = glow('bloom'), gRing = glow('ring'), gCore = glow('core'), gStreak = glow('streak');
  // Throws here when the browser has no WebGL, before anything is on the page
  const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: true });
  document.body.append(layer);
  renderer.setPixelRatio(Math.min(2, window.devicePixelRatio || 1));
  renderer.setSize(W, H);
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  renderer.toneMapping = THREE.ACESFilmicToneMapping;
  renderer.toneMappingExposure = 1.05;
  const scene = new THREE.Scene();
  const pmrem = new THREE.PMREMGenerator(renderer);
  const env = pmrem.fromScene(new Room(), 0.04).texture;
  scene.environment = env;
  scene.environmentIntensity = 0.5;
  const camera = new THREE.PerspectiveCamera(30, W / H, 20, 8000);
  const dist = (H / 2) / Math.tan(THREE.MathUtils.degToRad(15));
  camera.position.set(0, 0, dist);
  const key = new THREE.DirectionalLight(0xfff0dc, 1.7);
  key.position.set(-600, 800, 1000);
  const fill = new THREE.DirectionalLight(0x9db6ff, 0.55);
  fill.position.set(700, -300, 500);
  const flare = new THREE.PointLight(0xe4ecff, 0, 1600 * k, 1.3);
  scene.add(key, fill, flare, new THREE.AmbientLight(0xffffff, 0.12));

  const world = (x: number, y: number) => new THREE.Vector3(x - W / 2, H / 2 - y, 0);
  const tex = new THREE.CanvasTexture(medalCanvas(img));
  tex.colorSpace = THREE.SRGBColorSpace;
  tex.anisotropy = renderer.capabilities.getMaxAnisotropy();
  tex.repeat.set(1 / (2 * R), 1 / (2 * R));
  tex.offset.set(0.5, 0.5);
  const faceMat = new THREE.MeshStandardMaterial({ map: tex, roughness: 0.4, metalness: 0.06 });
  const edgeMat = new THREE.MeshStandardMaterial({ color: 0x3b424d, roughness: 0.3, metalness: 0.75 });
  const debrisMat = new THREE.MeshStandardMaterial({ color: 0xaeb8c5, roughness: 0.25, metalness: 0.6 });
  const depth = 12 * k;
  // No inset on the bevel: side by side the pieces meet without a seam until one slips
  const extrude = { depth, bevelEnabled: true, bevelThickness: 1.4 * k, bevelSize: 1.1 * k, bevelOffset: 0, bevelSegments: 1, curveSegments: 1 };

  const rng = mulberry((Math.random() * 1e9) | 0);
  const medal = new THREE.Group();
  const disc = new THREE.Shape();
  disc.absarc(0, 0, R, 0, Math.PI * 2, false);
  const whole = new THREE.Mesh(new THREE.ExtrudeGeometry(disc, { ...extrude, curveSegments: 64 }), [faceMat, edgeMat]);
  // Just behind the pieces, so it shows wherever a piece has not cracked away yet
  whole.geometry.translate(0, 0, -depth / 2 - 0.4);
  medal.add(whole);
  const impact: Point = [(-14 + rng() * 10) * k, (12 + rng() * 10) * k];
  const pieces = fracture(rng, R, impact).map(poly => {
    const cx = poly.reduce((s, p) => s + p[0], 0) / poly.length;
    const cy = poly.reduce((s, p) => s + p[1], 0) / poly.length;
    const geo = new THREE.ExtrudeGeometry(new THREE.Shape(poly.map(p => new THREE.Vector2(p[0], p[1]))), extrude);
    geo.translate(-cx, -cy, -depth / 2);
    const mesh = new THREE.Mesh(geo, [faceMat, edgeMat]);
    mesh.position.set(cx, cy, 0);
    mesh.visible = false;
    medal.add(mesh);
    const dx = cx - impact[0], dy = cy - impact[1];
    const d = Math.hypot(dx, dy) || 1;
    const near = Math.max(0, 1 - d / (R * 1.1));
    const dir = new THREE.Vector3(dx / d, dy / d, 0);
    const spd = (390 + near * 620 + rng() * 280) * (o.final ? 1 : 0.55);
    const vz = (near > 0.5 || rng() > 0.8 ? 490 + rng() * 780 : rng() * 380 - 150) * (o.final ? 1 : 0.5);
    return {
      mesh, base: new THREE.Vector3(cx, cy, 0), dir,
      reach: d / R, hair: (0.5 + rng()) * k, jit: rng(), sep: (1.2 + rng() * 2.4) * k, zj: (rng() - 0.5) * 5 * k, rj: (rng() - 0.5) * 0.02,
      vel: new THREE.Vector3(dir.x * spd, dir.y * spd * 0.8 + 160 * (o.final ? 1 : 0.6), vz),
      spin: new THREE.Vector3(rng() - 0.5, rng() - 0.5, (rng() - 0.5) * 0.6).normalize().multiplyScalar(2.1 + near * 6.6 + rng() * 2.9),
      delay: (d / R) * 0.05, live: false,
    };
  });
  const debris = Array.from({ length: o.final ? 70 : 24 }, () => {
    const s = (2.5 + rng() * 6) * Math.max(0.5, k);
    const tri = new THREE.Shape([new THREE.Vector2(0, s), new THREE.Vector2(s * 0.9, -s * 0.5), new THREE.Vector2(-s * 0.7, -s * 0.6)]);
    const mesh = new THREE.Mesh(new THREE.ExtrudeGeometry(tri, { depth: 1.5, bevelEnabled: false }), debrisMat);
    mesh.visible = false;
    scene.add(mesh);
    const a = rng() * Math.PI * 2, spd = (460 + rng() * 980) * (o.final ? 1 : 0.5);
    return { mesh, vel: new THREE.Vector3(Math.cos(a) * spd, Math.sin(a) * spd * 0.8 + 140, rng() * 900 - 200), spin: new THREE.Vector3(rng() * 20, rng() * 20, rng() * 20) };
  });
  scene.add(medal);

  const from = world(o.from.left + seatR, o.from.top + seatR);
  const to = o.final ? world(o.centre.x, o.centre.y) : from.clone();
  const startScale = seatR / R;
  const lift = o.final ? 70 : 30;
  medal.position.copy(from);
  medal.scale.setScalar(startScale);

  const place = (el: HTMLElement, x: number, y: number, sx: number, sy: number, opacity: number) => {
    el.style.transform = 'translate(' + (x - el.offsetWidth / 2) + 'px,' + (y - el.offsetHeight / 2) + 'px) scale(' + sx + ',' + sy + ')';
    el.style.opacity = Math.max(0, opacity).toFixed(3);
  };

  let t = 0;
  let titled = false;
  let last = performance.now();
  await new Promise<void>(done => {
    const frame = (now: number) => {
      const real = Math.min(0.05, (now - last) / 1000);
      last = now;
      const before = t;
      // Right after the burst the shot runs slower for a moment, then ramps back up
      let scale = 1;
      if (o.final && t >= T.burst) {
        const s = t - T.burst;
        scale = s < 0.05 ? 0.3 : s < 0.22 ? 0.3 + 0.7 * ease((s - 0.05) / 0.17) : 1;
      }
      const dt = real * scale;
      t += dt;
      step(before, dt);
      renderer.render(scene, camera);
      if (!titled && t >= T.title) {
        titled = true;
        o.onTitle?.();
      }
      if (t >= T.end) {
        done();
      } else {
        requestAnimationFrame(frame);
      }
    };
    requestAnimationFrame(frame);
  });

  function step(before: number, dt: number) {
    if (t < T.brk) {
      const m = ease(t / T.fly);
      medal.position.lerpVectors(from, to, m);
      medal.position.z = lift * m + (t > T.fly ? lift * 0.4 * ease((t - T.fly) / (T.brk - T.fly)) : 0);
      medal.scale.setScalar(startScale + (1 - startScale) * m);
      medal.rotation.x = o.final ? -0.35 * (1 - m) : 0;
      medal.rotation.y = o.final ? 0.28 * (1 - m) : 0;
      if (t > T.fly) {
        // A strain that builds: stronger and faster the closer it gets to giving way, with a slight swell
        const p = (t - T.fly) / (T.brk - T.fly);
        const amp = (0.6 + 8.5 * p * p) * k;
        const f = 20 + 16 * p;
        medal.position.x += amp * (Math.sin(t * f) * 0.65 + Math.sin(t * f * 1.73 + 1.1) * 0.35);
        medal.position.y += amp * (Math.sin(t * f * 1.21 + 0.7) * 0.65 + Math.sin(t * f * 1.93 + 2.3) * 0.35);
        medal.rotation.z = 0.035 * p * p * Math.sin(t * f * 0.9 + 0.4);
        medal.scale.multiplyScalar(1 + 0.025 * p * p);
      }
    }
    if (t >= T.crack && t < T.brk) {
      // Hairline cracks creep out from the weak point while it strains, slowly and then faster
      const c = Math.pow((t - T.crack) / (T.brk - T.crack), 1.6);
      for (const q of pieces) {
        const p = ease((c * 1.2 - q.reach * 0.95 - q.jit * 0.12) / 0.14);
        q.mesh.visible = p > 0.01;
        q.mesh.position.copy(q.base).addScaledVector(q.dir, q.hair * p);
        q.mesh.rotation.set(q.rj * 0.3 * p, -q.rj * 0.3 * p, q.rj * 0.6 * p);
      }
    }
    if (before < T.brk && t >= T.brk) {
      whole.visible = false;
      for (const q of pieces) q.mesh.visible = true;
      medal.position.set(to.x, to.y, lift * 1.4);
      medal.rotation.set(0, 0, 0);
      flare.position.set(to.x + impact[0], to.y + impact[1], 140 * k);
    }
    if (t >= T.brk && t < T.burst) {
      const p = ease((t - T.brk) / 0.07);
      for (const q of pieces) {
        q.mesh.position.copy(q.base).addScaledVector(q.dir, q.hair + q.sep * p);
        q.mesh.position.z = q.zj * p;
        q.mesh.rotation.set(q.rj * (0.3 + 0.7 * p), -q.rj * (0.3 + 0.7 * p), q.rj * (0.6 + 1.4 * p));
      }
      flare.intensity = t - T.brk < 0.07 ? 6 : 0;
    }
    if (before < T.burst && t >= T.burst) {
      for (const d of debris) {
        d.mesh.visible = true;
        d.mesh.position.set(to.x + impact[0] + (rng() - 0.5) * 30 * k, to.y + impact[1] + (rng() - 0.5) * 30 * k, 110 * k);
      }
    }
    if (t >= T.burst) {
      const s = t - T.burst;
      flare.intensity = 120 * Math.exp(-s * 9);
      for (const q of pieces) {
        if (!q.live && s >= q.delay) q.live = true;
        if (!q.live || !q.mesh.visible) continue;
        q.vel.y -= GRAVITY * dt;
        q.vel.multiplyScalar(1 - 0.15 * dt);
        q.mesh.position.addScaledVector(q.vel, dt);
        q.mesh.rotation.x += q.spin.x * dt;
        q.mesh.rotation.y += q.spin.y * dt;
        q.mesh.rotation.z += q.spin.z * dt;
        if (q.mesh.position.y + to.y < -H) q.mesh.visible = false;
      }
      for (const d of debris) {
        if (!d.mesh.visible) continue;
        d.vel.y -= GRAVITY * dt;
        d.mesh.position.addScaledVector(d.vel, dt);
        d.mesh.rotation.x += d.spin.x * dt;
        d.mesh.rotation.y += d.spin.y * dt;
        if (d.mesh.position.y < -H) d.mesh.visible = false;
      }
      // The light of the burst, added to what is behind it rather than laid over it
      const x = to.x + impact[0] + W / 2, y = H / 2 - (to.y + impact[1]);
      const rise = Math.min(1, s / 0.025);
      const core = (0.25 + 1.15 * ease(s / 0.3)) * k;
      place(gCore, x, y, core, core, rise * Math.exp(-Math.max(0, s - 0.025) * 10));
      const bloom = (0.5 + 0.8 * ease(s / 0.6)) * k;
      place(gBloom, x, y, bloom, bloom, rise * 0.9 * Math.exp(-s * 4));
      const ringK = Math.min(1, s / 0.55);
      const ring = (0.2 + 3.2 * ease(ringK)) * k;
      place(gRing, x, y, ring, ring, rise * 0.7 * Math.pow(1 - ringK, 2.2));
      place(gStreak, x, y, (0.3 + 0.8 * ease(s / 0.3)) * k, 1, rise * 0.4 * Math.exp(-s * 10));
    }
    // The camera takes the hit by moving, never by rolling
    let amp = 0;
    if (t >= T.brk && t < T.burst) amp = 3 * k * Math.exp(-(t - T.brk) * 16);
    if (t >= T.burst) amp = (o.final ? 13 : 4) * Math.exp(-(t - T.burst) * 6.5);
    camera.position.set((rng() - 0.5) * amp, (rng() - 0.5) * amp, dist);
  }

  scene.traverse(obj => {
    if (obj instanceof THREE.Mesh) obj.geometry.dispose();
  });
  [faceMat, edgeMat, debrisMat].forEach(m => m.dispose());
  tex.dispose();
  env.dispose();
  pmrem.dispose();
  renderer.dispose();
  layer.remove();
}
