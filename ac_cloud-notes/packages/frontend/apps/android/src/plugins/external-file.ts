import { registerPlugin } from '@capacitor/core';

export interface ExternalFileReadResult {
  content: string;
  name: string;
  size: number;
  path: string;
}

export interface ExternalReadError {
  code: string;
  message: string;
}

export interface ExternalDirEntry {
  name: string;
  isDir: boolean;
  size: number;
}

export interface ExternalDirListing {
  /** Always ends with '/'. */
  path: string;
  /** Absent at the storage root — there is nothing above it to open. */
  parent?: string | null;
  entries: ExternalDirEntry[];
}

export interface ExternalBinaryReadResult {
  /** base64, no line breaks */
  data: string;
  name: string;
  size: number;
  path: string;
}

/** #1642 the text file another app opened with this app (Android VIEW/EDIT), taken once. */
export interface ExternalPendingOpen {
  present: boolean;
  name?: string;
  size?: number;
  content?: string;
  error?: string;
}

export type ExternalFilePluginType = {
  takePendingOpen(): Promise<ExternalPendingOpen>;
  readFile(options: { path: string }): Promise<ExternalFileReadResult>;
  listDir(options: { path: string }): Promise<ExternalDirListing>;
  /** every non-hidden entry of one folder level, any file type (vault attachments) */
  listFiles(options: { path: string }): Promise<ExternalDirListing>;
  readBinary(options: { path: string }): Promise<ExternalBinaryReadResult>;
  openSettings(): Promise<void>;
};

// Wired to app.cloudnotes.pro.plugin.ExternalFilePlugin on Android. Every failure
// resolves to REJECT with a code AND a human message — nothing here returns an
// empty box (ticket #469 requirement).
export const ExternalFile = registerPlugin<ExternalFilePluginType>('ExternalFile');

// Where the explorer starts. The native side refuses anything outside it.
export const EXTERNAL_FILE_ROOT = '/storage/emulated/0/';

export const EXTERNAL_FILE_ERRORS: Record<string, string> = {
  NO_PATH: 'No path was given.',
  OUTSIDE_EMULATED_STORAGE: 'Path must start with /storage/emulated/0/.',
  PERMISSION_DENIED:
    'Storage access is not granted. Use the error message shown to open Settings.',
  NOT_FOUND: 'The file does not exist at that path.',
  IS_DIRECTORY: 'That path is a folder, not a file.',
  NOT_A_DIRECTORY: 'That path is a file, not a folder.',
  UNSUPPORTED_EXTENSION: 'That file type is not supported for import.',
  TOO_LARGE: 'The file is larger than the 10 MiB import limit.',
  READ_ERROR: 'The file could not be read.',
};
