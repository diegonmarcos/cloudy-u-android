// .canvas files (JSON Canvas) on a pan/zoom board: text cards (markdown), file cards, link
// cards and groups, with edges. Edits write back through serializeCanvas, so what Obsidian
// wrote (and any field we do not know) is kept.
import { type PointerEvent as RPointerEvent, type ReactNode, useEffect, useMemo, useRef, useState, type WheelEvent as RWheelEvent } from 'react';

import {
  addEdge,
  addGroup,
  addTextNode,
  anchor,
  type CanvasNode,
  canvasColor,
  type JsonCanvas,
  moveNode,
  parseCanvas,
  removeEdge,
  removeNode,
  serializeCanvas,
  updateNode,
} from './jsoncanvas';
import { IMAGE_EXT, parseFrontmatter, renderMarkdown } from './markdown';
import { Icon } from './icons';

interface View {
  x: number;
  y: number;
  k: number;
}

type Gesture =
  | { kind: 'pan'; sx: number; sy: number; vx: number; vy: number }
  | { kind: 'move'; id: string; sx: number; sy: number; dx: number; dy: number }
  | { kind: 'resize'; id: string; sx: number; sy: number; w: number; h: number }
  | null;

export interface CanvasViewProps {
  text: string;
  onChange: (text: string) => void;
  /** open a vault file (file cards) */
  onOpenFile: (target: string, subpath?: string) => void;
  /** #1717 a link tapped in a text card or a link card: target as written; href = markdown destination or URL */
  onOpenLink?: (target: string, opts: { href: boolean; heading?: string }) => void;
  /** #1717 file cards show what they point at: resolve the card's file, read an image or a note */
  resolveFile?: (target: string) => string | null;
  readDataUrl?: (path: string) => Promise<string | null>;
  readText?: (path: string) => Promise<string | null>;
  /** #1717 the control rail of Canvas mode; when given, zoom and fit live there instead of the tool row */
  rail?: (ctl: CanvasControls) => ReactNode;
}

export interface CanvasControls {
  k: number;
  zoomIn: () => void;
  zoomOut: () => void;
  /** back to 100% at the origin */
  reset: () => void;
  fit: () => void;
}

/** A file card: the image, or the note rendered, or (when it cannot be read) its name and path. */
const FileCard = ({ file, resolveFile, readDataUrl, readText }: Pick<CanvasViewProps, 'resolveFile' | 'readDataUrl' | 'readText'> & { file: string }) => {
  const [shown, setShown] = useState<{ kind: 'image'; url: string } | { kind: 'note'; html: string } | null>(null);
  useEffect(() => {
    let alive = true;
    setShown(null);
    const hit = resolveFile?.(file) ?? null;
    if (!hit) return;
    void (async () => {
      try {
        if (IMAGE_EXT.test(hit)) {
          const url = await readDataUrl?.(hit);
          if (alive && url) setShown({ kind: 'image', url });
        } else if (/\.(md|markdown)$/i.test(hit)) {
          const text = await readText?.(hit);
          if (alive && typeof text === 'string') setShown({ kind: 'note', html: renderMarkdown(parseFrontmatter(text).body) });
        }
      } catch {
        /* the card keeps its name and path */
      }
    })();
    return () => {
      alive = false;
    };
  }, [file, resolveFile, readDataUrl, readText]);
  if (shown?.kind === 'image') return <img src={shown.url} alt={file} />;
  if (shown?.kind === 'note') {
    return <div><strong>{file.split('/').pop()}</strong><div className="cnv-note" style={{ margin: 0, maxWidth: 'none' }} dangerouslySetInnerHTML={{ __html: shown.html }} /></div>;
  }
  return <div><strong>{file.split('/').pop()}</strong><div className="cnv-dim">{file}</div></div>;
};

const PRESETS = [undefined, '1', '2', '3', '4', '5', '6'];

