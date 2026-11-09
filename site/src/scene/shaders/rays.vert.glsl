// Light-shaft plane: passes plane-local coordinates in world units so the
// shaft pattern has a fixed physical scale regardless of the plane's size.

uniform vec2 uSize;

varying vec2 vP;
varying vec2 vUv;

void main() {
  vUv = uv;
  vP = (uv - 0.5) * uSize;
  gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
}
