// Configs (#1717): one screen, a strip of pages from build.json::vault.configs. The first six are the shared fleet Configs strip
// (General, Perms, Agents, Logcat, X-Ray App, About): a tap opens the Android shared Configs screen on that tab (the native
// host's openFleetConfigsPage); they are not redrawn here. Then this app's own pages: Notes (vault settings), Sources (where it may
// read and write), UI (font family, text size, overall scale) and Vault (what <vault>/.obsidian says).
import { useState } from 'react';

import type { UiPrefs } from '../core/ui-prefs';
import type { VaultDecl } from '../decl-types';
import { Icon } from '../icons';
import { ConfigsPane } from '../panes';
import { SourcePanel } from '../sidebar/SourcePanel';
import type { VaultApi } from '../use-vault';

declare const BUILD_CONFIG: { appVersion: string };

/** The native shell's bridge (NotesShell.Host); absent in a browser. */
const fleetHost = (): { openFleetConfigsPage?: (id: string) => void } | undefined =>
  (window as unknown as { CloudNotesHost?: { openFleetConfigsPage?: (id: string) => void } }).CloudNotesHost;

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
  const [nativeNote, setNativeNote] = useState<string | null>(null);
  const cfg = decl.configs;
  const fs = cfg.ui.font_size;
  const sc = cfg.ui.scale;
  const counts = api.fileIndex.counts({ showHidden: api.showHidden, skipDirs: decl.limits.skip_dirs });
  const byStatus = (s: string) => decl.plugins.filter(p => p.status === s).length;

  const openPage = (p: { id: string; label: string; native?: string }) => {
    if (p.native === undefined) {
      setNativeNote(null);
      setPage(p.id);
      return;
    }
    // a shared Configs tab: the Android screen opens on it; the page the user was on stays
    const h = fleetHost();
    if (h?.openFleetConfigsPage) {
      setNativeNote(null);
      try { h.openFleetConfigsPage(p.native); } catch { setNativeNote(`${p.label} could not be opened.`); }
    } else {
      setNativeNote(`${p.label} is part of the shared Configs of the Android app; it opens there.`);
    }
  };

  const body = () => {
    switch (page) {
      case 'notes':
        return (
          <div data-testid="settings-notes">
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
            <h3>App</h3>
            <Row label="Web bundle">{bundleVersion()}</Row>
            <Row label="Obsidian plugins mapped">{byStatus('builtin')} built in, {byStatus('planned')} planned, {byStatus('na')} not applicable</Row>
            <Row label="Config folder read">{decl.config_dir}</Row>
          </div>
        );
      case 'sources':
        return (
          <div data-testid="settings-sources">
            <p className="cnv-dim">Where Cloud Notes may read and write. A root that is not granted shows why and how to grant it.</p>
            <SourcePanel api={api} decl={decl} />
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
          <button key={p.id} type="button" role="tab" className="cnv-pagetab" ref={el => { if (el && p.id === page) el.scrollIntoView?.({ inline: 'nearest', block: 'nearest' }); }} aria-selected={p.id === page} data-page-tab={p.id} data-native={p.native ?? undefined} onClick={() => openPage(p)}>
            <Icon name={p.icon} size={14} />{p.label}
          </button>
        ))}
      </div>
      <div className="cnv-note cnv-settings">
        {nativeNote && <div className="cnv-banner" data-testid="settings-native-note">{nativeNote}</div>}
        {body()}
        <div style={{ height: 96 }} />
      </div>
    </div>
  );
};
