// Seabed from the Blender mesh (assets-src/seafloor): the dunes and rocks
// already carry their height, so this only hands the fragment stage the
// world position, the height above the mean sand level, and the normal.

uniform float uFloorY;

varying vec3 vWorld;
varying float vHeight;
varying vec3 vNormal;

void main() {
  vec4 world = modelMatrix * vec4(position, 1.0);
  vWorld = world.xyz;
  vHeight = world.y - uFloorY;
  vNormal = normalize(mat3(modelMatrix) * normal);
  gl_Position = projectionMatrix * viewMatrix * world;
}
