// One scoped stylesheet for the whole vault view (class prefix cnv-). #1369: every colour, size and radius is an
// Obsidian default-theme variable (component/src/theme/obsidian.gen.css, generated from data/obsidian-theme.json),
// so the vault wears the app theme in light and dark. No literal colours here (test/test-obsidian-theme.sh).
export const VAULT_CSS = `
.cnv-root{display:flex;flex-direction:column;position:fixed;inset:0;z-index:1;min-height:0;font-size:var(--font-ui-medium);font-family:var(--font-interface);color:var(--text-normal);background:var(--background-primary);overflow:hidden}
.cnv-body{display:flex;flex:1;min-height:0;position:relative}
.cnv-ribbon{display:flex;flex-direction:column;gap:2px;padding:6px 4px;border-right:1px solid var(--background-modifier-border);background:var(--background-secondary);flex:none}
.cnv-ribbon.cnv-narrow{flex-direction:row;overflow-x:auto;border-right:0;border-bottom:1px solid var(--background-modifier-border)}
.cnv-btn{display:inline-flex;align-items:center;justify-content:center;gap:6px;min-width:36px;min-height:36px;padding:4px 8px;border:0;border-radius:var(--radius-s);background:transparent;color:var(--text-muted);font:inherit;cursor:pointer}
.cnv-btn:hover,.cnv-btn[aria-pressed=true]{background:var(--background-modifier-hover);color:var(--text-normal)}
.cnv-btn.cnv-primary{background:var(--interactive-accent);color:var(--text-on-accent)}
.cnv-side{width:264px;flex:none;display:flex;flex-direction:column;min-height:0;border-right:1px solid var(--background-modifier-border);background:var(--background-secondary)}
.cnv-side.cnv-right{border-right:0;border-left:1px solid var(--background-modifier-border)}
.cnv-side.cnv-drawer{position:absolute;top:0;bottom:0;z-index:20;width:min(86vw,320px);background:var(--background-secondary);box-shadow:0 0 24px var(--shadow)}
.cnv-side.cnv-drawer.cnv-right{right:0}
.cnv-scrim{position:absolute;inset:0;z-index:19;background:var(--background-modifier-cover)}
.cnv-drawer-actions{display:flex;flex-direction:row;flex-wrap:wrap;justify-content:center;gap:2px;padding:6px 8px;border-bottom:1px solid var(--background-modifier-border);flex:none}
.cnv-drawer-actions .cnv-row{gap:12px;min-height:40px;font-size:var(--font-ui-medium);color:var(--text-normal)}
.cnv-drawer-actions .cnv-row svg{color:var(--text-muted)}
.cnv-side-tabs .cnv-tab{display:inline-flex;align-items:center;justify-content:center;min-width:36px}
.cnv-tabbar{display:flex;gap:2px;padding:4px 4px 0;background:var(--background-secondary);border-bottom:1px solid var(--background-modifier-border);flex:none;overflow-x:auto}
.cnv-tab{padding:6px 12px;border:0;border-radius:var(--radius-m) var(--radius-m) 0 0;background:transparent;color:var(--text-muted);font-size:var(--font-ui-small);cursor:pointer;white-space:nowrap}
.cnv-tab[aria-selected=true]{color:var(--text-normal);background:var(--background-primary)}
.cnv-scroll{flex:1;min-height:0;overflow:auto;padding:8px}
.cnv-row{display:flex;align-items:center;gap:6px;width:100%;padding:6px 8px;border:0;border-radius:var(--radius-s);background:transparent;color:var(--text-normal);font-size:var(--font-ui-small);text-align:left;cursor:pointer}
.cnv-row:hover{background:var(--background-modifier-hover)}
.cnv-row[aria-current=true]{background:var(--background-modifier-active-hover);color:var(--text-normal)}
.cnv-dim{color:var(--text-faint);font-size:var(--font-ui-smaller)}
.cnv-main{flex:1;min-width:0;display:flex;flex-direction:column;min-height:0}
.cnv-pane{flex:1;min-height:0;overflow:auto;padding:12px 16px}
.cnv-input{width:100%;box-sizing:border-box;padding:8px 10px;border:1px solid var(--background-modifier-border);border-radius:var(--radius-s);background:var(--background-primary);color:var(--text-normal);font:inherit}
.cnv-editor{width:100%;height:100%;min-height:50vh;box-sizing:border-box;padding:12px;border:0;outline:0;resize:none;background:transparent;color:inherit;font:var(--font-ui-small)/var(--line-height-normal) var(--font-monospace);tab-size:2}
.cnv-status{display:flex;gap:14px;padding:4px 10px;border-top:1px solid var(--background-modifier-border);background:var(--background-secondary);color:var(--text-muted);font-size:var(--font-ui-smaller);flex:none;overflow-x:auto;white-space:nowrap}
.cnv-note{max-width:700px;margin:0 auto;font-family:var(--font-text);font-size:var(--font-text-size);line-height:var(--line-height-normal);overflow-wrap:anywhere}
.cnv-note h1,.cnv-note h2,.cnv-note h3,.cnv-note h4{line-height:1.2;font-weight:700}.cnv-note h1{font-size:var(--h1-size)}.cnv-note h2{font-size:var(--h2-size)}.cnv-note h3{font-size:var(--h3-size)}.cnv-note h4{font-size:var(--h4-size)}
.cnv-note pre{overflow:auto;padding:10px;border-radius:var(--radius-s);background:var(--background-primary-alt);border:1px solid var(--background-modifier-border)}
.cnv-note code{font-family:var(--font-monospace);font-size:.875em}
.cnv-note table{border-collapse:collapse}.cnv-note td,.cnv-note th{border:1px solid var(--background-modifier-border);padding:4px 8px}
.cnv-note blockquote{margin:8px 0;padding:2px 12px;border-left:2px solid var(--blockquote-border-color)}
.cnv-note a,.cnv-note a.internal-link{color:var(--link-color);text-decoration:underline}
.cnv-note a.tag{color:var(--tag-color);background:var(--tag-background);border-radius:2em;padding:1px 8px;font-size:.875em;text-decoration:none}
.cnv-note .callout{margin:10px 0;padding:8px 12px;border-radius:var(--radius-s);border-left:4px solid var(--color-blue);background:var(--background-modifier-hover)}
.cnv-note .callout[data-callout=warning],.cnv-note .callout[data-callout=caution],.cnv-note .callout[data-callout=attention]{border-color:var(--color-orange);background:var(--background-tint-warning)}
.cnv-note .callout[data-callout=danger],.cnv-note .callout[data-callout=error],.cnv-note .callout[data-callout=failure],.cnv-note .callout[data-callout=bug]{border-color:var(--color-red);background:var(--background-tint-error)}
.cnv-note .callout[data-callout=tip],.cnv-note .callout[data-callout=success],.cnv-note .callout[data-callout=check],.cnv-note .callout[data-callout=done]{border-color:var(--color-green);background:var(--background-tint-success)}
.cnv-note .callout-title{font-weight:600;margin-bottom:4px}
.cnv-note .embed{display:block;margin:8px 0;padding:6px 10px;border:1px solid var(--background-modifier-border);border-radius:var(--radius-s)}
.cnv-note img{max-width:100%}
.cnv-note .task-list-item{list-style:none;margin-left:-1.2em}
.cnv-props{margin:0 auto 12px;max-width:700px;padding:8px 12px;border:1px solid var(--background-modifier-border);border-radius:var(--radius-m);font-size:var(--font-ui-small)}
.cnv-props dt{color:var(--text-muted);float:left;clear:left;margin-right:8px}.cnv-props dd{margin:0 0 2px}
.cnv-mermaid svg{max-width:100%;height:auto}
.cnv-banner{display:flex;gap:10px;align-items:center;flex-wrap:wrap;padding:8px 12px;border-bottom:1px solid var(--background-modifier-border);font-size:var(--font-ui-small);background:var(--background-tint-warning)}
.cnv-iframe{flex:1;width:100%;border:0;background:var(--color-base-00);min-height:60vh}
.cnv-tree-row{font-family:var(--font-monospace);font-size:var(--font-ui-small);display:flex;gap:6px;padding:2px 0;cursor:default;white-space:nowrap}
.cnv-board{position:relative;flex:1;min-height:0;overflow:hidden;touch-action:none;background-color:var(--background-primary);background-image:radial-gradient(var(--background-modifier-border-hover) 1px,transparent 1px);background-size:24px 24px}
.cnv-node{position:absolute;box-sizing:border-box;padding:8px;border:2px solid var(--background-modifier-border-focus);border-radius:var(--radius-m);background:var(--background-primary);color:var(--text-normal);overflow:hidden;font-size:var(--font-ui-small);user-select:none}
.cnv-node.cnv-group{background:var(--background-primary-alt);border-style:dashed;padding:4px 8px}
.cnv-node[aria-selected=true]{outline:2px solid var(--interactive-accent)}
.cnv-board-tools{position:absolute;left:8px;right:8px;bottom:96px;display:flex;gap:6px;flex-wrap:wrap;justify-content:center;z-index:5}
.cnv-board-tools .cnv-btn{background:var(--background-primary);border:1px solid var(--background-modifier-border)}
.cnv-palette{position:absolute;inset:0;z-index:40;display:flex;align-items:flex-start;justify-content:center;padding-top:10vh;background:var(--background-modifier-cover)}
.cnv-palette-box{width:min(94vw,700px);max-height:70vh;display:flex;flex-direction:column;border-radius:var(--radius-l);border:1px solid var(--background-modifier-border);background:var(--background-primary);box-shadow:0 12px 40px var(--shadow);overflow:hidden}
.cnv-palette-box .cnv-input{border:0;border-bottom:1px solid var(--background-modifier-border);border-radius:0;padding:12px 16px;font-size:var(--font-ui-medium)}
.cnv-palette-box .cnv-row{padding:8px 16px;font-size:var(--font-ui-medium)}
.cnv-palette-box .cnv-row[aria-current=true],.cnv-palette-box .cnv-row:hover{background:var(--background-modifier-hover)}
.cnv-palette-foot{display:flex;gap:16px;padding:8px 16px;border-top:1px solid var(--background-modifier-border);color:var(--text-faint);font-size:var(--font-ui-smaller)}
.cnv-start{max-width:34rem;margin:0 auto;padding:20px 16px 110px}
.cnv-top{display:flex;align-items:center;gap:8px;padding:8px 12px;flex:none;position:relative;z-index:6}
.cnv-round{display:inline-flex;align-items:center;justify-content:center;width:44px;height:44px;flex:none;border:0;border-radius:999px;background:var(--background-secondary);color:var(--text-muted);cursor:pointer}
.cnv-round:hover,.cnv-round[aria-pressed=true]{color:var(--text-normal);background:var(--background-modifier-hover)}
.cnv-round:disabled{opacity:.35;cursor:default}
.cnv-crumbs{flex:1;min-width:0;display:flex;justify-content:center;gap:4px;overflow:hidden;white-space:nowrap;font-size:var(--font-ui-small);color:var(--text-muted)}
.cnv-crumbs span{overflow:hidden;text-overflow:ellipsis;min-width:0;flex:0 1 auto}
.cnv-crumbs .cnv-crumb-name{color:var(--text-normal);font-weight:700;flex:0 0 auto;max-width:70%}
.cnv-crumbs .cnv-crumb-sep{color:var(--text-faint);flex:none}
.cnv-pill{display:inline-flex;align-items:center;gap:2px;padding:0 6px;height:44px;flex:none;border-radius:999px;background:var(--background-secondary)}
.cnv-pill .cnv-btn{min-width:36px;min-height:36px;border-radius:999px}
.cnv-mode{display:inline-flex;align-items:center;gap:4px;height:30px;padding:0 10px;border:0;border-radius:999px;background:transparent;color:var(--text-muted);font:inherit;font-size:var(--font-ui-smaller);cursor:pointer}
.cnv-mode[aria-pressed=true]{background:var(--interactive-accent);color:var(--text-on-accent)}
.cnv-mode:disabled{opacity:.35;cursor:default}
.cnv-tabstrip{display:flex;gap:2px;padding:0 8px;flex:none;overflow-x:auto;border-bottom:1px solid var(--background-modifier-border)}
.cnv-tabstrip .cnv-tab{display:inline-flex;align-items:center;gap:6px;max-width:220px}
.cnv-tabstrip .cnv-tab span{overflow:hidden;text-overflow:ellipsis}
.cnv-bottombar{position:absolute;left:50%;bottom:14px;transform:translateX(-50%);z-index:15;display:flex;align-items:center;gap:6px;padding:4px 14px;border-radius:999px;background:var(--background-secondary);box-shadow:0 4px 18px var(--shadow)}
.cnv-bottombar .cnv-btn{min-width:44px;min-height:44px;border-radius:999px;color:var(--text-normal)}
.cnv-bottombar .cnv-btn:disabled{color:var(--text-faint);cursor:default}
.cnv-tabcount{display:inline-flex;align-items:center;justify-content:center;min-width:22px;height:20px;padding:0 3px;border:2px solid currentColor;border-radius:6px;font-size:11px;font-weight:700;line-height:1}
.cnv-menu{position:absolute;z-index:30;min-width:220px;max-width:min(88vw,340px);max-height:70vh;overflow:auto;padding:6px;border:1px solid var(--background-modifier-border);border-radius:var(--radius-l);background:var(--background-primary);box-shadow:0 12px 40px var(--shadow)}
.cnv-menu .cnv-row{min-height:36px;gap:10px}
.cnv-menu hr{border:0;border-top:1px solid var(--background-modifier-border);margin:4px 0}
.cnv-menu-title{padding:6px 8px 2px;color:var(--text-faint);font-size:var(--font-ui-smaller);text-transform:uppercase;letter-spacing:.04em}
.cnv-shade{position:fixed;inset:0;z-index:29}
.cnv-tree .cnv-row{min-height:30px;padding:3px 8px;gap:4px;border-radius:var(--radius-s)}
.cnv-tree .cnv-chev{display:inline-flex;width:18px;flex:none;color:var(--text-faint);transition:transform .1s}
.cnv-tree .cnv-chev[data-open=true]{transform:rotate(90deg)}
.cnv-tree .cnv-name{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.cnv-badge{flex:none;padding:0 4px;border:1px solid currentColor;border-radius:4px;font-size:9px;font-weight:700;line-height:14px;letter-spacing:.03em;color:var(--text-faint)}
.cnv-badge[data-kind=canvas]{color:var(--color-purple)}.cnv-badge[data-kind=data]{color:var(--color-orange)}.cnv-badge[data-kind=image]{color:var(--color-green)}.cnv-badge[data-kind=web]{color:var(--color-blue)}.cnv-badge[data-kind=text]{color:var(--text-muted)}
.cnv-actions{display:flex;justify-content:center;gap:2px;padding:6px 4px;flex:none;flex-wrap:wrap}
.cnv-actions .cnv-btn{min-width:34px;min-height:40px;padding:4px}
.cnv-actions .cnv-btn[data-on=true]{color:var(--interactive-accent)}
.cnv-viewpill{display:flex;align-items:center;gap:10px;margin:2px 10px 6px;padding:0 16px;min-height:48px;border:0;border-radius:999px;background:var(--background-modifier-hover);color:var(--text-normal);font:inherit;font-weight:700;font-size:var(--font-ui-small);cursor:pointer;flex:none;position:relative}
.cnv-viewpill>span{flex:1;text-align:left}
.cnv-vaultfoot{display:flex;align-items:center;gap:8px;padding:4px 10px 10px 18px;flex:none}
.cnv-vaultfoot .cnv-vaultname{flex:1;min-width:0;border:0;background:transparent;color:var(--text-normal);font:inherit;text-align:left;cursor:pointer;padding:0}
.cnv-vaultfoot strong{display:inline-flex;align-items:center;gap:6px;font-size:var(--font-ui-medium)}
.cnv-source{padding:4px 8px 8px}
.cnv-source-root{padding:8px;border:1px solid var(--background-modifier-border);border-radius:var(--radius-m);margin-bottom:6px}
.cnv-source-root[data-mounted=true]{border-color:var(--interactive-accent)}
.cnv-source-head{display:flex;align-items:center;gap:8px;font-weight:600;font-size:var(--font-ui-small)}
.cnv-source-reason{display:flex;gap:8px;align-items:flex-start;margin-top:6px;color:var(--text-muted);font-size:var(--font-ui-smaller)}
.cnv-chip{display:inline-flex;align-items:center;gap:4px;min-height:28px;padding:0 10px;border:1px solid var(--background-modifier-border);border-radius:999px;background:transparent;color:var(--text-normal);font:inherit;font-size:var(--font-ui-smaller);cursor:pointer}
.cnv-chip.cnv-primary{background:var(--interactive-accent);color:var(--text-on-accent);border-color:transparent}
.cnv-propcard{max-width:700px;margin:0 auto 16px;padding:14px 18px;border-radius:24px;background:var(--background-secondary);font-size:var(--font-ui-small)}
.cnv-proptitle{max-width:700px;margin:4px auto 10px;font-weight:700;font-size:var(--font-ui-small)}
.cnv-proprow{display:grid;grid-template-columns:26px minmax(60px,96px) 1fr;gap:10px;align-items:center;min-height:30px;color:var(--text-muted)}
.cnv-proprow .cnv-propkey{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.cnv-proprow .cnv-propval{color:var(--text-normal);overflow-wrap:anywhere}
.cnv-proprow .cnv-propval[data-empty=true]{color:var(--text-faint)}
.cnv-propadd{display:flex;align-items:center;gap:10px;min-height:34px;border:0;background:transparent;color:var(--text-muted);font:inherit;font-weight:600;cursor:pointer;padding:0 2px}
.cnv-filebar{display:flex;gap:6px;align-items:center;flex-wrap:wrap;padding:0 12px 6px;flex:none;color:var(--text-muted);font-size:var(--font-ui-smaller)}
.cnv-rail{position:absolute;right:10px;top:10px;z-index:6;display:flex;flex-direction:column;gap:10px;align-items:center}
.cnv-rail-pill{display:flex;flex-direction:column;border-radius:999px;background:var(--background-secondary);overflow:hidden}
.cnv-rail .cnv-btn{width:40px;height:40px;min-width:40px;border-radius:999px;background:var(--background-secondary);color:var(--text-muted)}
.cnv-rail .cnv-btn:disabled{color:var(--text-faint);cursor:default}
.cnv-rail-pill .cnv-btn{background:transparent}
.cnv-node img{max-width:100%;max-height:100%;object-fit:contain;pointer-events:none}
.cnv-node .cnv-node-title{position:absolute;left:0;top:-22px;font-size:var(--font-ui-smaller);color:var(--text-muted);white-space:nowrap}
.cnv-sheet{position:absolute;inset:0;z-index:35;display:flex;align-items:flex-end;justify-content:center;background:var(--background-modifier-cover)}
.cnv-sheet-box{width:min(100%,640px);max-height:75%;display:flex;flex-direction:column;border-radius:var(--radius-l) var(--radius-l) 0 0;background:var(--background-primary);border:1px solid var(--background-modifier-border);overflow:hidden}
.cnv-sheet-head{display:flex;align-items:center;justify-content:space-between;padding:10px 14px;font-weight:700;border-bottom:1px solid var(--background-modifier-border)}
.cnv-status.cnv-status-inline{border-top:0;background:transparent;padding:6px 8px;flex-wrap:wrap;white-space:normal}
`;
