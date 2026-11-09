/**
 * The CyberFish mark as a cloud of points, for the floor chapter: the
 * `><>` drawn as one connected line, the way it is sketched by hand. The
 * two left arms cross and run on into the diamond, which is closed. The
 * stroke is drawn to an offscreen canvas and points are thrown at it
 * until enough land inside.
 */

/** The mark's proportions (width : height) and the stroke's width as a share of the width. */
const ASPECT = 2.5;
const STROKE = 0.095;
/** The line, in unit coordinates (x right 0..1, y down 0..1); inset so the round caps stay on the canvas. */
const LINE: Array<[number, number]> = [
  [0.06, 0.16],
  [0.38, 0.5],
  [0.66, 0.16],
  [0.94, 0.5],
  [0.66, 0.84],
  [0.38, 0.5],
  [0.06, 0.84],
];
/** Raster width used for the hit test. */
const RASTER_W = 1200;

/**
 * Resolve to `count` points (x, y, z triples) in a local frame: the mark
 * centred on the origin, `width` units wide, x right, y up, facing +Z. A
 * little depth jitter and a soft spread at the edges keep it from reading
 * as a flat cut-out.
 */
export async function loadLogoPoints(count: number, width: number): Promise<Float32Array> {
  const w = RASTER_W;
  const h = Math.round(RASTER_W / ASPECT);
  const canvas = document.createElement('canvas');
  canvas.width = w;
  canvas.height = h;
  const ctx = canvas.getContext('2d', { willReadFrequently: true });
  if (!ctx) throw new Error('No 2D context for the mark');
  ctx.lineWidth = STROKE * w;
  ctx.lineCap = 'round';
  ctx.lineJoin = 'round';
  ctx.strokeStyle = '#fff';
  ctx.beginPath();
  LINE.forEach(([x, y], i) => {
    if (i === 0) ctx.moveTo(x * w, y * h);
    else ctx.lineTo(x * w, y * h);
  });
  ctx.stroke();
  const alpha = ctx.getImageData(0, 0, w, h).data;
  const inside = (x: number, y: number): boolean => {
    const xi = Math.floor(x);
    const yi = Math.floor(y);
    if (xi < 0 || yi < 0 || xi >= w || yi >= h) return false;
    return alpha[(yi * w + xi) * 4 + 3] > 96;
  };

  const out = new Float32Array(count * 3);
  const scale = width / w;
  const height = h * scale;
  let n = 0;
  let tries = 0;
  while (n < count && tries < count * 400) {
    tries++;
    const x = Math.random() * w;
    const y = Math.random() * h;
    if (!inside(x, y)) continue;
    // Soft edges: a small normal-ish scatter around the sampled point.
    const jx = (Math.random() + Math.random() - 1) * 0.014 * w;
    const jy = (Math.random() + Math.random() - 1) * 0.014 * w;
    out[n * 3] = (x + jx) * scale - width / 2;
    out[n * 3 + 1] = height / 2 - (y + jy) * scale;
    out[n * 3 + 2] = (Math.random() + Math.random() - 1) * 0.016 * width;
    n++;
  }
  return n === count ? out : out.slice(0, n * 3);
}
