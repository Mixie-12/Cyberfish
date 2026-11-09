// Seabed: a flat plane lifted into dunes by value noise, with fine sand
// ripples on top. World position goes to the fragment stage, which takes
// its normal from screen-space derivatives (so no normal attribute).

uniform float uFloorY;

varying vec3 vWorld;
varying float vHeight;

float hash(vec2 p) {
  vec3 p3 = fract(vec3(p.xyx) * 0.1031);
  p3 += dot(p3, p3.yzx + 33.33);
  return fract((p3.x + p3.y) * p3.z);
}

float noise(vec2 p) {
  vec2 i = floor(p);
  vec2 f = fract(p);
  vec2 u = f * f * (3.0 - 2.0 * f);
  return mix(
    mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x),
    mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x),
    u.y
  );
}

float fbm(vec2 p) {
  float v = 0.0;
  float a = 0.5;
  for (int i = 0; i < 5; i++) {
    v += a * noise(p);
    p = p * 2.03 + vec2(17.1, 9.7);
    a *= 0.5;
  }
  return v;
}

void main() {
  vec3 p = position;
  vec2 xz = (modelMatrix * vec4(p, 1.0)).xz;
  // Dunes: broad swells plus a sharper ridge term.
  float dunes = fbm(xz * 0.035) * 2.4 - 1.2;
  float ridges = (1.0 - abs(fbm(xz * 0.09 + 31.0) * 2.0 - 1.0)) * 0.6;
  // Sand ripples: a soft wave across the swell direction, slightly bent.
  float ripple = sin(xz.x * 1.6 + xz.y * 0.9 + fbm(xz * 0.3) * 4.0) * 0.045;
  float h = dunes + ridges + ripple;
  p.y += h;
  vec4 world = modelMatrix * vec4(p, 1.0);
  vWorld = world.xyz;
  vHeight = h;
  gl_Position = projectionMatrix * viewMatrix * world;
}
