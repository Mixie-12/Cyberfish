import * as THREE from 'three';
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js';
import { MeshSurfaceSampler } from 'three/addons/math/MeshSurfaceSampler.js';

/**
 * Straight (un-posed) fish from Blender. Nose at -Z, tail at +Z, dorsal +Y,
 * ~6.75 units long. See assets-src/cyberfish/README.md.
 *
 * The fish hovers in place like a koi holding station: the body makes a slow,
 * shallow sway, the pectoral fins scull, the dorsal / anal / tail fins ripple.
 * All deformation is done in the vertex shader (one displacement field per
 * fin type), and normals are corrected from the field's Jacobian.
 */
const MODEL_URL = '/models/cyberfish_straight.glb';

/** Brings the ~6.75 unit model to ~6.1 units. */
const MODEL_SCALE = 0.9;

// ---------------------------------------------------------------- hovering
/** Drift around the anchor point (world units) and the periods (s) driving it. */
const DRIFT = new THREE.Vector3(0.28, 0.18, 0.12);
const DRIFT_PERIODS = [13, 17, 23];
/** Vertical bob. */
const BOB_AMPLITUDE = 0.1;
const BOB_PERIOD = 6.5;
/** Base facing: nose to screen right, turned a little toward the viewer. */
const BASE_DIR = new THREE.Vector3(1, 0, 0.42).normalize();
/** Slow attitude sway: yaw / pitch / roll amplitudes (rad) and periods (s). */
const SWAY_YAW = 0.1;
const SWAY_PITCH = 0.05;
const SWAY_ROLL = 0.06;
const SWAY_PERIODS = [11, 8.5, 14];
/** Cursor response: drift toward the pointer and turn toward it. */
const CURSOR_DRIFT = new THREE.Vector2(0.45, 0.35);
const CURSOR_TURN = new THREE.Vector2(0.5, 0.45);
const CURSOR_TAU = 1.1;
/** How much the spine curves toward the cursor (head leads, tail trails). */
const CURSOR_BEND = new THREE.Vector2(0.35, 0.5);

// ------------------------------------------------------------------- fins
/** Mesh kinds for the vertex shader. */
const KIND = {
  body: 0,
  tail: 1,
  pectoral: 2,
  pelvic: 3,
  dorsal: 4,
  anal: 5,
} as const;

const _pos = new THREE.Vector3();
const _dir = new THREE.Vector3();
const _m = new THREE.Matrix4();
const _q = new THREE.Quaternion();
const _roll = new THREE.Quaternion();
const _up = new THREE.Vector3(0, 1, 0);
const _zAxis = new THREE.Vector3(0, 0, 1);
const _origin = new THREE.Vector3();

export type ProgressCallback = (fraction: number) => void;

export interface FishOptions {
  reducedMotion: boolean;
  /** renderer.capabilities.getMaxAnisotropy(); keeps thin traces from twinkling. */
  anisotropy: number;
}

interface SharedUniforms {
  uTime: THREE.IUniform<number>;
  uSwim: THREE.IUniform<number>;
  uNoseZ: THREE.IUniform<number>;
  uTailZ: THREE.IUniform<number>;
  uLook: THREE.IUniform<THREE.Vector2>;
}

