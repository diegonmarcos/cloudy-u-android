import { useThemeColorV2 } from '@cloud-notes/component';
import { useMemo, useState } from 'react';

import { VAULT_DECL } from './decl';
import { Icon } from './icons';
import type { KeyValueStore } from './recent-vaults';
import { SourcePanel } from './sidebar/SourcePanel';
import { VAULT_CSS } from './style';
import { useVault } from './use-vault';
import { VaultShell } from './VaultShell';

const memoryStore = (): KeyValueStore => {
  const m = new Map<string, string>();
  return { getItem: k => m.get(k) ?? null, setItem: (k, v) => void m.set(k, v) };
};

const browserStore = (): KeyValueStore => {
  try {
    localStorage.getItem('cloud-notes:probe');
    return localStorage;
  } catch {
    return memoryStore();
  }
};

/** Pages > Vault: open a folder as a vault and work in it like Obsidian. */
export const VaultView = () => {
  useThemeColorV2('layer/background/mobile/primary');
  const decl = VAULT_DECL;
  const storage = useMemo(browserStore, []);
  const api = useVault(decl, storage);
  const [typed, setTyped] = useState(decl.open.direct_roots[0] ?? '');

  return (
    <>
      <style>{VAULT_CSS}</style>
      {api.vault ? (
        <VaultShell api={api} decl={decl} />
      ) : (
        <div className="cnv-start" data-testid="vault-start">
          <h2>Vaults</h2>
          <p className="cnv-dim">Open a folder as a vault: notes, canvases, JSON, HTML and Mermaid files open and edit here. Its <code>{decl.config_dir}</code> settings are shown under Vault configs.</p>
          <button type="button" className="cnv-btn cnv-primary" style={{ width: '100%' }} disabled={api.busy} onClick={() => void api.pickFolder()} data-testid="open-folder">
            <Icon name="folder" /> Open folder as vault
          </button>
          {!api.hasNative && <p className="cnv-dim">Folder access needs the Android app; this page is a preview.</p>}
          {api.error && <p style={{ color: 'var(--text-error)' }} data-testid="vault-error">{api.error}</p>}

          {api.recent.length > 0 && (
            <>
              <h3>Recent</h3>
              {api.recent.map(r => (
                <div key={r.root} style={{ display: 'flex', alignItems: 'center' }}>
                  <button type="button" className="cnv-row" onClick={() => void api.openRoot(r.root, r.name)}>
                    <Icon name="vault" size={16} />
                    <span style={{ flex: 1 }}>{r.name}<br /><span className="cnv-dim">{r.kind === 'saf' ? 'picked folder' : r.root}</span></span>
                  </button>
                  <button type="button" className="cnv-btn" aria-label={`Forget ${r.name}`} onClick={() => api.forget(r.root)}><Icon name="close" size={14} /></button>
                </div>
              ))}
            </>
          )}

          <h3>Open by path</h3>
          <p className="cnv-dim">A folder on the phone, or a clone in the fleet store as <code>store:{decl.open.store.git_subdir}/&lt;repo&gt;</code>.</p>
          <div style={{ display: 'flex', gap: 6 }}>
            <input className="cnv-input" value={typed} onChange={e => setTyped(e.target.value)} aria-label="Vault path" data-testid="path-input" />
            <button type="button" className="cnv-btn cnv-primary" disabled={api.busy} onClick={() => void api.openTyped(typed)}>Open</button>
          </div>
          {/* #1717 the same Source section the left sidebar has: the declared roots with their reason and grant action */}
          <h3>Source</h3>
          <SourcePanel api={api} decl={decl} rootsOnly />
        </div>
      )}
    </>
  );
};
