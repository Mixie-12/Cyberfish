import * as THREE from 'three';
import { RectAreaLightUniformsLib } from 'three/addons/lights/RectAreaLightUniformsLib.js';
import { GLTFLoader, type GLTF } from 'three/addons/loaders/GLTFLoader.js';
import { Post } from './post';
import { Fish, type ProgressCallback } from './fish';
import { Sky } from './sky';
import { Ocean, WAVE_CREST } from './ocean';
import { Deep } from './deep';
import { Floor } from './floor';
import { loadLogoPoints } from './logo';
import { FishLane, FishPerch, type FishRoute } from './lane';
import { Water } from './water';
import { COLORS, FISH_KEY_DIR, OCEAN, WATER_FOG_FAR, WATER_FOG_NEAR } from './palette';

/**
 * Framing: a long lens low over the water, pitched up a touch so the horizon
 * haze sits ~65% down the frame and the fish hangs in the sky above it.
 * Water rests at y = 0. The camera rides the swell: it stays CAMERA_CLEARANCE
 * above the local surface (smoothed), so it can sit low without the crests
 * reaching the lens, and the foreground swells fill the lower frame.
 */
const CAMERA_POS = new THREE.Vector3(0, 0, 13);
const CAMERA_CLEARANCE = 2.8;
const CAMERA_PITCH_UP = THREE.MathUtils.degToRad(4);
/** Lens: 28 degrees vertical in landscape, wider on portrait screens. */
const FOV_LANDSCAPE = 28;
const FOV_PORTRAIT = 48;
/** The fish hovers this far above the camera, so it holds its place in frame. */
const FISH_ABOVE_CAMERA = 1.2;
/**
 * During the dive, the fish's rectangular pool light hands over to a soft
 * round pool in the water over this part of its run (0..1): done before the
 * rectangle can show. The pool's peak emissive strength and radius (world
 * units, Gaussian sigma).
 */
const FISH_POOL_OUT = [0.02, 0.3] as const;
const FISH_POOL_GLOW = 1.7;
const FISH_POOL_RADIUS = 3.2;
/**
 * Where the fish hovers (y is set per frame from the camera height). Offset
 * to screen left in landscape, nose pointing right, leaving the right half
 * for the tagline.
 */
const FOCUS = new THREE.Vector3(-1.9, WAVE_CREST + CAMERA_CLEARANCE + FISH_ABOVE_CAMERA, -2);

export interface SceneOptions {
  reducedMotion: boolean;
  mobile: boolean;
}

/** Mouse parallax: camera offset in world units and extra rotation in radians. */
const PARALLAX_SHIFT = new THREE.Vector2(0.5, 0.25);
const PARALLAX_TILT = 0.025;

/**
 * The dive ("Deploy The Decoy?"): the sea churns, the fish dips its nose and
 * shoots into the water ahead, and the camera, still looking out to sea,
 * sinks slowly straight down through the surface until the frame goes to
 * black. Times in seconds from the click.
 */
/** Hard stop; normally the fade completes first (its timing follows the swell). */
const DIVE_DURATION = 6;
/** Churn ramps up over this window. */
const DIVE_CHURN = [0.1, 1.6] as const;
/** Where the fish goes in, relative to the camera's rest position: a little
 * to its own (left) side, far enough out to land in the lower frame of the
 * long, slightly up-tilted hero lens. */
const DIVE_HOLE = new THREE.Vector3(-1.6, 0, 26);
/** The fish's run from hover to the hole (ease-in). */
const DIVE_FISH = [0.3, 1.6] as const;
/** The camera sinks (smooth, slow) over this window, to this far below the
 * rest plane. */
const DIVE_SINK = [1.2, 4.6] as const;
const DIVE_DEPTH = 4;
/** Fade to black after the lens goes under. */
const DIVE_DARK_AFTER = 0.2;
const DIVE_DARK_LENGTH = 0.8;
/** Reduced motion: no camera move, just the fade. */
const DIVE_DURATION_STILL = 1.4;

/**
 * The deep (after the dive): the frame is black when the hand-off happens;
 * the new chapter then surfaces out of that black over DEEP_FADE_IN, with
 * the last of the lens wobble settling. The camera stays where the dive
 * left it; the parallax is bigger down here so the shaft layers show depth.
 */
const DEEP_BG = '#02050c';
const DEEP_FADE_IN = 3.2;
/** The DeKoi unit shown in the deep; fetched once the dive begins. */
const DEVICE_URL = '/models/dekoi.glb';
/** The seabed for the floor chapter (assets-src/seafloor). */
const SEAFLOOR_URL = '/models/seafloor.glb';
const DEEP_PARALLAX_SHIFT = new THREE.Vector2(1.4, 0.8);
const DEEP_PARALLAX_TILT = 0.05;
/** Where the lens aims in the deep, relative to its rest point. */
const DEEP_LOOK = new THREE.Vector3(0, 0, -10);
/**
 * Chapters below the surface, top to bottom, each a level further down.
 * Between the open-water chapters the lens glides down through the water;
 * the floor is reached through black (it has its own light and ground).
 */
export type Chapter = 'deep' | 'safety' | 'market' | 'business' | 'gtm' | 'team' | 'floor';
const CHAPTERS: Chapter[] = ['deep', 'safety', 'market', 'business', 'gtm', 'team', 'floor'];
/**
 * The chapters with the hero fish behind the copy, and how it is there:
 * swimming across (lane) or holding station in the empty top right (perch).
 */