const SWIM_CHUNK = /* glsl */ `
uniform float uTime;
uniform float uSwim;
uniform float uNoseZ;
uniform float uTailZ;
uniform vec2 uLook;
uniform float uKind;
uniform float uSide;

const float TAU = 6.2831853;

// Spine curve toward where the fish is looking: the head bends one way and
// the tail trails the other, pivoting ~40% back from the nose.
vec2 lookBend(float z) {
  float len = uTailZ - uNoseZ;
  float s = clamp((z - uNoseZ) / len, 0.0, 1.0);
  float q = 0.4 - s;
  return uLook * len * q * abs(q) * 0.6;
}

// Shallow body sway, nose -> tail. s: 0 at the nose, 1 at the tail-fin tip.
float bodySway(float z) {
  float len = uTailZ - uNoseZ;
  float s = clamp((z - uNoseZ) / len, 0.0, 1.0);
  float env = 0.003 + 0.026 * s * s;
  float phase = s * TAU * 0.9 - uTime * TAU * 0.38;
  return len * env * sin(phase);
}

vec3 swimDisp(vec3 p) {
  vec3 d = vec3(bodySway(p.z), 0.0, 0.0);
  d.xy += lookBend(p.z);

  if (uKind == 1.0) {
    // Tail fin: ripple that grows toward the trailing edge, plus the two
    // lobes fluttering against each other.
    float r = clamp((p.z + 2.3) / 2.85, 0.0, 1.0);
    float lobe = p.y / 1.3;
    d.x += 0.07 * r * r * sin(p.z * 1.6 - uTime * TAU * 0.38 - 1.1);
    d.x += 0.035 * r * lobe * sin(uTime * TAU * 0.55 + lobe * 1.2 + 0.7);
  } else if (uKind == 2.0) {
    // Pectoral: sculling. Hinged at the body (|x| ~ 0.44), the tip traces a
    // slow ellipse (down/forward, up/back) with a ripple running outward.
    float r = clamp((abs(p.x) - 0.44) / 0.86, 0.0, 1.0);
    float t = uTime * TAU * 0.5 + uSide * 1.7;
    float e = r * r;
    d.y += 0.14 * e * sin(t);
    d.z += 0.07 * e * cos(t);
    d.y += 0.025 * r * sin(r * 5.0 - t * 1.6);
  } else if (uKind == 3.0) {
    // Pelvic: small lateral flutter.
    float r = clamp((abs(p.x) - 0.27) / 0.26, 0.0, 1.0);
    float t = uTime * TAU * 0.6 + uSide * 2.3 + 0.9;
    d.x += uSide * 0.04 * r * r * sin(t);
    d.y += 0.02 * r * r * cos(t);
  } else if (uKind == 4.0) {
    // Dorsal: wave running back along the fin, growing toward the tip.
    float h = clamp((p.y - 0.45) / 1.13, 0.0, 1.0);
    d.x += 0.07 * h * h * sin(p.z * 2.1 - uTime * TAU * 0.45);
  } else if (uKind == 5.0) {
    // Anal: same, smaller.
    float h = clamp((-0.28 - p.y) / 0.45, 0.0, 1.0);
    d.x += 0.04 * h * h * sin(p.z * 2.6 - uTime * TAU * 0.5 + 1.3);
  }

  return d * uSwim;
}
`;

const NORMAL_CHUNK = /* glsl */ `
#include <beginnormal_vertex>
{
  // Jacobian of the displacement field by central differences, then
  // n' = (I + J)^-T n ~= n - J^T n for small deformations.
  const float e = 0.02;
  vec3 jx = (swimDisp(position + vec3(e, 0.0, 0.0)) - swimDisp(position - vec3(e, 0.0, 0.0))) / (2.0 * e);
  vec3 jy = (swimDisp(position + vec3(0.0, e, 0.0)) - swimDisp(position - vec3(0.0, e, 0.0))) / (2.0 * e);
  vec3 jz = (swimDisp(position + vec3(0.0, 0.0, e)) - swimDisp(position - vec3(0.0, 0.0, e))) / (2.0 * e);
  vec3 n = objectNormal;
  objectNormal = normalize(n - vec3(dot(jx, n), dot(jy, n), dot(jz, n)));
  #ifdef USE_TANGENT
  vec3 t = objectTangent;
  objectTangent = normalize(t + jx * t.x + jy * t.y + jz * t.z);
  #endif
}
`;

const POSITION_CHUNK = /* glsl */ `
#include <begin_vertex>
transformed += swimDisp(position);
`;

function patchMaterial(
  material: THREE.Material,
  shared: SharedUniforms,
  kind: number,
  side: number,
): void {
  const own = {
    uKind: { value: kind },
    uSide: { value: side },
  };
  material.customProgramCacheKey = () => `swim-${kind}`;
  material.onBeforeCompile = (shader) => {
    Object.assign(shader.uniforms, shared, own);
    shader.vertexShader = shader.vertexShader
      .replace('#include <common>', `#include <common>\n${SWIM_CHUNK}`)
      .replace('#include <beginnormal_vertex>', NORMAL_CHUNK)
      .replace('#include <begin_vertex>', POSITION_CHUNK);
  };
  material.needsUpdate = true;
}

function setAnisotropy(material: THREE.Material, anisotropy: number): void {
  for (const value of Object.values(material)) {
    if (value instanceof THREE.Texture) value.anisotropy = anisotropy;
  }
}

function kindFor(name: string): number {
  if (name.startsWith('CF_FinTail')) return KIND.tail;
  if (name.startsWith('CF_FinPectoral')) return KIND.pectoral;
  if (name.startsWith('CF_FinPelvic')) return KIND.pelvic;
  if (name.startsWith('CF_FinDorsal')) return KIND.dorsal;
  if (name.startsWith('CF_FinAnal')) return KIND.anal;
  return KIND.body;
}

const _a = new THREE.Vector3();
const _b = new THREE.Vector3();
const _c = new THREE.Vector3();
const _tri = new THREE.Triangle();

