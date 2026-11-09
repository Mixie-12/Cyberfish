// Underwater light shafts. One big additive plane per depth layer; the
// fragment paints soft, slowly drifting beams leaning off vertical, bright
// toward the light above and dying out below. A large-scale mask keeps most
// of the plane dark so the shafts gather in one or two regions.

uniform float uTime;
uniform float uSeed;
uniform float uAngle;      // lean of the shafts from vertical (rad)
uniform float uScale;      // across-shaft frequency (1 / world units)
uniform float uIntensity;
uniform vec2 uSize;        // plane size in world units
uniform float uAnchor;     // across-shaft centre of a band that is always lit
uniform float uAnchorWidth;
uniform vec3 uColorDeep;
uniform vec3 uColorBright;

varying vec2 vP;
varying vec2 vUv;

float hash1(float n) {
  return fract(sin(n) * 43758.5453123);
}

float hash2(vec2 p) {
  vec3 p3 = fract(vec3(p.xyx) * 0.1031);
  p3 += dot(p3, p3.yzx + 33.33);
  return fract((p3.x + p3.y) * p3.z);
}

float noise1(float x) {
  float i = floor(x);
  float f = fract(x);
  f = f * f * (3.0 - 2.0 * f);
  return mix(hash1(i), hash1(i + 1.0), f);
}

float noise2(vec2 p) {
  vec2 i = floor(p);
  vec2 f = fract(p);
  f = f * f * (3.0 - 2.0 * f);
  float a = hash2(i);
  float b = hash2(i + vec2(1.0, 0.0));
  float c = hash2(i + vec2(0.0, 1.0));
  float d = hash2(i + vec2(1.0, 1.0));
  return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

float fbm2(vec2 p) {
  float v = 0.0;
  float a = 0.5;
  for (int i = 0; i < 4; i++) {
    v += a * noise2(p);
    p = p * 2.03 + 17.1;
    a *= 0.5;
  }
  return v;
}

void main() {
  float t = uTime;
  float ca = cos(uAngle);
  float sa = sin(uAngle);
  // u runs across the shafts, v along them (+v toward the light).
  float u = vP.x * ca + vP.y * sa;
  float v = -vP.x * sa + vP.y * ca;

  // Where there are shafts at all. A fixed soft band per layer sets the
  // envelope, so the frame always carries light in the same places and
  // never more than this; a slow, very large-scale noise only varies it
  // inside that envelope, with a faint trace of shafts elsewhere.
  float mask = fbm2(vec2(u * 0.02, v * 0.007) + uSeed * 3.7 + vec2(t * 0.003, 0.0));
  mask = smoothstep(0.35, 0.7, mask);
  float du = (u - uAnchor) / uAnchorWidth;
  float anchor = exp(-du * du);
  float region = anchor * (0.65 + 0.35 * mask) + 0.08 * mask;

  // Shafts across u: a broad band broken up by finer beams, drifting and
  // bending slowly along v so they waver like light through a moving surface.
  float bend = noise2(vec2(v * 0.025 + uSeed, t * 0.025)) - 0.5;
  float x = u * uScale + bend * 1.6 + t * 0.012;
  // Floored so a lit band always carries some beams however the noise lands.
  float broad = 0.15 + 0.85 * noise1(x * 0.5 + uSeed * 7.0);
  float mid = noise1(x * 1.7 - t * 0.008 + uSeed * 3.0);
  float fine = noise1(x * 5.0 + t * 0.02 + uSeed * 11.0);
  float shafts = broad * (0.45 + 0.55 * mid) * (0.65 + 0.35 * fine);
  shafts = pow(max(shafts, 0.0), 3.0) * 1.6;

  // Along v: full toward the top, dying out before the lower frame; each
  // shaft reaches a different depth.
  float reach = 0.05 + 0.25 * noise1(x * 0.5 + 3.1 + uSeed);
  float along = smoothstep(-uSize.y * reach, uSize.y * 0.05, v);
  along *= 1.0 - smoothstep(uSize.y * 0.25, uSize.y * 0.5, v);

  // A faint haze hugging the brightest shafts.
  float haze = pow(broad, 3.0) * 0.12 * along;

  // Fade the plane's own edges so the rectangle never shows.
  vec2 e = smoothstep(0.0, 0.18, vUv) * smoothstep(0.0, 0.18, 1.0 - vUv);
  float edge = e.x * e.y;

  float I = (shafts * along + haze) * region * edge * uIntensity;
  vec3 col = mix(uColorDeep, uColorBright, clamp(shafts * 1.4, 0.0, 1.0)) * I;
  gl_FragColor = vec4(col, 1.0);
}
