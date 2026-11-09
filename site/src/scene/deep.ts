import * as THREE from 'three';
import type { GLTF } from 'three/addons/loaders/GLTFLoader.js';
import raysVert from './shaders/rays.vert.glsl?raw';
import raysFrag from './shaders/rays.frag.glsl?raw';
import motesVert from './shaders/motes.vert.glsl?raw';
import motesFrag from './shaders/motes.frag.glsl?raw';

/**
 * The deep: the first chapter below the surface. The water itself (shafts
 * and motes) is the shared Water; this chapter is the DeKoi unit
 * (public/models/dekoi.glb) hanging on the right, tipped toward the lens,
 * opening its lid once the chapter has surfaced. `root` is laid out around
 * the origin with the camera at the origin looking down -Z.
 *
 * This file also holds the shaft and mote builders, shared by the water
 * and the floor: additive planes at a few depths (so the mouse parallax
 * gives them volume), and one Points cloud moved in the vertex shader.
 */

/**
 * Shaft layers: distance in front of the camera, lean, seed, brightness,
 * and a band (centre and half-width, as a fraction of the distance, so
 * roughly a fraction of the frame's half-width) that always carries light.
 */
export interface RayLayer {
  distance: number;
  angle: number;
  seed: number;
  intensity: number;
  anchor: number;
  anchorWidth: number;
}

/** Plane size relative to its distance: generous, so parallax never shows an edge. */
const RAY_PLANE_HEIGHT = 1.3;
const RAY_PLANE_WIDTH = 3.2;
/** Across-shaft frequency scales with distance so all layers read alike on screen. */
const RAY_SCALE = 12;

const RAY_COLOR_DEEP = new THREE.Color('#0a2c74');
const RAY_COLOR_BRIGHT = new THREE.Color('#7fbfff');
const MOTE_COLOR = new THREE.Color('#a9d6ff');
/** Motes fill a wedge in front of the camera: this deep, this tall. */
const MOTE_DEPTH = [2, 46] as const;
const MOTE_HALF_HEIGHT = 12;

/**
 * The unit: real size is 9.4 cm, so it is scaled up to read at this
 * distance (about a third of the frame height). Landscape puts it on the
 * right; portrait centres it low. Tipped so the lid faces the lens, turned
 * so the long axis runs diagonally, then a slow drift on top.
 */
const DEVICE_DISTANCE = 6;
const DEVICE_SCALE = 8.6;
/** Sits a little low: the lid lifts up and toward the lens when it opens. */
const DEVICE_POS_LANDSCAPE = new THREE.Vector3(1.3, -0.3, -DEVICE_DISTANCE);
const DEVICE_POS_PORTRAIT = new THREE.Vector3(0, -0.65, -DEVICE_DISTANCE);
/**
 * The GLB's emissives are set for a bloom threshold around 1.0 with a small
 * radius; ours is wide and strong, so cap them here or the mark smears into
 * a white plate.
 */
const DEVICE_EMISSIVE_MAX = 1.6;
const DEVICE_TILT = 0.95;
const DEVICE_TURN = -0.55;
/** Model height is 3 cm with the base at y = 0: pivot about the middle. */
const DEVICE_CENTRE_Y = -0.015;
const LID_CLIP = 'DK_LidOpen';
/** Seconds after the hand-off before the lid lifts (the fade-in is ~3 s). */
const LID_OPEN_AT = 3.4;
/** The part the callout points at: the Pi's heatsink, mid-board. */
const ANCHOR_MESH = 'DK_Pi_sink';
/** The unit's outer shell, for pointer hit-testing (not the board's parts). */
const HIT_MESHES = new Set(['DK_CaseBody', 'DK_Lid']);

export class Deep {
  readonly root = new THREE.Group();

  /** Device rig: `drift` carries the float, `tilt` and `turn` the pose. */
  private readonly drift = new THREE.Group();
  private readonly tilt = new THREE.Group();
  private device: THREE.Object3D | null = null;
  private mixer: THREE.AnimationMixer | null = null;
  private lid: THREE.AnimationAction | null = null;
  private lidOpened = false;
  /** True once the lid clip has run to its end. */
  lidOpen = false;
  /** Mesh the callout points at, and the point on it (local space). */
  private anchorMesh: THREE.Mesh | null = null;
  private readonly anchorLocal = new THREE.Vector3();
  private readonly hitMeshes: THREE.Mesh[] = [];
  private readonly hits: THREE.Intersection[] = [];

  constructor() {
    // Device rig and its lights. The water's shaders ignore lights, so
    // these are for the unit alone: a cool key from above on the lens
    // side, a blue rim from behind, and a faint fill so the black case has
    // some form in its shadows.
    this.drift.position.copy(DEVICE_POS_LANDSCAPE);
    this.tilt.rotation.x = DEVICE_TILT;
    this.drift.add(this.tilt);
    this.root.add(this.drift);
    const key = new THREE.DirectionalLight('#dbe8ff', 1.0);
    key.position.set(3, 7, 4).add(DEVICE_POS_LANDSCAPE);
    key.target = this.drift;
    const rim = new THREE.DirectionalLight('#2ea8ff', 2.0);
    rim.position.set(-4, 2, -8).add(DEVICE_POS_LANDSCAPE);
    rim.target = this.drift;
    const fill = new THREE.HemisphereLight('#3b5a9a', '#02050c', 0.4);
    this.root.add(key, rim, fill);
  }

