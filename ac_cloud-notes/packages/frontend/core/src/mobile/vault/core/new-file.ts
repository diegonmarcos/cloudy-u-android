// New file (ticket 1788): every new note is created in ONE folder, named by build.json::vault.new_file_dir (Notes-Inbox), at the root of the
// mounted source, whatever folder the sidebar has focused. The folder is created when it is missing. Pure over a minimal file system so
// the rule is tested without a device.

export interface InboxFs {
  /** true when [path] exists */
  ensure(path: string): Promise<boolean>;
  /** a folder named [name] inside [dir]; answers its path */
  createFolder(dir: string, name: string): Promise<string>;
}

/** The inbox folder's path, created at the root when it is not there yet. */
export async function ensureInbox(fs: InboxFs, name: string): Promise<{ path: string; created: boolean }> {
  if (await fs.ensure(name)) return { path: name, created: false };
  const path = await fs.createFolder('', name);
  return { path, created: true };
}

/** The first free `<base>[ n]<ext>` among [taken] names. */
export function freeFileName(taken: ReadonlySet<string>, base: string, ext: string): string {
  let n = base;
  for (let i = 1; taken.has(n + ext); i++) n = `${base} ${i}`;
  return n + ext;
}
