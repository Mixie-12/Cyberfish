import * as THREE from 'three';
import { OCEAN } from './palette';

/**
 * Night sea from assets-src/ocean/ocean_look.json: dark navy satin water with
 * a clearcoat, big slow swells (Gerstner waves in the vertex shader, standing
 * in for Blender's Ocean modifier) and a fine procedural bump for sparkle.
 * Haze toward the horizon comes from scene.fog, which only the water uses.
 */

/** Swell direction from the spec, degrees from +X toward -Z. */
const DIRECTION_DEG = 35;
/** Global slowdown of the physically-derived wave speeds: dreamy, not stormy. */
const TIME_SCALE = 0.42;

interface Wave {
  /** Wavelength in metres. */
  length: number;
  /** Amplitude in metres (crest height above the mean). */
  amplitude: number;
  /** Direction offset from DIRECTION_DEG, degrees. */
  angle: number;
  /** Gerstner steepness 0..1 (sharper crests). */
  steepness: number;
}

/**
 * Big, heavy swells: a ~140 m dominant wave, two cross swells, then chop.
 * Total crest height ~3.1 m; the camera sits above that (CAMERA_POS.y).
 */
export const WAVES: Wave[] = [
  { length: 140, amplitude: 1.6, angle: 0, steepness: 0.3 },
  { length: 74, amplitude: 0.85, angle: 24, steepness: 0.36 },
  { length: 36, amplitude: 0.4, angle: -30, steepness: 0.42 },
  { length: 16, amplitude: 0.16, angle: 55, steepness: 0.4 },
  { length: 7, amplitude: 0.06, angle: -66, steepness: 0.3 },
  { length: 3.2, amplitude: 0.02, angle: 14, steepness: 0.25 },
];

/** Highest the surface can reach above y = 0. */
export const WAVE_CREST = WAVES.reduce((s, w) => s + w.amplitude, 0);

const GRAVITY = 9.81;

/** Churn: how much the shorter waves (index >= 2) grow at full churn. */
const CHURN_GAIN = 1.2;
/** Extra crest height that adds, for anyone keeping clear of the surface. */
export const CHURN_CREST = WAVES.slice(2).reduce((s, w) => s + w.amplitude, 0) * CHURN_GAIN;

/** Splash ripple: wavelength, amplitude and speed of the rings. */
const SPLASH_LENGTH = 3.0;
const SPLASH_AMPLITUDE = 0.5;
const SPLASH_SPEED = 3.4;

/** Shared by both stages: the splash ring field at a world xz. */
const SPLASH_CHUNK = /* glsl */ `
uniform float uTime;
// xy: impact point, z: impact time, w: strength (0 = none).
uniform vec4 uSplash;

// Rings running out from the impact, fading with distance and age.
// Returns x: envelope (0..strength), y: ring phase, z: distance, w: crater depth.
vec4 splashField(vec2 xz) {
  float age = uTime - uSplash.z;
  if (uSplash.w <= 0.0 || age <= 0.0) return vec4(0.0, 0.0, 1e4, 0.0);
  float r = distance(xz, uSplash.xy);
  float k = ${(Math.PI * 2) / SPLASH_LENGTH};
  float w = ${((Math.PI * 2) / SPLASH_LENGTH) * SPLASH_SPEED};
  float front = age * ${SPLASH_SPEED} + 1.5;
  float env = uSplash.w
    * smoothstep(0.0, 0.12, age) * exp(-age * 0.55)
    * exp(-r * 0.1)
    * smoothstep(front + 3.0, front - 1.0, r);
  // Crater: the surface punched down, springing back.
  float crater = uSplash.w * 1.6 * exp(-r * r / 7.0) * exp(-age * 1.8) * smoothstep(0.0, 0.08, age);
  return vec4(env, k * r - w * age, r, crater);
}
`;

