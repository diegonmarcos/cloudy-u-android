# cloud-writer

The application the owner asked for: **one place that owns Text Enhance,
Translate, Grammar Check and Summary ("Resume"), with its own model choice for
each of them separately.** Type or paste a text, pick a tool, get the result
back in a box you can still edit.

Design: [`a0_docs/eng-specs/cloud-writer-text-tools-home.md`](../a0_docs/eng-specs/cloud-writer-text-tools-home.md).

## What this application is, precisely

It **routes**. It holds its own registry (`build.json::writer_ai`), its own
composed prompts, its own provider, and a **model per tool** — which no other
application in this fleet has. It sends all of that to whichever peer serves
`ITextTools`, and that peer makes the request with its own credential.

It does **not serve**, it holds **no API key**, and it opens **no socket**.

## Why it does not serve yet, and what has to be true before it does

`TextTools.SERVICE_PACKAGES` is an ordered preference — cloud-writer first, then
cloud-keyboard — resolved at every bind against whoever actually publishes the
`ITextTools` action. So **the day this application's manifest publishes that
action, every consumer on the phone rebinds here at its next rebind**:
cloud-mail's Enhance and Resume, cloud-keyboard's own toolbar key, the
SuperApp's Tokens Fleet page. All four tools, at once, with no consumer commit.
That is the mechanism working exactly as designed, and it is why publishing the
action is not a step to take casually.

This build cannot honour that takeover, for two reasons:

1. **The engine is still inside `libs:keyboard`.** `AiRouter`, `TextEnhancer`
   and `GrammarChecker` read `helium314` settings constants and that module's own
   `BuildConfig`, so extracting them into `libs:text-engine` is a refactor of the
   keyboard's hot path — design section 9, step 3, which must land alone.
2. **The credential does not migrate, and nothing can make it.** The owner's
   provider key lives in cloud-keyboard's `SharedPreferences` under
   cloud-keyboard's user id. No install moves it. A serving cloud-writer would
   therefore take four working tools away from the peer that can still do them
   and answer `No API key` to all of them until the key was re-entered by hand.

Reason 2 alone settles it. An application that installs and hijacks the binding
while it cannot answer is worse than no application.

`build.json::_doc_what_serving_needs` lists the four things that must all be
true before this flips, and `test/test-cloud-writer-tools.sh` fails the build if
the manifest starts publishing the action before then.

## Where the tokens live

**In the serving application, exactly where they already are.** cloud-writer
names a provider and a model; the peer spends the credential and only the
resulting text crosses the binder. There is no key slot in `WriterPrefs` and
nothing here calls `revealAiKey` — both are asserted, and both were watched
failing against a planted defect.

The design document's section 4 proposes the opposite posture for the day
cloud-writer serves (every per-application key written into cloud-writer over
`setAiRouting`), and flags it as needing the owner's decision. Nothing in this
build takes that decision.

## Layout

| | |
|---|---|
| `WriterRegistry.kt` | the registry: providers, models, prompts. Reads `BuildConfig.WRITER_AI_ROUTING_B64`, baked from `build.json::writer_ai`. |
| `WriterPrefs.kt` | this application's own settings, **including the per-tool model**. Seeds once from the serving peer so the split does not look like a reset. |
| `WriterTools.kt` | the four tools and **the one routing decision** — which tool reaches which engine, with which model. |
| `MainActivity.kt` | the screen. Plain views, built in code. |

`build.json::modules` declares two: `:app`, and `:libs:text-tools` shared **by
reference** from `ab_cloud-libs-shared`. Nothing else is in this application's
gradle graph, and this application is in nobody else's — so it cannot fail
another application's release and none of them can fail this one.

## Building

There is no JVM in the container that maintains this, so CI is the only
compiler. `./build.sh build` locally; `ship-cloud-writer.yml` publishes
`Cloud-Writer.apk` to the rolling `latest` release and to
`ghcr.io/diegonmarcos/cloud-writer`.

## Design (#1532)

A premium writing surface on the fleet design system. Tokens, states and rules are pure data in `core`
(`Design.kt`: `TokenSet` DARK/LIGHT, `Contrast`, `Controls`, `WriterGrid`; `Format.kt`: `FormatAction`, `Focus`, `Tags`;
`Nav.kt`: `Dest`, `WriterScreens`) so the AA contrast test, the control states, the toolbar actions and the navigation run
on the JVM (`DesignTest`, `FormatTest`). `ui/WriterComponents.kt` is the component set (filled / tonal / text buttons at 40 to
48 dp, chips, document cards, empty state with a vector illustration); icons are Material Symbols rounded only. Static guards:
`test/test-cloud-writer-ui.sh` T7 to T10 and the density guard's `sp_literal` rule.

