import * as THREE from 'three';
import { EffectComposer } from 'three/addons/postprocessing/EffectComposer.js';
import { RenderPass } from 'three/addons/postprocessing/RenderPass.js';
import { UnrealBloomPass } from 'three/addons/postprocessing/UnrealBloomPass.js';
import { ShaderPass } from 'three/addons/postprocessing/ShaderPass.js';
import { OutputPass } from 'three/addons/postprocessing/OutputPass.js';
import grainFrag from './shaders/grain.frag.glsl?raw';

const GRAIN_VERT = /* glsl */ `
varying vec2 vUv;
void main() {
  vUv = uv;
  gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
}
`;

export interface PostOptions {
  mobile: boolean;
  reducedMotion: boolean;
}

/**
 * Render chain: scene -> bloom (traces and glow only) -> ACES + sRGB output
 * -> film grain and vignette in display space.
 */
export class Post {
  readonly composer: EffectComposer;
  private readonly bloom: UnrealBloomPass;
  private readonly grain: ShaderPass;

  constructor(
    renderer: THREE.WebGLRenderer,
    scene: THREE.Scene,
    camera: THREE.Camera,
    opts: PostOptions,
  ) {
    const size = renderer.getSize(new THREE.Vector2());
    const pr = renderer.getPixelRatio();

    const target = new THREE.WebGLRenderTarget(size.x * pr, size.y * pr, {
      type: THREE.HalfFloatType,
      samples: opts.mobile ? 0 : 4,
    });
    this.composer = new EffectComposer(renderer, target);
    this.composer.setPixelRatio(pr);
    this.composer.setSize(size.x, size.y);

    this.composer.addPass(new RenderPass(scene, camera));

    // Only the emissive circuit traces and eye rings should bloom, not the
    // white shell. UnrealBloomPass halves the given resolution for its first
    // mip, so pass the full size: a quarter-res first mip makes thin traces
    // and the tiny eye rings twinkle as the fish moves sub-pixel.
    this.bloom = new UnrealBloomPass(
      new THREE.Vector2(Math.max(1, size.x), Math.max(1, size.y)),
      0.4, // strength
      0.6, // radius
      0.9, // threshold
    );
    // Soft knee instead of the default near-hard cut (0.01): pixels hovering
    // around the threshold no longer flip in and out of the bloom each frame.
    const highPass = this.bloom.highPassUniforms as Record<string, THREE.IUniform<number>>;
    highPass.smoothWidth.value = 0.5;
    this.composer.addPass(this.bloom);

    this.composer.addPass(new OutputPass());

    this.grain = new ShaderPass({
      uniforms: {
        tDiffuse: { value: null },
        uGrain: { value: 0.07 },
        uGrainSize: { value: pr },
        uVignette: { value: 0.45 },
        uDiveTime: { value: 0 },
        uWobble: { value: 0 },
        uFlash: { value: 0 },
        uDeep: { value: 0 },
        uDark: { value: 0 },
      },
      vertexShader: GRAIN_VERT,
      fragmentShader: grainFrag,
    });
    this.composer.addPass(this.grain);
  }

  setSize(width: number, height: number): void {
    this.composer.setSize(width, height);
    this.bloom.setSize(Math.max(1, width), Math.max(1, height));
  }

  /** Dive transition state, all 0..1 except the clock. */
  setDive(d: { time: number; wobble: number; flash: number; deep: number; dark: number }): void {
    const u = this.grain.uniforms;
    u.uDiveTime.value = d.time;
    u.uWobble.value = d.wobble;
    u.uFlash.value = d.flash;
    u.uDeep.value = d.deep;
    u.uDark.value = d.dark;
  }

  render(): void {
    // The grain is static: a fixed texture over the picture, not a flicker.
    this.composer.render();
  }

  dispose(): void {
    this.composer.dispose();
  }
}
