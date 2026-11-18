// Zoom (ticket 1788): two-finger pinch on a note (View, Raw) scales its text; on a canvas it zooms the board. The value is kept per tab.
// Pure: the numbers a gesture turns into; the DOM listeners are in use-pinch.ts.

export interface ZoomRange { min: number; max: number }

/** Text scale of a note or data file (1 = the size set in Configs > UI). */
export const TEXT_ZOOM: ZoomRange = { min: 0.6, max: 3 };
/** Board zoom of a canvas (the range the board already used). */
export const CANVAS_ZOOM: ZoomRange = { min: 0.1, max: 4 };
export const ZOOM_STEP = 1.2;

export const clampZoom = (z: number, r: ZoomRange): number => (Number.isFinite(z) ? Math.min(r.max, Math.max(r.min, z)) : 1);

/** Two fingers moved from [startDist] apart to [dist]: the zoom follows the ratio. */
export const pinchZoom = (startDist: number, dist: number, startZoom: number, r: ZoomRange): number =>
  startDist <= 0 ? clampZoom(startZoom, r) : clampZoom(startZoom * (dist / startDist), r);

/** A menu / keyboard step: in or out by [ZOOM_STEP], rounded so repeated steps do not drift. */
export const stepZoom = (z: number, dir: 'in' | 'out', r: ZoomRange): number =>
  clampZoom(Math.round((dir === 'in' ? z * ZOOM_STEP : z / ZOOM_STEP) * 100) / 100, r);

/** Whatever storage holds -> a usable zoom for a file type (1 when absent or junk). */
export const storedZoom = (v: unknown, r: ZoomRange): number => (typeof v === 'number' && Number.isFinite(v) ? clampZoom(v, r) : 1);

export const distance = (a: { x: number; y: number }, b: { x: number; y: number }): number => Math.hypot(a.x - b.x, a.y - b.y);
