import * as THREE from 'three';
import vertexShader from './shaders/sky.vert.glsl?raw';
import fragmentShader from './shaders/sky.frag.glsl?raw';
import { OCEAN } from './palette';

/**
 * Night sky dome: ramp + cirrus from the ocean look spec. Also used to build
 * the environment map that the water and the fish shell reflect.
 */
export class Sky {
  readonly mesh: THREE.Mesh;
  private readonly uniforms: { uTime: THREE.IUniform<number> };

  constructor(radius = 800) {
    const geometry = new THREE.SphereGeometry(radius, 48, 24);
    const uTime = { value: 0 };
    this.uniforms = { uTime };
    const material = new THREE.ShaderMaterial({
      vertexShader,
      fragmentShader,
      side: THREE.BackSide,
      depthWrite: false,
      fog: false,
      uniforms: {
        uHorizon: { value: OCEAN.skyHorizon.clone() },
        uLow: { value: OCEAN.skyLow.clone() },
        uMid: { value: OCEAN.skyMid.clone() },
        uTop: { value: OCEAN.skyTop.clone() },
        uCloudTint: { value: OCEAN.skyCloudTint.clone() },
        uCloudStrength: { value: 0.35 },
        uTime,
      },
    });
    this.mesh = new THREE.Mesh(geometry, material);
    this.mesh.frustumCulled = false;
    this.mesh.renderOrder = -1;
  }

  update(time: number): void {
    this.uniforms.uTime.value = time;
  }

  /** Keep the dome centred on the camera so the gradient never shifts. */
  follow(camera: THREE.Camera): void {
    this.mesh.position.copy(camera.position);
  }

  /**
   * Prefiltered environment of this sky, for reflections. Rendered once; the
   * cirrus drift is far too slow to matter in a blurred reflection.
   */
  buildEnvironment(renderer: THREE.WebGLRenderer): THREE.Texture {
    const pmrem = new THREE.PMREMGenerator(renderer);
    const envScene = new THREE.Scene();
    const dome = new THREE.Mesh(this.mesh.geometry, this.mesh.material);
    envScene.add(dome);
    const texture = pmrem.fromScene(envScene, 0, 0.1, 2000).texture;
    pmrem.dispose();
    return texture;
  }
}