export const CanvasView = ({ text, onChange, onOpenFile, onOpenLink, resolveFile, readDataUrl, readText, rail }: CanvasViewProps) => {
  const parsed = useMemo(() => parseCanvas(text), [text]);
  const canvas = parsed.canvas;
  const [view, setView] = useState<View>({ x: 40, y: 40, k: 1 });
  const [selected, setSelected] = useState<string | null>(null);
  const [selectedEdge, setSelectedEdge] = useState<string | null>(null);
  const [connectFrom, setConnectFrom] = useState<string | null>(null);
  const [editing, setEditing] = useState<string | null>(null);
  const [gesture, setGesture] = useState<Gesture>(null);
  const pointers = useRef(new Map<number, { x: number; y: number }>());
  const pinch = useRef<{ dist: number; k: number } | null>(null);
  const boardRef = useRef<HTMLDivElement>(null);
  // #1717 the board captures the pointer, so a link in a card is followed from down/up, not from a click
  const linkTap = useRef<{ a: HTMLElement; x: number; y: number } | null>(null);

  const commit = (next: JsonCanvas) => onChange(serializeCanvas(next));

  // while a node is dragged or resized the board shows the result without writing yet
  const shown: JsonCanvas = useMemo(() => {
    if (gesture?.kind === 'move') return moveNode(canvas, gesture.id, gesture.dx, gesture.dy);
    if (gesture?.kind === 'resize') {
      return updateNode(canvas, gesture.id, { width: Math.max(60, gesture.w), height: Math.max(40, gesture.h) });
    }
    return canvas;
  }, [canvas, gesture]);

  const byId = useMemo(() => new Map(shown.nodes.map(n => [n.id, n])), [shown]);
  const center = () => {
    const r = boardRef.current?.getBoundingClientRect();
    return { x: ((r?.width ?? 300) / 2 - view.x) / view.k, y: ((r?.height ?? 300) / 2 - view.y) / view.k };
  };

  const down = (e: RPointerEvent<HTMLDivElement>) => {
    (e.currentTarget as HTMLElement).setPointerCapture?.(e.pointerId);
    pointers.current.set(e.pointerId, { x: e.clientX, y: e.clientY });
    const a = (e.target as HTMLElement).closest<HTMLElement>('a.internal-link, a.external-link');
    linkTap.current = a ? { a, x: e.clientX, y: e.clientY } : null;
    if (pointers.current.size === 2) {
      const [a, b] = [...pointers.current.values()];
      pinch.current = { dist: Math.hypot(a!.x - b!.x, a!.y - b!.y), k: view.k };
      setGesture(null);
      return;
    }
    const target = (e.target as HTMLElement).closest<HTMLElement>('[data-node]');
    const handle = (e.target as HTMLElement).closest<HTMLElement>('[data-resize]');
    if (target && handle) {
      const n = byId.get(target.dataset.node!)!;
      setSelected(n.id);
      setGesture({ kind: 'resize', id: n.id, sx: e.clientX, sy: e.clientY, w: n.width, h: n.height });
    } else if (target) {
      const id = target.dataset.node!;
      if (connectFrom && connectFrom !== id) {
        const r = addEdge(canvas, connectFrom, id, { toEnd: 'arrow' });
        if (r.id) commit(r.canvas);
        setConnectFrom(null);
        return;
      }
      setSelected(id);
      setSelectedEdge(null);
      setGesture({ kind: 'move', id, sx: e.clientX, sy: e.clientY, dx: 0, dy: 0 });
    } else {
      const edge = (e.target as HTMLElement).closest<SVGElement | HTMLElement>('[data-edge]');
      if (edge) {
        setSelectedEdge((edge as HTMLElement).dataset.edge ?? null);
        setSelected(null);
        return;
      }
      setSelected(null);
      setSelectedEdge(null);
      setGesture({ kind: 'pan', sx: e.clientX, sy: e.clientY, vx: view.x, vy: view.y });
    }
  };

  const move = (e: RPointerEvent<HTMLDivElement>) => {
    if (pointers.current.has(e.pointerId)) pointers.current.set(e.pointerId, { x: e.clientX, y: e.clientY });
    if (pointers.current.size === 2 && pinch.current) {
      const [a, b] = [...pointers.current.values()];
      const d = Math.hypot(a!.x - b!.x, a!.y - b!.y);
      const k = Math.min(4, Math.max(0.1, (pinch.current.k * d) / pinch.current.dist));
      setView(v => ({ ...v, k }));
      return;
    }
    if (!gesture) return;
    if (gesture.kind === 'pan') setView(v => ({ ...v, x: gesture.vx + e.clientX - gesture.sx, y: gesture.vy + e.clientY - gesture.sy }));
    else if (gesture.kind === 'move') {
      setGesture({ ...gesture, dx: (e.clientX - gesture.sx) / view.k, dy: (e.clientY - gesture.sy) / view.k });
    } else {
      setGesture({ ...gesture, w: gesture.w, h: gesture.h });
      const n = canvas.nodes.find(x => x.id === gesture.id);
      if (n) {
        setGesture({
          ...gesture,
          w: n.width + (e.clientX - gesture.sx) / view.k,
          h: n.height + (e.clientY - gesture.sy) / view.k,
        });
      }
    }
  };

  const up = (e: RPointerEvent<HTMLDivElement>) => {
    pointers.current.delete(e.pointerId);
    pinch.current = null;
    const tap = linkTap.current;
    linkTap.current = null;
    if (tap && onOpenLink && Math.hypot(e.clientX - tap.x, e.clientY - tap.y) < 8) {
      const ext = tap.a.classList.contains('external-link');
      onOpenLink(ext ? (tap.a.getAttribute('href') ?? '') : (tap.a.dataset.target ?? ''), { href: ext || tap.a.dataset.href === '1', heading: tap.a.dataset.heading });
      setGesture(null);
      return;
    }
    if (!gesture) return;
    if (gesture.kind === 'move' && (Math.abs(gesture.dx) > 1 || Math.abs(gesture.dy) > 1)) {
      commit(moveNode(canvas, gesture.id, gesture.dx, gesture.dy));
    } else if (gesture.kind === 'resize') {
      commit(updateNode(canvas, gesture.id, { width: Math.round(Math.max(60, gesture.w)), height: Math.round(Math.max(40, gesture.h)) }));
    }
    setGesture(null);
  };

  const wheel = (e: RWheelEvent<HTMLDivElement>) => {
    const r = e.currentTarget.getBoundingClientRect();
    const k = Math.min(4, Math.max(0.1, view.k * (e.deltaY < 0 ? 1.1 : 1 / 1.1)));
    const px = e.clientX - r.left;
    const py = e.clientY - r.top;
    setView({ k, x: px - ((px - view.x) / view.k) * k, y: py - ((py - view.y) / view.k) * k });
  };

  const fit = () => {
    const r = boardRef.current?.getBoundingClientRect();
    if (!r || canvas.nodes.length === 0) return setView({ x: 40, y: 40, k: 1 });
    const x0 = Math.min(...canvas.nodes.map(n => n.x));
    const y0 = Math.min(...canvas.nodes.map(n => n.y));
    const x1 = Math.max(...canvas.nodes.map(n => n.x + n.width));
    const y1 = Math.max(...canvas.nodes.map(n => n.y + n.height));
    const k = Math.min(2, Math.max(0.1, Math.min(r.width / (x1 - x0 + 80), r.height / (y1 - y0 + 80))));
    setView({ k, x: (r.width - (x1 - x0) * k) / 2 - x0 * k, y: (r.height - (y1 - y0) * k) / 2 - y0 * k });
  };

  const sel: CanvasNode | undefined = selected ? byId.get(selected) : undefined;
  const editable = sel && (sel.type === 'text' || sel.type === 'group' || sel.type === 'link');
  const editValue = sel ? (sel.type === 'text' ? (sel.text ?? '') : sel.type === 'group' ? (sel.label ?? '') : (sel.url ?? '')) : '';

  return (
    <div style={{ display: 'flex', flexDirection: 'column', flex: 1, minHeight: 0 }} data-testid="canvas-view">
      {parsed.errors.length > 0 && (
        <div className="cnv-banner" data-testid="canvas-errors">
          {parsed.errors.length} problem(s) in this canvas; those entries are skipped: {parsed.errors[0]}
        </div>
      )}
      <div ref={boardRef} className="cnv-board" onPointerDown={down} onPointerMove={move} onPointerUp={up} onPointerCancel={up} onWheel={wheel}>
        <div style={{ position: 'absolute', left: 0, top: 0, transform: `translate(${view.x}px,${view.y}px) scale(${view.k})`, transformOrigin: '0 0' }}>
          {shown.nodes
            .filter(n => n.type === 'group')
            .map(n => renderNode(n, selected === n.id, connectFrom === n.id))}
          <svg width="1" height="1" style={{ position: 'absolute', overflow: 'visible', pointerEvents: 'none' }}>
            <defs>
              <marker id="cnv-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="8" markerHeight="8" orient="auto-start-reverse">
                <path d="M0 0L10 5L0 10z" fill="currentColor" />
              </marker>
            </defs>
            {shown.edges.map(ed => {
              const a = byId.get(ed.fromNode);
              const b = byId.get(ed.toNode);
              if (!a || !b) return null;
              const pa = anchor(a, ed.fromSide, { x: b.x + b.width / 2, y: b.y + b.height / 2 });
              const pb = anchor(b, ed.toSide, { x: a.x + a.width / 2, y: a.y + a.height / 2 });
              const color = canvasColor(ed.color) ?? 'var(--text-faint)';
              return (
                <g key={ed.id} style={{ color }}>
                  <line x1={pa.x} y1={pa.y} x2={pb.x} y2={pb.y} stroke="transparent" strokeWidth={18} data-edge={ed.id} style={{ pointerEvents: 'stroke' }} />
                  <line
                    x1={pa.x} y1={pa.y} x2={pb.x} y2={pb.y} stroke={color} strokeWidth={selectedEdge === ed.id ? 4 : 2}
                    markerEnd={ed.toEnd === 'none' ? undefined : 'url(#cnv-arrow)'}
                    markerStart={ed.fromEnd === 'arrow' ? 'url(#cnv-arrow)' : undefined}
                  />
                  {ed.label && (
                    <text x={(pa.x + pb.x) / 2} y={(pa.y + pb.y) / 2 - 4} textAnchor="middle" fontSize={13} fill="currentColor">
                      {ed.label}
                    </text>
                  )}
                </g>
              );
            })}
          </svg>
          {shown.nodes.filter(n => n.type !== 'group').map(n => renderNode(n, selected === n.id, connectFrom === n.id))}
        </div>
        <div className="cnv-board-tools" onPointerDown={e => e.stopPropagation()}>
          <button type="button" className="cnv-btn" onClick={() => { const r = addTextNode(canvas, center()); commit(r.canvas); setSelected(r.id); setEditing(r.id); }}>
            <Icon name="plus" /> Card
          </button>
          <button type="button" className="cnv-btn" onClick={() => { const c = center(); const r = addGroup(canvas, { x: c.x - 200, y: c.y - 140, width: 400, height: 280 }, 'Group'); commit(r.canvas); setSelected(r.id); }}>
            <Icon name="plus" /> Group
          </button>
          {sel && sel.type !== 'group' && (
            <button type="button" className="cnv-btn" aria-pressed={connectFrom === sel.id} onClick={() => setConnectFrom(connectFrom ? null : sel.id)}>
              {connectFrom ? 'Pick target' : 'Connect'}
            </button>
          )}
          {sel && editable && (
            <button type="button" className="cnv-btn" onClick={() => setEditing(sel.id)}>
              <Icon name="edit" /> Edit
            </button>
          )}
          {sel && sel.type === 'file' && sel.file && (
            <button type="button" className="cnv-btn" onClick={() => onOpenFile(sel.file!, typeof sel.subpath === 'string' ? sel.subpath : undefined)}>Open file</button>
          )}
          {sel && sel.type === 'link' && sel.url && onOpenLink && (
            <button type="button" className="cnv-btn" data-testid="canvas-open-link" onClick={() => onOpenLink(sel.url!, { href: true })}>Open link</button>
          )}
          {sel && (
            <button type="button" className="cnv-btn" onClick={() => commit(updateNode(canvas, sel.id, { color: PRESETS[(PRESETS.indexOf(sel.color) + 1) % PRESETS.length] }))}>
              Colour
            </button>
          )}
          {sel && (
            <button type="button" className="cnv-btn" onClick={() => { commit(removeNode(canvas, sel.id)); setSelected(null); }}>
              <Icon name="close" /> Delete
            </button>
          )}
          {selectedEdge && (
            <button type="button" className="cnv-btn" onClick={() => { commit(removeEdge(canvas, selectedEdge)); setSelectedEdge(null); }}>
              <Icon name="close" /> Delete link
            </button>
          )}
          {!rail && <button type="button" className="cnv-btn" onClick={() => setView(v => ({ ...v, k: Math.min(4, v.k * 1.25) }))}>+</button>}
          {!rail && <button type="button" className="cnv-btn" onClick={() => setView(v => ({ ...v, k: Math.max(0.1, v.k / 1.25) }))}>-</button>}
          {!rail && <button type="button" className="cnv-btn" onClick={fit}>Fit</button>}
        </div>
        {rail && (
          <div className="cnv-rail" data-testid="canvas-rail" onPointerDown={e => e.stopPropagation()} onPointerUp={e => e.stopPropagation()}>
            {rail({
              k: view.k,
              zoomIn: () => setView(v => ({ ...v, k: Math.min(4, v.k * 1.25) })),
              zoomOut: () => setView(v => ({ ...v, k: Math.max(0.1, v.k / 1.25) })),
              reset: () => setView({ x: 40, y: 40, k: 1 }),
              fit,
            })}
          </div>
        )}
      </div>
      {editing && sel && sel.id === editing && (
        <div className="cnv-banner" style={{ flexDirection: 'column', alignItems: 'stretch' }}>
          <textarea
            className="cnv-input" rows={4} autoFocus defaultValue={editValue} data-testid="canvas-edit"
            onChange={e => {
              const v = e.target.value;
              commit(updateNode(canvas, sel.id, sel.type === 'text' ? { text: v } : sel.type === 'group' ? { label: v } : { url: v }));
            }}
          />
          <button type="button" className="cnv-btn cnv-primary" onClick={() => setEditing(null)}>Done</button>
        </div>
      )}
    </div>
  );

  function renderNode(n: CanvasNode, isSel: boolean, isFrom: boolean) {
    const color = canvasColor(n.color);
    return (
      <div
        key={n.id}
        data-node={n.id}
        aria-selected={isSel}
        className={n.type === 'group' ? 'cnv-node cnv-group' : 'cnv-node'}
        style={{ left: n.x, top: n.y, width: n.width, height: n.height, borderColor: color, outline: isFrom ? '2px dashed orange' : undefined }}
      >
        {n.type === 'text' && <div className="cnv-note" style={{ margin: 0, maxWidth: 'none' }} dangerouslySetInnerHTML={{ __html: renderMarkdown(n.text ?? '') }} />}
        {n.type === 'file' && <FileCard file={n.file ?? ''} resolveFile={resolveFile} readDataUrl={readDataUrl} readText={readText} />}
        {n.type === 'link' && <div><strong>Link</strong><div className="cnv-dim" style={{ overflowWrap: 'anywhere' }}>{n.url}</div></div>}
        {n.type === 'group' && <strong style={{ color }}>{n.label}</strong>}
        {isSel && (
          <div data-resize="1" style={{ position: 'absolute', right: 0, bottom: 0, width: 22, height: 22, cursor: 'nwse-resize', background: 'var(--interactive-accent)', borderTopLeftRadius: 8 }} />
        )}
      </div>
    );
  }
};