/** Total triangle area of a mesh, in its own (scaled) world space. */
function surfaceArea(mesh: THREE.Mesh): number {
  const g = mesh.geometry;
  const pos = g.getAttribute('position');
  const index = g.getIndex();
  const n = index ? index.count : pos.count;
  let area = 0;
  for (let i = 0; i < n; i += 3) {
    const ia = index ? index.getX(i) : i;
    const ib = index ? index.getX(i + 1) : i + 1;
    const ic = index ? index.getX(i + 2) : i + 2;
    _tri.set(
      _a.fromBufferAttribute(pos, ia).applyMatrix4(mesh.matrixWorld),
      _b.fromBufferAttribute(pos, ib).applyMatrix4(mesh.matrixWorld),
      _c.fromBufferAttribute(pos, ic).applyMatrix4(mesh.matrixWorld),
    );
    area += _tri.getArea();
  }
  return area;
}

export class Fish {
  /** Add this to the scene. Position and orientation are driven by update(). */
  readonly root = new THREE.Group();
  readonly centre: THREE.Vector3;
  /** Which way the hover faces (nose direction, world); BASE_DIR unless a chapter turns it. */
  readonly facing = BASE_DIR.clone();
  static readonly BASE_DIR: Readonly<THREE.Vector3> = BASE_DIR;
  private readonly reducedMotion: boolean;
  private readonly cursor = new THREE.Vector2();
  private readonly uniforms: SharedUniforms;
  /** When set, update() places the fish here instead of hovering. */
  private override: { pos: THREE.Vector3; dir: THREE.Vector3 } | null = null;

  private constructor(model: THREE.Object3D, centre: THREE.Vector3, opts: FishOptions) {
    const { reducedMotion } = opts;
    this.centre = centre.clone();
    this.reducedMotion = reducedMotion;

    const box = new THREE.Box3().setFromObject(model);
    this.uniforms = {
      uTime: { value: 0 },
      uSwim: { value: reducedMotion ? 0 : 1 },
      uNoseZ: { value: box.min.z },
      uTailZ: { value: box.max.z },
      uLook: { value: new THREE.Vector2() },
    };

    // Pivot around the body's centre of mass so the sway rotates about it.
    const pivot = box.getCenter(new THREE.Vector3());
    pivot.z = THREE.MathUtils.lerp(box.min.z, box.max.z, 0.4);
    model.position.sub(pivot).multiplyScalar(MODEL_SCALE);
    model.scale.setScalar(MODEL_SCALE);

    model.traverse((o) => {
      if (!(o instanceof THREE.Mesh)) return;
      o.frustumCulled = false;
      const kind = kindFor(o.name);
      const side = o.name.endsWith('.R') ? -1 : 1;
      // Every mesh gets its own material so each fin can carry its own
      // kind / side uniforms. Textures stay shared.
      const source = Array.isArray(o.material) ? o.material[0] : o.material;
      const mat = source.clone();
      // Scene fog is the water haze; the fish is far inside the fog's near.
      mat.fog = false;
      if (mat.transparent) {
        // Blended fins, drawn after the body. They DO write depth (Blender's
        // "no transparency overlap"): otherwise a fin's own folds and
        // overlapping fins blend in triangle order and pop as they flutter.
        mat.depthWrite = true;
        o.renderOrder = 1;
      }
      setAnisotropy(mat, opts.anisotropy);
      patchMaterial(mat, this.uniforms, kind, side);
      o.material = mat;
    });

    this.root.add(model);
    this.root.position.copy(centre);
    this.face(BASE_DIR, 0);
  }

  static load(
    centre: THREE.Vector3,
    opts: FishOptions,
    onProgress?: ProgressCallback,
  ): Promise<Fish> {
    return new Promise((resolve, reject) => {
      new GLTFLoader().load(
        MODEL_URL,
        (gltf) => resolve(new Fish(gltf.scene, centre, opts)),
        (e) => {
          if (onProgress && e.lengthComputable && e.total > 0) onProgress(e.loaded / e.total);
        },
        (err) => reject(err instanceof Error ? err : new Error(String(err))),
      );
    });
  }

  /**
   * Take the fish off its hover: put it at `pos` with its nose along `dir`
   * (world units). Used by the dive. Pass null to resume hovering.
   */
  steer(pos: THREE.Vector3 | null, dir?: THREE.Vector3): void {
    if (!pos || !dir) {
      this.override = null;
      return;
    }
    if (!this.override) this.override = { pos: pos.clone(), dir: dir.clone() };
    else {
      this.override.pos.copy(pos);
      this.override.dir.copy(dir);
    }
  }