const FISH_ROUTES: Partial<Record<Chapter, () => FishRoute>> = {
  safety: () => new FishLane(),
  market: () => new FishLane(),
  gtm: () => new FishPerch(),
  team: () => new FishLane(),
};
const LEVEL_DROP = 16;
const GLIDE_TIME = 2.0;
/** To and from the floor: a quick dip through black, and the floor comes up out of it. */
const CHAPTER_FADE_OUT = 0.7;
const CHAPTER_FADE_IN = 1.6;
/** Particles in the floor's mark, and how wide it hangs (world units). */
const MARK_POINTS = 21000;
const MARK_POINTS_MOBILE = 8000;
const MARK_WIDTH = 5.2;
/** Back to the top: the deep goes dark, then the sea returns out of black. */
const SURFACE_FADE_OUT = 1.2;
const SURFACE_FADE_IN = 2.6;

const easeInQuad = (t: number): number => t * t;
const window01 = (t: number, [a, b]: readonly [number, number]): number =>
  THREE.MathUtils.clamp((t - a) / (b - a), 0, 1);

interface DiveState {
  /** Seconds since the click. */
  t: number;
  /** Camera position when the dive began. */
  camStart: THREE.Vector3;
  /** Fish position when the dive began, and its path's control point. */
  fishStart: THREE.Vector3;
  fishCtrl: THREE.Vector3;
  /** World point where the fish goes in. */
  hole: THREE.Vector3;
  /** When the fish / the lens went under, or -1. */
  fishSplashAt: number;
  lensSplashAt: number;
  /** Fired once, the moment the fish hits the water. */
  onSplash: (() => void) | null;
  resolve: () => void;
}

export interface DiveOptions {
  /** Called when the fish hits the water (at once under reduced motion). */
  onSplash?: () => void;
}

/** Where the DeKoi unit's board is on screen this frame (CSS px), for the callout. */
export interface DeviceAnchor {
  x: number;
  y: number;
  /** The lid has finished opening. */
  open: boolean;
}

function quadBezier(
  a: THREE.Vector3,
  c: THREE.Vector3,
  b: THREE.Vector3,
  t: number,
  out: THREE.Vector3,
): THREE.Vector3 {
  const u = 1 - t;
  return out
    .copy(a)
    .multiplyScalar(u * u)
    .addScaledVector(c, 2 * u * t)
    .addScaledVector(b, t * t);
}

function quadBezierTangent(
  a: THREE.Vector3,
  c: THREE.Vector3,
  b: THREE.Vector3,
  t: number,
  out: THREE.Vector3,
): THREE.Vector3 {
  // d/dt = 2(1-t)(c-a) + 2t(b-c)
  return out
    .subVectors(c, a)
    .multiplyScalar(2 * (1 - t))
    .addScaledVector(_tan.subVectors(b, c), 2 * t);
}

const _tan = new THREE.Vector3();
const _v1 = new THREE.Vector3();
const _v2 = new THREE.Vector3();

/**
 * Owns the renderer, camera, sky, water, lights, post chain and the render loop.
 */
export class CyberScene {
  readonly renderer: THREE.WebGLRenderer;
  readonly scene = new THREE.Scene();
  readonly camera: THREE.PerspectiveCamera;
  readonly post: Post;
  readonly sky: Sky;
  readonly ocean: Ocean;
  fish: Fish | null = null;

  private readonly container: HTMLElement;
  private readonly opts: SceneOptions;
  private readonly clock = new THREE.Clock(false);
  private time = 0;
  private running = false;
  private raf = 0;
  /** Camera aim: straight ahead, pitched up by CAMERA_PITCH_UP. Re-based per frame. */
  private readonly lookTarget = new THREE.Vector3();
  /** Smoothed camera height above the rest plane. */
  private cameraY = WAVE_CREST + CAMERA_CLEARANCE;
  /** Pointer position in -1..1, and its damped copy used by the camera. */
  private readonly pointer = new THREE.Vector2();
  private readonly parallax = new THREE.Vector2();
  private resizeObserver: ResizeObserver;
  private readonly focus = FOCUS.clone();
  private diving: DiveState | null = null;
  /** Which chapter below the surface is current (meaningful once dived). */
  chapter: Chapter = 'deep';
  /** The water shared by every chapter below, and the chapters' own content. */
  private water: Water | null = null;
  deep: Deep | null = null;
  /** The hero fish's route, in the chapters that have it (FISH_ROUTES), and which one. */
  lane: FishRoute | null = null;
  private laneChapter: Chapter | null = null;
  floor: Floor | null = null;
  /** Seconds since the current chapter's hand-off, and the lens's rest point below. */
  private deepT = 0;
  private readonly deepBase = new THREE.Vector3();
  /** A chapter change in progress. */
  private transition: {
    kind: 'surface' | 'fade' | 'glide';
    target: Chapter;
    t: number;
    resolve: () => void;
  } | null = null;
  /** A chapter surfacing out of black (>= 0 while running), and how long it takes. */
  private fadeT = -1;
  private fadeIn = DEEP_FADE_IN;
  /** The sea's fade back in after surfacing. */
  private risingT = -1;
  /** The mark as points, for the floor; loaded on first use and kept. */
  private markCloud: Float32Array | null = null;
  private markLoading: Promise<Float32Array> | null = null;
  /** The seabed mesh for the floor; loaded on first use and kept. */
  private seafloor: THREE.Object3D | null = null;
  private seafloorLoading: Promise<THREE.Object3D | null> | null = null;
  /** True while a mouse has moved over the page (parallax and the floor's stir). */
  private pointerLive = false;
  /** The unit's GLB, loaded once and reused across visits to the deep. */
  private device: Promise<GLTF | null> | null = null;
  /** The fish's blue lights and their rest intensities (dimmed during the dive). */
  private fishLights: Array<{ light: THREE.Light; intensity: number }> = [];
  /** The sea's key and fill; off in the deep so its black plastic stays black. */
  private heroLights: THREE.Light[] = [];
  /** Dev: slow the dive down (1 = real time) to inspect the transition. */
  diveTimeScale = 1;
  /**
   * Every frame in the deep: where the unit's board is on screen, or null
   * while there is nothing to point at (and once more when the deep goes).
   */
  onDeviceAnchor: ((anchor: DeviceAnchor | null) => void) | null = null;
  private readonly anchorWorld = new THREE.Vector3();
  private anchorShown = false;
  /** The mouse moves onto (true) or off (false) the unit in the deep. */
  onDeviceHover: ((over: boolean) => void) | null = null;
  private readonly raycaster = new THREE.Raycaster();
  private deviceHovered = false;
  /** The cursor starts (true) or stops (false) disturbing the floor's particle mark. */
  onMarkStir: ((stirring: boolean) => void) | null = null;
  private markStirred = false;

