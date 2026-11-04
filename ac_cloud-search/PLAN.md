# Cloud Search (Cloud Research #1162-#1197) — the plan per section (#797; nav since #1472: Scrappers, Research, CGC Search, Image, Videos, 3D Models; Search (AI Chat) and Media are parked off the bar. News, Shopping and Brokers were removed in #1472)

The design is the owner's mockup (`_dispatch/assets/cloud-search-mockup.html`): a glass theme with
light/dark tokens, a top bar (menu · dynamic island · profile menu), and the app's OWN bottom nav
(House · Jobs · Search · Groceries · Things, Phosphor icons). Every list below is data in
`build.json::search`; this file says what each section reads and why.

Rules that hold for every section:

- **One network door** (`core/Engine.kt` `UrlHttp`) through Android's resolver; one token door
  (`data/Account.kt`, the fleet Account's OpenRouter key, never stored or logged).
- **Caching**: every answer is stored raw under `filesDir` keyed by what was asked; an answer
  younger than `cache_ttl_minutes` (60) is reused, an older one is refetched.
- **Offline**: a failed refetch shows the last stored answer marked *stale* with its date; a
  source never fetched says *failed* and why. Nothing is ever filled in.
- **Legality**: only public APIs whose terms allow it are read. A site whose terms forbid
  automated access, or that has no API, is a `link` — its own search opens in cloud-browser.
  A source that cannot be done reliably is `enabled: false` with its reason, shown on screen.
- **The fleet scraping backend** (scrappers-api, successor of the archived crawlee-cloud) is not a
  source: it sits behind two-factor auth, `/scrape` returns a summary rather than the data, and
  every portal it could be pointed at (ImmoScout24, Immowelt, Kleinanzeigen, StepStone, Indeed,
  LinkedIn, REWE, Lidl, Kaufland, eBay, Vinted) forbids scraping. Its generic crawler now refuses
  any URL the site's robots.txt disallows (cloud-u-containers, #797), so it stays usable for
  sites that do permit it.

## Search

One page, as in the mockup: four engine boxes (Google, DuckDuckGo, Brave, Qwant — each opens the
engine's own results in cloud-browser; no engine's results are scraped) and an AI chat over
OpenRouter. Sessions are stored on the phone (`chat-sessions.json`), grouped Today / Previous 7
Days / Older. The model list is OpenRouter's live catalogue (cached `catalog_ttl_hours`), flagging
models that search the web natively; the Web switch uses native search or the `web` plugin. The
token is read from the fleet Account on every send; without one the chat says why and posts nothing.

## Screen-locked checks

`/api/search/verticals`, `/query?v=&q=&city=`, `/calc?name=&…`, `/analysis?v=`, `/feed?v=` and `/things?q=&lat=&lon=|city=&radius=` run the
same engine and cache as the screens (libs:devtools debug API, group `search`).

## Navigation (#913, #913b)

The island is Me · LLC · Chat · Agents · Reports (`build.json::ui`), drawn in the owner's mockup style (SearchHtmlIsland):

| Section | Pages (PageTabs) | What it is |
|---|---|---|
| LLC | Commercial · Business · Suppliers · Services | commercial search, every source a site search opened in cloud-browser (no portal has an API this app may read): Commercial Real Estate (ImmoScout24, Immowelt, Kleinanzeigen Gewerbe; RS_Commercial-Stores, RS_Commercial-Buildings, Real-Estate), Buy a Business (nexxt-change, DUB), Suppliers / B2B (wer liefert was, Europages, Alibaba), Business Services (Gelbe Seiten, Malt, freelancermap; Services_General, Job-Placement) |
| Chat | Search · Apps · Messages · Code | Search is the engine boxes + AI chat (where a query from outside lands, #937); Apps, Messages and Code are the fleet searches that were the Cloud page: Apps searches the fleet manifest and launches the app, Messages asks Cloud Mail through the read-only agent door, Code searches the owner's repositories on GitHub in Cloud Browser and opens Cloud Code |
| Agents | Agents · Runs · Templates · Settings | draft-only agents, grouped Buy-Side › Real Estate / Things / Services, then Sell-Side |
| Reports | list, then detail | what agents published, newest first |

## Agents (#913): draft only

An agent READS and DRAFTS; a person copies the draft and sends it. Nothing is posted, submitted or sent for
the owner, and there is no button that does. Everything is declared in `build.json::search.agents`.

**Ten agents, one engine (#913b).** Buy-Side › Real Estate: RS_House-Purchase, RS_House-Rental (the house search
below, renamed: its seen listings, filters and source switches move to the new id; its template, runs and reports stay
its own), RS_Commercial-Stores, RS_Commercial-Buildings. Buy-Side › Things: Things_Housing, Things_Electronics. Buy-Side ›
Services: Services_Medical, Services_General. Sell-Side: Real-Estate (your own listing text beside each comparable) and
Job-Placement (a cover letter per job). Each is a definition in `build.json::search.agents.agents`: side, category, goal
(filled from its filters: location, radius, price range, size, dates, keywords), sources (whose alert mails it reads, which
links count, and the site search the person opens), template and outputs (a result table and drafts). The parser refuses
an agent that is not `draft_only`, that names an `auto_*` or action key (submit, send, apply, buy, ...) or any key not
on its list. Each agent's screen says it: searching, comparing, summarising and drafting are the agent's; sending,
applying, buying and submitting are the person's, in Cloud Browser.

**House search.** (1) Cloud Mail's agent door lists the mails from `wg-gesucht.de` of the look-back window;
(2) each mail's body is read and the listing links extracted (host-checked, one per listing id, earlier runs'
listings skipped); (3) Cloud Browser's agent door fetches each listing page's text (a read-only GET); (4) the
owner's template is filled with their details and, inside the budget, one short model paragraph; (5) the drafts
land in a report. Each draft has Copy message, Open listing in Cloud Browser, and the owner's own "I sent it" /
Dismiss marks.

* **Doors** (`docs/agents-engine-audit.md`): mail `content://<mail pkg>.agentmail/{messages,body}` and browser
  `AGENT_OPEN` activity + `fetch_text` provider, all behind CONSTELLATION_DATA (signature). Read-only.
* **Key**: OpenRouter's, read from the fleet Account per model call (`Account.token`), only into the
  Authorization header. The model gets no tools; its text is cleaned (no links, addresses) before it is inserted.
* **Budget**: US-dollar caps per run and per day (Settings). Each call is priced at its worst case first and not
  made if it could pass a cap; the real cost (the provider's, else tokens x price) is recorded.
* **Audit**: every run logs what it read (mail ids and subjects, URLs) and drafted; never a body, page text or
  message. Runs page shows it.
* **Reports** (`ReportStore`, filesDir/agents/reports.json): the local API agents publish through
  (`ReportSink.publish`); the Reports page lists newest first and opens a detail with the drafts.
* **Owner setup**: the OpenRouter key in the fleet Account (SuperApp > Profile), Cloud Mail with the wg-gesucht.de
  alert mails delivered, Cloud Browser installed (both updated to the builds with the agent doors), your details
  and template on Agents > Templates, a budget on Agents > Settings.
