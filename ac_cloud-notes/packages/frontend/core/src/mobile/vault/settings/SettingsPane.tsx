// Configs (#1717): one screen, a strip of pages. The three the whole fleet offers (General, Perms, About), then UI (font
// family, text size, overall scale) and Vault (what <vault>/.obsidian says). The pages come from build.json::vault.configs.
import { useState } from 'react';

import type { UiPrefs } from '../core/ui-prefs';
import type { VaultDecl } from '../decl-types';
import { Icon } from '../icons';
import { ConfigsPane } from '../panes';
import { SourcePanel } from '../sidebar/SourcePanel';
import type { VaultApi } from '../use-vault';

declare const BUILD_CONFIG: { appVersion: string };

const bundleVersion = (): string => {
  try { return BUILD_CONFIG.appVersion; } catch { return 'unknown'; }
};

export interface SettingsProps {
  api: VaultApi;
  decl: VaultDecl;
  ui: UiPrefs;
  onUi: (next: UiPrefs) => void;
  followTheme: boolean;
  onToggleTheme: () => void;
}

const Row = ({ label, children, hint }: { label: string; children: React.ReactNode; hint?: string }) => (
  <div className="cnv-setting">
    <div className="cnv-setting-text"><div>{label}</div>{hint && <div className="cnv-dim">{hint}</div>}</div>
    <div className="cnv-setting-control">{children}</div>
  </div>
);

export const SettingsPane = ({ api, decl, ui, onUi, followTheme, onToggleTheme }: SettingsProps) => {
  const [page, setPage] = useState(decl.configs.default_page);
  const cfg = decl.configs;
  const fs = cfg.ui.font_size;
  const sc = cfg.ui.scale;
  const counts = api.fileIndex.counts({ showHidden: api.showHidden, skipDirs: decl.limits.skip_dirs });
  const byStatus = (s: string) => decl.plugins.filter(p => p.status === s).length;

  const body = () => {
    switch (page) {
      case 'general':
        return (
          <div data-testid="settings-general">
            <h3>Vault</h3>
            <Row label="Name">{api.vault?.name ?? 'No vault mounted'}</Row>
            <Row label="Contents">{counts.files} files, {counts.folders} folders{api.walked ? '' : ' (reading)'}</Row>
            <Row label="Show hidden files" hint="Dot files and folders in the sidebar tree">
              <button type="button" className="cnv-chip" aria-pressed={api.showHidden} onClick={api.toggleHidden}>{api.showHidden ? 'On' : 'Off'}</button>
            </Row>
            <Row label="Follow the app theme" hint="Off: the vault is always dark">
              <button type="button" className="cnv-chip" aria-pressed={followTheme} onClick={onToggleTheme}>{followTheme ? 'On' : 'Off'}</button>
            </Row>
            <Row label="Rebuild the vault index" hint="Re-read every note for links, tags and search">
              <button type="button" className="cnv-chip" onClick={() => void api.rebuildIndex()}>Rebuild</button>
            </Row>
            <h3>Editing</h3>
            <Row label="Autosave">after {decl.limits.autosave_ms} ms of no typing</Row>
            <Row label="Open tabs">up to {decl.layout.tab_max}</Row>
          </div>
        );
      case 'perms':
        return (
          <div data-testid="settings-perms">
            <p className="cnv-dim">Where Cloud Notes may read and write. A root that is not granted shows why and how to grant it.</p>
            <SourcePanel api={api} decl={decl} />
          </div>
        );
      case 'about':
        return (
          <div data-testid="settings-about">
            <Row label="App">Cloud Notes</Row>
            <Row label="Web bundle">{bundleVersion()}</Row>
            <Row label="Vault route">{decl.route}</Row>
            <Row label="Obsidian plugins mapped">{byStatus('builtin')} built in, {byStatus('planned')} planned, {byStatus('na')} not applicable</Row>
            <Row label="Config folder read">{decl.config_dir}</Row>
          </div>
        );
      case 'ui':
        return (
          <div data-testid="settings-ui">
            <Row label="Font family" hint="Interface and notes">
              <select className="cnv-input" aria-label="Font family" data-testid="ui-family" value={ui.family} onChange={e => onUi({ ...ui, family: e.target.value })}>
                {cfg.ui.font_families.map(f => <option key={f.id} value={f.id}>{f.label}</option>)}
              </select>
            </Row>
            <Row label="Font size" hint={`${ui.size} px`}>
              <input type="range" aria-label="Font size" data-testid="ui-size" min={fs.min} max={fs.max} step={fs.step} value={ui.size} onChange={e => onUi({ ...ui, size: Number(e.target.value) })} />
            </Row>
            <Row label="UI scale" hint={`${ui.scale} %`}>
              <input type="range" aria-label="UI scale" data-testid="ui-scale" min={sc.min} max={sc.max} step={sc.step} value={ui.scale} onChange={e => onUi({ ...ui, scale: Number(e.target.value) })} />
            </Row>
            <Row label="Reset">
              <button type="button" className="cnv-chip" data-testid="ui-reset" onClick={() => onUi({ family: cfg.ui.font_families[0]!.id, size: fs.default, scale: sc.default })}>Defaults</button>
            </Row>
            <div className="cnv-note" style={{ marginTop: 12 }}>
              <h3>Preview</h3>
              <p>The quick brown fox jumps over the lazy dog. 0123456789</p>
            </div>
          </div>
        );
      default:
        return <ConfigsPane config={api.config} decl={decl} vaultName={api.vault?.name ?? ''} />;
    }
  };

  return (
    <div className="cnv-pane" data-testid="configs-pane" data-page={page}>
      <div className="cnv-pagetabs" role="tablist" aria-label="Configs pages" data-testid="configs-tabs">
        {cfg.pages.map(p => (
          <button key={p.id} type="button" role="tab" className="cnv-pagetab" aria-selected={p.id === page} data-page-tab={p.id} onClick={() => setPage(p.id)}>
            <Icon name={p.icon} size={14} />{p.label}
          </button>
        ))}
      </div>
      <div className="cnv-note">{body()}<div style={{ height: 96 }} /></div>
    </div>
  );
};
