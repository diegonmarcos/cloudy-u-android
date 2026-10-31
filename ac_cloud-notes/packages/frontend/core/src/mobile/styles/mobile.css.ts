import { cssVarV2 } from '@cloud-notes/theme/v2';
import { globalStyle } from '@vanilla-extract/css';

import { globalVars } from './variables.css';

globalStyle(':root', {
  vars: {
    [globalVars.appTabHeight]: BUILD_CONFIG.isIOS ? '49px' : '62px',
    [globalVars.appTabSafeArea]: `calc(${globalVars.appTabHeight} + var(--safe-area-inset-bottom, env(safe-area-inset-bottom, 0px)))`,
    '--cloud-notes-edgeless-zoom-toolbar-bottom': `calc(10px + ${globalVars.appTabSafeArea})`,
  },
  userSelect: 'none',
  WebkitUserSelect: 'none',
});

globalStyle('body', {
  height: 'auto',
  minHeight: '100dvh',
  overflowY: 'unset',
});
globalStyle('body:has(>#app-tabs):not(:has(cloud-notes-keyboard-toolbar))', {
  paddingBottom: globalVars.appTabSafeArea,
});
globalStyle('body:has(cloud-notes-keyboard-toolbar)', {
  paddingBottom: `calc(${globalVars.appTabSafeArea} + 46px)`,
});
globalStyle('body:has(cloud-notes-keyboard-toolbar[data-keyboard-visible])', {
  paddingBottom: `calc(${globalVars.appKeyboardHeight} + 46px)`,
});
globalStyle('body:has(cloud-notes-keyboard-toolbar[data-panel-open])', {
  paddingBottom: `calc(${globalVars.appKeyboardStaticHeight} + 46px)`,
});
// #1369 the editor toolbar above the keyboard wears Obsidian mobile's toolbar (the blocksuite widget is reused, not forked):
// --background-secondary, a hairline top border and no shadow, muted 20px Lucide-weight icons, the accent on press.
// The height stays 46px (the body padding above counts on it).
globalStyle('cloud-notes-keyboard-toolbar .keyboard-toolbar', {
  backgroundColor: 'var(--background-secondary)',
  borderTop: '1px solid var(--background-modifier-border)',
  boxShadow: 'none',
  gap: '4px',
});
globalStyle('cloud-notes-keyboard-toolbar .keyboard-toolbar icon-button', {
  color: 'var(--text-muted)',
  borderRadius: 'var(--radius-s)',
});
globalStyle('cloud-notes-keyboard-toolbar .keyboard-toolbar icon-button:active', {
  color: 'var(--interactive-accent)',
});
globalStyle('cloud-notes-keyboard-toolbar .keyboard-toolbar icon-button svg', {
  width: '20px',
  height: '20px',
});
globalStyle('cloud-notes-keyboard-toolbar .keyboard-toolbar .divider', {
  borderColor: 'var(--background-modifier-border)',
});
globalStyle('body:has(>#app-tabs) edgeless-toolbar-widget', {
  bottom: globalVars.appTabSafeArea,
});

globalStyle('html', {
  height: '100dvh',
  overflowY: 'auto',
  background: cssVarV2('layer/background/secondary'),
});

globalStyle('a:focus', {
  outline: 'none',
});
globalStyle('button:focus', {
  outline: 'none',
});
