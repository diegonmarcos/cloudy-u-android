import { useThemeColorV2 } from '@cloud-notes/component';
import { useEffect, useMemo } from 'react';

import { VAULT_DECL } from './decl';
import type { KeyValueStore } from './recent-vaults';
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

  // #1717 there is no start screen: the shell is always the screen. With nothing mounted the sidebar opens on its Source section
  // (the declared roots with reason and grant, a picked folder, recent vaults, a typed path, the import).
  const { vault, prefs, setPrefs } = api;
  useEffect(() => {
    if (!vault && prefs.view !== 'source') setPrefs({ view: 'source' });
  }, [vault, prefs.view, setPrefs]);

  return (
    <>
      <style>{VAULT_CSS}</style>
      <VaultShell api={api} decl={decl} />
    </>
  );
};
