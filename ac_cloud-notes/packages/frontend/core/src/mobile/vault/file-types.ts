import type { FileTypeRow, VaultDecl } from './decl-types';

export const extOf = (path: string): string => {
  const base = path.slice(path.lastIndexOf('/') + 1);
  const dot = base.lastIndexOf('.');
  return dot <= 0 ? '' : base.slice(dot + 1).toLowerCase();
};

export const baseName = (path: string): string => path.slice(path.lastIndexOf('/') + 1);

export const stem = (path: string): string => {
  const b = baseName(path);
  const dot = b.lastIndexOf('.');
  return dot <= 0 ? b : b.slice(0, dot);
};

/** Which viewer handles a file: the row of build.json::vault.file_types its extension is in. */
export function handlerFor(decl: Pick<VaultDecl, 'file_types'>, path: string): FileTypeRow {
  const ext = extOf(path);
  const row = decl.file_types.find(t => t.ext.includes(ext));
  // An external tab (Open with) only ever carries text: the app half reads it as text before handing it over. Its name is
  // the sender's display name, which may have no extension (WhatsApp "DOC-20261123-WA0012") or one the vault does not
  // claim (.log, .conf, .sh), so anything that would land on 'unsupported' opens as Markdown, which shows plain text as is.
  if (path.startsWith('external:') && (!row || row.handler === 'unsupported')) {
    const md = decl.file_types.find(t => t.handler === 'markdown');
    if (md) return md;
  }
  return (
    row ??
    decl.file_types.find(t => t.handler === 'unsupported') ?? {
      handler: 'unsupported',
      label: 'Not supported yet',
      ext: [],
      views: [],
      default_view: '',
      editable: false,
    }
  );
}

/** Handlers whose files are text the editor can load and save. */
export const isTextHandler = (h: FileTypeRow) => h.views.includes('source');