  constructor(container: HTMLElement, opts: SceneOptions) {
    this.container = container;
    this.opts = opts;

    this.renderer = new THREE.WebGLRenderer({
      antialias: true,
      powerPreference: 'high-performance',
    });
    this.renderer.setPixelRatio(Math.min(window.devicePixelRatio, 1.5));
    this.renderer.toneMapping = THREE.ACESFilmicToneMapping;
    this.renderer.toneMappingExposure = 1.0;
    this.renderer.outputColorSpace = THREE.SRGBColorSpace;
    this.renderer.setClearColor(COLORS.bg, 1);
    container.appendChild(this.renderer.domElement);
    // LTC lookup tables for RectAreaLight (the fish's pool of light on the water).
    RectAreaLightUniformsLib.init();

    this.camera = new THREE.PerspectiveCamera(FOV_LANDSCAPE, 1, 0.1, 1200);
    this.camera.position.set(CAMERA_POS.x, this.cameraY, CAMERA_POS.z);
    this.aimCamera();

    // Sky dome, and its prefiltered copy as the environment for reflections:
    // the water mirrors the sky, the fish shell picks up a violet sheen.
    this.sky = new Sky();
    this.sky.follow(this.camera);
    this.scene.add(this.sky.mesh);
    const skyEnv = this.sky.buildEnvironment(this.renderer);
    this.scene.environment = skyEnv;
    this.scene.environmentIntensity = 0.6;

    // Haze: linear fog toward the horizon colour. Only the water uses it (the
    // sky and fish materials have fog off), matching the Blender setup.
    this.scene.fog = new THREE.Fog(OCEAN.waterFog, WATER_FOG_NEAR, WATER_FOG_FAR);

    this.ocean = new Ocean({
      mobile: opts.mobile,
      reducedMotion: opts.reducedMotion,
      envMap: skyEnv,
    });
    this.scene.add(this.ocean.mesh);

    // Lighting: a cool-white key from the camera side, a big soft sky fill,
    // and the fish itself as the only coloured light (added with the fish).
    const key = new THREE.DirectionalLight(OCEAN.key, 1.5);
    key.position.copy(FISH_KEY_DIR).multiplyScalar(30).add(this.focus);
    key.target.position.copy(this.focus);
    this.scene.add(key, key.target);
    const fill = new THREE.HemisphereLight(OCEAN.fill, OCEAN.waterBase, 0.55);
    this.scene.add(fill);
    this.heroLights = [key, fill];

    this.resize();
    this.post = new Post(this.renderer, this.scene, this.camera, {
      mobile: opts.mobile,
      reducedMotion: opts.reducedMotion,
    });
    window.addEventListener('resize', this.resize);
    this.resizeObserver = new ResizeObserver(this.resize);
    this.resizeObserver.observe(container);
    document.addEventListener('visibilitychange', this.onVisibility);
    // Mouse parallax only: touch devices and reduced motion keep a still camera.
    if (!opts.mobile && !opts.reducedMotion) {
      window.addEventListener('pointermove', this.onPointerMove, { passive: true });
      window.addEventListener('pointerleave', this.onPointerLeave);
    }
  }

  /** Loads the hero fish and adds it to the scene. Progress is 0..1 of the download. */
  async loadFish(onProgress?: ProgressCallback): Promise<void> {
    const fish = await Fish.load(
      this.focus,
      {
        reducedMotion: this.opts.reducedMotion,
        anisotropy: this.renderer.capabilities.getMaxAnisotropy(),
      },
      onProgress,
    );
    this.fish = fish;
    this.scene.add(fish.root);

    // The fish is the scene's only neon: two blue point lights ride with it,
    // one at the belly and one lower down toward the water (ocean_look.json).
    // A small point light at the belly lights the fish itself from below; a
    // wide area light under it throws a broad, diffuse glow onto the water
    // (a point source there would read as one hard hot spot).
    // The belly light sits well below the fish and is cut off before the
    // water: far enough that it washes the underside evenly instead of
    // glaring off the metal in one spot, and never adding to the pool.
    const glow = new THREE.PointLight(OCEAN.fishGlow, 0.9, 3.2, 2);
    glow.position.set(0, -1.9, 0);
    const pool = new THREE.RectAreaLight(OCEAN.fishGlow, 5, 10, 6);
    pool.position.set(0, -1.6, 0);
    pool.rotation.x = -Math.PI / 2; // emit along -Y
    fish.root.add(glow, pool);
    this.fishLights = [
      { light: glow, intensity: glow.intensity },
      { light: pool, intensity: pool.intensity },
    ];

    // Settle the heading before the first visible frame.
    fish.update(this.time, 1 / 60, this.pointer);
  }

