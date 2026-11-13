import type { ReactElement } from 'react';

// #1369 Lucide icons (lucide.dev, ISC licence), the set Obsidian draws with: 24x24 grid, stroke 1.75, default 18px.
// Keys are the names build.json::vault.layout.ribbon[].icon uses, each pointing at its Lucide icon (named in the comment).
const r = (x: number, y: number, w: number, h: number) =>
  `M${x + 2} ${y}h${w - 4}a2 2 0 0 1 2 2v${h - 4}a2 2 0 0 1-2 2h-${w - 4}a2 2 0 0 1-2-2v-${h - 4}a2 2 0 0 1 2-2z`;
const c = (x: number, y: number, rad: number) => `M${x} ${y - rad}a${rad} ${rad} 0 1 0 0 ${2 * rad}a${rad} ${rad} 0 1 0 0-${2 * rad}`;
export const LUCIDE: Record<string, string[]> = {
  folder: ['M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z', 'M2 10h20'], // folder-closed
  search: [c(11, 11, 8), 'm21 21-4.3-4.3'], // search
  jump: ['M14 2v4a2 2 0 0 0 2 2h4', 'M4.268 21a2 2 0 0 0 1.727 1H18a2 2 0 0 0 2-2V7l-5-5H6a2 2 0 0 0-2 2v3', 'm9 18-1.5-1.5', c(5, 14, 3)], // file-search
  graph: [c(12, 18, 3), c(6, 6, 3), c(18, 6, 3), 'M18 9v2c0 .6-.4 1-1 1H7c-.6 0-1-.4-1-1V9', 'M12 12v3'], // git-fork
  canvas: ['M3 3h7v9H3z', 'M14 3h7v5h-7z', 'M14 12h7v9h-7z', 'M3 16h7v5H3z'], // layout-dashboard
  terminal: ['m4 17 6-6-6-6', 'M12 19h8'], // terminal
  gear: ['M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z', c(12, 12, 3)], // settings
  vault: [r(3, 3, 18, 18), c(12, 12, 3), 'm7.9 7.9 2.7 2.7', 'm13.4 13.4 2.7 2.7', 'm16.1 7.9-2.7 2.7', 'm10.6 13.4-2.7 2.7'], // vault
  file: ['M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z', 'M14 2v4a2 2 0 0 0 2 2h4'], // file
  chevron: ['m9 18 6-6-6-6'], // chevron-right
  plus: ['M5 12h14', 'M12 5v14'], // plus
  close: ['M18 6 6 18', 'm6 6 12 12'], // x
  menu: ['M4 12h16', 'M4 6h16', 'M4 18h16'], // menu
  eye: ['M2.062 12.348a1 1 0 0 1 0-.696 10.75 10.75 0 0 1 19.876 0 1 1 0 0 1 0 .696 10.75 10.75 0 0 1-19.876 0', c(12, 12, 3)], // eye
  edit: ['M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z', 'm15 5 4 4'], // pencil
  panelLeft: [r(3, 3, 18, 18), 'M9 3v18'], // panel-left
  panelRight: [r(3, 3, 18, 18), 'M15 3v18'], // panel-right
  outline: ['M3 12h.01', 'M3 18h.01', 'M3 6h.01', 'M8 12h13', 'M8 18h13', 'M8 6h13'], // list
  backlinks: ['M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71', 'M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71'], // link
  outgoing: ['M7 7h10v10', 'M7 17 17 7'], // arrow-up-right
  tags: ['m15 5 6.3 6.3a2.4 2.4 0 0 1 0 3.4L17 19', 'M9.586 5.586A2 2 0 0 0 8.172 5H3a1 1 0 0 0-1 1v5.172a2 2 0 0 0 .586 1.414L8.29 18.29a2.426 2.426 0 0 0 3.42 0l3.58-3.58a2.426 2.426 0 0 0 0-3.42z'], // tags
  // #1717 the redesigned shell (drawn for this app on the same 24x24 grid and stroke)
  newFile: ['M12 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7', 'M18.4 2.6a2.1 2.1 0 0 1 3 3L12 15l-4 1 1-4z'],
  newFolder: ['M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z', 'M12 10v6', 'M9 13h6'],
  sort: ['m3 8 4-4 4 4', 'M7 4v16', 'M11 12h4', 'M11 16h7', 'M11 20h10'],
  collapseAll: ['m7 20 5-5 5 5', 'm7 4 5 5 5-5'],
  expandAll: ['m7 15 5 5 5-5', 'm7 9 5-5 5 5'],
  recent: ['M7 2h10', 'M5 6h14', r(3, 10, 18, 12)],
  filter: ['M22 3H2l8 9.46V19l4 2v-8.54z'],
  more: [c(12, 5, 1), c(12, 12, 1), c(12, 19, 1)],
  back: ['m15 18-6-6 6-6'],
  minus: ['M5 12h14'],
  reset: ['M21 12a9 9 0 1 1-9-9c2.52 0 4.93 1 6.74 2.74L21 8', 'M21 3v5h-5'],
  fit: ['M3 7V5a2 2 0 0 1 2-2h2', 'M17 3h2a2 2 0 0 1 2 2v2', 'M21 17v2a2 2 0 0 1-2 2h-2', 'M7 21H5a2 2 0 0 1-2-2v-2'],
  undo: ['M9 14 4 9l5-5', 'M4 9h10.5a5.5 5.5 0 0 1 0 11H11'],
  redo: ['m15 14 5-5-5-5', 'M20 9H9.5a5.5 5.5 0 0 0 0 11H13'],
  smartphone: [r(5, 2, 14, 20), 'M12 18h.01'],
  usb: [c(10, 7, 1), c(4, 20, 1), 'M4.7 19.3 19 5', 'm21 3-3 1 2 2Z', 'M9.26 7.68 5 12l2 5', 'm10 14 5 2 3.5-3.5'],
  lock: [r(3, 11, 18, 11), 'M7 11V7a5 5 0 0 1 10 0v4'],
  propText: ['M17 6.1H3', 'M21 12.1H3', 'M15.1 18H3'],
  propNumber: ['M4 9h16', 'M4 15h16', 'M10 3 8 21', 'M16 3l-2 18'],
  propCheck: [r(3, 3, 18, 18), 'm9 12 2 2 4-4'],
  chevronDown: ['m6 9 6 6 6-6'],
  check: ['M20 6 9 17l-5-5'],
  import: ['M12 3v12', 'm7 10 5 5 5-5', 'M5 21h14'],
  folderOpen: ['m6 14 1.5-2.9A2 2 0 0 1 9.24 10H20a2 2 0 0 1 1.94 2.5l-1.54 6a2 2 0 0 1-1.95 1.5H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h3.9a2 2 0 0 1 1.69.9l.81 1.2a2 2 0 0 0 1.67.9H18a2 2 0 0 1 2 2v2'],
  link: ['M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71', 'M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71'],
};

export const Icon = ({ name, size = 18 }: { name: string; size?: number }): ReactElement => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.75} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" className={`svg-icon lucide-${name}`}>
    {(LUCIDE[name] ?? LUCIDE.file).map(d => <path key={d} d={d} />)}
  </svg>
);
