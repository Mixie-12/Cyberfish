varying vec3 vWorldPos;

void main() {
  vWorldPos = (modelMatrix * vec4(position, 1.0)).xyz;
  vec4 mvPosition = viewMatrix * vec4(vWorldPos, 1.0);
  gl_Position = projectionMatrix * mvPosition;
  // Push to the far plane so the sky always sits behind everything.
  gl_Position.z = gl_Position.w * 0.99999;
}