  start(): void {
    if (this.running) return;
    this.running = true;
    this.clock.start();
    this.raf = requestAnimationFrame(this.frame);
  }

  stop(): void {
    this.running = false;
    this.clock.stop();
    cancelAnimationFrame(this.raf);
  }

  /**
   * Take the camera down through the water. Resolves once the frame is fully
   * black; by then the scene has already switched to the deep, which
   * surfaces out of that black on its own.
   */
  dive({ onSplash }: DiveOptions = {}): Promise<void> {
    if (this.diving || this.water) return Promise.resolve();
    // The unit is wanted a few seconds from now; start fetching it.
    this.loadDevice();
    return new Promise((resolve) => {
      const hole = new THREE.Vector3(
        CAMERA_POS.x + DIVE_HOLE.x,
        0,
        CAMERA_POS.z - DIVE_HOLE.z,
      );
      hole.y = this.ocean.heightAt(hole.x, hole.z);
      const fishStart = this.fish ? this.fish.root.position.clone() : hole.clone();
      // Arc: a little lift and a swing toward the camera before the run in.
      const fishCtrl = fishStart.clone().lerp(hole, 0.45);
      fishCtrl.y = fishStart.y + 0.8;
      fishCtrl.z += 1.5;
      this.diving = {
        t: 0,
        camStart: this.camera.position.clone(),
        fishStart,
        fishCtrl,
        hole,
        fishSplashAt: -1,
        lensSplashAt: -1,
        onSplash: onSplash ?? null,
        resolve,
      };
    });
  }

  /** Fetch the DeKoi unit once; resolves null (and logs) if it fails. */
  private loadDevice(): Promise<GLTF | null> {
    this.device ??= new Promise((resolve) => {
      new GLTFLoader().load(
        DEVICE_URL,
        (gltf) => resolve(gltf),
        undefined,
        (err) => {
          console.error('DeKoi model failed to load', err);
          resolve(null);
        },
      );
    });
    return this.device;
  }

  /**
   * Back to the top: the deep fades to black, the sea is put back as it was
   * and fades in. Resolves at the black frame, so the HUD can return along
   * with the picture.
   */
  surface(): Promise<void> {
    if (!this.water || this.transition) return Promise.resolve();
    return new Promise((resolve) => {
      this.transition = { kind: 'surface', target: 'deep', t: 0, resolve };
    });
  }

  /**
   * One chapter down. Between the deep and the safety chapter the lens
   * glides down through the water; to the floor it fades through black.
   * Resolves when the new chapter is current (`chapter`).
   */
  descend(): Promise<void> {
    const i = CHAPTERS.indexOf(this.chapter);
    if (!this.water || this.transition || i >= CHAPTERS.length - 1) return Promise.resolve();
    return this.moveTo(CHAPTERS[i + 1]);
  }

  /** One chapter up. */
  ascend(): Promise<void> {
    const i = CHAPTERS.indexOf(this.chapter);
    if (!this.water || this.transition || i <= 0) return Promise.resolve();
    return this.moveTo(CHAPTERS[i - 1]);
  }

  private moveTo(target: Chapter): Promise<void> {
    const kind = target === 'floor' || this.chapter === 'floor' ? 'fade' : 'glide';
    return new Promise((resolve) => {
      this.transition = { kind, target, t: 0, resolve };
      // A glide has both chapters in the water at once.
      if (kind === 'glide') this.enter(target);
    });
  }

  /**
   * Compile all shaders now so the first visible frame does not stutter.
   * Rendering one frame through the composer compiles the exact program
   * variants used in production (render-target path, no on-screen tone mapping).
   */
  compile(): void {
    this.update(0);
    this.render();
  }

  dispose(): void {
    this.stop();
    window.removeEventListener('resize', this.resize);
    this.resizeObserver.disconnect();
    document.removeEventListener('visibilitychange', this.onVisibility);
    window.removeEventListener('pointermove', this.onPointerMove);
    window.removeEventListener('pointerleave', this.onPointerLeave);
    this.post.dispose();
    this.ocean.dispose();
    this.deep?.dispose();
    this.lane?.dispose();
    this.floor?.dispose();
    this.water?.dispose();
    this.renderer.dispose();
  }

  private readonly onPointerMove = (e: PointerEvent): void => {
    if (e.pointerType !== 'mouse') return;
    this.pointer.set(
      (e.clientX / window.innerWidth) * 2 - 1,
      -((e.clientY / window.innerHeight) * 2 - 1),
    );
    this.pointerLive = true;
  };

  private readonly onPointerLeave = (): void => {
    this.pointer.set(0, 0);
    this.pointerLive = false;
  };

  private readonly resize = (): void => {
    const w = this.container.clientWidth || window.innerWidth;
    const h = this.container.clientHeight || window.innerHeight;
    this.renderer.setSize(w, h, false);
    this.camera.aspect = w / h;
    // Long lens in landscape; wider on narrow screens so the fish and some
    // water both stay in frame.
    this.camera.fov = w < h ? FOV_PORTRAIT : FOV_LANDSCAPE;
    this.camera.updateProjectionMatrix();
    this.post?.setSize(w, h);
  };

