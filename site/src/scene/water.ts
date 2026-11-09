import * as THREE from 'three';
import { createMotes, createRays, type RayLayer } from './deep';

/**
 * The water itself, below the surface: shafts of light leaning down from
 * above and motes drifting in the dark. One instance serves every chapter
 * down here; it rides along with the camera as it sinks (with a little
 * lag, so the shafts drift upward), the motes stream up to sell the
 * descent, and the light thins out with depth.
 */

export interface WaterOptions {
  mobile: boolean;
  pixelRatio: number;
}

const RAY_LAYERS: RayLayer[] = [
  { distance: 16, angle: -0.3, seed: 1.3, intensity: 1.1, anchor: 0.28, anchorWidth: 0.3 },
  { distance: 28, angle: -0.36, seed: 4.7, intensity: 1.0, anchor: -0.22, anchorWidth: 0.26 },
  { distance: 44, angle: -0.24, seed: 8.1, intensity: 0.9, anchor: 0.1, anchorWidth: 0.4 },
];
const MOTE_COUNT = 720;
const MOTE_COUNT_MOBILE = 300;
/** How much of the camera's descent the water follows (the rest is drift). */
const FOLLOW = 0.9;
/** How far the motes stream per unit of descent. */
const MOTE_STREAM = 0.6;
/** Light left at the bottom of the descent range, as a fraction. */
const DIM_FLOOR = 0.55;

export class Water {
  readonly root = new THREE.Group();
  private readonly rays: Array<{ material: THREE.ShaderMaterial; intensity: number }> = [];
  private readonly motes: THREE.ShaderMaterial;
  private readonly geometries: THREE.BufferGeometry[] = [];

  constructor(opts: WaterOptions) {
    const layers = opts.mobile ? RAY_LAYERS.slice(0, 2) : RAY_LAYERS;
    for (const layer of layers) {
      const rays = createRays(layer);
      this.rays.push({ material: rays.material, intensity: layer.intensity });
      this.geometries.push(rays.geometry);
      this.root.add(rays.object);
    }
    const motes = createMotes(opts.mobile ? MOTE_COUNT_MOBILE : MOTE_COUNT, opts.pixelRatio);
    this.motes = motes.material;
    this.geometries.push(motes.geometry);
    this.root.add(motes.object);
  }

  /** Shafts tested against scene depth (so they stop at solid things), or not. */
  set depthTest(on: boolean) {
    for (const r of this.rays) r.material.depthTest = on;
  }

  /**
   * @param time     scene clock
   * @param base     the camera's rest point at the top of the water
   * @param descent  how far the camera has sunk below `base` (>= 0)
   * @param range    the full descent range, for the dimming
   */
  update(time: number, base: THREE.Vector3, descent: number, range: number): void {
    this.root.position.set(base.x, base.y - descent * FOLLOW, base.z);
    const depth = range > 0 ? THREE.MathUtils.clamp(descent / range, 0, 1) : 0;
    const dim = 1 - (1 - DIM_FLOOR) * depth;
    for (const r of this.rays) {
      r.material.uniforms.uTime.value = time;
      r.material.uniforms.uIntensity.value = r.intensity * dim;
    }
    this.motes.uniforms.uTime.value = time;
    this.motes.uniforms.uScroll.value = descent * MOTE_STREAM;
  }

  dispose(): void {
    for (const r of this.rays) r.material.dispose();
    this.motes.dispose();
    for (const g of this.geometries) g.dispose();
  }
}