const WAVE_CHUNK = /* glsl */ `
#define OC_WAVES ${WAVES.length}
// xy: direction, z: wavenumber k, w: amplitude
uniform vec4 uWaveA[OC_WAVES];
// x: angular frequency, y: steepness Q
uniform vec2 uWaveB[OC_WAVES];
// 0..1: the sea getting agitated (chop and cross swells grow).
uniform float uChurn;

// Gerstner sum (GPU Gems 1, ch. 1). Returns the displaced position and normal
// for a point on the rest plane at xz.
void oceanWave(vec2 xz, out vec3 disp, out vec3 nrm) {
  disp = vec3(0.0);
  vec3 n = vec3(0.0, 1.0, 0.0);
  for (int i = 0; i < OC_WAVES; i++) {
    vec2 d = uWaveA[i].xy;
    float k = uWaveA[i].z;
    float a = uWaveA[i].w;
    float w = uWaveB[i].x;
    float q = uWaveB[i].y;
    if (i >= 2) {
      // Churn: the shorter waves heap up, the two big swells stay put.
      a *= 1.0 + uChurn * ${CHURN_GAIN};
      q = min(q * (1.0 + uChurn * 0.6), 0.7);
    }
    float phase = k * dot(d, xz) - w * uTime;
    float s = sin(phase);
    float c = cos(phase);
    disp.xz += q * a * d * c;
    disp.y += a * s;
    n.xz -= d * k * a * c;
    n.y -= q * k * a * s;
  }

  // Splash: crater plus rings.
  vec4 sp = splashField(xz);
  if (sp.x > 0.0 || sp.w > 0.0) {
    vec2 dir = (xz - uSplash.xy) / max(sp.z, 1e-3);
    float k = ${(Math.PI * 2) / SPLASH_LENGTH};
    float a = ${SPLASH_AMPLITUDE};
    disp.y += a * sp.x * sin(sp.y) - sp.w;
    n.xz -= dir * (a * sp.x * k * cos(sp.y) + sp.w * (2.0 * sp.z / 7.0));
  }
  nrm = normalize(n);
}
`;

/**
 * Fragment side: the decoy glows under the surface where it went in, and the
 * rings carry the light outward on their crests. Evaluated per fragment (the
 * mesh is far too coarse for the rings) and emissive, so it feeds the bloom.
 * Albedo is near black here, so a light would not read.
 */
const GLOW_CHUNK = /* glsl */ `
uniform float uGlow;
uniform vec3 uGlowColor;
// The fish's pool of light on the water during its dive: xy world xz,
// z strength, w radius (Gaussian sigma).
uniform vec4 uPool;
varying vec2 vOcWorld;
`;
const GLOW_FRAG = /* glsl */ `
#include <emissivemap_fragment>
if (uGlow > 0.0) {
  vec4 sp = splashField(vOcWorld);
  float core = exp(-sp.z * sp.z / 2.0);
  float rings = sp.x * pow(max(sin(sp.y), 0.0), 6.0);
  totalEmissiveRadiance += uGlowColor * uGlow * (core * 0.35 + rings * 0.6);
}
if (uPool.z > 0.0) {
  vec2 dp = vOcWorld - uPool.xy;
  totalEmissiveRadiance += uGlowColor * uPool.z * exp(-dot(dp, dp) / (2.0 * uPool.w * uPool.w));
}
`;

export interface OceanOptions {
  mobile: boolean;
  reducedMotion: boolean;
  /** Prefiltered sky, reflected by the water. */
  envMap: THREE.Texture;
}

export class Ocean {
  readonly mesh: THREE.Mesh;
  readonly material: THREE.MeshPhysicalMaterial;
  private readonly uTime: THREE.IUniform<number> = { value: 0 };
  private readonly uChurn: THREE.IUniform<number> = { value: 0 };
  private readonly uSplash: THREE.IUniform<THREE.Vector4> = { value: new THREE.Vector4() };
  private readonly uGlow: THREE.IUniform<number> = { value: 0 };
  private readonly uPool: THREE.IUniform<THREE.Vector4> = { value: new THREE.Vector4() };
  private readonly bump: THREE.DataTexture;
  private readonly reducedMotion: boolean;
  private readonly waveA: THREE.Vector4[];
  private readonly waveB: THREE.Vector2[];

