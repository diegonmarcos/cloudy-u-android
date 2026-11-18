// The pencil's ink: turns a raw pointer trail into a filled outline whose
// width breathes with pressure (or, for mice, with speed). A compact take on
// the perfect-freehand idea — streamline the input, give every point a
// radius, walk the spine offsetting perpendicular both ways, cap the ends.
// Dependency-free ESM (see palette.js).

// input: flat [x, y, pressure, ...] triplets (pressure 0..1, 0.5 = no reading)
// output: flat [x, y, ...] closed outline polygon
export function strokeOutline(pts, { size = 4, thinning = 0.55, streamline = 0.32, simulate = true, taper = true } = {}) {
  const n = Math.floor(pts.length / 3)
  if (n === 0) return []
  if (n === 1) {
    // a tap leaves a dot
    return dotOutline(pts[0], pts[1], size / 2)
  }

  // 1. streamline: exponential follow smooths jitter without lagging corners
  const sp = new Array(n * 2)
  sp[0] = pts[0]
  sp[1] = pts[1]
  for (let i = 1; i < n; i++) {
    sp[i * 2] = sp[(i - 1) * 2] + (pts[i * 3] - sp[(i - 1) * 2]) * (1 - streamline)
    sp[i * 2 + 1] = sp[(i - 1) * 2 + 1] + (pts[i * 3 + 1] - sp[(i - 1) * 2 + 1]) * (1 - streamline)
  }

  // 2. radius per point from pressure — real when the device reports it,
  // simulated from velocity when it doesn't (fast = thin, like a real pen)
  const radii = new Array(n)
  let vel = 0
  for (let i = 0; i < n; i++) {
    let p = pts[i * 3 + 2]
    const hasReal = p > 0 && p !== 0.5
    if (!hasReal && simulate) {
      const d = i ? Math.hypot(sp[i * 2] - sp[(i - 1) * 2], sp[i * 2 + 1] - sp[(i - 1) * 2 + 1]) : 0
      vel = vel + (Math.min(1, d / (size * 1.8)) - vel) * 0.35
      p = 1 - vel * 0.75
    } else if (!hasReal) {
      p = 0.6
    }
    radii[i] = Math.max(0.35, (size / 2) * (1 - thinning + thinning * p))
  }
  // taper the tips so strokes start and end in a point — over a fixed,
  // short distance (~2 widths): counting samples instead stretched fast
  // strokes into long tails, since quick motion spaces the samples out
  if (taper) {
    const span = size * 2
    const ease = (from, step) => {
      let d = 0
      for (let i = from; i >= 0 && i < n; i += step) {
        if (i !== from) d += Math.hypot(sp[i * 2] - sp[(i - step) * 2], sp[i * 2 + 1] - sp[(i - step) * 2 + 1])
        if (d >= span) break
        radii[i] *= 0.5 + 0.5 * (d / span)
      }
    }
    ease(0, 1)
    ease(n - 1, -1)
  }

  // 3. offset both ways along the spine
  const left = []
  const right = []
  for (let i = 0; i < n; i++) {
    const x = sp[i * 2], y = sp[i * 2 + 1]
    const px = sp[Math.max(0, i - 1) * 2], py = sp[Math.max(0, i - 1) * 2 + 1]
    const nx2 = sp[Math.min(n - 1, i + 1) * 2], ny2 = sp[Math.min(n - 1, i + 1) * 2 + 1]
    let dx = nx2 - px, dy = ny2 - py
    const len = Math.hypot(dx, dy)
    if (len < 0.001) {
      if (left.length) continue
      dx = 1; dy = 0
    } else {
      dx /= len; dy /= len
    }
    const r = radii[i]
    left.push(x - dy * r, y + dx * r)
    right.push(x + dy * r, y - dx * r)
  }
  if (!left.length) return dotOutline(sp[0], sp[1], radii[0])

  // 4. stitch: left side out, rounded end cap, right side back
  const out = left.slice()
  capInto(out, sp[(n - 1) * 2], sp[(n - 1) * 2 + 1], right[right.length - 2], right[right.length - 1], left[left.length - 2], left[left.length - 1])
  for (let i = right.length - 2; i >= 0; i -= 2) out.push(right[i], right[i + 1])
  capInto(out, sp[0], sp[1], left[0], left[1], right[0], right[1])
  return out
}

// rounded cap: short arc from point a around center c to point b
function capInto(out, cx, cy, ax, ay, bx, by) {
  const a0 = Math.atan2(ay - cy, ax - cx)
  let a1 = Math.atan2(by - cy, bx - cx)
  while (a1 < a0) a1 += Math.PI * 2
  const r = Math.hypot(ax - cx, ay - cy)
  const steps = Math.max(2, Math.ceil((a1 - a0) / 0.5))
  for (let i = 1; i < steps; i++) {
    const a = a0 + ((a1 - a0) * i) / steps
    out.push(cx + Math.cos(a) * r, cy + Math.sin(a) * r)
  }
  out.push(bx, by)
}

function dotOutline(x, y, r) {
  const out = []
  const rr = Math.max(r, 0.75)
  for (let i = 0; i < 12; i++) {
    const a = (i / 12) * Math.PI * 2
    out.push(x + Math.cos(a) * rr, y + Math.sin(a) * rr)
  }
  return out
}