  private readonly onVisibility = (): void => {
    if (document.hidden) this.stop();
    else this.start();
  };

  private readonly frame = (): void => {
    if (!this.running) return;
    // The whole scene slows together when the dive is being inspected.
    const dt = Math.min(this.clock.getDelta(), 0.1) * (this.diving ? this.diveTimeScale : 1);
    if (!this.opts.reducedMotion) this.time += dt;
    this.update(dt);
    this.render();
    this.raf = requestAnimationFrame(this.frame);
  };

  protected update(dt: number): void {
    if (this.water) {
      this.updateBelow(dt);
      return;
    }

    this.ocean.update(this.time);
    this.sky.update(this.time);

    // Ride the swell: ease the camera height toward clearance above the
    // local surface. Slow enough to feel like a hull, quick enough that the
    // big swell never reaches the lens.
    const surface = this.ocean.heightAt(CAMERA_POS.x, CAMERA_POS.z);
    const targetY = Math.max(surface, 0) * 0.85 + CAMERA_CLEARANCE;
    const ky = this.opts.reducedMotion ? 1 : 1 - Math.exp(-dt / 1.6);
    this.cameraY += (targetY - this.cameraY) * ky;

    if (this.fish) {
      this.fish.centre.x = this.camera.aspect < 1 ? 0 : this.focus.x;
      this.fish.centre.y = this.cameraY + FISH_ABOVE_CAMERA;
      this.fish.update(this.time, dt, this.pointer);
    }

    // Damped mouse parallax: a small shift plus a tilt of a couple of degrees.
    const kp = 1 - Math.exp(-dt * 2.5);
    this.parallax.lerp(this.pointer, kp);
    this.camera.position.set(
      CAMERA_POS.x + this.parallax.x * PARALLAX_SHIFT.x,
      this.cameraY + this.parallax.y * PARALLAX_SHIFT.y,
      CAMERA_POS.z,
    );
    this.aimCamera();
    this.camera.rotateY(-this.parallax.x * PARALLAX_TILT);
    this.camera.rotateX(this.parallax.y * PARALLAX_TILT * 0.6);

    if (this.diving) this.updateDive(this.diving, dt);
    this.sky.follow(this.camera);

    // Back from the deep: the sea comes up out of the black.
    if (this.risingT >= 0) {
      this.risingT += dt;
      const k = THREE.MathUtils.smoothstep(this.risingT / SURFACE_FADE_IN, 0, 1);
      this.post.setDive({
        time: this.risingT,
        wobble: 0.3 * (1 - k),
        flash: 0,
        deep: 1 - k,
        dark: 1 - k,
      });
      if (k >= 1) this.risingT = -1;
    }
  }

  /** Overrides the camera and drives the water / post effects during the dive. */
  private updateDive(d: DiveState, dt: number): void {
    d.t += dt;
    const t = d.t;

    if (this.opts.reducedMotion) {
      // No camera motion: the picture just sinks into the dark.
      this.fireSplash(d);
      const k = THREE.MathUtils.clamp(t / DIVE_DURATION_STILL, 0, 1);
      this.post.setDive({ time: t, wobble: 0, flash: 0, deep: k, dark: k * k });
      if (t >= DIVE_DURATION_STILL) this.finishDive(d);
      return;
    }

    const churn = THREE.MathUtils.smoothstep(window01(t, DIVE_CHURN), 0, 1);
    this.ocean.churn = churn;
    // Ruffle the fine bump too so the churn reads at distance.
    this.ocean.material.normalScale.setScalar(0.06 + churn * 0.25);

    // 1. The fish runs in along a quadratic arc and goes under.
    const run = easeInQuad(window01(t, DIVE_FISH));
    if (this.fish && d.fishSplashAt < 0) {
      if (run > 0) {
        const pos = quadBezier(d.fishStart, d.fishCtrl, d.hole, run, _v1);
        const dir = quadBezierTangent(d.fishStart, d.fishCtrl, d.hole, run, _v2);
        this.fish.steer(pos, dir);
        // Its lights would blow out the water as it closes in. The pool is
        // a rectangle of light: as the lens sinks and the fish comes down,
        // its reflection shows as a hard-edged slab. So early in the run it
        // hands over to a soft round pool drawn in the water itself, which
        // then dims on the same curve the lights do.
        const dim = 1 - run * 0.8;
        const handover = THREE.MathUtils.smoothstep(run, FISH_POOL_OUT[0], FISH_POOL_OUT[1]);
        for (const { light, intensity } of this.fishLights) {
          light.intensity =
            intensity * dim * (light instanceof THREE.RectAreaLight ? 1 - handover : 1);
        }
        this.ocean.pool(pos.x, pos.z, FISH_POOL_GLOW * dim * handover, FISH_POOL_RADIUS);
      }
      if (run >= 1) {
        d.fishSplashAt = t;
        this.fish.root.visible = false;
        // The splash glow takes over from the pool.
        this.ocean.pool(0, 0, 0, 1);
        this.ocean.splash(d.hole.x, d.hole.z, 1);
      }
    }
    // Also without a fish (model failed to load), so the cue still lands.
    if (run >= 1) this.fireSplash(d);

    // The hole glows blue: hard on impact, then the decoy's light dies away
    // beneath the surface while the rings carry it outward.
    let glow = 0;
    if (d.fishSplashAt >= 0) {
      const since = t - d.fishSplashAt;
      glow = 0.25 + 0.75 * Math.exp(-since * 1.6);
    }
    this.ocean.glow = glow;

    // 2. The camera sinks straight down, still looking out to sea. No
    // rotation: the water simply rises up the frame and closes over.
    const sink = THREE.MathUtils.smoothstep(window01(t, DIVE_SINK), 0, 1);
    const cam = this.camera.position;
    cam.x = d.camStart.x;
    cam.z = d.camStart.z;
    cam.y = THREE.MathUtils.lerp(d.camStart.y, -DIVE_DEPTH, sink);

    // 3. The lens goes under: a second splash, and the haze becomes deep
    // water so the underside of the surface reads dark.
    const surface = this.ocean.heightAt(cam.x, cam.z);
    if (d.lensSplashAt < 0 && sink > 0 && cam.y < surface) {
      d.lensSplashAt = t;
      this.ocean.splash(cam.x, cam.z, 0.7);
      const fog = this.scene.fog as THREE.Fog;
      fog.color.set('#020509');
      fog.near = 0.5;
      fog.far = 14;
      this.renderer.setClearColor('#020509', 1);
      // No sky down here: the dome's lower half would show below the plane.
      this.sky.mesh.visible = false;
    }

    // Lens effects: a little spray as the fish hits, wobble as the water
    // nears the lens, then the deep tint and the fade. No flash.
    const near = THREE.MathUtils.clamp((surface + 2.0 - cam.y) / 2.0, 0, 1);
    let wobble = near * 0.6;
    let deep = 0;
    let dark = 0;
    if (d.fishSplashAt >= 0) {
      const since = t - d.fishSplashAt;
      wobble = Math.max(wobble, 0.3 * Math.exp(-since * 2.5));
    }
    if (d.lensSplashAt >= 0) {
      const since = t - d.lensSplashAt;
      wobble = 1 - 0.5 * THREE.MathUtils.clamp(since / 1.2, 0, 1);
      deep = THREE.MathUtils.clamp(since / 0.15, 0, 1);
      const k = window01(since, [DIVE_DARK_AFTER, DIVE_DARK_AFTER + DIVE_DARK_LENGTH]);
      dark = THREE.MathUtils.smoothstep(k, 0, 1);
    }
    this.post.setDive({ time: t, wobble, flash: 0, deep, dark });

    if (dark >= 1 || t >= DIVE_DURATION) {
      this.post.setDive({ time: t, wobble: 0, flash: 0, deep: 1, dark: 1 });
      this.finishDive(d);
    }
  }

