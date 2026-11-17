# Architecture

This document describes how Sterna is built. It is aimed at developers.

## Goals

- A production-quality JMAP-first email client for Android; IMAP/SMTP are fully
  supported through the same cache and UI.
- Privacy by default: no telemetry, no Google/FCM, FOSS-only dependencies.
- Clean, testable layers, the protocol clients are pure JVM and unit-tested
  without Android.

## Stack

- **Language:** Kotlin (K2 compiler).
- **UI:** Jetpack Compose + Material 3 (Material You dynamic color).
- **Architecture:** MVVM with unidirectional data flow (`ViewModel` + `StateFlow`),
  manual DI (`AppContainer`).
- **Async:** Kotlin Coroutines + Flow.
- **Networking:** OkHttp + kotlinx.serialization (JMAP is batched JSON, so a thin
  typed client rather than Retrofit).
- **Persistence:** Room (`sterna.db`, the offline cache + outbox) with Paging 3,
  DataStore for settings, AndroidKeyStore-encrypted credentials.
- **Background:** WorkManager for everything that must survive the process
  (destroys, outbox, scheduled send, snooze, the push fallback poll).
- **Build:** Gradle (Kotlin DSL) with a version catalog; minSdk 26, target/compileSdk 36.

## Module layout

| Module              | Type            | Responsibility                                       |
|---------------------|-----------------|------------------------------------------------------|
| `:app`              | Android app     | Compose UI, ViewModels, push services/workers, notifications, OpenPGP provider binding, app lock. |
| `:core:jmap`        | Kotlin JVM lib  | JMAP client, session, batched method calls, typed RFC 8620/8621 models, EventSource push, OAuth device flow (RFC 8628). |
| `:core:imap`        | Kotlin JVM lib  | IMAP/SMTP clients, protocol parsing, IDLE, MIME parser/builder, PGP/MIME, XOAUTH2. |
| `:core:data`        | Android library | `MailRepository` (the one seam the UI talks to), Room cache, account store, settings, PGP seam, storage caps. |
| `:libs:openpgp-api` | Android library | Vendored openpgp-api client (Java), for the bound OpenPGP provider service. |

## Mail sync

**JMAP.** Each folder is synced *uncollapsed* (`collapseThreads=false`): the cache
holds every in-folder thread member, and conversations collapse at display time.
A cold folder does a full `Email/query`; from then on `Email/queryChanges` +
`Email/changes` deltas run against per-(account, folder) cursors persisted in
`SyncStateStore` (surviving process death, essential for push wakeups). When the
server cannot compute the delta or it exceeds `MAX_CHANGES` (200), sync falls
back to a full re-query. Reconciliation never evicts ids mutated locally in the
last ~45 s (the *recently-mutated* spare set): a delta computed from a
pre-mutation cursor may falsely report a just-flagged or just-restored message
as removed. After a local `Email/set`, the stored `emailState` is advanced to
the response's new state so push echoes of the app's own action are not re-applied.

**The cache is the UI's source.** Screens read Room via Paging 3; the network
only ever writes into Room. A single folder view is backed by a `RemoteMediator`
that extends the cache on scroll: it anchors the next `Email/query` page on the
oldest cached thread-*representative* (stable when new mail arrives on top,
unlike an absolute offset), falls back once to a positional query on
`anchorNotFound`, and in conversation view keeps fetching (bounded) until the
page has produced enough *new thread rows*, one giant thread must not count as
a full page of progress.

**IMAP** (`ImapMailService`) is the parallel read/write path: it maps folders
and messages onto the same Room entities, so paging, conversations and the UI
are protocol-agnostic. It keeps a windowed cache (newest N per folder, older
pages fetched by offset on scroll) over one pooled, serialised connection per
account.

The per-account **sync window** (by count or age, default 90 days) bounds what
stays cached; on JMAP the scroll mediator pages past it on demand. On IMAP it is
also where the reader's list ends: measured on the bench, an IMAP folder stayed
flat at its 1 000 cached messages while it was scrolled to the bottom, and why
is not established (the mediator's IMAP branch does ask for older pages). It
bounds what is kept *in addition to* the folder's current page, the page a
refresh just fetched is never pruned by it, whatever its age. An age window
also keeps the folder's newest N messages regardless of age (N being that window's page size),
so a quiet folder is never emptied down to whatever happens to be recent.

## Conversations

