// Sidebar section C, Source: choose the root ("Parent") to mount. The declared roots (Emulated,
// Termux home, Nix home, External drive) come from VaultSource; a root that cannot be opened
// shows its reason and the action that grants it. Below them: a folder picked in the system
// picker, the recent vaults, a typed path, and the import of a file or folder into the workspace
// (the former floating button). The same panel is the body of the start screen.
import { useState } from 'react';

import type { VaultDecl } from '../decl-types';
import { sourceOfRoot } from '../core/vault-source';
import { Icon } from '../icons';
import type { VaultApi } from '../use-vault';

/** The event the workspace's file importer listens for (apps/android/src/app.tsx, ExternalFileOpener). */
export const IMPORT_EVENT = 'cloud-notes:open-file';
export const requestImport = () => window.dispatchEvent(new Event('cloud-notes:open-file'));

export const SourcePanel = ({ api, decl, onMounted, rootsOnly }: { api: VaultApi; decl: VaultDecl; onMounted?: () => void; /** the start screen lists picked, recent and typed vaults itself */ rootsOnly?: boolean }) => {
  const [typed, setTyped] = useState(decl.open.direct_roots[0] ?? '');
  const mounted = api.vault ? sourceOfRoot(api.sources, api.vault.root) : null;
  const after = (p: Promise<unknown>) => void p.then(() => onMounted?.()).catch(() => undefined);

  return (
    <div className={rootsOnly ? 'cnv-source' : 'cnv-scroll cnv-source'} data-testid="source-panel">
      <div className="cnv-menu-title">Source: root to mount</div>
      {api.sources.length === 0 && <div className="cnv-dim">No roots are declared.</div>}
      {api.sources.map(r => (
        <div key={r.id} className="cnv-source-root" data-source={r.id} data-accessible={r.accessible} data-mounted={mounted?.id === r.id}>
          <div className="cnv-source-head">
            <Icon name={r.icon} size={16} />
            <span style={{ flex: 1 }}>{r.label}</span>
            {mounted?.id === r.id && <span className="cnv-dim">mounted</span>}
          </div>
          {r.places.map(p => (
            <div key={p.root} style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 6 }}>
              <span className="cnv-dim" style={{ flex: 1, overflowWrap: 'anywhere' }}>{p.label}{p.root.startsWith('content://') ? '' : `: ${p.root}`}</span>
              <button type="button" className="cnv-chip cnv-primary" disabled={api.busy} data-open-root={r.id} onClick={() => after(api.mountPlace(p))}>Open</button>
            </div>
          ))}
          {r.reason && (
            <div className="cnv-source-reason" data-reason={r.reason} data-testid="source-reason">
              <Icon name="lock" size={14} />
              <span style={{ flex: 1 }}>{r.reasonText}</span>
            </div>
          )}
          {r.reason && (
            <div style={{ display: 'flex', gap: 6, marginTop: 6, flexWrap: 'wrap' }}>
              {r.action !== 'none' && (
                <button type="button" className="cnv-chip cnv-primary" data-grant={r.id} onClick={() => void api.grantSource(r)}>{r.actionLabel || 'Grant access'}</button>
              )}
              <button type="button" className="cnv-chip" onClick={() => void api.refreshSources()}>Retry</button>
            </div>
          )}
        </div>
      ))}

      {!rootsOnly && (
        <>
      <div className="cnv-menu-title">Picked folder</div>
      <button type="button" className="cnv-row" disabled={api.busy} onClick={() => after(api.pickFolder())} data-testid="source-pick">
        <Icon name="folder" size={16} /> Choose a folder in the system picker
      </button>
      {api.persisted.map(v => (
        <button key={v.root} type="button" className="cnv-row" onClick={() => after(api.openRoot(v.root, v.name))}>
          <Icon name="vault" size={16} /><span style={{ flex: 1 }}>{v.name}<span className="cnv-dim"> granted folder</span></span>
        </button>
      ))}

      {api.recent.length > 0 && <div className="cnv-menu-title">Recent vaults</div>}
      {api.recent.map(r => (
        <div key={r.root} style={{ display: 'flex', alignItems: 'center' }}>
          <button type="button" className="cnv-row" onClick={() => after(api.openRoot(r.root, r.name))}>
            <Icon name="vault" size={16} />
            <span style={{ flex: 1, overflowWrap: 'anywhere' }}>{r.name}<br /><span className="cnv-dim">{r.kind === 'saf' ? 'picked folder' : r.root}</span></span>
          </button>
          <button type="button" className="cnv-btn" aria-label={`Forget ${r.name}`} onClick={() => api.forget(r.root)}><Icon name="close" size={14} /></button>
        </div>
      ))}

      <div className="cnv-menu-title">Open by path</div>
      <p className="cnv-dim" style={{ margin: '0 0 6px' }}>A folder on the phone, or a clone in the fleet store as <code>store:{decl.open.store.git_subdir}/&lt;repo&gt;</code>.</p>
      <div style={{ display: 'flex', gap: 6 }}>
        <input className="cnv-input" value={typed} onChange={e => setTyped(e.target.value)} aria-label="Vault path" data-testid="source-path-input" />
        <button type="button" className="cnv-chip cnv-primary" disabled={api.busy} onClick={() => after(api.openTyped(typed))}>Open</button>
      </div>

        </>
      )}

      <div className="cnv-menu-title">Workspace</div>
      <button type="button" className="cnv-row" onClick={requestImport} data-testid="source-import">
        <Icon name="import" size={16} /> Import a file or folder into the workspace
      </button>
      {api.vault && (
        <button type="button" className="cnv-row" onClick={api.closeVault} data-testid="source-close">
          <Icon name="close" size={16} /> Close this vault
        </button>
      )}
      {api.error && !rootsOnly && <p style={{ color: 'var(--text-error)' }} onClick={() => api.setError(null)}>{api.error}</p>}
      <div style={{ height: 24 }} />
    </div>
  );
};
