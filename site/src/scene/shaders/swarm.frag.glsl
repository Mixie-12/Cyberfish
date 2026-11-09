// Soft additive sprite. Most particles are a cool white speck; a share
// are brighter and whiter so the bloom picks them out as sparkles.

uniform vec3 uColor;
uniform float uBright;

varying float vSeed;
varying float vTwinkle;

void main() {
  vec2 c = gl_PointCoord - 0.5;
  float d2 = dot(c, c);
  if (d2 > 0.25) discard;
  float core = exp(-d2 * 28.0);
  float halo = exp(-d2 * 7.0) * 0.35;
  float a = (core + halo) * vTwinkle;

  // One particle in twelve or so burns hotter and blooms.
  float hot = step(0.92, vSeed);
  vec3 col = mix(uColor, vec3(1.0), hot * 0.6);
  float gain = mix(0.28, 1.6, hot);

  gl_FragColor = vec4(col * a * gain * uBright, 1.0);
}
