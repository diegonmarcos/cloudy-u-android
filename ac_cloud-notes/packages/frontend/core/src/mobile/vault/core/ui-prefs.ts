// The Configs > UI settings (#1717): font family, base text size and the overall UI scale. Pure: parse and clamp what
// storage holds, and turn it into the CSS custom properties the shell root wears. The choices, ranges and defaults are
// declared in build.json::vault.configs.ui; nothing here states a number or a font stack.

export interface FontFamilyDecl { id: string; label: string; stack: string }
export interface RangeDecl { min: number; max: number; step: number; default: number }
export interface UiDecl {
  storage_key: string;
  font_families: FontFamilyDecl[];
  font_size: RangeDecl;
  scale: RangeDecl;
}

export interface UiPrefs {
  family: string;
  /** base text size in px */
  size: number;
  /** overall UI scale in percent */
  scale: number;
}

export const defaultUiPrefs = (d: UiDecl): UiPrefs => ({ family: d.font_families[0]!.id, size: d.font_size.default, scale: d.scale.default });

const clamp = (v: unknown, r: RangeDecl): number => {
  const n = typeof v === 'number' && Number.isFinite(v) ? v : r.default;
  const snapped = Math.round((n - r.min) / r.step) * r.step + r.min;
  return Math.min(r.max, Math.max(r.min, snapped));
};

/** Whatever storage holds (null, junk, an older shape) becomes valid prefs. */
export function parseUiPrefs(raw: string | null, d: UiDecl): UiPrefs {
  let o: Record<string, unknown> = {};
  try {
    const j = raw ? JSON.parse(raw) : null;
    if (j && typeof j === 'object') o = j as Record<string, unknown>;
  } catch { /* defaults */ }
  const family = d.font_families.some(f => f.id === o.family) ? (o.family as string) : d.font_families[0]!.id;
  return { family, size: clamp(o.size, d.font_size), scale: clamp(o.scale, d.scale) };
}

export const serializeUiPrefs = (p: UiPrefs): string => JSON.stringify({ family: p.family, size: p.size, scale: p.scale });

/**
 * The custom properties for the shell root. The UI sizes follow the text size by the same ratio the Obsidian defaults
 * use (small 13, medium 15, text 16), and the scale is the root's zoom.
 */
export function uiCssVars(p: UiPrefs, d: UiDecl): Record<string, string> {
  const stack = (d.font_families.find(f => f.id === p.family) ?? d.font_families[0]!).stack;
  const ratio = p.size / d.font_size.default;
  const px = (n: number) => `${Math.round(n * ratio * 10) / 10}px`;
  return {
    '--font-interface': stack,
    '--font-text': stack,
    '--font-text-size': `${p.size}px`,
    '--font-ui-small': px(13),
    '--font-ui-medium': px(15),
    '--font-ui-large': px(20),
    '--cnv-scale': String(p.scale / 100),
  };
}
