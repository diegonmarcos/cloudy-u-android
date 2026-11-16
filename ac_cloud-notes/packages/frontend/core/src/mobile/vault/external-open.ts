// #1717 a text file another app opened with Cloud Notes (Open with): the app half hands it here and it
// becomes a tab of the vault shell. The queue outlives the shell, so a file that arrives before the
// vault is mounted is taken as soon as it is.
export interface ExternalDoc {
  name: string;
  content: string;
}

export const EXTERNAL_PREFIX = 'external:';
export const EXTERNAL_DOC_EVENT = 'cloud-notes:external-doc';

const queue: ExternalDoc[] = [];

export const isExternalPath = (path: string): boolean => path.startsWith(EXTERNAL_PREFIX);

export const pushExternalDoc = (doc: ExternalDoc): void => {
  queue.push(doc);
  if (typeof window !== 'undefined') window.dispatchEvent(new Event(EXTERNAL_DOC_EVENT));
};

export const takeExternalDocs = (): ExternalDoc[] => queue.splice(0, queue.length);