Conversation view is pure SQL over the uncollapsed cache
(`conversationSql`, unit-tested against real SQLite): one row per thread per
folder, showing the thread's newest in-folder message. A conversation is scoped
to *its folder's members plus the account's Sent replies*, so the same thread
is a different (split) conversation in Inbox and in Trash, and the count chip
always equals exactly what the unfolded conversation shows. A separate
account-wide total only gates the expand affordance (the row can unfold when
the other members live elsewhere). All joins pin the row's `accountId`, since
same-server accounts can collide on server-assigned mailbox/thread ids.

The thread key is the server's `threadId` on JMAP. On IMAP it is rebuilt by
`imapThreadKey` (the root of `References`, i.e. its first id; failing that
`In-Reply-To`; failing that `Message-ID`; angle brackets kept), recomputed on
every write pass from the list FETCH, which carries
`BODY.PEEK[HEADER.FIELDS (REFERENCES)]` for that purpose. A message with none
of the three keeps its own row.

Drawer badges are WYSIWYG: for JMAP folders the badge is a live Room aggregate,
unread *threads* in conversation view, unread *messages* in flat view, so it
always equals the bold rows in the list and moves instantly on read/move/
delete/snooze. IMAP folders keep the stored server counter instead (their
windowed cache would under-count), so there the badge counts messages where the
list shows threads; and the "All inboxes" header sums the same
per-account sources.

## Actions & safety

- **Network-first:** delete, archive and move write to the server first and only
  then drop the cached row; optimistic count nudges are reconciled by the next
  sync. A failure surfaces instead of silently diverging.
- **Destroy is held back:** a permanent destroy (delete-from-Trash, Empty trash)
  only ever runs through a persisted WorkManager job (`MessageDestroyWorker`)
  delayed past the Undo window, killing the app cannot drop a confirmed destroy,
  and Undo is a work cancellation. Folder deletion follows the same model.
- **A destroy names messages, never a folder:** "Empty trash" records the exact
  ids at the moment the user confirms (table `purge_snapshot`, keyed by purge,
  account and message) and the held-back job destroys only that list. It used to
  carry a folder id and re-read the folder when it ran, so mail moved to Trash
  during the Undo window was destroyed with the rest. Undo erases the list, an
  abandoned list is swept by age, and no list means no destroy.
- **Per-id failure parsing:** `Email/set` responses are parsed per id
  (`notUpdated`/`notCreated`/`notDestroyed`), so bulk actions report exactly
  which messages failed and rejected destroys resurface after a re-query.
- **Account-scoped everything:** the `mailboxes` table is keyed
  `(accountId, id)`, sync cursors by `(accountId, mailboxId)`, and every action
  from the unified inbox resolves folders on the message's own account.

## Push & notifications

