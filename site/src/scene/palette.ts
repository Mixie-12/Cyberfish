import * as THREE from 'three';

/**
 * Brand colours as three.js Colors. Hex values are sRGB and three converts
 * them to linear on construction, so shaders receive linear light.
 * Source: reference/BRAND.md
 */
export const COLORS = {
  bg: new THREE.Color('#060a14'),
  accent: new THREE.Color('#2EA8FF'),
  fishBody: new THREE.Color('#e8edf5'),
  fishFin: new THREE.Color('#dbe6f5'),
  /** Cool key light. */
  light: new THREE.Color('#b8ccff'),
  /** Hemisphere lighting for the hero asset. Not brand tokens; lighting only. */
  hemiSky: new THREE.Color('#8691a8'),
  hemiGround: new THREE.Color('#1c2a52'),
};

/**
 * Ocean hero look. Structure from assets-src/ocean/ocean_look.json; colours
 * retuned to a cold navy palette (deep blue sky, grey-blue haze, near-black
 * water). Blues only: no purple, green, yellow, orange or red anywhere.
 */
export const OCEAN = {
  /** Sky ramp, horizon haze band -> zenith. */
  /** Identical to waterFog so the horizon dissolves instead of drawing a line. */
  skyHorizon: new THREE.Color('#5b6b92'),
  skyLow: new THREE.Color('#3b4f82'),
  skyMid: new THREE.Color('#1d3878'),
  skyTop: new THREE.Color('#0f2866'),
  skyCloudTint: new THREE.Color('#7585a8'),
  /** Water albedo: near-black navy, the sky reflection does the rest. */
  waterBase: new THREE.Color('#040a18'),
  /** Water-only linear fog toward the haze band. Short line of sight. */
  waterFog: new THREE.Color('#5b6b92'),
  /** The two point lights at the fish: the only neon in the scene. */
  fishGlow: new THREE.Color('#95ceff'),
  /** Big soft overhead fill. */
  fill: new THREE.Color('#9fb3dc'),
  /** Cool white key from the camera side. */
  key: new THREE.Color('#d9e6ff'),
};

export const WATER_FOG_NEAR = 18;
export const WATER_FOG_FAR = 140;

/** Direction toward the key light: above, camera side, a little to the left. */
export const FISH_KEY_DIR = new THREE.Vector3(-0.3, 0.45, 0.85).normalize();