  /**
   * Hang the loaded unit on the rig, hidden and closed. The caller shows it
   * with reveal() once its shaders are compiled; the lid opens in update().
   */
  setDevice(gltf: GLTF): void {
    if (this.device) return;
    const model = gltf.scene;
    model.position.y = DEVICE_CENTRE_Y;
    model.traverse((o) => {
      if (!(o instanceof THREE.Mesh)) return;
      if (o.name === ANCHOR_MESH) {
        // Top face, centre.
        o.geometry.computeBoundingBox();
        const bb = o.geometry.boundingBox!;
        bb.getCenter(this.anchorLocal).setY(bb.max.y);
        this.anchorMesh = o;
      }
      if (HIT_MESHES.has(o.name)) this.hitMeshes.push(o);
      const mats = Array.isArray(o.material) ? o.material : [o.material];
      for (const m of mats) {
        // The deep's fog is tuned for the water, not for a thing 6 m away.
        m.fog = false;
        if (m instanceof THREE.MeshStandardMaterial) {
          m.emissiveIntensity = Math.min(m.emissiveIntensity, DEVICE_EMISSIVE_MAX);
          // Flat polished metal (port housings, shield) catches the key as
          // a solid white face; a little roughness turns that into a sheen.
          if (m.metalness > 0.5) m.roughness = Math.max(m.roughness, 0.5);
        }
        // The case and lid are a satin black in the renders; under our key
        // the clearcoat and low roughness read as grey. Matte keeps it black.
        if (m instanceof THREE.MeshPhysicalMaterial && m.name === 'DK_LidPlastic') {
          m.clearcoat = 0;
          m.roughness = 0.65;
        }
        if (m instanceof THREE.MeshStandardMaterial && m.name === 'DK_CasePlastic') {
          m.roughness = 0.65;
        }
      }
    });
    const turn = new THREE.Group();
    turn.rotation.y = DEVICE_TURN;
    turn.scale.setScalar(DEVICE_SCALE);
    turn.visible = false;
    turn.add(model);
    this.tilt.add(turn);
    this.device = model;

    const clip = THREE.AnimationClip.findByName(gltf.animations, LID_CLIP);
    if (clip) {
      this.mixer = new THREE.AnimationMixer(model);
      const action = this.mixer.clipAction(clip);
      action.setLoop(THREE.LoopOnce, 1);
      action.clampWhenFinished = true;
      // Write the closed pose now (the model may arrive open from a
      // previous visit), then hold until it is time.
      action.play();
      this.mixer.update(0);
      action.paused = true;
      this.lid = action;
      this.mixer.addEventListener('finished', () => {
        this.lidOpen = true;
      });
    }
  }

  /** Show the unit (after its programs are compiled). */
  reveal(): void {
    if (this.device?.parent) this.device.parent.visible = true;
  }

  /**
   * World position of the callout's anchor on the board. False while the
   * unit is not loaded or not yet shown.
   */
  anchor(out: THREE.Vector3): boolean {
    const mesh = this.anchorMesh;
    if (!mesh || !this.device?.parent?.visible) return false;
    mesh.updateWorldMatrix(true, false);
    mesh.localToWorld(out.copy(this.anchorLocal));
    return true;
  }

  /** Does this ray (from the pointer) touch the unit's case or lid? */
  hitTest(ray: THREE.Raycaster): boolean {
    if (!this.hitMeshes.length || !this.device?.parent?.visible) return false;
    this.hits.length = 0;
    ray.intersectObjects(this.hitMeshes, false, this.hits);
    return this.hits.length > 0;
  }

  /**
   * @param time    scene clock (drives the drift)
   * @param dt      frame delta
   * @param since   seconds since the hand-off into the deep
   * @param aspect  camera aspect, for the device's placement
   */
  update(time: number, dt: number, since: number, aspect: number): void {
    // The unit floats: a slow bob and the gentlest roll and nod.
    const at = aspect < 1 ? DEVICE_POS_PORTRAIT : DEVICE_POS_LANDSCAPE;
    this.drift.position.set(at.x, at.y + Math.sin(time * 0.45) * 0.07, at.z);
    this.drift.rotation.z = Math.sin(time * 0.31) * 0.05;
    this.drift.rotation.x = Math.sin(time * 0.23 + 1.7) * 0.035;

    if (this.lid && !this.lidOpened && since >= LID_OPEN_AT) {
      this.lidOpened = true;
      this.lid.paused = false;
    }
    this.mixer?.update(dt);
  }

