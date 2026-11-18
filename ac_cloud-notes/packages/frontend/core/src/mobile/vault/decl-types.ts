// Types of build.json::vault (mirrored in vault-decl.json). Declaration is data; these
// are only its shape so the pure modules can take it as an argument.

import type { SourcesDecl } from './core/vault-source';
import type { UiDecl } from './core/ui-prefs';

export type PluginStatus = 'builtin' | 'planned' | 'na';

export interface PluginRow {
  id: string;
  kind: 'core' | 'community';
  status: PluginStatus;
  feature: string;
}

export interface FileTypeRow {
  handler: string;
  label: string;
  ext: string[];
  views: string[];
  default_view: string;
  editable: boolean;
}

export interface RibbonItem {
  id: string;
  label: string;
  icon: string;
  action: string;
}

export interface CommandRow {
  id: string;
  label: string;
  hotkey?: string;
}

export interface HtmlDecl {
  sandbox: string;
  /** #1717 the frame Edit mode uses: allow-same-origin, never allow-scripts */
  edit_sandbox: string;
  csp_default: Record<string, string[]>;
  cdn: {
    default: boolean;
    label: string;
    script: string[];
    style: string[];
    font: string[];
    script_extra: string[];
  };
}

export interface VaultDecl {
  route: string;
  open: {
    saf: boolean;
    direct_roots: string[];
    store: { owner_package: string; authority: string; permission: string; git_subdir: string };
    recent_max: number;
    storage_key: string;
  };
  /** #1717 the roots the Source section offers (core/vault-source.ts) */
  sources: SourcesDecl & { recent_files_max: number };
  limits: { max_text_bytes: number; autosave_ms: number; max_notes: number; max_depth: number; index_chunk: number; skip_dirs: string[] };
  config_dir: string;
  config_files: { id: string; file: string; label: string }[];
  config_dirs: { id: string; dir: string; ext?: string; manifest?: string; label: string }[];
  layout: {
    ribbon: RibbonItem[];
    left_panes: string[];
    right_panes: string[];
    status: string[];
    tab_max: number;
    wide_breakpoint_px: number;
    /** #1717 the floating bottom pill, in order */
    bottom_bar: string[];
    /** #1717 the top-right switch, in order: Raw | Edit | View */
    modes: { id: 'raw' | 'edit' | 'view'; label: string; icon: string }[];
  };
  /** #1717 the Configs screen: its page strip and the UI page's choices */
  configs: { default_page: string; pages: { id: string; label: string; icon: string }[]; ui: UiDecl };
  /** #1717 per handler, the tab view each mode shows; a mode that is missing is not offered */
  view_modes: Record<string, Partial<Record<'raw' | 'edit' | 'view', string>>>;
  commands: CommandRow[];
  file_types: FileTypeRow[];
  html: HtmlDecl;
  plugins: PluginRow[];
}
