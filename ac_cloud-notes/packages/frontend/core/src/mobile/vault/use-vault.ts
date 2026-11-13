// All state of the open vault, held by four models and nothing else (#1717):
//   VaultSource  (core/vault-source.ts)  which root is mounted, which could be, and why not
//   FileIndex    (core/file-index.ts)    what is in the root: tree, recent, filters, order, counts
//   TabStore     (core/tab-store.ts)     the open tabs, their order, the active one, history; persisted
//   LinkResolver (core/link-resolver.ts) every link kind, resolved and followed in one place
// This hook wires them to React and to the native plugin; the buffers of the open tabs, the note
// index (built off the UI thread), the recent vault list and the .obsidian report live here too.
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';

import { DEFAULT_PREFS, FileIndex, type IndexEntry, parsePrefs, prefsKey, type SidebarPrefs } from './core/file-index';
import { LinkResolver } from './core/link-resolver';
import {
  activateTab,
  canGoBack,
  canGoForward,
  closeTab as closeTabState,
  EMPTY_TABS,
  goBack as goBackState,
  goForward as goForwardState,
  loadTabs,
  modeOfTab,
  moveTab as moveTabState,
  openTab,
  patchTab as patchTabState,
  readLastVault,
  saveTabs,
  type TabMeta,
  type TabState,
  type ViewMode,
  viewForMode,
  writeLastVault,
} from './core/tab-store';
import { mountSource, resolveSources, type SourcePlace, type SourceRoot } from './core/vault-source';
import type { VaultDecl } from './decl-types';
import { handlerFor, isTextHandler, stem } from './file-types';
import { runIndex } from './index-runner';
import { vaultNative } from './native-host';
import { readObsidianConfig, type VaultConfigReport } from './obsidian-config';
import {
  forgetRecent,
  type KeyValueStore,
  readRecent,
  type RecentVault,
  touchRecent,
} from './recent-vaults';
import { INDEXED_EXT, type VaultIndex } from './vault-index';
import { parentOf, type VaultEntry, type VaultFS, walkVault } from './vault-fs';

export interface Tab extends TabMeta {
  text: string | null;
  dirty: boolean;
  error: string | null;
}

interface Buffer {
  text: string | null;
  dirty: boolean;
  error: string | null;
}

const NO_BUFFER: Buffer = { text: null, dirty: false, error: null };

export interface OpenVault {
  root: string;
  name: string;
  fs: VaultFS;
}

const errText = (e: unknown) =>
  e instanceof Error ? e.message : typeof e === 'object' && e !== null && 'message' in e ? String((e as { message: unknown }).message) : String(e);

const NO_NATIVE = 'This build has no folder access (the Vault plugin is missing).';