  dispose(): void {
    this.mixer?.stopAllAction();
    this.device?.traverse((o) => {
      if (!(o instanceof THREE.Mesh)) return;
      o.geometry.dispose();
      const mats = Array.isArray(o.material) ? o.material : [o.material];
      for (const m of mats) m.dispose();
    });
  }

}

/** A built scene part: the object to add, and what to dispose. */
export interface Part<T extends THREE.Object3D = THREE.Object3D> {
  object: T;
  material: THREE.ShaderMaterial;
  geometry: THREE.BufferGeometry;
}

/**
 * One layer of light shafts: an additive plane facing the camera at
 * `layer.distance`, sized so parallax never shows an edge. Shared by the
 * deep and the floor.
 */
export function createRays(layer: RayLayer): Part<THREE.Mesh> {
  const height = layer.distance * RAY_PLANE_HEIGHT;
  const width = layer.distance * RAY_PLANE_WIDTH;
  const geometry = new THREE.PlaneGeometry(width, height);
  const material = new THREE.ShaderMaterial({
    uniforms: {
      uTime: { value: 0 },
      uSeed: { value: layer.seed },
      uAngle: { value: layer.angle },
      uScale: { value: RAY_SCALE / layer.distance },
      uIntensity: { value: layer.intensity },
      uSize: { value: new THREE.Vector2(width, height) },
      uAnchor: { value: layer.anchor * layer.distance },
      uAnchorWidth: { value: layer.anchorWidth * layer.distance },
      uColorDeep: { value: RAY_COLOR_DEEP.clone() },
      uColorBright: { value: RAY_COLOR_BRIGHT.clone() },
    },
    vertexShader: raysVert,
    fragmentShader: raysFrag,
    transparent: true,
    depthWrite: false,
    // The shaft planes are well behind the unit; depth-tested so the
    // light does not shine through its black case.
    depthTest: true,
    blending: THREE.CustomBlending,
    blendSrc: THREE.OneFactor,
    blendDst: THREE.OneFactor,
  });
  const mesh = new THREE.Mesh(geometry, material);
  mesh.position.z = -layer.distance;
  // Shafts come from above: sit the plane a little high so its bright end
  // is at and beyond the top of the frame.
  mesh.position.y = height * 0.12;
  mesh.frustumCulled = false;
  mesh.renderOrder = 1;
  return { object: mesh, material, geometry };
}

/**
 * Motes drifting in the water in front of the camera (motion is in the
 * vertex shader; set `uTime`). Shared by the deep and the floor.
 */
export function createMotes(count: number, pixelRatio: number): Part<THREE.Points> {
  const positions = new Float32Array(count * 3);
  const seeds = new Float32Array(count * 4);
  const sizes = new Float32Array(count);
  const [near, far] = MOTE_DEPTH;
  for (let i = 0; i < count; i++) {
    const r = Math.random();
    // Mostly small sparks; some bright enough to bloom; a few big soft
    // bokeh discs close to the lens.
    const kind = r < 0.1 ? 2 : r < 0.24 ? 1 : 0;
    const depth = kind === 2 ? near + Math.random() * 8 : near + Math.random() * (far - near);
    // Wider spread further out, to keep the frustum filled.
    const halfWidth = depth * 0.62 + 1.5;
    positions[i * 3] = (Math.random() * 2 - 1) * halfWidth;
    positions[i * 3 + 1] = (Math.random() * 2 - 1) * MOTE_HALF_HEIGHT;
    positions[i * 3 + 2] = -depth;
    seeds[i * 4] = Math.random();
    seeds[i * 4 + 1] = Math.random();
    seeds[i * 4 + 2] = Math.random();
    seeds[i * 4 + 3] = kind;
    sizes[i] =
      kind === 2 ? 5 + Math.random() * 4 : kind === 1 ? 1.0 + Math.random() : 0.7 + Math.random() * 1.1;
  }
  const geometry = new THREE.BufferGeometry();
  geometry.setAttribute('position', new THREE.BufferAttribute(positions, 3));
  geometry.setAttribute('aSeed', new THREE.BufferAttribute(seeds, 4));
  geometry.setAttribute('aSize', new THREE.BufferAttribute(sizes, 1));
  const material = new THREE.ShaderMaterial({
    uniforms: {
      uTime: { value: 0 },
      uPixelRatio: { value: pixelRatio },
      uHalfHeight: { value: MOTE_HALF_HEIGHT },
      uScroll: { value: 0 },
      uColor: { value: MOTE_COLOR.clone() },
    },
    vertexShader: motesVert,
    fragmentShader: motesFrag,
    transparent: true,
    depthWrite: false,
    // Tested against the unit's depth so the motes behind it do not show
    // through its case (the water itself writes no depth).
    depthTest: true,
    blending: THREE.CustomBlending,
    blendSrc: THREE.OneFactor,
    blendDst: THREE.OneFactor,
  });
  const points = new THREE.Points(geometry, material);
  points.frustumCulled = false;
  points.renderOrder = 2;
  return { object: points, material, geometry };
}
