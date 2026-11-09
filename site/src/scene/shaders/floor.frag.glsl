// Seabed shading: dim cool sand lit from above, a pool of caustics where
// the light from the surface reaches down, and darkness with distance.

uniform float uTime;
uniform vec3 uSand;
uniform vec3 uSandLit;
uniform vec3 uCaustic;
uniform vec3 uFog;
uniform vec3 uLightDir;
// Where the light pool sits on the floor (world xz) and its radius.
uniform vec2 uPool;
uniform float uPoolRadius;
uniform vec3 uCamera;
// The chapter's origin in world space (uPool is relative to it).
uniform vec3 uOrigin;

varying vec3 vWorld;
varying float vHeight;
#ifdef SMOOTH_NORMAL
varying vec3 vNormal;
#endif

vec2 hash2(vec2 p) {
  vec3 p3 = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973));
  p3 += dot(p3, p3.yzx + 33.33);
  return fract((p3.xx + p3.yz) * p3.zy);
}

// Distance from the second-nearest to the nearest cell point: thin where
// cells meet, which is where refracted light piles up.
float cells(vec2 p, float t) {
  vec2 n = floor(p);
  vec2 f = fract(p);
  float f1 = 8.0;
  float f2 = 8.0;
  for (int j = -1; j <= 1; j++) {
    for (int i = -1; i <= 1; i++) {
      vec2 g = vec2(float(i), float(j));
      vec2 o = hash2(n + g);
      o = 0.5 + 0.45 * sin(t + 6.2831 * o);
      vec2 r = g + o - f;
      float d = dot(r, r);
      if (d < f1) {
        f2 = f1;
        f1 = d;
      } else if (d < f2) {
        f2 = d;
      }
    }
  }
  return sqrt(f2) - sqrt(f1);
}

float caustics(vec2 p, float t) {
  float a = 1.0 - smoothstep(0.0, 0.38, cells(p * 0.85, t * 0.5));
  float b = 1.0 - smoothstep(0.0, 0.34, cells(p * 1.7 + 37.0, t * 0.7 + 2.0));
  return pow(a, 2.2) * 0.7 + pow(b, 2.4) * 0.4;
}

void main() {
#ifdef SMOOTH_NORMAL
  // The mesh's own normal (the dunes shade smoothly).
  vec3 n = normalize(vNormal);
#else
  // Geometric normal from the world position's screen derivatives
  // (the procedural plane, and the rocks' facets).
  vec3 n = normalize(cross(dFdx(vWorld), dFdy(vWorld)));
  if (n.y < 0.0) n = -n;
#endif

  float ndl = max(dot(n, normalize(uLightDir)), 0.0);
  // A soft wrap so the troughs are not pure black.
  float light = 0.15 + 0.85 * ndl;
  // Higher ground is a touch paler, like dry ridges on the dunes.
  float pale = smoothstep(-0.8, 1.4, vHeight);

  // The light pool: the sand is lit, and the caustics play, where the light
  // from above reaches; elsewhere it falls to the dark.
  vec2 local = vWorld.xz - uOrigin.xz;
  float dp = length(local - uPool) / uPoolRadius;
  float pool = exp(-dp * dp * 1.4);
  vec3 col = mix(uSand, uSandLit, light * (0.45 + 0.55 * pale) * (0.35 + 0.5 * pool));
  float c = caustics(local, uTime) * (0.004 + 0.16 * pool) * (0.4 + 0.6 * ndl);
  col += uCaustic * c;

  // Fade into the water with distance from the lens: darken, then let the
  // water behind show through so the far edge never draws a line. Long
  // enough that the far dunes and rocks still show as a horizon.
  float dist = length(vWorld - uCamera);
  float fog = smoothstep(12.0, 80.0, dist);
  col = mix(col, uFog, fog * 0.7);

  gl_FragColor = vec4(col, 1.0 - fog);
}