export function useVault(decl: VaultDecl, storage: KeyValueStore) {
  const native = vaultNative();
  const [vault, setVault] = useState<OpenVault | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [recent, setRecent] = useState<RecentVault[]>(() => readRecent(storage, decl.open.storage_key));
  const [persisted, setPersisted] = useState<{ root: string; name: string }[]>([]);
  const [showHidden, setShowHidden] = useState(false);
  const [dirs, setDirs] = useState<Record<string, VaultEntry[]>>({});
  const [files, setFiles] = useState<VaultEntry[]>([]);
  const [folders, setFolders] = useState<VaultEntry[]>([]);
  const [walked, setWalked] = useState(false);
  const [truncated, setTruncated] = useState(false);
  const [index, setIndex] = useState<VaultIndex | null>(null);
  const [indexing, setIndexing] = useState<{ parsed: number; total: number } | null>(null);
  const [tabState, setTabState] = useState<TabState>(EMPTY_TABS);
  const [buffers, setBuffers] = useState<Record<string, Buffer>>({});
  const [config, setConfig] = useState<VaultConfigReport | null>(null);
  const [sources, setSources] = useState<SourceRoot[]>(() => resolveSources(decl, native ? [] : null));
  const [prefs, setPrefsState] = useState<SidebarPrefs>(DEFAULT_PREFS);
  const texts = useRef(new Map<string, string>());
  const tabStateRef = useRef<TabState>(EMPTY_TABS);
  tabStateRef.current = tabState;
  const buffersRef = useRef<Record<string, Buffer>>({});
  buffersRef.current = buffers;
  const loading = useRef(new Set<string>());
  const anchor = useRef<{ path: string; heading?: string; block?: string } | null>(null);
  const gen = useRef(0);

  // ---- VaultSource ----

  const refreshSources = useCallback(async () => {
    if (!native) return setSources(resolveSources(decl, null));
    try {
      setSources(resolveSources(decl, (await native.roots()).roots ?? []));
    } catch {
      setSources(resolveSources(decl, []));
    }
  }, [native, decl]);

  useEffect(() => {
    if (!native) return;
    native.persisted().then(r => setPersisted(r.vaults)).catch(() => setPersisted([]));
    void refreshSources();
    // a grant given in Settings or in another app is seen when the page comes back
    const onShow = () => { if (document.visibilityState === 'visible') void refreshSources(); };
    document.addEventListener('visibilitychange', onShow);
    return () => document.removeEventListener('visibilitychange', onShow);
  }, [native, refreshSources]);

  const loadDir = useCallback(
    async (fs: VaultFS, dir: string) => {
      const list = await fs.list(dir);
      setDirs(d => ({ ...d, [dir]: list }));
      return list;
    },
    []
  );

  const reindex = useCallback(
    async (fs: VaultFS, hidden: boolean, myGen: number) => {
      const walkedNow = await walkVault(fs, decl.limits, { hidden });
      if (gen.current !== myGen) return;
      setFiles(walkedNow.files);
      setFolders(walkedNow.dirs);
      setWalked(true);
      setTruncated(walkedNow.truncated);
      const notes = walkedNow.files.filter(f => INDEXED_EXT.includes(f.name.slice(f.name.lastIndexOf('.') + 1).toLowerCase()));
      const chunk = decl.limits.index_chunk;
      const map = new Map<string, string>();
      setIndexing({ parsed: 0, total: notes.length });
      for (let i = 0; i < notes.length; i += chunk) {
        const batch = notes.slice(i, i + chunk);
        const got = await Promise.all(
          batch.map(async n => {
            try {
              return [n.path, n.size > decl.limits.max_text_bytes ? '' : await fs.readText(n.path)] as const;
            } catch {
              return [n.path, ''] as const;
            }
          })
        );
        if (gen.current !== myGen) return;
        got.forEach(([p, t]) => map.set(p, t));
        setIndexing({ parsed: map.size, total: notes.length });
      }
      texts.current = map;
      const idx = await runIndex(map, walkedNow.files.map(f => f.path), {
        chunk,
        onProgress: (parsed, total) => gen.current === myGen && setIndexing({ parsed, total }),
      });
      if (gen.current !== myGen) return;
      setIndex(idx);
      setIndexing(null);
    },
    [decl.limits]
  );

  // ---- tab buffers ----

  const setBuffer = useCallback((path: string, patch: Partial<Buffer>) => {
    setBuffers(b => ({ ...b, [path]: { ...(b[path] ?? NO_BUFFER), ...patch } }));
  }, []);

  /** Read a tab's file into its buffer (once): text handlers load text, others only need to exist. */
  const loadBuffer = useCallback(
    async (fs: VaultFS, path: string, myGen: number) => {
      if (loading.current.has(path)) return;
      const have = buffersRef.current[path];
      if (have && (have.text !== null || have.error !== null)) return;
      loading.current.add(path);
      const next: Buffer = { text: null, dirty: false, error: null };
      try {
        const h = handlerFor(decl, path);
        if (!(await fs.ensure(path))) throw new Error('This file is no longer in the vault.');
        if (isTextHandler(h)) next.text = await fs.readText(path);
        else if (h.handler === 'unsupported') next.error = 'This file type is not supported yet.';
        else next.text = '';
      } catch (e) {
        next.error = errText(e);
      } finally {
        loading.current.delete(path);
      }
      if (gen.current === myGen) setBuffers(b => ({ ...b, [path]: next }));
    },
    [decl]
  );

  const openRoot = useCallback(
    async (root: string, name: string) => {
      if (!native) {
        setError(NO_NATIVE);
        return;
      }
      setBusy(true);
      setError(null);
      const myGen = ++gen.current;
      try {
        const fs = mountSource(native, root);
        await loadDir(fs, '');
        // TabStore: the tabs this root had open last time come back in the same order, same one active
        const restored = loadTabs(storage, root, decl, decl.layout.tab_max);
        setTabState(restored);
        setBuffers({});
        buffersRef.current = {};
        loading.current = new Set();
        setPrefsState(parsePrefs(storage.getItem(prefsKey(root))));
        setVault({ root, name, fs });
        writeLastVault(storage, { root, name });
        setRecent(touchRecent(storage, decl.open.storage_key, decl.open.recent_max, { root, name }));
        setIndex(null);
        setFiles([]);
        setFolders([]);
        setWalked(false);
        setDirs(d => ({ '': d[''] ?? [] }));
        void (async () => {
          const order = restored.active ? [restored.active, ...restored.tabs.map(t => t.path).filter(p => p !== restored.active)] : [];
          for (const p of order) await loadBuffer(fs, p, myGen);
        })();
        readObsidianConfig(
          {
            tryReadText: p => fs.tryReadText(p),
            listNames: async dir => {
              try {
                const parts = dir.split('/');
                // list level by level so every id is known
                let cur = '';
                for (const part of parts) {
                  const hit = (await fs.list(cur)).find(e => e.name === part && e.isDir);
                  if (!hit) return [];
                  cur = hit.path;
                }
                return (await fs.list(cur)).map(e => ({ name: e.name, isDir: e.isDir, size: e.size }));
              } catch {
                return [];
              }
            },
          },
          decl
        ).then(c => gen.current === myGen && setConfig(c)).catch(() => undefined);
        void reindex(fs, false, myGen).catch(e => setError('Indexing failed: ' + errText(e)));
      } catch (e) {
        setError(errText(e));
        setVault(null);
      } finally {
        setBusy(false);
      }
    },
    [native, loadDir, reindex, storage, decl, loadBuffer]
  );

  // The root that was open last is mounted again on start, so its tabs are restored after a restart.
  const autoOpened = useRef(false);
  useEffect(() => {
    if (autoOpened.current || !native) return;
    autoOpened.current = true;
    const last = readLastVault(storage);
    if (last) void openRoot(last.root, last.name);
  }, [native, storage, openRoot]);

  // TabStore persistence: every change of the tabs of the mounted root is written.
  useEffect(() => {
    if (vault) saveTabs(storage, vault.root, tabState);
  }, [vault, tabState, storage]);

  const setPrefs = useCallback(
    (patch: Partial<SidebarPrefs>) => {
      setPrefsState(p => {
        const next = { ...p, ...patch };
        if (vault) {
          try { storage.setItem(prefsKey(vault.root), JSON.stringify(next)); } catch { /* ignored */ }
        }
        return next;
      });
    },
    [vault, storage]
  );

  const pickFolder = useCallback(async () => {
    if (!native) return setError(NO_NATIVE);
    try {
      const v = await native.pickVault();
      await openRoot(v.root, v.name);
      void refreshSources();
    } catch (e) {
      const msg = errText(e);
      if (!/No folder was chosen/i.test(msg)) setError(msg);
    }
  }, [native, openRoot, refreshSources]);

  const openTyped = useCallback(
    async (path: string) => {
      if (!native) return setError(NO_NATIVE);
      try {
        const v = await native.openPath({ path });
        await openRoot(v.root, v.name);
      } catch (e) {
        setError(errText(e));
      }
    },
    [native, openRoot]
  );

  /** Source section: mount one place of a declared root. */
  const mountPlace = useCallback(
    async (place: SourcePlace) => {
      if (place.root.startsWith('content://')) return openRoot(place.root, place.label);
      return openTyped(place.root);
    },
    [openRoot, openTyped]
  );

  /** Source section: the grant action of a root that cannot be opened. */
  const grantSource = useCallback(
    async (root: SourceRoot) => {
      if (!native) return setError(NO_NATIVE);
      try {
        if (root.action === 'grant_storage') await native.openSettings();
        else if (root.action === 'pick_folder') await pickFolder();
        else if (root.action === 'open_app') await native.openApp({ id: root.id });
      } catch (e) {
        setError(errText(e));
      }
      void refreshSources();
    },
    [native, pickFolder, refreshSources]
  );

  const closeVault = useCallback(() => {
    gen.current++;
    writeLastVault(storage, null);
    setVault(null);
    setTabState(EMPTY_TABS);
    setBuffers({});
    setIndex(null);
    setConfig(null);
    setFiles([]);
    setFolders([]);
    setWalked(false);
    setDirs({});
    texts.current = new Map();
  }, [storage]);

  const forget = useCallback(
    (root: string) => {
      setRecent(forgetRecent(storage, decl.open.storage_key, root));
      if (root.startsWith('content://')) void native?.releaseVault({ root }).catch(() => undefined);
      setPersisted(p => p.filter(v => v.root !== root));
    },
    [storage, decl.open.storage_key, native]
  );

  const toggleHidden = useCallback(() => {
    if (!vault) return;
    const next = !showHidden;
    setShowHidden(next);
    const myGen = ++gen.current;
    void reindex(vault.fs, next, myGen).catch(() => undefined);
  }, [vault, showHidden, reindex]);

  const rebuildIndex = useCallback(() => {
    if (!vault) return;
    const myGen = ++gen.current;
    void reindex(vault.fs, showHidden, myGen).catch(e => setError('Indexing failed: ' + errText(e)));
  }, [vault, showHidden, reindex]);

  // ---- FileIndex ----

  const fileIndex = useMemo(() => {
    const listed: IndexEntry[] = Object.values(dirs).flat();
    return new FileIndex([...listed, ...folders, ...files], walked);
  }, [dirs, folders, files, walked]);

  const allPaths = useMemo(() => files.map(f => f.path), [files]);

  // ---- LinkResolver ----

  const resolver = useMemo(() => new LinkResolver(allPaths), [allPaths]);

  // ---- TabStore ----

  const tabs: Tab[] = useMemo(
    () => tabState.tabs.map(t => ({ ...t, ...(buffers[t.path] ?? NO_BUFFER) })),
    [tabState.tabs, buffers]
  );
  const active = tabState.active;

  const openFile = useCallback(
    async (path: string) => {
      if (!vault) return;
      setTabState(s => openTab(s, decl, path, decl.layout.tab_max));
      const size = fileIndex.get(path)?.size ?? 0;
      if (size > decl.limits.max_text_bytes && isTextHandler(handlerFor(decl, path))) {
        setBuffer(path, { text: null, error: 'This file is larger than the text limit.' });
        return;
      }
      await loadBuffer(vault.fs, path, gen.current);
    },
    [vault, decl, fileIndex, loadBuffer, setBuffer]
  );

  const setActive = useCallback(
    (path: string) => {
      setTabState(s => activateTab(s, path));
      if (vault) void loadBuffer(vault.fs, path, gen.current);
    },
    [vault, loadBuffer]
  );

  const closeTab = useCallback((path: string) => {
    setTabState(s => closeTabState(s, path));
    setBuffers(b => {
      const rest = { ...b };
      delete rest[path];
      return rest;
    });
  }, []);

  const moveTab = useCallback((from: number, to: number) => setTabState(s => moveTabState(s, from, to)), []);
  const goBack = useCallback(() => setTabState(s => goBackState(s)), []);
  const goForward = useCallback(() => setTabState(s => goForwardState(s)), []);

  /** Patch a tab: view and cdn belong to the TabStore, text / dirty / error to its buffer. */
  const patchTab = useCallback(
    (path: string, patch: Partial<Tab>) => {
      const { view, cdn, text, dirty, error: err } = patch;
      if (view !== undefined || cdn !== undefined) {
        setTabState(s => patchTabState(s, path, { ...(view !== undefined ? { view } : {}), ...(cdn !== undefined ? { cdn } : {}) }));
      }
      if (text !== undefined || dirty !== undefined || err !== undefined) {
        setBuffer(path, { ...(text !== undefined ? { text } : {}), ...(dirty !== undefined ? { dirty } : {}), ...(err !== undefined ? { error: err } : {}) });
      }
    },
    [setBuffer]
  );

  /** The mode switch: a tab moves to the view that shows [mode]; a file without that mode stays where it is. */
  const setMode = useCallback(
    (path: string, mode: ViewMode) => {
      const view = viewForMode(decl, path, mode);
      if (view !== null) setTabState(s => patchTabState(s, path, { view }));
    },
    [decl]
  );

  const setText = useCallback((path: string, text: string) => setBuffer(path, { text, dirty: true }), [setBuffer]);

  const save = useCallback(
    async (path: string) => {
      const buf = buffersRef.current[path];
      if (!vault || !buf || buf.text === null || !buf.dirty) return;
      const text = buf.text;
      try {
        await vault.fs.writeText(path, text);
        // typed again while the write ran: the buffer stays dirty and the next autosave writes it
        setBuffers(b => (b[path] && b[path]!.text === text ? { ...b, [path]: { ...b[path]!, dirty: false, error: null } } : b));
        const now = Date.now();
        setFiles(f => f.map(x => (x.path === path ? { ...x, mtime: now, size: text.length } : x)));
        if (INDEXED_EXT.includes(path.slice(path.lastIndexOf('.') + 1).toLowerCase())) {
          texts.current.set(path, text);
          const myGen = gen.current;
          void runIndex(texts.current, files.map(f => f.path), { chunk: decl.limits.index_chunk })
            .then(idx => gen.current === myGen && setIndex(idx))
            .catch(() => undefined);
        }
      } catch (e) {
        setBuffer(path, { error: 'Save failed: ' + errText(e) });
      }
    },
    [vault, setBuffer, files, decl.limits.index_chunk]
  );

  // autosave: a dirty tab is written after a quiet period
  useEffect(() => {
    const dirty = tabs.filter(t => t.dirty);
    if (dirty.length === 0) return;
    const id = setTimeout(() => dirty.forEach(t => void save(t.path)), decl.limits.autosave_ms);
    return () => clearTimeout(id);
  }, [tabs, save, decl.limits.autosave_ms]);

  const createFile = useCallback(
    async (dir: string, name: string, text = '') => {
      if (!vault) return null;
      try {
        if (dir !== '' && !(await vault.fs.ensure(dir))) throw new Error(`The folder ${dir} does not exist.`);
        const path = await vault.fs.createFile(dir, name, text);
        texts.current.set(path, text);
        await loadDir(vault.fs, dir);
        const entry: VaultEntry = { path, name, isDir: false, size: text.length, mtime: Date.now() };
        setFiles(f => [...f.filter(x => x.path !== path), entry]);
        setBuffer(path, { text, dirty: false, error: null });
        setTabState(s => openTab(s, decl, path, decl.layout.tab_max));
        return path;
      } catch (e) {
        setError(errText(e));
        return null;
      }
    },
    [vault, loadDir, setBuffer, decl]
  );

  const freeName = useCallback(
    async (dir: string, base: string, ext: string) => {
      let listed = dirs[dir];
      if (!listed && vault) listed = await loadDir(vault.fs, dir).catch(() => []);
      const names = new Set((listed ?? []).map(e => e.name));
      let n = base;
      for (let i = 1; names.has(n + ext); i++) n = `${base} ${i}`;
      return n + ext;
    },
    [dirs, vault, loadDir]
  );

  const newNote = useCallback(async (dir = '') => createFile(dir, await freeName(dir, 'Untitled', '.md')), [createFile, freeName]);

  const newCanvas = useCallback(
    async (dir = '') => createFile(dir, await freeName(dir, 'Untitled', '.canvas'), '{\n\t"nodes": [],\n\t"edges": []\n}'),
    [createFile, freeName]
  );

  /** A new folder inside [dir]; answers its path, or null with the reason in `error`. */
  const newFolder = useCallback(
    async (dir = '', name?: string) => {
      if (!vault) return null;
      try {
        if (dir !== '' && !(await vault.fs.ensure(dir))) throw new Error(`The folder ${dir} does not exist.`);
        const path = await vault.fs.createFolder(dir, name?.trim() || (await freeName(dir, 'Untitled folder', '')));
        await loadDir(vault.fs, dir);
        const entry: VaultEntry = { path, name: path.slice(path.lastIndexOf('/') + 1), isDir: true, size: 0, mtime: Date.now() };
        setFolders(f => [...f.filter(x => x.path !== path), entry]);
        return path;
      } catch (e) {
        setError(errText(e));
        return null;
      }
    },
    [vault, loadDir, freeName]
  );

  /**
   * Follow a link of any kind (LinkResolver decides): open the file it resolves to, open a web
   * address outside, scroll to a heading, or create the missing note next to the current one.
   * `href` marks a markdown destination (percent-encoded, relative), as opposed to a wikilink.
   */
  const followLink = useCallback(
    async (target: string, from: string, opts: { href?: boolean; heading?: string } = {}) => {
      const raw = opts.heading && !target.includes('#') ? `${target}#${opts.heading}` : target;
      const act = resolver.followTarget(raw, from, opts.href === true);
      switch (act.type) {
        case 'open':
          anchor.current = act.heading || act.block ? { path: act.path, heading: act.heading, block: act.block } : null;
          await openFile(act.path);
          if (anchor.current) window.dispatchEvent(new CustomEvent('cloud-notes:vault-anchor', { detail: anchor.current }));
          return;
        case 'external':
          window.open(act.url, '_blank', 'noopener,noreferrer');
          return;
        case 'anchor':
          anchor.current = { path: from, heading: act.heading, block: act.block };
          window.dispatchEvent(new CustomEvent('cloud-notes:vault-anchor', { detail: anchor.current }));
          return;
        case 'create':
          // while the root is still being read a miss may only mean "not seen yet": never create a duplicate then
          if (!walked) return setError('The vault is still being read; try the link again in a moment.');
          await createFile(fileIndex.get(act.dir)?.isDir ? act.dir : parentOf(from), act.name);
          return;
        default:
          setError(act.reason);
      }
    },
    [resolver, openFile, createFile, walked, fileIndex]
  );

  /** The heading a just-followed link asked for in [path] (taken once by the view that renders it). */
  const takeAnchor = useCallback((path: string) => {
    const a = anchor.current;
    if (!a || a.path !== path) return null;
    anchor.current = null;
    return a;
  }, []);

  const readDataUrl = useCallback(
    async (path: string): Promise<string | null> => {
      if (!vault) return null;
      try {
        if (!(await vault.fs.ensure(path))) return null;
        const ext = path.slice(path.lastIndexOf('.') + 1).toLowerCase();
        const mime = ext === 'svg' ? 'image/svg+xml' : ext === 'jpg' ? 'image/jpeg' : `image/${ext}`;
        return `data:${mime};base64,${await vault.fs.readBinary(path)}`;
      } catch {
        return null;
      }
    },
    [vault]
  );

  /** Text of a vault file for an embed: the indexed copy, else a read; null when it cannot be read. */
  const readNote = useCallback(
    async (path: string): Promise<string | null> => {
      const cached = texts.current.get(path);
      if (cached !== undefined) return cached;
      return vault ? vault.fs.tryReadText(path) : null;
    },
    [vault]
  );

  const noteTexts = texts;
  const activeTab = tabs.find(t => t.path === active) ?? null;

  return {
    hasNative: native !== null,
    vault, busy, error, setError, recent, persisted, showHidden, dirs, files, allPaths, truncated,
    index, indexing, tabs, active, setActive, config, noteTexts,
    pickFolder, openRoot, openTyped, closeVault, forget, toggleHidden, rebuildIndex,
    loadDir: (dir: string) => (vault ? loadDir(vault.fs, dir) : Promise.resolve([])),
    openFile, closeTab, patchTab, setText, save, newNote, newCanvas, followLink, readDataUrl,
    titleOf: stem,
    // #1717
    sources, refreshSources, mountPlace, grantSource,
    fileIndex, walked, prefs, setPrefs, newFolder,
    tabState, moveTab, goBack, goForward, canGoBack: canGoBack(tabState), canGoForward: canGoForward(tabState),
    mode: modeOfTab(decl, activeTab), setMode,
    resolver, takeAnchor, readNote,
  };
}

export type VaultApi = ReturnType<typeof useVault>;
