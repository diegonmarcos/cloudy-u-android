// File mode, the Properties card: the frontmatter of a note as typed rows, and the one edit it
// offers (add a property). Pure: the card is only a renderer.
import { type FrontmatterValue, parseFrontmatter } from '../markdown';

export type PropertyType = 'text' | 'list' | 'number' | 'checkbox' | 'date' | 'tags';

export interface PropertyRow {
  key: string;
  type: PropertyType;
  /** display text; '' when the property has no value (the card prints "Empty") */
  value: string;
  empty: boolean;
}

const DATE = /^\d{4}-\d{2}-\d{2}([ T]\d{2}:\d{2}(:\d{2})?)?/;

export function propertyType(key: string, v: FrontmatterValue): PropertyType {
  const k = key.toLowerCase();
  if (k === 'tags' || k === 'tag') return 'tags';
  if (Array.isArray(v)) return 'list';
  if (typeof v === 'number') return 'number';
  if (typeof v === 'boolean') return 'checkbox';
  if (typeof v === 'string' && DATE.test(v)) return 'date';
  return 'text';
}

const show = (v: FrontmatterValue): string =>
  Array.isArray(v) ? v.map(show).filter(x => x !== '').join(', ') : v === null || v === undefined ? '' : String(v);

/** The rows of the Properties card, in the order the frontmatter lists them. */
export function propertyRows(text: string): { present: boolean; rows: PropertyRow[] } {
  const fm = parseFrontmatter(text);
  const rows = Object.entries(fm.data).map(([key, v]) => {
    const value = show(v);
    return { key, type: propertyType(key, v), value, empty: value === '' };
  });
  return { present: fm.present, rows };
}

/** Lucide icon name (icons.tsx) for a property type. */
export const PROPERTY_ICON: Record<PropertyType, string> = {
  text: 'propText', list: 'propText', number: 'propNumber', checkbox: 'propCheck', date: 'propText', tags: 'tags',
};

const KEY_OK = /^[^\s:#][^:\n]*$/;

/**
 * Add `key: value` to the note's frontmatter (creating the block when the note has none).
 * Returns the new text, or an error sentence; the note is never rewritten beyond that one line.
 */
export function addProperty(text: string, key: string, value: string): { text: string } | { error: string } {
  const k = key.trim();
  if (!KEY_OK.test(k)) return { error: 'Give the property a name without a colon.' };
  const fm = parseFrontmatter(text);
  if (Object.keys(fm.data).some(x => x.toLowerCase() === k.toLowerCase())) return { error: `The note already has a property named ${k}.` };
  const v = value.replace(/\r?\n/g, ' ').trim();
  const line = v === '' ? `${k}:` : `${k}: ${/^[\w ./:+-]+$/.test(v) ? v : JSON.stringify(v)}`;
  if (!fm.present) return { text: `---\n${line}\n---\n${text}` };
  const m = /^---[ \t]*\r?\n([\s\S]*?)\r?\n?---[ \t]*(?:\r?\n|$)/.exec(text);
  if (!m) return { error: 'The properties block could not be read.' };
  const inner = m[1]!.replace(/\s+$/, '');
  return { text: `---\n${inner === '' ? '' : inner + '\n'}${line}\n---\n${text.slice(m[0].length)}` };
}
