<!-- DO NOT EDIT: generated from build.json::vault by scripts/gen-vault-decl.py -->
# Obsidian plugin mapping (Cloud Notes vault view)

Obsidian plugin to Cloud Notes feature. status: builtin = delivered in the app now, planned = on the roadmap (the plugin is listed as not yet supported), na = not applicable (hosted service, desktop-only, or owned by another fleet app). The default set is the union of the core and community plugins enabled in cloud-me-pvt-notes and git-repos-master; test/test-vault.sh fails when a plugin enabled in either vault is missing here.

## Core plugins

| Obsidian plugin | Status | Cloud Notes feature |
|---|---|---|
| `file-explorer` | built-in | Files pane: folder tree, create, open, show-hidden toggle |
| `global-search` | built-in | Search pane: file names and note contents |
| `switcher` | built-in | Quick switcher (Ctrl+O): fuzzy file name jump |
| `graph` | built-in | Graph view over the vault link index (shared graph canvas) |
| `backlink` | built-in | Right sidebar: Backlinks |
| `canvas` | built-in | .canvas files (JSON Canvas) viewed and edited in the canvas pane |
| `outgoing-link` | built-in | Right sidebar: Outgoing links, with unresolved links marked |
| `tag-pane` | built-in | Right sidebar: Tags, with counts, nested tags |
| `footnotes` | planned | Footnote rendering is not in the Markdown renderer yet |
| `properties` | built-in | Frontmatter shown as a Properties block above the note |
| `page-preview` | planned | Hover preview of a linked note |
| `daily-notes` | planned | Vault daily note; the workspace Daily page already exists for app docs |
| `templates` | planned | Insert template command, with the Templater work |
| `note-composer` | planned | Merge and extract notes |
| `command-palette` | built-in | Command palette (Ctrl+P), commands declared in build.json::vault.commands |
| `slash-command` | planned | Slash menu in the editor |
| `editor-status` | built-in | Status bar: words, characters, backlinks, path |
| `bookmarks` | planned | Bookmarks pane |
| `markdown-importer` | not applicable | Not needed: a folder is opened in place as the vault, nothing is imported or converted |
| `zk-prefixer` | not applicable | Unique-note prefixer: not wanted, notes keep their names |
| `random-note` | planned | Open a random note command |
| `outline` | built-in | Right sidebar: Outline from headings |
| `word-count` | built-in | Status bar word and character count |
| `slides` | planned | Presentation from a note |
| `audio-recorder` | not applicable | Cloud Camera and the recorder apps own capture; attach the file instead |
| `workspaces` | planned | Saved pane layouts |
| `file-recovery` | not applicable | The vault is a git clone; history is git, handled in Cloud Drive Git |
| `publish` | not applicable | Obsidian Publish is a hosted service; Cloud Notes is de-clouded |
| `sync` | not applicable | Obsidian Sync is a hosted service; the fleet syncs vaults through Cloud Drive |
| `bases` | planned | Database views over properties, with the dataview work |
| `webviewer` | not applicable | The HTML viewer (sandboxed) covers local pages; no general web browsing in the editor |

## Community plugins

| Obsidian plugin | Status | Cloud Notes feature |
|---|---|---|
| `obsidian-excalidraw-plugin` | planned | Drawing: .canvas covers boards; .excalidraw drawings are not opened yet |
| `templater-obsidian` | planned | Templates with variables, with the templates core plugin |
| `dataview` | planned | Query blocks over frontmatter and tasks, built on the vault index |
| `obsidian-tasks-plugin` | planned | Task queries; checkboxes already render and the index extracts tasks |
| `table-editor-obsidian` | planned | Markdown table editing helpers; tables render now |
| `obsidian-git` | not applicable | Git belongs to Cloud Drive Git (the vault is a clone in the shared store) |
| `calendar` | planned | Calendar pane for daily notes, with the daily-notes core plugin |
| `obsidian-style-settings` | planned | CSS snippet settings; snippets are listed read-only now |
| `obsidian-kanban` | planned | Kanban boards from markdown |
| `remotely-save` | not applicable | Third-party cloud sync; the fleet uses Cloud Drive |
| `quickadd` | planned | Capture macros, with templates and commands |
| `omnisearch` | built-in | Full-text search over note contents in the Search pane (ranked fuzzy search is next) |
| `beautiful-mermaid-renderer` | built-in | Mermaid fences and .mmd files render (mermaid library) |
| `beautiful-mermaid` | built-in | Mermaid fences and .mmd files render (mermaid library) |
| `pdf-plus` | planned | PDF viewer and annotations |
| `obsidian-zotero-desktop-connector` | not applicable | Needs the Zotero desktop app, which the phone does not have |
| `obsidian-html-plugin` | built-in | .html files: sandboxed render plus source, offline unless the CDN toggle is on |
| `json-editor` | built-in | .json files: tree viewer plus text editor |
| `xml-viewer` | planned | XML tree viewer; .xml opens as text now |
| `notebook-navigator` | planned | Alternative file navigator; the Files pane is the plain tree |
| `show-hidden-files` | built-in | Files pane toggle: show dotfiles and the .obsidian folder |
| `new-3d-graph` | planned | 3D graph; the 2D graph ships now |

Totals: built-in 19, planned 24, not applicable 10.
