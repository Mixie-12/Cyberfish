import * as THREE from 'three';
import { createMotes, createRays } from './deep';
import floorVert from './shaders/floor.vert.glsl?raw';
import terrainVert from './shaders/terrain.vert.glsl?raw';
import floorFrag from './shaders/floor.frag.glsl?raw';
import swarmVert from './shaders/swarm.vert.glsl?raw';
import swarmFrag from './shaders/swarm.frag.glsl?raw';

/**
 * The floor: the last chapter, down on the seabed. Dunes of dim sand under
 * a pool of caustics, rocks framing the view, one shaft of light from far
 * above, motes, and the CyberFish mark hanging in the water as a cloud of
 * particles that swirl away from the cursor and drift back into shape.
 *
 * The seabed is the Blender mesh from assets-src/seafloor (terrain plus
 * rocks, modelled in this chapter's space), shaded here. Until it has
 * loaded, a procedural plane of dunes stands in.
 *
 * Laid out like the deep: `root` sits where the camera rests, the camera
 * looks down -Z (pitched down a little by `lookOffset`).
 */

export interface FloorOptions {
  mobile: boolean;
  pixelRatio: number;
  /** The mark's points (loadLogoPoints), or null to attach them later with setCloud(). */
  cloud: Float32Array | null;
  /** The seabed mesh (the GLB's scene), or null to attach it later with setTerrain(). */
  terrain: THREE.Object3D | null;
}

/** Camera height above the mean sand level, and where the lens aims. */
const FLOOR_Y = -5.2;
const LOOK_OFFSET = new THREE.Vector3(0, -0.9, -10);
const FLOOR_SIZE = 260;
const FLOOR_SEGMENTS = 320;
const FLOOR_SEGMENTS_MOBILE = 160;
/** The floor plane's centre: pushed ahead so it runs out past the fog. */
const FLOOR_CENTRE_Z = -70;

const SAND = new THREE.Color('#0b1222');
const SAND_LIT = new THREE.Color('#44587c');
/** The rocks: a shade greyer and darker than the sand, lit the same way. */
const ROCK = new THREE.Color('#0a0e18');
const ROCK_LIT = new THREE.Color('#3d4c66');
const CAUSTIC = new THREE.Color('#9fd2ff');
const FOG = new THREE.Color('#02050c');
const LIGHT_DIR = new THREE.Vector3(0.35, 1, 0.25);
/** The light pool on the sand (xz relative to root) and its radius. */
const POOL = new THREE.Vector2(9, -21);
const POOL_RADIUS = 9;

/** One shaft, leaning in from the upper right where the pool is. */
const RAY = { distance: 30, angle: -0.32, seed: 2.9, intensity: 0.5, anchor: 0.3, anchorWidth: 0.24 };
const MOTE_COUNT = 420;
const MOTE_COUNT_MOBILE = 180;

/** The mark: where it hangs (facing the lens) and its colour. */
const MARK_POS = new THREE.Vector3(0, -0.3, -11);
const MARK_COLOR = new THREE.Color('#a9d6ff');

/**
 * Particle dynamics, in world units. A soft spring draws each particle
 * back to its place in the mark, under-damped so the cloud eases home
 * rather than snapping; the cursor ray pushes particles out of a tube
 * around it and swirls them round it, so the break-up flows like water.
 */
const SPRING = 5;
const DAMPING = 2.4;
const STIR_RADIUS = 0.7;
const STIR_FORCE = 12;
const SWIRL = 0.7;
/** Cap on how far any particle can be flung from its rest point. */
const STIR_REACH = 1.6;
/** How many particles the ray must be pushing for it to count as touching the mark. */
const STIR_TOUCH = 12;

const _m = new THREE.Matrix4();
const _q = new THREE.Quaternion();
const _up = new THREE.Vector3(0, 1, 0);
const _ray = new THREE.Ray();

export class Floor {
  readonly root = new THREE.Group();
  /** Where the camera aims, relative to its rest point. */
  readonly lookOffset = LOOK_OFFSET;

