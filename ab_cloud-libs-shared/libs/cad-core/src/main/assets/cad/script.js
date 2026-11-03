// Loads a classic (UMD/IIFE) vendor script once and resolves with the global it defines.
const done = new Map();
export function script(url, global) {
  if (!done.has(url)) done.set(url, new Promise((res, rej) => {
    const s = document.createElement('script'); s.src = url;
    s.onload = () => (window[global] ? res(window[global]) : rej(new Error(global + ' missing')));
    s.onerror = () => rej(new Error('cannot load ' + url)); document.head.appendChild(s);
  }));
  return done.get(url);
}