  /**
   * How hard the body swims, 0..1 (1 is the hover's cruise). The safety
   * chapter eases it down while the fish stops to look round. Ignored under
   * reduced motion, where the body stays still.
   */
  set swim(amount: number) {
    this.uniforms.uSwim.value = this.reducedMotion ? 0 : THREE.MathUtils.clamp(amount, 0, 1);
  }

  /** `pointer` is in -1..1 (x right, y up); it is damped inside. */
  update(time: number, dt: number, pointer: THREE.Vector2): void {
    this.uniforms.uTime.value = time;
    if (this.override) {
      this.root.position.copy(this.override.pos);
      this.uniforms.uLook.value.set(0, 0);
      this.face(_dir.copy(this.override.dir).normalize(), 0);
      return;
    }
    if (this.reducedMotion) {
      // Still: at its anchor, facing its way.
      this.root.position.copy(this.centre);
      this.uniforms.uLook.value.set(0, 0);
      this.face(_dir.copy(this.facing), 0);
      return;
    }
    if (dt <= 0) return;

    this.cursor.lerp(pointer, 1 - Math.exp(-dt / CURSOR_TAU));

    // Position: anchor + slow multi-period drift + bob + cursor pull.
    const w = (i: number) => (Math.PI * 2 * time) / DRIFT_PERIODS[i];
    _pos.set(
      DRIFT.x * Math.sin(w(0)) * Math.cos(w(2) * 0.5),
      DRIFT.y * Math.sin(w(1) + 1.1) + BOB_AMPLITUDE * Math.sin((time / BOB_PERIOD) * Math.PI * 2),
      DRIFT.z * Math.sin(w(2) + 2.4),
    );
    _pos.x += this.cursor.x * CURSOR_DRIFT.x;
    _pos.y += this.cursor.y * CURSOR_DRIFT.y;
    this.root.position.copy(this.centre).add(_pos);

    // Attitude: slow sway around the base facing, turned toward the cursor.
    const s = (i: number) => Math.sin((Math.PI * 2 * time) / SWAY_PERIODS[i]);
    // Cursor behind the fish (screen left) brings its face round toward the
    // camera, cursor ahead of it looks away: a positive yaw turns the +X nose
    // away from +Z. Cursor up lifts the nose.
    const yaw = SWAY_YAW * s(0) + this.cursor.x * CURSOR_TURN.x;
    const pitch = SWAY_PITCH * s(1) + this.cursor.y * CURSOR_TURN.y;
    const roll = SWAY_ROLL * s(2);
    this.uniforms.uLook.value.set(
      -this.cursor.x * CURSOR_BEND.x,
      this.cursor.y * CURSOR_BEND.y,
    );
    _dir.copy(this.facing).applyAxisAngle(_up, yaw);
    _dir.y += pitch;
    this.face(_dir.normalize(), roll);
  }

  /**
   * Points spread evenly over the fish's surface, in root space (the model's
   * un-posed shape, centred on its pivot, nose toward -Z, ~6.1 units long).
   * The floor chapter draws the fish from these.
   */
  samplePoints(count: number): Float32Array {
    const meshes: THREE.Mesh[] = [];
    this.root.updateWorldMatrix(true, true);
    this.root.traverse((o) => {
      if (o instanceof THREE.Mesh) meshes.push(o);
    });
    const areas = meshes.map(surfaceArea);
    const total = areas.reduce((s, a) => s + a, 0);
    const out = new Float32Array(count * 3);
    const toRoot = this.root.matrixWorld.clone().invert();
    let n = 0;
    meshes.forEach((mesh, i) => {
      const share = i === meshes.length - 1 ? count - n : Math.round((count * areas[i]) / total);
      if (share <= 0) return;
      const sampler = new MeshSurfaceSampler(mesh).build();
      _m.copy(toRoot).multiply(mesh.matrixWorld);
      for (let k = 0; k < share && n < count; k++, n++) {
        sampler.sample(_pos);
        _pos.applyMatrix4(_m);
        out[n * 3] = _pos.x;
        out[n * 3 + 1] = _pos.y;
        out[n * 3 + 2] = _pos.z;
      }
    });
    return out;
  }

  private face(dir: THREE.Vector3, roll: number): void {
    // Matrix4.lookAt(eye, target) leaves -Z pointing at the target, which is
    // where the fish's nose is.
    _m.lookAt(_origin, dir, _up);
    _q.setFromRotationMatrix(_m);
    _roll.setFromAxisAngle(_zAxis, roll);
    this.root.quaternion.copy(_q).multiply(_roll);
  }
}