  private readonly materials: THREE.ShaderMaterial[] = [];
  private readonly geometries: THREE.BufferGeometry[] = [];
  /** The seabed materials (plane, terrain, rocks): all want the camera each frame. */
  private readonly seabedMaterials: THREE.ShaderMaterial[] = [];
  /** The procedural dunes, shown until the mesh arrives. */
  private readonly plane: THREE.Mesh;
  private terrain: THREE.Object3D | null = null;
  private swarm: Swarm | null = null;
  private readonly pixelRatio: number;
  private readonly markGroup = new THREE.Group();

  constructor(opts: FloorOptions) {
    // Seabed: the procedural plane first, the mesh over it when it is here.
    const segments = opts.mobile ? FLOOR_SEGMENTS_MOBILE : FLOOR_SEGMENTS;
    const geometry = new THREE.PlaneGeometry(FLOOR_SIZE, FLOOR_SIZE, segments, segments);
    geometry.rotateX(-Math.PI / 2);
    this.geometries.push(geometry);
    this.plane = new THREE.Mesh(geometry, this.seabedMaterial(floorVert, SAND, SAND_LIT, false));
    this.plane.position.set(0, FLOOR_Y, FLOOR_CENTRE_Z);
    this.plane.frustumCulled = false;
    this.root.add(this.plane);
    if (opts.terrain) this.setTerrain(opts.terrain);

    // Light from above, and the water's drift.
    const rays = createRays(RAY);
    // Light in the water in front of the sand: nothing here should cut it.
    rays.material.depthTest = false;
    this.materials.push(rays.material);
    this.geometries.push(rays.geometry);
    this.root.add(rays.object);
    const motes = createMotes(opts.mobile ? MOTE_COUNT_MOBILE : MOTE_COUNT, opts.pixelRatio);
    this.materials.push(motes.material);
    this.geometries.push(motes.geometry);
    this.root.add(motes.object);

    // The mark.
    this.pixelRatio = opts.pixelRatio;
    this.markGroup.position.copy(MARK_POS);
    this.root.add(this.markGroup);
    if (opts.cloud) this.setCloud(opts.cloud);
  }

  /**
   * Put the seabed mesh in place of the procedural plane (once; later calls
   * are ignored). The GLB is modelled in this chapter's space, so it drops
   * straight in; its meshes are dressed in the floor shader: the terrain
   * (`SF_Terrain`) shaded smoothly from its normals, everything else (the
   * rocks) faceted. The mesh is shared with later visits, so its geometry is
   * left alone; only the materials made here are disposed with the chapter.
   */
  setTerrain(terrain: THREE.Object3D): void {
    if (this.terrain) return;
    this.terrain = terrain;
    const sand = this.seabedMaterial(terrainVert, SAND, SAND_LIT, true);
    const rock = this.seabedMaterial(terrainVert, ROCK, ROCK_LIT, false);
    terrain.traverse((o) => {
      if (!(o instanceof THREE.Mesh)) return;
      o.material = o.name.startsWith('SF_Terrain') ? sand : rock;
    });
    this.root.remove(this.plane);
    this.root.add(terrain);
  }

  private seabedMaterial(
    vertexShader: string,
    dark: THREE.Color,
    lit: THREE.Color,
    smooth: boolean,
  ): THREE.ShaderMaterial {
    const material = new THREE.ShaderMaterial({
      uniforms: {
        uTime: { value: 0 },
        uFloorY: { value: FLOOR_Y },
        uSand: { value: dark.clone() },
        uSandLit: { value: lit.clone() },
        uCaustic: { value: CAUSTIC.clone() },
        uFog: { value: FOG.clone() },
        uLightDir: { value: LIGHT_DIR.clone() },
        uPool: { value: POOL.clone() },
        uPoolRadius: { value: POOL_RADIUS },
        uCamera: { value: new THREE.Vector3() },
        uOrigin: { value: new THREE.Vector3() },
      },
      defines: smooth ? { SMOOTH_NORMAL: '' } : {},
      vertexShader,
      fragmentShader: floorFrag,
      // Fades out with distance (alpha), so no far edge against the water.
      transparent: true,
    });
    this.materials.push(material);
    this.seabedMaterials.push(material);
    return material;
  }

