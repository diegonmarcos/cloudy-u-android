// URL.canParse (Chrome 120) and URL.parse (Chrome 126) for older Android System
// WebViews (the CI emulator ships WebView 113): without them opening a page threw
// "URL.canParse is not a function" and the editor showed the 500 screen (#1369).
// Guarded: native implementations are never replaced.
type URLStatics = typeof URL & {
  canParse?: (url: string | URL, base?: string | URL) => boolean;
  parse?: (url: string | URL, base?: string | URL) => URL | null;
};

const U = globalThis.URL as URLStatics | undefined;

if (U && typeof U.canParse !== 'function') {
  Object.defineProperty(U, 'canParse', {
    configurable: true,
    writable: true,
    value: (url: string | URL, base?: string | URL): boolean => {
      try {
        new U(url, base);
        return true;
      } catch {
        return false;
      }
    },
  });
}

if (U && typeof U.parse !== 'function') {
  Object.defineProperty(U, 'parse', {
    configurable: true,
    writable: true,
    value: (url: string | URL, base?: string | URL): URL | null => {
      try {
        return new U(url, base);
      } catch {
        return null;
      }
    },
  });
}

export {};
