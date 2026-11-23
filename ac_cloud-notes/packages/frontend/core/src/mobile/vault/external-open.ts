// #1717 a text file another app opened with Cloud Notes (Open with): the app half hands it here and it
// becomes a tab of the vault shell. The queue outlives the shell, so a file that arrives before the
// vault is mounted is taken as soon as it is.
export interface ExternalDoc {
  name: string;
  content: string;
  /** #1866 an EDIT on a writable content URI: saving the tab writes back to the source */
  writable?: boolean;
}

export const EXTERNAL_PREFIX = 'external:';
export const EXTERNAL_DOC_EVENT = 'cloud-notes:external-doc';

const queue: ExternalDoc[] = [];

export const isExternalPath = (path: string): boolean => path.startsWith(EXTERNAL_PREFIX);

// #1866 write-back: the app half registers the saver (native saveOpenFile); the tabs opened from a writable EDIT are remembered by name.
type ExternalSaver = (name: string, text: string) => Promise<void>;
let saver: ExternalSaver | null = null;
const writableNames = new Set<string>();

export const setExternalSaver = (fn: ExternalSaver | null): void => {
  saver = fn;
};

export const isExternalWritable = (path: string): boolean =>
  saver !== null && isExternalPath(path) && writableNames.has(path.slice(EXTERNAL_PREFIX.length));

export const saveExternalDoc = async (path: string, text: string): Promise<void> => {
  if (!saver || !isExternalWritable(path)) throw new Error('This file was not opened for editing.');
  await saver(path.slice(EXTERNAL_PREFIX.length), text);
};

export const pushExternalDoc = (doc: ExternalDoc): void => {
  if (doc.writable) writableNames.add(doc.name);
  else writableNames.delete(doc.name);
  queue.push(doc);
  if (typeof window !== 'undefined') window.dispatchEvent(new Event(EXTERNAL_DOC_EVENT));
};

export const takeExternalDocs = (): ExternalDoc[] => queue.splice(0, queue.length);
