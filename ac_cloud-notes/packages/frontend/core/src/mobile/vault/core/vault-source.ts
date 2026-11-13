// VaultSource: the ONE answer to "which root is mounted, and which roots could be". The
// declared roots (build.json::vault.sources: Emulated, Termux home, Nix home, External drive;
// the same ids, labels, order and reason names as Cloud Drive's editor explorer, pinned by
// test/vault/source.test.mjs) are merged with what the phone answers (VaultPlugin.roots). A root
// that cannot be opened is NOT dropped: it stays in the Source section with its reason and the
// action that grants it. Mounting a place is the only way a root becomes a VaultFS.
import type { VaultDecl } from '../decl-types';
import { nativeVaultFS, type VaultFS, type VaultNative } from '../vault-fs';

/** Why a root cannot be browsed (the names Cloud Drive's EditorRoots.Reason uses). */
export type SourceReason = 'NO_STORAGE_GRANT' | 'APP_NOT_INSTALLED' | 'NOT_READABLE' | 'NO_DRIVE';
export const SOURCE_REASONS: SourceReason[] = ['NO_STORAGE_GRANT', 'APP_NOT_INSTALLED', 'NOT_READABLE', 'NO_DRIVE'];

/** What the grant button does. */
export type SourceAction = 'grant_storage' | 'pick_folder' | 'open_app' | 'none';

export interface SourceRootDecl {
  id: string;
  label: string;
  icon: string;
  kind: string;
  /** the app that owns this home (Termux, Nix) */
  owner?: { package: string };
  fleet?: string;
}

export interface SourceReasonDecl {
  /** sentence shown beside the root; {label} is the root's label */
  text: string;
  action: SourceAction;
  action_label: string;
}

export interface SourcesDecl {
  roots: SourceRootDecl[];
  reasons: Record<string, SourceReasonDecl>;
  /** shown when the page runs without the Android app (web preview) */
  no_native: string;
}

/** One folder a root opens at; External carries one per mounted drive. */
export interface SourcePlace {
  label: string;
  /** what VaultPlugin mounts: an absolute path, a content:// tree or store:<path> */
  root: string;
}

/** What VaultPlugin.roots answers for one declared id. */
export interface NativeRootAnswer {
  id: string;
  places: SourcePlace[];
  reason?: string | null;
}

export interface SourceRoot extends SourceRootDecl {
  places: SourcePlace[];
  reason: SourceReason | null;
  /** the sentence for [reason], or '' */
  reasonText: string;
  action: SourceAction;
  actionLabel: string;
  accessible: boolean;
}

const isReason = (r: unknown): r is SourceReason => typeof r === 'string' && (SOURCE_REASONS as string[]).includes(r);

/**
 * The declared roots in their declared order, each with its places or its reason. [answers] null
 * means there is no native side (web preview): every root is listed as not readable, with the
 * declared no-native sentence, so the section never comes up empty or throws.
 */
export function resolveSources(decl: Pick<VaultDecl, 'sources'>, answers: readonly NativeRootAnswer[] | null): SourceRoot[] {
  const sources = decl.sources;
  if (!sources || !Array.isArray(sources.roots)) return [];
  const byId = new Map((answers ?? []).filter(a => a && typeof a.id === 'string').map(a => [a.id, a]));
  return sources.roots.map(r => {
    const a = byId.get(r.id);
    const places = (a?.places ?? []).filter(p => p && typeof p.root === 'string' && p.root !== '').map(p => ({ label: p.label || r.label, root: p.root }));
    let reason: SourceReason | null = null;
    if (places.length === 0) reason = isReason(a?.reason) ? a.reason : 'NOT_READABLE';
    const rd = reason ? sources.reasons?.[reason] : undefined;
    const text = answers === null ? sources.no_native : (rd?.text ?? '');
    return {
      ...r,
      places,
      reason,
      reasonText: reason ? text.replace(/\{label\}/g, r.label) : '',
      action: reason && answers !== null ? (rd?.action ?? 'none') : 'none',
      actionLabel: reason && answers !== null ? (rd?.action_label ?? '') : '',
      accessible: reason === null,
    };
  });
}

/** The declared root a mounted vault root sits in (the Source section marks it), or null for a picked folder. */
export function sourceOfRoot(roots: readonly SourceRoot[], mounted: string): SourceRoot | null {
  const m = mounted.replace(/\/+$/, '');
  for (const r of roots) {
    for (const p of r.places) {
      const base = p.root.replace(/\/+$/, '');
      if (m === base || (base !== '' && !base.startsWith('content://') && m.startsWith(base + '/')) || (base === 'store:' && m.startsWith('store:'))) return r;
    }
  }
  return null;
}

/** Mount a root: the one place a VaultFS is made. */
export function mountSource(native: VaultNative, root: string): VaultFS {
  return nativeVaultFS(native, root);
}