  private fireSplash(d: DiveState): void {
    const fn = d.onSplash;
    d.onSplash = null;
    fn?.();
  }

  /** The frame is black: swap the sea for the deep, then let the caller know. */
  private finishDive(d: DiveState): void {
    this.diving = null;
    this.goBelow();
    this.enter('deep');
    this.arrive('deep');
    this.fadeT = 0;
    this.fadeIn = DEEP_FADE_IN;
    this.post.setDive({ time: 0, wobble: 0, flash: 0, deep: 1, dark: 1 });
    d.resolve();
  }

  /** Where a chapter's root sits: each one a level further down. */
  private levelOf(chapter: Chapter): number {
    return CHAPTERS.indexOf(chapter) * LEVEL_DROP;
  }

  private rootFor(chapter: Chapter, root: THREE.Object3D): void {
    root.position.copy(this.deepBase);
    root.position.y -= this.levelOf(chapter);
  }

  /** Build a chapter's content (the water is shared and already there). */
  private enter(chapter: Chapter): void {
    this.deepT = 0;
    if (chapter === 'deep') {
      const deep = new Deep();
      this.rootFor('deep', deep.root);
      this.scene.add(deep.root);
      this.deep = deep;
      void this.loadDevice().then(async (gltf) => {
        // Only if this visit is still the current one.
        if (!gltf || this.deep !== deep) return;
        deep.setDevice(gltf);
        // Compile its programs off the frame so the fade-in does not hitch.
        await this.renderer.compileAsync(deep.root, this.camera, this.scene);
        if (this.deep === deep) deep.reveal();
      });
    } else if (FISH_ROUTES[chapter]) {
      // Copy over the water, with the hero fish back in the picture far
      // behind the words, lit like the unit. There is one fish: when two of
      // these chapters are adjacent, the glide between them keeps the fish
      // on the route it is on, and the new one is laid on arrival (see
      // arrive()).
      if (!this.lane) this.addLane(chapter);
    } else if (chapter === 'floor') {
      const floor = new Floor({
        mobile: this.opts.mobile,
        pixelRatio: this.renderer.getPixelRatio(),
        cloud: this.markCloud,
        terrain: this.seafloor,
      });
      this.rootFor('floor', floor.root);
      this.scene.add(floor.root);
      this.floor = floor;
      if (!this.markCloud) {
        void this.loadMark().then((cloud) => {
          if (this.floor === floor) floor.setCloud(cloud);
        });
      }
      if (!this.seafloor) {
        void this.loadSeafloor().then((terrain) => {
          if (terrain && this.floor === floor) floor.setTerrain(terrain);
        });
      }
    }
    // 'business' is copy over the water alone.
  }

  /** Lay the fish's route for a chapter and put the fish on it, at its start. */
  private addLane(chapter: Chapter): void {
    const make = FISH_ROUTES[chapter];
    if (!make) return;
    const lane = make();
    this.rootFor(chapter, lane.root);
    this.scene.add(lane.root);
    this.lane = lane;
    this.laneChapter = chapter;
    if (this.fish) {
      lane.update(1 / 60, this.camera, this.fish);
      this.fish.update(this.time, 1 / 60, this.pointer);
      this.fish.root.visible = true;
      for (const { light, intensity } of this.fishLights) light.intensity = intensity;
    }
  }