  /** Hang the mark's points in the water (once; later calls are ignored). */
  setCloud(points: Float32Array): void {
    if (this.swarm) return;
    this.swarm = new Swarm(points, this.pixelRatio);
    this.materials.push(this.swarm.material);
    this.geometries.push(this.swarm.geometry);
    this.markGroup.add(this.swarm.points);
  }

  /**
   * @param time    scene clock
   * @param dt      frame delta
   * @param camera  for the floor's distance fade
   * @param ray     the pointer's ray in world space, or null when there is none
   * @returns whether the ray is disturbing the mark this frame
   */
  update(time: number, dt: number, camera: THREE.Camera, ray: THREE.Ray | null): boolean {
    for (const m of this.materials) if (m.uniforms.uTime) m.uniforms.uTime.value = time;
    for (const m of this.seabedMaterials) {
      m.uniforms.uCamera.value.copy(camera.position);
      this.root.getWorldPosition(m.uniforms.uOrigin.value as THREE.Vector3);
    }

    // The mark holds station: a slow drift and the gentlest turn.
    this.markGroup.position.set(
      MARK_POS.x + Math.sin(time * 0.31) * 0.16,
      MARK_POS.y + Math.sin(time * 0.47 + 1.2) * 0.12,
      MARK_POS.z + Math.sin(time * 0.23 + 2.5) * 0.1,
    );
    _q.setFromAxisAngle(_up, Math.sin(time * 0.19) * 0.09);
    this.markGroup.quaternion.copy(_q);

    if (!this.swarm) return false;
    let local: THREE.Ray | null = null;
    if (ray) {
      this.markGroup.updateWorldMatrix(true, false);
      _m.copy(this.markGroup.matrixWorld).invert();
      local = _ray.copy(ray).applyMatrix4(_m);
    }
    return this.swarm.update(dt, local) >= STIR_TOUCH;
  }

  dispose(): void {
    // The seabed mesh is kept for the next visit.
    if (this.terrain) this.root.remove(this.terrain);
    for (const m of this.materials) m.dispose();
    for (const g of this.geometries) g.dispose();
  }
}

/** The particle mark: a rest shape, and a spring simulation on the CPU. */
class Swarm {
  readonly points: THREE.Points;
  readonly material: THREE.ShaderMaterial;
  readonly geometry: THREE.BufferGeometry;
  private readonly rest: Float32Array;
  private readonly pos: Float32Array;
  private readonly vel: Float32Array;
  /** Per-particle response to the stir (0.6..1.4), so the cloud breaks unevenly. */
  private readonly kick: Float32Array;
  private readonly attr: THREE.BufferAttribute;
  /** Whether anything is still moving (skips the upload when settled). */
  private live = true;

  constructor(rest: Float32Array, pixelRatio: number) {
    const n = rest.length / 3;
    this.rest = rest;
    this.pos = new Float32Array(rest);
    this.vel = new Float32Array(rest.length);
    this.kick = new Float32Array(n);
    const seeds = new Float32Array(n * 3);
    for (let i = 0; i < n; i++) {
      this.kick[i] = 0.5 + Math.random() * 1.0;
      seeds[i * 3] = Math.random();
      // Sizes: mostly fine dust, a long tail of larger grains, and a few
      // big soft ones, so the mark reads as uneven, hand-strewn.
      const r = Math.random();
      let size = 0.55 + Math.pow(r, 2.2) * 2.2;
      if (Math.random() < 0.035) size += 1.4 + Math.random() * 1.8;
      seeds[i * 3 + 1] = size;
      seeds[i * 3 + 2] = Math.random();
    }
    this.geometry = new THREE.BufferGeometry();
    this.attr = new THREE.BufferAttribute(this.pos, 3);
    this.attr.setUsage(THREE.DynamicDrawUsage);
    this.geometry.setAttribute('position', this.attr);
    this.geometry.setAttribute('aSeed', new THREE.BufferAttribute(seeds, 3));
    this.material = new THREE.ShaderMaterial({
      uniforms: {
        uTime: { value: 0 },
        uPixelRatio: { value: pixelRatio },
        uColor: { value: MARK_COLOR.clone() },
        uBright: { value: 1 },
      },
      vertexShader: swarmVert,
      fragmentShader: swarmFrag,
      transparent: true,
      depthWrite: false,
      depthTest: true,
      blending: THREE.CustomBlending,
      blendSrc: THREE.OneFactor,
      blendDst: THREE.OneFactor,
    });
    this.points = new THREE.Points(this.geometry, this.material);
    this.points.frustumCulled = false;
    this.points.renderOrder = 3;
  }

