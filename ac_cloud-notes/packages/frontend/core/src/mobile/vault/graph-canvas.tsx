import type { GraphModel, GraphNode, Positions } from '@blocksuite/cloud-notes-graph';
import { adjacency } from '@blocksuite/cloud-notes-graph';
import { useEffect, useMemo, useRef } from 'react';

const DOC_COLOR = '#7a8cff';
const TAG_COLOR = '#f2a65a';
const ORPHAN_COLOR = '#9aa0a6';
const CENTER_COLOR = '#ff6b6b';
const MATCH_COLOR = '#ffd93d';

interface View {
  scale: number;
  tx: number;
  ty: number;
}

export interface GraphCanvasProps {
  model: GraphModel;
  positions: Positions;
  /** the open doc of the local graph */
  centerId?: string | null;
  /** ids to highlight (the search box) */
  matches: Set<string>;
  onOpen: (node: GraphNode) => void;
  /** logical size the layout was computed for */
  size?: number;
}

/**
 * Canvas 2D renderer of a laid-out graph: drag to pan, pinch / wheel to zoom,
 * tap a node to open it. No dependency: this is the whole renderer.
 */
export const GraphCanvas = ({
  model,
  positions,
  centerId,
  matches,
  onOpen,
  size = 1000,
}: GraphCanvasProps) => {
  const ref = useRef<HTMLCanvasElement>(null);
  const view = useRef<View>({ scale: 1, tx: 0, ty: 0 });
  const degree = useMemo(() => {
    const d = new Map<string, number>();
    for (const [id, s] of adjacency(model)) d.set(id, s.size);
    return d;
  }, [model]);
  const draw = useRef<() => void>(() => {});

  // fit the layout to the canvas whenever the graph changes
  useEffect(() => {
    const c = ref.current;
    if (!c) return;
    const fit = () => {
      const w = c.clientWidth || 1;
      const h = c.clientHeight || 1;
      const scale = Math.min(w, h) / size;
      view.current = {
        scale,
        tx: (w - size * scale) / 2,
        ty: (h - size * scale) / 2,
      };
      draw.current();
    };
    fit();
  }, [model, positions, size]);

  useEffect(() => {
    const c = ref.current;
    if (!c) return;
    const ctx = c.getContext('2d');
    if (!ctx) return;

    draw.current = () => {
      const dpr = window.devicePixelRatio || 1;
      const w = c.clientWidth;
      const h = c.clientHeight;
      if (c.width !== Math.round(w * dpr) || c.height !== Math.round(h * dpr)) {
        c.width = Math.round(w * dpr);
        c.height = Math.round(h * dpr);
      }
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      ctx.clearRect(0, 0, w, h);
      const { scale, tx, ty } = view.current;
      const sx = (x: number) => x * scale + tx;
      const sy = (y: number) => y * scale + ty;
      const text = getComputedStyle(c).color || '#888';

      ctx.lineWidth = 1;
      for (const e of model.edges) {
        const a = positions.get(e.source);
        const b = positions.get(e.target);
        if (!a || !b) continue;
        ctx.strokeStyle = e.kind === 'tag' ? 'rgba(242,166,90,0.35)' : 'rgba(140,150,170,0.55)';
        ctx.beginPath();
        ctx.moveTo(sx(a.x), sy(a.y));
        ctx.lineTo(sx(b.x), sy(b.y));
        ctx.stroke();
      }
      ctx.font = '11px sans-serif';
      ctx.textAlign = 'center';
      for (const n of model.nodes) {
        const p = positions.get(n.id);
        if (!p) continue;
        const x = sx(p.x);
        const y = sy(p.y);
        if (x < -20 || y < -20 || x > w + 20 || y > h + 20) continue;
        const r = 4 + Math.sqrt(degree.get(n.id) ?? 0) * 1.6;
        const isCenter = n.id === centerId;
        const isMatch = matches.has(n.id);
        ctx.fillStyle = isCenter
          ? CENTER_COLOR
          : isMatch
            ? MATCH_COLOR
            : n.kind === 'tag'
              ? TAG_COLOR
              : n.orphan
                ? ORPHAN_COLOR
                : DOC_COLOR;
        ctx.beginPath();
        if (n.kind === 'tag') {
          ctx.rect(x - r, y - r, r * 2, r * 2);
        } else {
          ctx.arc(x, y, r, 0, Math.PI * 2);
        }
        ctx.fill();
        if (isCenter || isMatch || scale > 0.9 || (degree.get(n.id) ?? 0) >= 6) {
          ctx.fillStyle = text;
          ctx.fillText(n.kind === 'tag' ? `#${n.label}` : n.label, x, y - r - 3);
        }
      }
    };
    draw.current();

    const hit = (px: number, py: number): GraphNode | null => {
      const { scale, tx, ty } = view.current;
      let best: GraphNode | null = null;
      let bestD = 24 * 24; // finger-sized
      for (const n of model.nodes) {
        const p = positions.get(n.id);
        if (!p) continue;
        const dx = p.x * scale + tx - px;
        const dy = p.y * scale + ty - py;
        const d = dx * dx + dy * dy;
        if (d < bestD) {
          bestD = d;
          best = n;
        }
      }
      return best;
    };

    const pointers = new Map<number, { x: number; y: number }>();
    let moved = 0;
    let downAt = 0;
    let pinch = 0;
    const local = (e: PointerEvent) => {
      const r = c.getBoundingClientRect();
      return { x: e.clientX - r.left, y: e.clientY - r.top };
    };
    const zoomAt = (px: number, py: number, factor: number) => {
      const v = view.current;
      const next = Math.min(8, Math.max(0.1, v.scale * factor));
      const k = next / v.scale;
      view.current = { scale: next, tx: px - (px - v.tx) * k, ty: py - (py - v.ty) * k };
      draw.current();
    };
    const down = (e: PointerEvent) => {
      c.setPointerCapture(e.pointerId);
      pointers.set(e.pointerId, local(e));
      if (pointers.size === 1) {
        moved = 0;
        downAt = Date.now();
      }
      if (pointers.size === 2) {
        const [a, b] = Array.from(pointers.values());
        pinch = Math.hypot(a.x - b.x, a.y - b.y);
      }
    };
    const move = (e: PointerEvent) => {
      const prev = pointers.get(e.pointerId);
      if (!prev) return;
      const cur = local(e);
      pointers.set(e.pointerId, cur);
      if (pointers.size === 1) {
        moved += Math.abs(cur.x - prev.x) + Math.abs(cur.y - prev.y);
        view.current.tx += cur.x - prev.x;
        view.current.ty += cur.y - prev.y;
        draw.current();
      } else if (pointers.size === 2) {
        const [a, b] = Array.from(pointers.values());
        const d = Math.hypot(a.x - b.x, a.y - b.y);
        if (pinch > 0) zoomAt((a.x + b.x) / 2, (a.y + b.y) / 2, d / pinch);
        pinch = d;
        moved += 100;
      }
    };
    const up = (e: PointerEvent) => {
      const p = pointers.get(e.pointerId);
      pointers.delete(e.pointerId);
      if (p && pointers.size === 0 && moved < 8 && Date.now() - downAt < 500) {
        const n = hit(p.x, p.y);
        if (n) onOpen(n);
      }
      if (pointers.size < 2) pinch = 0;
    };
    const wheel = (e: WheelEvent) => {
      e.preventDefault();
      const r = c.getBoundingClientRect();
      zoomAt(e.clientX - r.left, e.clientY - r.top, e.deltaY < 0 ? 1.15 : 1 / 1.15);
    };
    const resize = () => draw.current();

    c.addEventListener('pointerdown', down);
    c.addEventListener('pointermove', move);
    c.addEventListener('pointerup', up);
    c.addEventListener('pointercancel', up);
    c.addEventListener('wheel', wheel, { passive: false });
    window.addEventListener('resize', resize);
    return () => {
      c.removeEventListener('pointerdown', down);
      c.removeEventListener('pointermove', move);
      c.removeEventListener('pointerup', up);
      c.removeEventListener('pointercancel', up);
      c.removeEventListener('wheel', wheel);
      window.removeEventListener('resize', resize);
    };
  }, [model, positions, centerId, matches, degree, onOpen]);

  return (
    <canvas
      ref={ref}
      data-testid="graph-canvas"
      style={{
        width: '100%',
        height: '100%',
        display: 'block',
        touchAction: 'none',
        color: 'var(--cloud-notes-text-primary-color, currentColor)',
      }}
    />
  );
};