  /** The chapter is now the current one: settle the water for it. */
  private arrive(chapter: Chapter): void {
    this.chapter = chapter;
    // After a glide from another fish chapter (its route went with leave()).
    if (FISH_ROUTES[chapter] && !this.lane) this.addLane(chapter);
    if (this.water) {
      // The floor brings its own light; above it the shafts stop at the unit.
      this.water.root.visible = chapter !== 'floor';
      this.water.depthTest = chapter === 'deep';
    }
  }

  private leave(chapter: Chapter): void {
    if (chapter === 'deep' && this.deep) this.removeDeep(this.deep);
    if (chapter === this.laneChapter && this.lane) this.removeLane(this.lane);
    if (chapter === 'floor' && this.floor) this.removeFloor(this.floor);
  }

  private removeLane(lane: FishRoute): void {
    this.scene.remove(lane.root);
    lane.dispose();
    this.lane = null;
    this.laneChapter = null;
    if (this.fish) {
      // Back to how it hovers above the surface (surfacing re-anchors it).
      this.fish.steer(null);
      this.fish.facing.copy(Fish.BASE_DIR);
      this.fish.swim = 1;
      this.fish.root.visible = false;
    }
  }

  /** The mark's points, loaded once (started early so the floor has them). */
  private loadMark(): Promise<Float32Array> {
    this.markLoading ??= loadLogoPoints(
      this.opts.mobile ? MARK_POINTS_MOBILE : MARK_POINTS,
      MARK_WIDTH,
    ).then((cloud) => {
      this.markCloud = cloud;
      return cloud;
    });
    return this.markLoading;
  }

  /** The seabed mesh, fetched once; resolves null (and logs) if it fails, and the dunes plane stays. */
  private loadSeafloor(): Promise<THREE.Object3D | null> {
    this.seafloorLoading ??= new Promise((resolve) => {
      new GLTFLoader().load(
        SEAFLOOR_URL,
        (gltf) => {
          this.seafloor = gltf.scene;
          resolve(gltf.scene);
        },
        undefined,
        (err) => {
          console.error('Seafloor model failed to load', err);
          resolve(null);
        },
      );
    });
    return this.seafloorLoading;
  }

  /** Swap the sea out for the chapters below it. */
  private goBelow(): void {
    this.ocean.mesh.visible = false;
    this.sky.mesh.visible = false;
    if (this.fish) this.fish.root.visible = false;
    this.renderer.setClearColor(DEEP_BG, 1);
    // The floor's mark takes a moment to sample and its seabed a moment to
    // fetch; start now, it is a long way down.
    void this.loadMark().catch(() => undefined);
    void this.loadSeafloor();
    // Rest where the dive left the lens (its own parallax is re-based here).
    this.deepBase.set(CAMERA_POS.x, this.camera.position.y, CAMERA_POS.z);
    // The sky environment is far too bright for metal 6 m under the sea, and
    // the sea's own lights would grey out the unit's black case.
    this.scene.environmentIntensity = 0.12;
    for (const l of this.heroLights) l.visible = false;

    const water = new Water({
      mobile: this.opts.mobile,
      pixelRatio: this.renderer.getPixelRatio(),
    });
    water.root.position.copy(this.deepBase);
    this.scene.add(water.root);
    this.water = water;
  }

  private removeDeep(deep: Deep): void {
    this.scene.remove(deep.root);
    deep.dispose();
    this.deep = null;
    if (this.anchorShown) this.onDeviceAnchor?.(null);
    this.anchorShown = false;
    if (this.deviceHovered) this.onDeviceHover?.(false);
    this.deviceHovered = false;
  }

  private removeFloor(floor: Floor): void {
    this.scene.remove(floor.root);
    floor.dispose();
    this.floor = null;
    if (this.markStirred) this.onMarkStir?.(false);
    this.markStirred = false;
  }

