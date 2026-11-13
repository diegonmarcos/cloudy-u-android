// The file system the Vault view works on. Everything is vault-relative ('a/b.md'); the
// native plugin speaks opaque ids (SAF document ids, absolute paths, store paths), so this
// adapter keeps a path -> id map filled by listings. Reads/writes need the path to have been
// listed once (the explorer lists before anything can be opened), except the root.

export interface NativeEntry {
  name: string;
  id: string;
  isDir: boolean;
  size: number;
  mtime?: number;
}

/** What the Kotlin VaultPlugin implements (name "Vault"). */
export interface VaultNative {
  pickVault(): Promise<{ root: string; name: string }>;
  persisted(): Promise<{ vaults: { root: string; name: string }[] }>;
  releaseVault(options: { root: string }): Promise<void>;
  openPath(options: { path: string }): Promise<{ root: string; name: string }>;
  list(options: { root: string; id: string }): Promise<{ entries: NativeEntry[] }>;
  readText(options: { root: string; id: string }): Promise<{ text: string; size: number }>;
  readBinary(options: { root: string; id: string }): Promise<{ data: string; size: number }>;
  writeText(options: { root: string; id: string; text: string }): Promise<void>;
  create(options: { root: string; parentId: string; name: string; isDir: boolean }): Promise<{ id: string }>;
  rename(options: { root: string; id: string; name: string }): Promise<{ id: string }>;
  remove(options: { root: string; id: string }): Promise<void>;
  openSettings(): Promise<void>;
  /** #1717 the declared roots as this phone sees them (places or a reason each) */
  roots(): Promise<{ roots: { id: string; places: { label: string; root: string }[]; reason?: string | null }[] }>;
  /** #1717 launch the app that owns a root (the open_app grant action) */
  openApp(options: { id: string }): Promise<void>;
}

export interface VaultEntry {
  /** vault-relative path with '/' */
  path: string;
  name: string;
  isDir: boolean;
  size: number;
  mtime: number;
}

export interface VaultFS {
  readonly root: string;
  list(dir: string): Promise<VaultEntry[]>;
  readText(path: string): Promise<string>;
  readBinary(path: string): Promise<string>;
  writeText(path: string, text: string): Promise<void>;
  createFile(dir: string, name: string, text?: string): Promise<string>;
  /** #1717 a new folder inside [dir]; answers its path */
  createFolder(dir: string, name: string): Promise<string>;
  /** #1717 make [path] addressable (list its ancestors); false when it does not exist */
  ensure(path: string): Promise<boolean>;
  /** missing file reads resolve null instead of throwing */
  tryReadText(path: string): Promise<string | null>;
}

export const joinPath = (dir: string, name: string) => (dir === '' ? name : `${dir}/${name}`);
export const parentOf = (path: string) => (path.includes('/') ? path.slice(0, path.lastIndexOf('/')) : '');

export function nativeVaultFS(native: VaultNative, root: string): VaultFS {
  const ids = new Map<string, string>([['', '']]);
  const need = (path: string) => {
    const id = ids.get(path);
    if (id === undefined) throw new Error(`not listed yet: ${path}`);
    return id;
  };
  const fs: VaultFS = {
    root,
    async list(dir) {
      const { entries } = await native.list({ root, id: need(dir) });
      return entries
        .map(e => {
          const path = joinPath(dir, e.name);
          ids.set(path, e.id);
          return { path, name: e.name, isDir: e.isDir, size: e.size, mtime: e.mtime ?? 0 };
        })
        .sort((a, b) => Number(b.isDir) - Number(a.isDir) || a.name.localeCompare(b.name, undefined, { numeric: true }));
    },
    async readText(path) {
      return (await native.readText({ root, id: need(path) })).text;
    },
    async readBinary(path) {
      return (await native.readBinary({ root, id: need(path) })).data;
    },
    async writeText(path, text) {
      await native.writeText({ root, id: need(path), text });
    },
    async createFile(dir, name, text = '') {
      const { id } = await native.create({ root, parentId: need(dir), name, isDir: false });
      const path = joinPath(dir, name);
      ids.set(path, id);
      if (text !== '') await native.writeText({ root, id, text });
      return path;
    },
    async createFolder(dir, name) {
      const { id } = await native.create({ root, parentId: need(dir), name, isDir: true });
      const path = joinPath(dir, name);
      ids.set(path, id);
      return path;
    },
    async ensure(path) {
      try {
        if (ids.has(path)) return true;
        const parts = path.split('/');
        let dir = '';
        for (const part of parts) {
          const next = joinPath(dir, part);
          if (!ids.has(next) && !(await fs.list(dir)).some(e => e.path === next)) return false;
          dir = next;
        }
        return ids.has(path);
      } catch {
        return false;
      }
    },
    async tryReadText(path) {
      try {
        // walk down listing each level so the ids exist
        const parts = path.split('/');
        let dir = '';
        for (const part of parts.slice(0, -1)) {
          if (!ids.has(joinPath(dir, part))) {
            const found = (await fs.list(dir)).find(e => e.name === part && e.isDir);
            if (!found) return null;
          }
          dir = joinPath(dir, part);
        }
        if (!ids.has(path)) {
          const found = (await fs.list(dir)).find(e => e.path === path && !e.isDir);
          if (!found) return null;
        }
        return await fs.readText(path);
      } catch {
        return null;
      }
    },
  };
  return fs;
}

/** Walk the vault and return every file entry (and, #1717, every folder), bounded by the declared limits. */
export async function walkVault(
  fs: VaultFS,
  limits: { max_depth: number; max_notes: number; skip_dirs: string[] },
  opts: { hidden?: boolean } = {}
): Promise<{ files: VaultEntry[]; dirs: VaultEntry[]; truncated: boolean }> {
  const files: VaultEntry[] = [];
  const dirs: VaultEntry[] = [];
  let truncated = false;
  const visit = async (dir: string, depth: number): Promise<void> => {
    if (depth > limits.max_depth) return void (truncated = true);
    for (const e of await fs.list(dir)) {
      if (e.isDir) {
        if (limits.skip_dirs.includes(e.name)) continue;
        if (e.name.startsWith('.') && !opts.hidden) continue;
        dirs.push(e);
        await visit(e.path, depth + 1);
      } else {
        if (e.name.startsWith('.') && !opts.hidden) continue;
        if (files.length >= limits.max_notes * 4) return void (truncated = true);
        files.push(e);
      }
      if (truncated) return;
    }
  };
  await visit('', 0);
  return { files, dirs, truncated };
}
