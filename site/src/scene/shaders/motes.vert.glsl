// Floating motes. Motion is computed here from time and a per-point seed, so
// the buffer is never touched after upload: a slow rise that wraps inside the
// box, a lazy sideways sway, and a twinkle.

uniform float uTime;
uniform float uPixelRatio;
uniform float uHalfHeight;
// Extra rise (world units): the camera sinking makes the motes stream up.
uniform float uScroll;

// x: speed, y: phase, z: sway, w: kind (0 plain, 1 bright, 2 bokeh)
attribute vec4 aSeed;
attribute float aSize;

varying float vAlpha;
varying float vKind;

void main() {
  float t = uTime;
  vec3 p = position;
  p.y = mod(p.y + t * (0.06 + aSeed.x * 0.12) + uScroll + uHalfHeight, 2.0 * uHalfHeight) - uHalfHeight;
  p.x += sin(t * (0.12 + aSeed.z * 0.18) + aSeed.y * 6.2831) * (0.3 + aSeed.z * 0.6);
  p.z += cos(t * 0.09 + aSeed.y * 3.0) * 0.3;

  vec4 mv = modelViewMatrix * vec4(p, 1.0);
  float depth = max(-mv.z, 0.1);

  float twinkle = 0.55 + 0.45 * sin(t * (0.7 + aSeed.x * 1.5) + aSeed.y * 6.2831);
  // Nothing pops right at the lens.
  float nearFade = smoothstep(0.6, 3.0, depth);
  vAlpha = twinkle * nearFade;
  vKind = aSeed.w;

  gl_PointSize = min(aSize * uPixelRatio * (60.0 / depth), 90.0 * uPixelRatio);
  gl_Position = projectionMatrix * mv;
}