  /** Camera, content and fades for the chapters below the surface. */
  private updateBelow(dt: number): void {
    this.deepT += dt;
    const deep = this.deep;
    const floor = this.floor;
    const tr = this.transition;
    if (tr) tr.t += dt;

    // How far down the lens is: at its chapter's level, or gliding between.
    let descent = this.levelOf(this.chapter);
    if (tr?.kind === 'glide') {
      const k = THREE.MathUtils.smoothstep(tr.t / GLIDE_TIME, 0, 1);
      descent = THREE.MathUtils.lerp(this.levelOf(this.chapter), this.levelOf(tr.target), k);
    }

    // Slower, wider parallax than above: the shaft layers give it depth.
    const kp = 1 - Math.exp(-dt * 1.8);
    this.parallax.lerp(this.pointer, kp);
    const base = this.deepBase;
    const baseY = base.y - descent;
    this.camera.position.set(
      base.x + this.parallax.x * DEEP_PARALLAX_SHIFT.x,
      baseY + this.parallax.y * DEEP_PARALLAX_SHIFT.y,
      base.z,
    );
    const look = floor && this.chapter === 'floor' ? floor.lookOffset : DEEP_LOOK;
    this.lookTarget.set(base.x + look.x, baseY + look.y, base.z + look.z);
    this.camera.lookAt(this.lookTarget);
    this.camera.rotateY(-this.parallax.x * DEEP_PARALLAX_TILT);
    this.camera.rotateX(this.parallax.y * DEEP_PARALLAX_TILT * 0.6);
    this.camera.updateMatrixWorld();

    this.water?.update(this.time, base, descent, this.levelOf('floor'));
    if (this.lane && this.laneChapter && this.fish) {
      this.lane.update(dt, this.camera, this.fish);
      this.fish.update(this.time, dt, this.pointer);
    }
    if (deep) {
      deep.update(this.time, dt, this.deepT, this.camera.aspect);
      this.reportAnchor(deep);
      this.reportHover(deep);
    }
    if (floor) {
      let ray: THREE.Ray | null = null;
      if (this.pointerLive) {
        this.raycaster.setFromCamera(this.pointer, this.camera);
        ray = this.raycaster.ray;
      }
      const stirring = floor.update(this.time, dt, this.camera, ray);
      if (stirring !== this.markStirred) {
        this.markStirred = stirring;
        this.onMarkStir?.(stirring);
      }
    }

    if (tr) {
      if (tr.kind === 'glide') {
        // Both chapters are in the scene; when the lens has settled at the
        // new level the old one is taken down.
        if (tr.t < GLIDE_TIME) return;
        this.transition = null;
        this.leave(this.chapter);
        this.arrive(tr.target);
        tr.resolve();
        return;
      }
      // Into the black, then hand over.
      const out = tr.kind === 'surface' ? SURFACE_FADE_OUT : CHAPTER_FADE_OUT;
      const k = THREE.MathUtils.smoothstep(tr.t / out, 0, 1);
      const wobble = tr.kind === 'surface' ? 0.3 * k : 0;
      this.post.setDive({ time: this.deepT, wobble, flash: 0, deep: k * 0.5, dark: k });
      if (k < 1) return;
      this.transition = null;
      this.leave(this.chapter);
      if (tr.kind === 'surface') {
        this.returnToSea();
      } else {
        this.enter(tr.target);
        this.arrive(tr.target);
        this.fadeT = 0;
        this.fadeIn = CHAPTER_FADE_IN;
      }
      tr.resolve();
      return;
    }

    // Surfacing out of the black after a hand-over; the lens wobble (the
    // dive's last ripples) settles with it in the deep.
    if (this.fadeT >= 0) {
      this.fadeT += dt;
      const k = THREE.MathUtils.smoothstep(this.fadeT / this.fadeIn, 0, 1);
      this.post.setDive({
        time: this.fadeT,
        wobble: this.chapter === 'deep' ? 0.4 * (1 - k) : 0,
        flash: 0,
        deep: 1 - k,
        dark: 1 - k,
      });
      if (k >= 1) this.fadeT = -1;
    }
  }

  /** Project the unit's board into CSS pixels for the callout. */
  private reportAnchor(deep: Deep): void {
    const fn = this.onDeviceAnchor;
    if (!fn) return;
    const p = this.anchorWorld;
    if (!deep.anchor(p)) {
      if (this.anchorShown) fn(null);
      this.anchorShown = false;
      return;
    }
    p.project(this.camera);
    const rect = this.renderer.domElement.getBoundingClientRect();
    this.anchorShown = true;
    fn({
      x: rect.left + (p.x + 1) * 0.5 * rect.width,
      y: rect.top + (1 - p.y) * 0.5 * rect.height,
      open: deep.lidOpen,
    });
  }

  /** Mouse over the unit? Reported on change only. */
  private reportHover(deep: Deep): void {
    if (!this.onDeviceHover) return;
    this.raycaster.setFromCamera(this.pointer, this.camera);
    const over = deep.hitTest(this.raycaster);
    if (over === this.deviceHovered) return;
    this.deviceHovered = over;
    this.onDeviceHover(over);
  }

  /** The frame is black: put the sea back exactly as it was before the dive. */
  private returnToSea(): void {
    if (this.water) {
      this.scene.remove(this.water.root);
      this.water.dispose();
      this.water = null;
    }
    this.chapter = 'deep';
    this.ocean.mesh.visible = true;
    this.sky.mesh.visible = true;
    this.ocean.churn = 0;
    this.ocean.glow = 0;
    this.ocean.pool(0, 0, 0, 1);
    this.ocean.material.normalScale.setScalar(0.06);
    if (this.fish) {
      this.fish.steer(null);
      // Back to its hover above the water (x and y follow the camera per frame).
      this.fish.centre.copy(this.focus);
      this.fish.root.visible = true;
      for (const { light, intensity } of this.fishLights) light.intensity = intensity;
    }
    const fog = this.scene.fog as THREE.Fog;
    fog.color.copy(OCEAN.waterFog);
    fog.near = WATER_FOG_NEAR;
    fog.far = WATER_FOG_FAR;
    this.renderer.setClearColor(COLORS.bg, 1);
    this.scene.environmentIntensity = 0.6;
    for (const l of this.heroLights) l.visible = true;

    // update() puts the camera back at its rest; fade the sea in from here.
    this.risingT = 0;
    this.post.setDive({ time: 0, wobble: 0, flash: 0, deep: 1, dark: 1 });
  }

  /** Look straight ahead from the current height, pitched up by CAMERA_PITCH_UP. */
  private aimCamera(): void {
    this.lookTarget.set(
      CAMERA_POS.x,
      this.camera.position.y + CAMERA_POS.z * Math.tan(CAMERA_PITCH_UP),
      0,
    );
    this.camera.lookAt(this.lookTarget);
  }

  protected render(): void {
    this.post.render();
  }
}
