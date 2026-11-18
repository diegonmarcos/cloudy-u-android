// The two-finger gesture (ticket 1788) as a hook: while two fingers are down on [ref] the zoom follows their distance; the final value is
// reported once, when the fingers lift (it is then written to the tab). One finger is left alone, so scrolling and selecting still work.
import { type RefObject, useEffect, useRef } from 'react';

import { distance, pinchZoom, type ZoomRange } from './core/zoom';

export function usePinch(ref: RefObject<HTMLElement | null>, zoom: number, range: ZoomRange, onLive: (z: number) => void, onDone: (z: number) => void): void {
  const state = useRef({ zoom, onLive, onDone, range });
  state.current = { zoom, onLive, onDone, range };

  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    let start: { dist: number; zoom: number } | null = null;
    let live = state.current.zoom;
    const pts = (e: TouchEvent) => [...e.touches].slice(0, 2).map(t => ({ x: t.clientX, y: t.clientY }));
    const onStart = (e: TouchEvent) => {
      if (e.touches.length === 2) {
        const [a, b] = pts(e);
        start = { dist: distance(a!, b!), zoom: state.current.zoom };
        live = state.current.zoom;
      }
    };
    const onMove = (e: TouchEvent) => {
      if (!start || e.touches.length !== 2) return;
      e.preventDefault(); // no browser page zoom: this is the app's own scale
      const [a, b] = pts(e);
      live = pinchZoom(start.dist, distance(a!, b!), start.zoom, state.current.range);
      state.current.onLive(live);
    };
    const onEnd = (e: TouchEvent) => {
      if (start && e.touches.length < 2) {
        start = null;
        state.current.onDone(live);
      }
    };
    el.addEventListener('touchstart', onStart, { passive: true });
    el.addEventListener('touchmove', onMove, { passive: false });
    el.addEventListener('touchend', onEnd, { passive: true });
    el.addEventListener('touchcancel', onEnd, { passive: true });
    return () => {
      el.removeEventListener('touchstart', onStart);
      el.removeEventListener('touchmove', onMove);
      el.removeEventListener('touchend', onEnd);
      el.removeEventListener('touchcancel', onEnd);
    };
  }, [ref]);
}