`PushController` picks a transport per account, automatically, and the first
rule that applies wins: a linked sub-account (issue #31) → the periodic worker,
because the server never pushes an ACL-shared account's changes; otherwise an
account whose UnifiedPush endpoint is ACTIVE → UnifiedPush, whatever its
protocol, so an IMAP relay address (issue #177) rides this too; otherwise
battery saver → the periodic worker; otherwise JMAP → an EventSource (SSE)
connection; otherwise IMAP → IDLE. ACTIVE means the server's `PushVerification`
came back on JMAP, and on a relay that a payload really arrived, never merely
that an address exists: once an account has asked for one, the subsystem enrols
whatever distributor is installed and an address appears on its own, so arming on
that alone would trade a working IMAP IDLE for an endpoint nobody ever posts to. Direct connections live in a foreground
service declared `specialUse` (not `dataSync`, which Android 15+ budgets and
kills). With UnifiedPush on a JMAP account the server posts a
WebPush-encrypted `StateChange` to the distributor's endpoint; the connector
library generates and holds the P-256 keys on-device and hands Sterna
decrypted payloads, which enqueue an expedited fetch worker, the process can
be dead between pushes. On an IMAP relay Sterna hands out nothing: no VAPID
call, no WebPush keys and not even the address, which it publishes locally for
the user to copy into a relay of their own choosing. What arrives is whatever
that relay posted, so the nominal path is an undecryptable payload treated as
a bare wake signal, which enqueues the same fetch. A periodic ~30-minute
`MailFetchWorker` is the safety net for every transport (push can die
silently) and polls what IDLE cannot see.

Notifications diff against *persisted per-folder baselines* of already-announced
ids (shared by live push and the fallback worker, so they never double-notify),
with an age floor so backfilled old mail is never announced as new. New-mail
bursts collapse per thread, one notification per conversation, not per reply.

## Auth & secrets

Three schemes, resolved by a single `jmapAuth()`: **Basic** (username/password),
**Microsoft OAuth** via the RFC 8628 device flow (Bearer for JMAP, XOAUTH2 for
IMAP/SMTP, access tokens refreshed on demand), and **API tokens** (e.g.
Fastmail) sent as Bearer. Passwords, tokens and OAuth refresh tokens are
encrypted with an AES-256-GCM key that never leaves the AndroidKeyStore
(AAD-bound to the account). Sterna requires TLS; cleartext JMAP is rejected by
design.

## Storage & retention

| Data | Location | Bound |
|---|---|---|
| Message list rows | Room `emails` | Pruned to the sync window (spare set excepted). |
| Message bodies | Room `email_bodies` | Opened/prefetched messages; LRU cap per account. |
| Search index | Room `email_fts` (FTS4) | Headers of every folder except Trash and Spam; outlives the display window. |
| Outbox / scheduled / snoozed | Room | User data, additive migrations, never dropped. |
| Attachments | `cacheDir/attachments/` | Size + age cap, LRU eviction. |
| Credentials | SharedPreferences, KeyStore-encrypted | `AccountStore`. |
| Settings | DataStore | Reactive `Flow` per setting. |

Sign-out purges the account's rows, bodies, baselines and attachments. The
cache portion of the DB uses destructive migration (it is a disposable mirror);
outbox-bearing tables migrate additively.

## Other subsystems

- **Search:** hybrid. A local accent-folded, prefix-matched FTS4 index over the
  *headers* of every folder except Trash and Spam (crawled in the background)
  answers instantly and offline; after a typing pause the server's own full-text
  search (which sees bodies) is unioned in, results are only ever added, never
  removed. Both sides skip Trash and Spam from the same role source
  (`excludedSearchFolderIds`), so a deleted or spam message never surfaces in
  results whatever the protocol; a message also filed outside Trash is still
  found through its other folder.
- **OpenPGP:** via an external OpenPGP app over the vendored openpgp-api bound
  service, behind the `PgpEngine` seam. Encrypt-at-compose (JMAP uploads the
  ciphertext blob + `Email/import`; IMAP builds PGP/MIME); decrypted plaintext
  lives only in memory, never in the cache, except an attachment: one you open is
  written to the app's own storage until the cache is cleared, and one you save is
  streamed from the server into the document the system picker returns, never
  through the cache. Printing, and saving as a PDF, are the other way out: the
  in-memory plaintext body goes to the system print service. See
  [ENCRYPTION.md](ENCRYPTION.md).
- **Outbox & scheduled send:** sends are persisted Room rows delivered by
  WorkManager jobs (network constraint, backoff, undo-send hold-back), so a
  send survives process death and offline spells.
- **Background full sync (#1768):** `OfflineSyncWorker` (WorkManager, exponential
  backoff) keeps the whole "Keep mail offline for" window (90 days by default)
  downloaded: Email/changes per folder, older header chunks to the window's edge,
  full bodies of every folder, and attachments by the "Download attachments"
  setting (Always / Wi-Fi only (default) / Off; a tap always downloads). Every 15
  minutes without push, on a push state change when there is push, on connectivity
  regain, after an app update and on app open. Battery saver stops it, a metered
  link takes the newest batch only, and it stops filling at 90% of the 250 MB
  per-account caps (bodies and attachments are capped separately; the oldest go
  first past the cap). Encrypted bodies of folders other than the inbox are stored
  raw and never import the sender's key until the message is opened. The engine is
  `core/data` `FullSync`; the opening of a stored mail makes no network call.
- **Snooze:** a Room table filtered out of every list query, plus a WorkManager
  job that resurfaces the message at its time.
- **Settings backup:** a portable JSON snapshot of preferences + account
  *configuration*. Credentials are excluded (device-bound keys can't move);
  imported accounts stay inert until signed in. K-9/Thunderbird `.k9s` imports
  work the same way.
- **App lock:** biometric/PIN gate in front of the UI.

## Build & releases

Release builds are R8-minified and resource-shrunk (`-PnoR8` for faster test
builds) and must be **reproducible** for F-Droid: built from a clean tree with
`--no-build-cache`, no VCS info or dependency-metadata blob embedded, and the
ART baseline profile deliberately not packaged (`ArtProfile` tasks disabled),
it is not byte-stable across build environments. R8 mappings are archived per
release.
