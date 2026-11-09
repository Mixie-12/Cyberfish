// The mark as particles. Positions come from the CPU simulation (rest
// shape plus whatever the cursor has stirred up); this adds each grain's
// own slow wander so the cloud never sits still, and sizes the sprites.

attribute vec3 aSeed;   // x: random, y: size, z: phase

uniform float uTime;
uniform float uPixelRatio;

varying float vSeed;
varying float vTwinkle;

void main() {
  vec3 p = position;
  // Two drifts per grain at unrelated rates: a slow loop and a quicker
  // flutter, each with its own phase, so neighbours never move together.
  float ph = aSeed.z * 6.2831;
  float t1 = uTime * (0.35 + aSeed.x * 0.4) + ph;
  float t2 = uTime * (1.1 + aSeed.z * 0.9) + ph * 1.7;
  p += vec3(sin(t1), cos(t1 * 0.8 + 1.3), sin(t1 * 0.6 + 2.1)) * 0.05;
  p += vec3(cos(t2 * 1.3), sin(t2), cos(t2 * 0.7 + 0.6)) * 0.018;

  vec4 mv = modelViewMatrix * vec4(p, 1.0);
  gl_Position = projectionMatrix * mv;
  float depth = max(-mv.z, 0.1);
  gl_PointSize = min(aSeed.y * uPixelRatio * 30.0 / depth, 32.0 * uPixelRatio);

  vSeed = aSeed.x;
  vTwinkle = 0.75 + 0.25 * sin(uTime * 2.3 + ph);
}