  /**
   * `ray` is in the swarm's local space, or null for no cursor.
   * @returns how many particles the ray pushed this frame
   */
  update(dt: number, ray: THREE.Ray | null): number {
    const h = Math.min(dt, 1 / 30);
    const { rest, pos, vel, kick } = this;
    const n = kick.length;
    let touched = 0;
    const ox = ray?.origin.x ?? 0;
    const oy = ray?.origin.y ?? 0;
    const oz = ray?.origin.z ?? 0;
    const dx = ray?.direction.x ?? 0;
    const dy = ray?.direction.y ?? 0;
    const dz = ray?.direction.z ?? 0;
    const r2 = STIR_RADIUS * STIR_RADIUS;
    const reach2 = STIR_REACH * STIR_REACH;
    let energy = 0;

    for (let i = 0; i < n; i++) {
      const k = i * 3;
      const px = pos[k];
      const py = pos[k + 1];
      const pz = pos[k + 2];
      // Spring back toward the surface, with drag.
      let ax = -SPRING * (px - rest[k]) - DAMPING * vel[k];
      let ay = -SPRING * (py - rest[k + 1]) - DAMPING * vel[k + 1];
      let az = -SPRING * (pz - rest[k + 2]) - DAMPING * vel[k + 2];

      if (ray) {
        // Distance from the particle to the ray; push out of the tube with
        // a smooth falloff, and swirl round it (radial x ray direction), so
        // the cloud parts and eddies instead of bursting.
        const t = (px - ox) * dx + (py - oy) * dy + (pz - oz) * dz;
        if (t > 0) {
          const rx = px - (ox + dx * t);
          const ry = py - (oy + dy * t);
          const rz = pz - (oz + dz * t);
          const d2 = rx * rx + ry * ry + rz * rz;
          if (d2 < r2) {
            touched++;
            const d = Math.sqrt(d2) + 1e-4;
            const u = 1 - d / STIR_RADIUS;
            const fall = u * u * (3 - 2 * u);
            const f = (STIR_FORCE * kick[i] * fall) / d;
            const sx = ry * dz - rz * dy;
            const sy = rz * dx - rx * dz;
            const sz = rx * dy - ry * dx;
            ax += rx * f + sx * f * SWIRL;
            ay += ry * f + sy * f * SWIRL;
            az += rz * f + sz * f * SWIRL;
          }
        }
      }

      let vx = vel[k] + ax * h;
      let vy = vel[k + 1] + ay * h;
      let vz = vel[k + 2] + az * h;
      let nx = px + vx * h;
      let ny = py + vy * h;
      let nz = pz + vz * h;
      // Keep the cloud within reach of its shape.
      const ex = nx - rest[k];
      const ey = ny - rest[k + 1];
      const ez = nz - rest[k + 2];
      const e2 = ex * ex + ey * ey + ez * ez;
      if (e2 > reach2) {
        const s = STIR_REACH / Math.sqrt(e2);
        nx = rest[k] + ex * s;
        ny = rest[k + 1] + ey * s;
        nz = rest[k + 2] + ez * s;
        vx *= 0.5;
        vy *= 0.5;
        vz *= 0.5;
      }
      vel[k] = vx;
      vel[k + 1] = vy;
      vel[k + 2] = vz;
      pos[k] = nx;
      pos[k + 1] = ny;
      pos[k + 2] = nz;
      energy += vx * vx + vy * vy + vz * vz;
    }

    // Upload while anything moves (and once more as it settles).
    const moving = energy > 1e-6 * n;
    if (moving || this.live) this.attr.needsUpdate = true;
    this.live = moving;
    return touched;
  }
}
