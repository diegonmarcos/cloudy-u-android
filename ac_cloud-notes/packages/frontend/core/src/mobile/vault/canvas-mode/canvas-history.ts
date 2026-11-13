// Canvas mode: undo / redo of the board's text (the serialized .canvas), and the zoom steps of
// the control rail. Pure, bounded, and independent of React so it is tested under node.
export interface CanvasHistory {
  past: string[];
  future: string[];
}

export const EMPTY_HISTORY: CanvasHistory = { past: [], future: [] };
const MAX = 100;

/** Record that the board changed from [before]. A change clears redo. */
export function recordChange(h: CanvasHistory, before: string, after: string): CanvasHistory {
  if (before === after) return h;
  return { past: [...h.past, before].slice(-MAX), future: [] };
}

export const canUndo = (h: CanvasHistory) => h.past.length > 0;
export const canRedo = (h: CanvasHistory) => h.future.length > 0;

export function undo(h: CanvasHistory, current: string): { history: CanvasHistory; text: string } | null {
  if (h.past.length === 0) return null;
  const text = h.past[h.past.length - 1]!;
  return { history: { past: h.past.slice(0, -1), future: [...h.future, current].slice(-MAX) }, text };
}

export function redo(h: CanvasHistory, current: string): { history: CanvasHistory; text: string } | null {
  if (h.future.length === 0) return null;
  const text = h.future[h.future.length - 1]!;
  return { history: { past: [...h.past, current].slice(-MAX), future: h.future.slice(0, -1) }, text };
}

export const ZOOM_MIN = 0.1;
export const ZOOM_MAX = 4;
export const ZOOM_STEP = 1.25;
export const zoomIn = (k: number) => Math.min(ZOOM_MAX, k * ZOOM_STEP);
export const zoomOut = (k: number) => Math.max(ZOOM_MIN, k / ZOOM_STEP);
