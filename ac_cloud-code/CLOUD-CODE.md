# cloud-code — Acode, vendored, with a 7-tab bottom nav (#562) and a Chat tab

Owned clone (not a fork: #253/#469) of
[Acode-Foundation/Acode](https://github.com/Acode-Foundation/Acode) at
`b7dbe3d69dcf5165e19581fe3927f0838dce09f4`, with its one git submodule
(`codemirror-lsp-client` @ `db6930c2b6db67e14978f08ae8f5aeb149d29468`) inlined.
No `.git`, no upstream remote. Pins live in `build.json::upstream`.

## Name

The brief proposed `ac_cloud-ide-acode`. That collides with the brand #561
retired: `test-app-names-pattern.sh` T9 fails any build.json value matching
`cloud[-_ ]?ide` as a whole word. The app is **cloud-code** (`ac_cloud-code`,
`com.diegonmarcos.code`, `Cloud-Code.apk`, GHCR `cloud-code`).

## Licence (the brief said "Acode is GPL — verify")

Measured at the pinned revision, it is **not**:

| Part | Licence | In this tree |
|---|---|---|
| Acode (`license.txt`, `package.json`) | MIT | vendored |
| `codemirror-lsp-client` | MIT | vendored |
| `src/plugins/admob`, `cordova-plugin-buildinfo` | MIT | vendored (AdMob never built: paid flavour) |
| `src/plugins/ftp`, `src/plugins/sftp` | Apache-2.0, ftp4j LGPL-2.1 | vendored |
| `src/plugins/proot`: prebuilt `libproot*.so` (GPL-2.0), `libtalloc.so` (LGPL-3.0), `libaxs.so`, three `alpine.rootfs` images | GPL/LGPL **binaries without source** | **PRUNED** |

The proot plugin was the only GPL content, and it was 21 MB of binaries with no
corresponding source. Redistributing those from a public repo and APK would
carry the GPL's source obligation. Pruning them costs nothing here: upstream's own
F-Droid flavour removes that plugin, and the terminal need is served by
the MyTerminal tab. `test/test-cloud-nav.sh` keeps them out: no `*.so` or
`*.rootfs` anywhere, and every `build.json::upstream.pruned` path stays absent.

The fleet-level guard #239 requires of every vendored upstream is the `acode`
entry of `1_cicd/src/data/licence-boundaries.json` (run by `licence-guard.yml`
on every push): it finds this tree by shape, fails if `src/plugins/proot`
returns, and fails if `package.json`, `package-lock.json` or `config.xml`
name the plugin again. Case 11 of `licence-boundary-guard.test.sh` holds it
red against upstream's pristine `package.json` and green against ours.

## What differs from upstream (the whole list)

1. `.github/`, `src/plugins/proot/` removed; proot dropped from `package.json`
   (`devDependencies`, `cordova.plugins`) and `package-lock.json` (3 entries).
2. `.gitmodules` removed, submodule inlined; its `.gitignore` no longer hides
   `dist/`, which upstream commits and `package.json` points `main` at.
3. `.gitignore`: `/build.json` un-ignored. It is the fleet declaration here, and
   the engine hands Cordova an explicit empty `--buildConfig` instead.
4. `build-extras.gradle`: applicationId/version from `build.json` in
   `androidComponents.onVariants`. The widget id stays `com.foxdebug.acode`
   because it is also the Java namespace, and plugin sources import
   `com.foxdebug.acode.R`.
5. `src/plugins/system/plugin.xml`: the FileProvider authority was the literal
   `com.foxdebug.provider`, so installing next to a real Acode would be refused by
   Android (`INSTALL_FAILED_CONFLICTING_PROVIDER`; the Android rule, not observed here). It is now `${applicationId}.foxdebug.provider`
   (nothing referenced the literal).
6. `src/lib/settings.js`: first run no longer adopts `navigator.language` (#299,
   English base). The default `en-us` stands; Settings still offers every language.
7. `src/main.js`: **the seam**, one import plus one `mountCloudNav()` after
   `root.appendOuter(...)`.
8. New: `src/cloud/` (the nav, the app chrome, the Chat tab), `src/plugins/cloudchat/`
   (the Chat's native half, MIT, ours), `tools/resolve-targets.py`, `test/`,
   `build.json`, `build.sh` (vendored engine), this file.
9. `package.json` / `package-lock.json`: the one local plugin `cordova-plugin-cloudchat`
   (`file:src/plugins/cloudchat`), added the way upstream lists its own local plugins.

The editor itself is untouched.

## Flavour

Built as upstream's `paid dev apk fdroid`. **Paid** means no AdMob. **fdroid** means no
proot and no Google Play Billing (`cordova-plugin-iap` removed after setup), and
`hooks/post-process.js` pins targetSdk 28 only when the engine says the declared target is <= 28 (#1708: it is 36 now; proot, its loaders and axs ship as `lib*.so` and run from `nativeLibraryDir`, see `tools/native-tools.json`). These are upstream's own axes, declared in
`build.json::upstream.flavor` and reproduced by `cloud-code-engine.sh`.

## The nav

`src/cloud/nav.json` is the one declaration. The tabs, in order, are
Backlog | Editor | Repos | Chat | Agents | Browser | MyTerminal. Chat replaced Home (its
tiles duplicated the bar; its Configs card, the all-files grant, moved to Profile & Config).

- **Editor**: Acode, untouched. Selecting it hides every cloud panel.
- **Backlog** (#1699): opens on the clone's root `index.html` (sandboxed frame) with Refresh, an offline
  last-good copy (app data dir) and a visible reason when the clone cannot be read; the "Views" switch keeps
  the earlier page: renders `cloud-me-pvt-tasks/1___BACKLOG___/1.1.Product-Backlog/dist/*.md`
  as-is and follows the files' own "Views:" links. That repo is private and this
  APK is public, so it is read from the owner's on-device clone (the folder can be
  changed in the tab). Nothing is re-derived.
- **Agents**: the SAME source as Backlog, by design. The backlog engine
  derives task-effort, task-complexity and agent-model per task and curates
  agent-slot, and emits them as its own "agent batches" and "per agent-model"
  views; this tab renders `nav.json::agents.entry` through the one renderer
  Backlog uses, from the same folder. Only the live half (running status,
  token use) is behind `seams.js::listAgents`, and that is the stub.
- **Repos**: UI over `src/cloud/seams.js`, where every provider
  is a `SEAM:`-marked function. Repos is a small real engine (children of a root
  that hold `.git`, tap opens the folder in the editor).
- **Chat**: see "The Chat tab" below.
- **Browser**: local pages in Acode's own browser plugin (the one Run uses).
- **MyTerminal**: link-out. `nav.json::myterminal.target` is a fleet id
  (`cloud-myterminal`, #561). `tools/resolve-targets.py` derives package and
  launcher activity from that app's own `build.json` and manifest at build
  time, and fails the build if the id resolves to nothing.

### Fit (measured, not assumed)

Seven tabs on the 360 px narrowest viewport are 44.6 px cells in the shared bar (the fleet's
crowded-bar floor is 44, nav-shape N1; `bars.js::cellsClear`, asserted by `cloud_bars.mjs`). The
bottom bar names every tab when the names fit their cells and only the selected tab when one would not
(`fit()`, measured on the device), so a label is never truncated (#565's rule). The older per-tab check (`tabs.js::clearsTouchFloor`, 51.4 px at 48) stays in the tester.

### The shared bars (#1158, the fleet's #1154 design)

The fleet's default navigation is drawn in the page by `src/cloud/bars-dom.js` from `nav.json`,
on the shared page gradient:

- **First row** (`libs:ui-nav-bars`): plain hamburger (tap only, no edge-swipe) opening
  the active page's drawer; the title as a centred island whose tap opens the hierarchy dropdown
  (the bottom page, then its sub-pages); plain AI (sparkles), Profile and Configs (vertical dots).
  AI hands over to the SuperApp (`extapp:cloud-superapp/ai`), Profile to Cloud Account
  (`extapp:cloud-account`), Configs opens Profile & Config, this app's settings.
- **Second row** (`libs:ui-nav-bars`): the page's sub-pages as small icon pills with their
  names, violet rounded-rectangle selection. Backlog: Status, Project, Priority, Effort;
  Agents: Batches, Models (the views the backlog engine already emits). Pages with fewer than two
  sub-pages have no second row.
- **Bottom bar** (`libs:ui-nav-bars`): Cloud Search's glass pill, accent-coloured selection, every
  item an icon over its name.
- **Scroll down** hides the first row and compacts the other two (shorter tabs and bar, only the
  selected item keeps its name); scroll up or the top restores all three (`bars.js::collapseStep`).

Sizes and colours are `nav.json::bars`, equal to the libs' Kotlin constants; the nav-shape guard
(rule W) fails when either side drifts, and holds `nav.json` to `build.json::ui`.

### Why not the fleet's Compose bars hosted natively (declared #565 exception, re-decided in #1158)

The shared libs are Compose. This app is a Cordova web shell whose Android project is generated at
build time (no committed gradle module, no Compose plugin) and whose entire UI is DOM in a single
WebView, so hosting the WebView in a Compose Activity would replace Cordova's activity in a
generated project and stack a second window over the editor. So the same bars are drawn in
Acode's own UI layer (icon font, `fileSystem`, markdown-it, browser plugin, `openFolder`) from the
same declaration and the libs' numbers.

## App chrome

The first row is the app's (see The shared bars): its **hamburger** opens a
drawer of the ACTIVE page's own items (each renderer registers its provider in
`index.js::MENUS`; `tabs.js::menuFor` picks the active tab's and never another's), including the
actions the old per-panel top bar held. `src/cloud/chrome.js::topBar` only names a panel's title
(the AI tab names its agent). Data-dense rows.

- Chat's drawer: New chat, Chats (history: Pinned / Today / Previous 7 days / Older), Search
  chats, Pinned, Export this chat (opens it as Markdown in an editor tab), Rename, Delete.
- Backlog / Agents: Reload, Back to the entry, Edit this file, Open the repository, Folder.
- Repos: Rescan, Root folder. Browser: the presets.
- Profile & Config (`src/cloud/profile.js`): Account (the fleet Account: whether the Cloud
  Account binder answers and whether it holds an OpenRouter key, never the key; Open Cloud Account),
  the OpenRouter token (password field, saved encrypted by the plugin, shown masked, Test,
  Remove), Chat defaults (agent, model, effort, permission mode, MCP per agent, the gateway),
  Storage (the all-files grant), About (version, commit, licences, source).

## The Chat tab

Cloud Search's chat (Chat › Search) without the search boxes: one message list, streamed
replies, one composer. Declared in `nav.json::chat`; the fleet-shaped data is DERIVED at build
time by `tools/resolve-targets.py` into `targets.gen.json::chat`:

- **Agents** — two sources, never a guess: the C3 catalogue (every `cloud-agi-*` service in
  `ac_cloud-c3/data/services_private.json`, baked at build time) and the fleet agent gateway's
  live `GET /health.modes` (my-ai-api, `cloud-agi-goose`, WireGuard-only). Order: nav.json's
  declared agents (Hermes, OpenClaw, Goose, Claude, **DeepSeek** (the gateway's deepseek mode: DeepSeek models with its MCP tools; only DeepSeek rows can be picked), then **OpenRouter · fleet** — the gateway's
  own OpenAI-compatible face, any model, paid by the gateway's key), then any mode the gateway
  serves that the app does not declare (it appears with no app change: its model and functions
  come from `/health`), then any other catalogue agent ("no app API yet"), then OpenRouter
  direct (your token). States: ready, **offline** (the gateway did not answer: still
  selectable), unavailable (the gateway says why), not deployed, no app API. `X-Agent-Mode`
  picks hermes / openclaw / goose / claude-cli / openrouter. **OpenClaw** runs as
  cloud-agi-openclaw (loopback-only, behind the gateway). **Hermes** is the real Hermes Agent API
  once the gateway holds its key (`/health.modes[hermes].native`), else the Hermes model behind
  the gateway's MCP loop; the picker says which.
- **Model** — the button sits right after Agent and opens a full **page**: libs:model-catalogue
  (Cloud Search's catalogue, ONE implementation: its logic package and assets are compiled in by
  reference, the plugin hands its `CatalogueJson` to the page), the four Text tables **A0 Code,
  A1 Agentic, A2 Base, A3 Reasoning** with Cloud Search's columns, one radio per row (exactly
  one selected), saved **per agent**; Back closes without change. Rows the agent cannot run are
  greyed with the reason: Hermes, Goose and OpenClaw run their own model ("set by …"), Claude
  only Anthropic rows (passed as the CLI id), OpenRouter (direct or fleet) any row.
- **Effort** Low / Medium / High / Max → OpenRouter `reasoning.effort` (the gateway passes extra
  body fields through), disabled when the model in use does not reason (OpenRouter's
  `supported_parameters`), hidden for Claude, OpenClaw and native Hermes (they take none per
  request).
- **Permission mode** Auto / Accept / Plan, on the composer. No backend the app reaches takes a
  per-request approval switch, so it is sent as an instruction (via=prompt); hidden for
  OpenRouter direct (no tools).
- **MCP** — none for OpenClaw and the OpenRouter backends; the gateway's live `/v1/mcp/status` for Hermes and Goose, else the fleet's derived
  `.mcp.json` names (`_dotfiles/apps/src/root/mcp.json`); toggles saved per agent. The gateway has no
  per-request server switch yet, so the toggles are advisory there (the sheet says so).
- **More** — `nav.json::chat.functions`, filtered by the gateway's live `/health.plugins`:
  gateway sessions (resume one), tool search, models, health; Headroom / RTK / Caveman /
  Principles / compression mode as per-request headers; each agent's OWN functions, live from
  the gateway (`GET /agents/<agent>/<fn>`: Hermes skills, toolsets, sessions, scheduled jobs;
  OpenClaw agent targets); memory; Claude's OAuth
  login; retry, undo, refresh prices. Offline, the declared list, marked offline.
- **+** — the Android photo picker or SAF (no storage permission), and a voice message recorded
  as 16 kHz WAV (RECORD_AUDIO, asked on first use) sent as `input_audio`. The app has no
  speech-to-text, so the audio is sent, not transcribed.

`src/plugins/cloudchat` is the native half: the OpenRouter token in EncryptedSharedPreferences
(Keystore master key; fleet manifest store `cloud_code_chat_secrets`, class secret), never
returned to the page, never logged, added by the plugin to requests to https://openrouter.ai
only; streamed HTTP; the catalogue; attachments. The fleet Account's key is never read
(decisions-use guard D5: no new module holds it; only `aiRoutingSnapshot`'s key_present is asked). `test/test-chat.sh` holds all of it.

## i18n (#299)

English base: see (6) above, held by `test/test-cloud-nav.sh`. No module
entry goes in `1_cicd/src/i18n-policy.json`, because the Android project
is generated by Cordova at build time and there is no committed
`src/main/res/values/strings.xml` for the guard to read. The i18n guard refuses
entries for modules without one. Acode's UI strings are its own
`src/lang/*.json` catalogue.

## Not verified (needs a phone)

The WebView layout, the Backlog read from the device clone, the Repos listing
under scoped storage, the MyTerminal launch, and the Chat on the mesh: the gateway reached at
its private DNS name, a streamed OpenRouter reply, the photo picker, the microphone prompt,
and the Cloud Account binder answering this app.
