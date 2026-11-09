// Soft round mote. Plain ones are a small core with a faint halo; bright
// ones are pushed past the bloom threshold so they sparkle; bokeh ones are
// big, dim discs for depth close to the lens. Additive, so colour carries
// the alpha.

uniform vec3 uColor;

varying float vAlpha;
varying float vKind;

void main() {
  vec2 c = gl_PointCoord - 0.5;
  float d = length(c) * 2.0;
  if (d > 1.0) discard;

  vec3 col = uColor;
  float a;
  if (vKind > 1.5) {
    a = exp(-d * d * 2.2) * 0.06;
  } else {
    float core = exp(-d * d * 7.0);
    float halo = exp(-d * d * 1.8) * 0.3;
    a = core + halo;
    if (vKind > 0.5) {
      a *= 2.4;
      col = mix(uColor, vec3(1.0), 0.6);
    }
  }
  gl_FragColor = vec4(col * a * vAlpha, 1.0);
}
