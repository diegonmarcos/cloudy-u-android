# libs:ai

The fleet's one AI chat (`com.diegonmarcos.superapp.ai`), shared by reference. It replaces `libs:search-page`
and is the page **and** everything behind it, so no Kotlin app draws a chat of its own
(`1_cicd/src/scripts/cloud-android-ai-chat-guard.py` holds them to that). The label is **AI**, everywhere.

## What is in it

| Piece | Where |
|---|---|
| Chat page, Cloud Code's design: greeting, conversation (user bubble, flat Markdown answers with an `agent · model` line), answer **streaming** in, the input and the config bar | `AiPage.kt` (`AiChatPage`, `AiTheme`, `materialAiTheme`) |
| Config bar under the input: (+) · Task · Agent · Model · Effort · Perms · MCP · (More), collapsible to one chip | `AiComposerBar.kt` (`AiComposerBar`) |
| The Model button opens the full **Model Catalogue** (subpages Frontier · Core · Specialist · Medium · Small; Core = libs:model-catalogue's A Text / B Search / C Audio & Speech / D Visual Media tables) and the Task button the **Task Catalogue** (Big LLMs; Medium & Small LLMs); both replace the chat | `AiCataloguePages.kt` (`AiModelCatalogue`, `AiTaskCatalogue`), data `src/main/assets/ai/ai-catalogue.json` via `AiCatalogue.kt` (Cloud Code reads the same file; the Medium / Small rows are held in step with Cloud Tools' `tools-models.json` by test-tools-app.sh) |
| The one design source: sizes, controls, permission modes, effort levels, the More sheet's groups | `src/main/assets/ai/ai-design.json` via `AiDesign.kt`; Cloud Code reads the same file |
| The task mapping (task to agent + model) | `src/main/assets/ai/ai-tasks.json` (held in step with Cloud Tools' `build.json::tools.tasks`) |
| Consent card a mutating tool waits on (Allow / Deny) | `AiPage.kt` (`AiConsentCard`) |
| State: open session, streaming text, pending consent | `AiChatState.kt` |
| Host seam and the standard host (sessions, prefs, backends, tool loop) | `AiHost.kt` (`AiHost`, `AiStandardHost`, `AiAgents`) |
| Backends: fleet gateway, OpenRouter, or an app's own | `AiBackend.kt` (`AiBackend`, `AiGatewayBackend`, `AiOpenRouterBackend`, `AiUrlHttp`) |
| Tool-plugin interface (no tool ships here) | `AiTool.kt` |
| Wire: bodies, SSE parser, answer parser | `AiProtocol.kt` |
| Sessions: stores and Today / Previous 7 days / Older | `AiSessions.kt` |
| The shared contract | `src/main/assets/ai/ai.json` |
| The shared protocol cases | `src/main/assets/ai/protocol-cases.json` |

## The contract (`assets/ai/ai.json`)

One file, read by every consumer: Kotlin apps through `AiConfig`, Cloud Code (JS) through
`ac_cloud-code/tools/resolve-targets.py`, which copies the `gateway` and `openrouter` blocks into
`targets.gen.json`. Nobody restates an endpoint, a path or the `X-Agent-Mode` header name; Cloud Code's
`nav.json::chat` no longer carries `gateway` or `direct`. The agents themselves (identity, mode,
capabilities) stay in `libs:model-catalogue` `assets/agents/agents.json`, which `AiAgents` turns into the picker.

## Backends

* **Gateway** (`AiGatewayBackend`): my-ai-api, WireGuard-only, an OpenAI-style `/v1/chat/completions` that
  picks the agent with the `X-Agent-Mode` header. No token on the phone. The app bakes the gateway's mesh
  address (`ai-gateway.gradle` does it from the fleet catalogue for apps that have no gateway code yet).
* **OpenRouter** (`AiOpenRouterBackend`): the token comes from an `AiTokenSource` the *app* supplies, read at
  the moment of each send. The lib never reads the fleet Account (decisions-use-guard D5) and never stores or
  logs a token.
* **Your own**: implement `AiBackend` (Cloud Tools Small LLMs wraps its task router).

`AiStandardHost` sends through the gateway when an agent is picked, otherwise through `direct`. Streaming is used
when the app's `AiHttp` can hand over chunks (`AiUrlHttp` can; an app's fakeable client gets the whole answer).

