// HTML in Edit mode (#1717): the page is shown in a script-less iframe (sandbox allow-same-origin only, so nothing in it can run
// while the app can edit its document), edited in place, and written back as a whole document. These are the pure string parts:
// the policy meta that View adds is marked on the way in and removed on the way out, and the doctype the file started with
// is kept.

export const INJECTED_ATTR = 'data-cnv-injected';

/** Mark the CSP meta that buildSandboxFrame added, so it can be taken out again. */
export function markInjected(srcdoc: string): string {
  return srcdoc.replace(/<meta\s+http-equiv="Content-Security-Policy"/i, `<meta ${INJECTED_ATTR}="1" http-equiv="Content-Security-Policy"`);
}

/** Remove what the editor added (the marked meta). */
export function stripInjected(html: string): string {
  return html.replace(new RegExp(`<meta\\s+${INJECTED_ATTR}="1"[^>]*>\\s*`, 'i'), '');
}

/** The doctype line the page began with, or '' when it had none (the browser would add one on serialising). */
export function doctypeOf(page: string): string {
  return /^\s*(<!doctype[^>]*>)/i.exec(page)?.[1] ?? '';
}

/** The document the editor holds -> the file text: the original doctype, then the document element without our meta. */
export function toFileText(original: string, documentElementHtml: string): string {
  const doctype = doctypeOf(original);
  return (doctype ? doctype + '\n' : '') + stripInjected(documentElementHtml) + '\n';
}

/** The sandbox for the editing frame: the declared one must not allow scripts, whatever it says. */
export function editSandbox(declared: string): string {
  return declared
    .split(/\s+/)
    .filter(t => t !== '' && t !== 'allow-scripts')
    .join(' ');
}