  constructor(opts: OceanOptions) {
    this.reducedMotion = opts.reducedMotion;

    // Fog hides everything past ~100 m, so the plane can stay fairly tight
    // and spend its vertices on wave detail.
    const size = 600;
    const segments = opts.mobile ? 160 : 384;
    const geometry = new THREE.PlaneGeometry(size, size, segments, segments);
    geometry.rotateX(-Math.PI / 2);

    this.bump = makeBumpTexture(128);
    this.bump.wrapS = this.bump.wrapT = THREE.RepeatWrapping;
    this.bump.repeat.set(70, 70);

    this.material = new THREE.MeshPhysicalMaterial({
      color: OCEAN.waterBase,
      // Glossy, but not mirror-smooth: a directional key on a mirror throws
      // hard glints across the whole sea. The clearcoat carries the sheen.
      roughness: 0.2,
      metalness: 0,
      clearcoat: 0.6,
      clearcoatRoughness: 0.14,
      ior: 1.33,
      envMap: opts.envMap,
      envMapIntensity: 0.7,
      normalMap: this.bump,
      normalScale: new THREE.Vector2(0.06, 0.06),
      fog: true,
      // The dive takes the camera through the surface; the underside has to
      // exist, and flipping the side later would recompile mid-transition.
      side: THREE.DoubleSide,
    });

    const waveA = WAVES.map((w) => {
      const theta = THREE.MathUtils.degToRad(DIRECTION_DEG + w.angle);
      const k = (Math.PI * 2) / w.length;
      return new THREE.Vector4(Math.cos(theta), -Math.sin(theta), k, w.amplitude);
    });
    const waveB = WAVES.map((w) => {
      const k = (Math.PI * 2) / w.length;
      return new THREE.Vector2(Math.sqrt(GRAVITY * k) * TIME_SCALE, w.steepness);
    });
    this.waveA = waveA;
    this.waveB = waveB;

    this.material.onBeforeCompile = (shader) => {
      shader.uniforms.uTime = this.uTime;
      shader.uniforms.uWaveA = { value: waveA };
      shader.uniforms.uWaveB = { value: waveB };
      shader.uniforms.uChurn = this.uChurn;
      shader.uniforms.uSplash = this.uSplash;
      shader.uniforms.uGlow = this.uGlow;
      shader.uniforms.uPool = this.uPool;
      shader.uniforms.uGlowColor = { value: new THREE.Color(OCEAN.fishGlow) };
      shader.vertexShader = shader.vertexShader
        .replace(
          '#include <common>',
          `#include <common>\n${SPLASH_CHUNK}\n${WAVE_CHUNK}\nvarying vec2 vOcWorld;`,
        )
        .replace(
          '#include <beginnormal_vertex>',
          `vec3 ocDisp; vec3 ocNormal;
          // World xz, so heightAt() and splash() on the CPU line up.
          vOcWorld = (modelMatrix * vec4(position, 1.0)).xz;
          oceanWave(vOcWorld, ocDisp, ocNormal);
          vec3 objectNormal = ocNormal;
          #ifdef USE_TANGENT
          vec3 objectTangent = vec3(tangent.xyz);
          #endif`,
        )
        .replace('#include <begin_vertex>', 'vec3 transformed = position + ocDisp;');
      shader.fragmentShader = shader.fragmentShader
        .replace('#include <common>', `#include <common>\n${SPLASH_CHUNK}\n${GLOW_CHUNK}`)
        .replace('#include <emissivemap_fragment>', GLOW_FRAG);
    };
    this.material.customProgramCacheKey = () => 'ocean';

    this.mesh = new THREE.Mesh(geometry, this.material);
    this.mesh.frustumCulled = false;
    // Water sits at y = 0; the far edge is well past the fog's far distance.
    this.mesh.position.set(0, 0, -size / 2 + 100);
    this.update(this.reducedMotion ? 10 : 0);
  }

