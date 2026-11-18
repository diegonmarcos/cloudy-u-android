// The .json viewer's model: parse with a readable error, flatten to visible tree rows.
// The text editor and the tree share one source string; the tree is only a view.

export type JsonKind = 'object' | 'array' | 'string' | 'number' | 'boolean' | 'null';

export interface JsonRow {
  /** JSON Pointer (RFC 6901) of this value; '' is the root */
  pointer: string;
  /** the copyable JSONPath ($.a.b[0]), as the fleet JSON viewer (libs:json-viewer) shows it */
  path: string;
  key: string;
  depth: number;
  kind: JsonKind;
  /** the scalar text, or "{3}" / "[2]" for containers */
  preview: string;
  expandable: boolean;
  expanded: boolean;
}

export type JsonParse = { ok: true; value: unknown } | { ok: false; error: string };

export function parseJson(text: string): JsonParse {
  try {
    return { ok: true, value: JSON.parse(text) };
  } catch (e) {
    return { ok: false, error: e instanceof Error ? e.message : String(e) };
  }
}

export const kindOf = (v: unknown): JsonKind =>
  v === null ? 'null' : Array.isArray(v) ? 'array' : (typeof v as JsonKind);

const esc = (k: string) => k.replace(/~/g, '~0').replace(/\//g, '~1');
const unesc = (k: string) => k.replace(/~1/g, '/').replace(/~0/g, '~');

/** A child's JSONPath: $.name for a plain key, $['odd key'] otherwise, $[0] for an array index. */
export const pathChild = (path: string, key: string, index: boolean): string =>
  index ? `${path}[${key}]` : /^[A-Za-z_$][\w$]*$/.test(key) ? `${path}.${key}` : `${path}['${key.replace(/\\/g, '\\\\').replace(/'/g, "\\'")}']`;

export function jsonRows(value: unknown, expanded: ReadonlySet<string>, maxRows = 5000): JsonRow[] {
  const rows: JsonRow[] = [];
  const visit = (v: unknown, key: string, pointer: string, depth: number, path: string) => {
    if (rows.length >= maxRows) return;
    const kind = kindOf(v);
    const container = kind === 'object' || kind === 'array';
    const open = container && (pointer === '' || expanded.has(pointer));
    const size = container ? (kind === 'array' ? (v as unknown[]).length : Object.keys(v as object).length) : 0;
    rows.push({
      pointer,
      path,
      key,
      depth,
      kind,
      preview: container ? (kind === 'array' ? `[${size}]` : `{${size}}`) : kind === 'string' ? JSON.stringify(v) : String(v),
      expandable: container && size > 0,
      expanded: open,
    });
    if (!open) return;
    if (kind === 'array') (v as unknown[]).forEach((c, i) => visit(c, String(i), `${pointer}/${i}`, depth + 1, pathChild(path, String(i), true)));
    else for (const [k, c] of Object.entries(v as Record<string, unknown>)) visit(c, k, `${pointer}/${esc(k)}`, depth + 1, pathChild(path, k, false));
  };
  visit(value, '$', '', 0, '$');
  return rows;
}

/**
 * The search view, as the fleet viewer does it: the nodes whose key or (for a leaf) value contains the query, case-insensitively,
 * each with all its ancestors, everything open. An empty query is the normal tree.
 */
export function searchRows(value: unknown, expanded: ReadonlySet<string>, query: string, maxRows = 5000): JsonRow[] {
  const q = query.trim().toLowerCase();
  if (q === '') return jsonRows(value, expanded, maxRows);
  const all = jsonRows(value, new Set(allContainers(value)), Number.MAX_SAFE_INTEGER);
  const keep = new Set<string>();
  for (const r of all) {
    const leaf = !r.expandable && r.kind !== 'object' && r.kind !== 'array';
    if ((r.pointer !== '' && r.key.toLowerCase().includes(q)) || (leaf && r.preview.toLowerCase().includes(q))) {
      let p = r.pointer;
      for (;;) {
        keep.add(p);
        if (p === '') break;
        p = p.slice(0, p.lastIndexOf('/'));
      }
    }
  }
  return all.filter(r => keep.has(r.pointer)).slice(0, maxRows);
}

const allContainers = (value: unknown): string[] => {
  const out: string[] = [];
  const visit = (v: unknown, pointer: string) => {
    const k = kindOf(v);
    if (k === 'array') { out.push(pointer); (v as unknown[]).forEach((c, i) => visit(c, `${pointer}/${i}`)); }
    else if (k === 'object') { out.push(pointer); for (const [key, c] of Object.entries(v as Record<string, unknown>)) visit(c, `${pointer}/${esc(key)}`); }
  };
  visit(value, '');
  return out;
};

/** The value at a JSON Pointer, or undefined. */
export function valueAtPointer(value: unknown, pointer: string): unknown {
  let v: unknown = value;
  if (pointer === '') return v;
  for (const part of pointer.slice(1).split('/').map(unesc)) {
    if (v === null || typeof v !== 'object') return undefined;
    v = (v as Record<string, unknown>)[part];
  }
  return v;
}

/** What "Copy value" puts on the clipboard: a string without its quotes, anything else as JSON (pretty for containers). */
export const copyText = (v: unknown): string => (typeof v === 'string' ? v : JSON.stringify(v, null, 2));

export const formatJson = (text: string, indent = 2): string => JSON.stringify(JSON.parse(text), null, indent) + '\n';