## Using it

```kotlin
val cfg = AiConfig.load(context)
val host = AiStandardHost(
    cfg, AiFileSessions(File(filesDir, "ai-sessions.json")), AiMemoryPrefs(cfg.defaultModel),
    direct = AiOpenRouterBackend(cfg, "my-app", AiTokenSource { /* the app's token read */ AiToken(value, why) }),
    gateway = AiGatewayBackend(cfg, BuildConfig.AI_GATEWAY_URL),
    agents = AiAgents.load(context),
)
val chat = AiChatState(host)
// in Compose:
AiChatPage(chat, materialAiTheme())
```

An app that only wants this chat (gateway, history, no token) skips the wiring: `AiFleetChat.chat(context, BuildConfig.AI_GATEWAY_URL)`
is the process's one `AiChatState` (sessions in `filesDir/ai-sessions.json`), and `AiFleetChatPanel(gatewayUrl)` is the page for a
container that is not a whole screen: it is what libs:bottomnav's shared top bar (`AppTopBar`, `ui.top_bar.ai`) shows in its AI panel.

`AiStandardHost` takes `AiTool`s too: a model-called tool that is `mutating` waits for the consent card
(`AiSink.consent`), runs only after Allow, and is told "denied" on Deny. There is no tool in the lib: per-app
tools come with the apps that need them.

## Tests

`AiProtocolTest` (JVM, runs in the consumers' `gradle test`) feeds `protocol-cases.json` to the Kotlin wire.
Cloud Code's `test/chat.mjs` feeds the same file to the JS wire; add a case once and both sides fail until
both implement it.

## One design, two renderers

`assets/ai/ai-design.json` is the only place the chat's sizes, composer controls, Permission Modes, Effort levels and More-sheet groups are written.
Compose reads it through `AiDesign`; Cloud Code (HTML) gets it copied into `targets.gen.json::chat.design` by `tools/resolve-targets.py`, and `view.js` sets every
`metrics` entry as a `--ai-*` CSS custom property. Colours stay the host's own (Acode's theme in Cloud Code, the app's theme in Compose).

An app declares which controls it enables and nothing else: Kotlin apps in `assets/ai/composer.json` (`controls`, `default_task`, `task_required`), Cloud Code in `nav.json::chat.composer`.
A selected Task greys Agent and Model and sets them from the mapping. Permission Mode, MCP selection and the optimisation-plugin toggles ride the request (a standing system
instruction, and request headers on the gateway); Effort is `reasoning.effort`. Usage & Cost shows only what the backend reported (or the app's Costs ledger).

## Model Catalogue tabs, attachments and the image flow (data-driven)

* **Tabs** (`AiModelCatalogue`): `assets/ai/model-tabs.json` declares the layout (header `Core`, then Frontier | Text | Search / Audio & Speech | Image | Video / Specialist | Tasks | Medium | Small), each tab's rules (OpenRouter `architecture.input_modalities` / `output_modalities`, `supported_parameters`, the images / videos / embeddings lists, the declared providers) and the live search (`GET https://openrouter.ai/api/v1/models`, debounced, cached `ttl_minutes`, never polled). `assets/ai/models-snapshot.json` is the baked list `tools/gen-model-snapshot.py --fetch` writes from those rules at refresh time (id, name, provider, modalities, context, price in/out, link); `--check` is the guard (no tab empty, no placeholder text, every entry complete). Cloud Code keeps reading `ai-catalogue.json` unchanged.
* **Attachments**: the (+) menu's Files (system document picker, any type), Image (photo picker) and Camera (a shot into a cache file) become chips with a remove button (`AiChatState.attachments`) and ride the next message (`AiHost.sendWith`). Camera and media access are libs:perms rows the app declares (`ai-design.json` composer.plus.attach[].perms); the lib asks for nothing and shows the host's "Fix in Perms" chip (`AiAttachHost`).
* **Image flow** (`AiMedia`, `AiImageFlow`): an image in the message, or an ask for one, goes to OpenRouter chat completions (`modalities` image + text) with the user's key read per send by the app; the picked model when it draws, else the Image tab's first chat-capable entry. The returned `message.images[]` are kept as files, drawn inline in the answer, and can be saved, shared or re-attached for the next turn.
