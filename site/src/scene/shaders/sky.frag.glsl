// Night sky from assets-src/ocean/ocean_look.json: a four-stop ramp over the
// view direction's height (horizon band -> indigo -> violet zenith) and soft,
// horizontally stretched cirrus that fades out toward the zenith.
// No stars, no moon. Blues and purples only.

uniform vec3 uHorizon;
uniform vec3 uLow;
uniform vec3 uMid;
uniform vec3 uTop;
uniform vec3 uCloudTint;
uniform float uCloudStrength;
uniform float uTime;

varying vec3 vWorldPos;

// --- value-noise fbm ------------------------------------------------------
float hash3(vec3 p) {
  p = fract(p * 0.3183099 + vec3(0.1, 0.2, 0.3));
  p *= 17.0;
  return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}

float vnoise(vec3 p) {
  vec3 i = floor(p);
  vec3 f = fract(p);
  f = f * f * (3.0 - 2.0 * f);
  return mix(
    mix(mix(hash3(i), hash3(i + vec3(1, 0, 0)), f.x),
        mix(hash3(i + vec3(0, 1, 0)), hash3(i + vec3(1, 1, 0)), f.x), f.y),
    mix(mix(hash3(i + vec3(0, 0, 1)), hash3(i + vec3(1, 0, 1)), f.x),
        mix(hash3(i + vec3(0, 1, 1)), hash3(i + vec3(1, 1, 1)), f.x), f.y),
    f.z);
}

float fbm(vec3 p) {
  float v = 0.0;
  float a = 0.5;
  for (int i = 0; i < 5; i++) {
    v += a * vnoise(p);
    p = p * 2.03 + vec3(7.1, 3.7, 1.9);
    a *= 0.5;
  }
  return v;
}

void main() {
  vec3 d = normalize(vWorldPos - cameraPosition);

  // Ramp position: view height -0.01 .. 0.3 maps to 0 .. 1. A tall haze band
  // melts into the deep blue; the zenith is only reached well above the frame.
  float t = clamp((d.y + 0.01) / 0.31, 0.0, 1.0);
  vec3 col = mix(uHorizon, uLow, smoothstep(0.06, 0.26, t));
  col = mix(col, uMid, smoothstep(0.18, 0.6, t));
  col = mix(col, uTop, smoothstep(0.55, 1.0, t));

  // Cirrus: noise stretched along the horizon (vertical axis compressed), with
  // a threshold so it reads as streaks, fading from the horizon to the zenith.
  float h = max(d.y, 0.0);
  vec3 p = vec3(d.x, d.y * 3.2, d.z) * 2.2;
  // Project onto a dome so streaks do not pinch toward the zenith.
  p.xz /= (0.35 + h);
  p.x += uTime * 0.004;
  float n = fbm(p);
  float cloud = smoothstep(0.38, 0.72, n);
  float fade = mix(1.0, 0.15, smoothstep(0.0, 0.55, h));
  col = mix(col, uCloudTint, uCloudStrength * cloud * fade);

  gl_FragColor = vec4(col, 1.0);

  #include <tonemapping_fragment>
  #include <colorspace_fragment>
}