  update(time: number): void {
    if (this.reducedMotion && this.uTime.value !== 0) return;
    this.uTime.value = time;
    // Drift the fine bump slowly across the swell direction.
    this.bump.offset.set(time * 0.012, -time * 0.007);
  }

  /** 0..1: agitate the sea (the shorter waves heap up). */
  set churn(value: number) {
    this.uChurn.value = value;
  }

  /** Punch the surface at world (x, z) now; rings run out from there. */
  splash(x: number, z: number, strength = 1): void {
    this.uSplash.value.set(x, z, this.uTime.value, strength);
  }

  /** 0..1: blue glow at the splash point and along its rings. */
  set glow(value: number) {
    this.uGlow.value = value;
  }

  /**
   * A soft round pool of the fish's light on the water, centred on world
   * (x, z): emissive, so it has no edges and no highlight whatever the
   * angle. Strength 0 turns it off.
   */
  pool(x: number, z: number, strength: number, radius: number): void {
    this.uPool.value.set(x, z, strength, radius);
  }

  /**
   * Surface height above the rest plane at world (x, z), same wave sum as the
   * shader (churn included). Ignores the horizontal Gerstner shift and the
   * splash, so allow a margin.
   */
  heightAt(x: number, z: number): number {
    const t = this.uTime.value;
    const churn = this.uChurn.value;
    let y = 0;
    for (let i = 0; i < this.waveA.length; i++) {
      const a = this.waveA[i];
      const w = this.waveB[i].x;
      const amp = i >= 2 ? a.w * (1 + churn * CHURN_GAIN) : a.w;
      y += amp * Math.sin(a.z * (a.x * x + a.y * z) - w * t);
    }
    return y;
  }

  dispose(): void {
    this.mesh.geometry.dispose();
    this.material.dispose();
    this.bump.dispose();
  }
}

/** Tileable noise normal map for the small-scale water bump. */
function makeBumpTexture(size: number): THREE.DataTexture {
  const height = new Float32Array(size * size);
  // Sum of a few random tileable sines so the texture wraps seamlessly.
  const comps: Array<[number, number, number, number]> = [];
  let seed = 7;
  const rnd = () => {
    seed = (seed * 16807) % 2147483647;
    return seed / 2147483647;
  };
  for (let i = 0; i < 14; i++) {
    comps.push([
      Math.round(1 + rnd() * 6) * (rnd() < 0.5 ? -1 : 1),
      Math.round(1 + rnd() * 6) * (rnd() < 0.5 ? -1 : 1),
      rnd() * Math.PI * 2,
      1 / (1 + i * 0.35),
    ]);
  }
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      let h = 0;
      for (const [fx, fy, ph, amp] of comps) {
        h += amp * Math.sin(((x * fx + y * fy) / size) * Math.PI * 2 + ph);
      }
      height[y * size + x] = h;
    }
  }
  const data = new Uint8Array(size * size * 4);
  const at = (x: number, y: number) =>
    height[((y + size) % size) * size + ((x + size) % size)];
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      const dx = (at(x + 1, y) - at(x - 1, y)) * 0.5;
      const dy = (at(x, y + 1) - at(x, y - 1)) * 0.5;
      const n = new THREE.Vector3(-dx, -dy, 1).normalize();
      const i = (y * size + x) * 4;
      data[i] = Math.round((n.x * 0.5 + 0.5) * 255);
      data[i + 1] = Math.round((n.y * 0.5 + 0.5) * 255);
      data[i + 2] = Math.round((n.z * 0.5 + 0.5) * 255);
      data[i + 3] = 255;
    }
  }
  const tex = new THREE.DataTexture(data, size, size, THREE.RGBAFormat);
  tex.minFilter = THREE.LinearMipmapLinearFilter;
  tex.magFilter = THREE.LinearFilter;
  tex.generateMipmaps = true;
  tex.anisotropy = 8;
  tex.needsUpdate = true;
  return tex;
}
