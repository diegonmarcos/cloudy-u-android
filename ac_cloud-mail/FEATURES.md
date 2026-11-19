# Features

This document tracks Sterna Mail's feature set: what's built, what's planned on the
roadmap, and proposed additions drawn from K-9 Mail / Thunderbird for Android
and from what users expect of a modern, complete email client.

It complements [ARCHITECTURE.md](ARCHITECTURE.md), which describes how the app is
actually built: module layout, mail sync, conversations, push and notifications,
auth and secrets, storage. For each proposed feature I note when JMAP makes it
cheap to build, several things that are hard over IMAP are nearly free in JMAP.

**Status key**

- ✅ **Done**, shipped.
- 🔜 **Planned**, next up, not yet built.
- 💡 **Proposed**, not yet scheduled.
- ⭐ **JMAP-native**, RFC 8620/8621 gives this almost for free.

Status is read against `main`, not against the latest published release (1.4.9),
so a ✅ can be ahead of the version you have installed.

---

## Roadmap, what's next (prioritized)

The categories further down list the full feature set; this is the order of work.

**Tier 1, close functional holes** *(done)*
- ✅ IMAP push (IDLE), IMAP accounts now get new-mail notifications like JMAP
- ✅ Folder management (create / rename / delete)
- ✅ Report spam / not-spam (move to/from Junk)

**Tier 2, modern compose & send** *(done)*
- ✅ Compose overhaul: cross-account From picker, frameless full-width line fields,
  icon actions, auto-focused To, expandable Cc/Bcc
- ✅ Recipient autocomplete: recent/cached contacts + opt-in device contacts; recipients show as chips (removable), with email-format validation, invalid addresses are flagged and block sending
- ✅ Undo send (hold-back window); ✅ full Outbox + retry (persistent queue, survives app death, auto-retries on network return)
- ✅ Schedule send (quick presets; persisted + fired by WorkManager, survives app close)
- ✅ Snooze a message until later

**Tier 3, privacy & JMAP-native power** *(done)*
- ✅ OpenPGP (via an external OpenPGP app), read + send, both protocols
- ✅ ⭐ Vacation responder (JMAP `VacationResponse`)
- ✅ Tracking-param stripping (utm_*, fbclid, gclid… removed from tapped links); ✅ per-sender image allowlist; ✅ link confirmation
- ✅ Server-side Sieve filters/rules (form-based rule builder); ✅ server `Quota` display

**Tier 4, polish**
- ✅ Richer search filters (from/subject/has-attachment/date, AND-combined); 💡 `SearchSnippet` highlights
- ✅ Sterna brand identity, Arctic (light) / Pelagic (dark) palette, a calm sea-teal action colour with a coral accent (the tern's beak); Material You is an opt-in toggle; coastal line-art empty states; calmer microcopy
- ✅ Per-account colour (avatar + unified-inbox chip); ✅ accessibility pass v1 (screen-reader labels, font scaling, system-bar contrast); ✅ home-screen widgets (unread count, recent mail); 💡 fuller TalkBack audit
- ✅ List and reading pane side by side from 600 dp of window width (a phone in landscape, a tablet, a desktop window); the folder drawer stays open beside them from 1200 dp
- ✅ Bundled/grouped notifications (per-account summary) + quiet hours (silent nightly window)
- ✅ Settings export/import (app preferences → JSON file via SAF; excludes accounts/credentials); ✅ `/.well-known/jmap` autodiscovery (email → server); ✅ password-free OAuth2 sign-in (device flow, RFC 8628; authorization code + PKCE, RFC 6749 §4.1 + RFC 7636, for servers with no device endpoint)

---

## Protocols

- ✅ **JMAP** (RFC 8620/8621), the primary, modern backend.
- ✅ **IMAP + SMTP**, a hand-rolled client (no JavaMail), at parity with JMAP:
  folder list, paged read with server-side load-more, MIME body + attachments,
  star / archive / delete with undo, SMTP send (incl. multipart attachments) with
  APPEND-to-Sent, and server-side search. Add via Add account → "IMAP / SMTP"
  (host/port/security for both). The data layer routes per-account by protocol,
  so the cache, paging, and entire UI are protocol-agnostic.
  - ✅ One pooled connection per account, reused across calls (no reconnect per page).
  - ✅ IDLE push (new-mail notifications) via a dedicated IDLE connection.
  - 💡 IMAP gap: CONDSTORE incremental sync.

## Reading & triage

- ✅ Friendly empty states, coastal line-art illustrations (a tern over the horizon for an empty inbox, a magnifier for search, a folder, a swept bin for the trash, a drifting cloud for offline), theme-aware and drawn from the Material colour scheme; a calm welcome on first-run setup ("Your email, finally yours.")
- ✅ Reply-all / mass-send guard, sending to 5+ recipients (To+Cc+Bcc) asks for confirmation, with the count
- ✅ Sign-out asks for confirmation before removing an account and clearing its cache
- ✅ Inbox list and message view (HTML in a WebView, remote content blocked;
  dark mode: theme colours for plain text, CSS invert for rich HTML); plain-text
  bodies preserve paragraphs and unwrap `format=flowed` soft line breaks (RFC 3676)
  so they read as written instead of one run-on block or mid-sentence wraps
- ✅ In **HTML**, a reply that ends on the thread's history opens on the reply: the trailing quoted block is collapsed behind a "Quoted text" button, and one tap unfolds it. Only that one block, and only when there is something above it to read, so an interleaved or bottom-posted reply is left as written. Closed again on every opening (nothing is remembered), no setting, and no JavaScript. Printing is not affected: the quote goes on paper in full, since a PDF cannot be unfolded
- ✅ Print a message, or save it as PDF, from the message menu (system print preview; remote images stay blocked on paper)
- ✅ Offline reading (Room cache)
- ✅ Mark read/unread, star, archive, delete *(M3; JMAP + IMAP)*
- ✅ Optional "mark as read when deleting" (Settings → Reading and writing), so deleted mail doesn't sit unread in Trash
- ✅ Unread shown by bold text (not a status dot)
- ✅ Folder navigation drawer; view any mailbox *(M3)*
- ✅ ⭐ Conversation threading, on JMAP and IMAP alike. The list collapses a thread into one row with a message-count badge (Settings → Reading and writing → Conversation view, on by default; toggle off for a flat list); opening a row shows the thread view. Grouping is done in SQL (representative = latest message, unread if any in the thread). On a **JMAP** account the thread is the server's (native `Thread` objects); on an **IMAP** account it is rebuilt on the client from `Message-ID` / `In-Reply-To` / `References` (the root is the first id of `References`, as in K-9 and Thunderbird). The setting applies to both and is never greyed out
- ✅ Unarchive on reply (Settings → Reading and writing), on **JMAP**: when a new reply arrives, the conversation's archived messages come back to the Inbox. On IMAP the setting does nothing: unarchiving is a server write on the members of the thread, and that write is not made on IMAP
- ✅ Pull-to-refresh
- ✅ Swipe actions (configurable) with an Undo snackbar for delete/archive; "Empty trash" (Trash overflow menu) destroys, behind the same held-back Undo, at most the messages the folder held when you confirmed, mail filed there afterwards is not touched
- ✅ Configurable swipe actions (left/right, in Settings → Reading and writing), fired at about half the distance the finger used to travel, and a quick flick triggers them on its own, as in K-9 and Thunderbird; a long press into multi-select gives a short haptic tick
- ✅ Sort (newest/oldest, subject, sender, unread-first, starred-first) + Mark-all-read
- ✅ Multi-select (long-press / select-all): bulk read/unread toggle (keeps the
  selection), archive (Unarchive → Inbox from the Archive folder), move-to-folder, delete
- ✅ Opening a folder starts at the top of its list
- ✅ Snooze a message until later
- ✅ Paged list (Jetpack Paging 3 + Room), large folders load in pages while scrolling, constant memory; scroll-position indicator on the right
- ✅ Scroll to load more on JMAP, a Paging `RemoteMediator` fetches older mail (anchor-based) from the server when you scroll past the cached window, with a loading/retry footer. On IMAP a folder stops at the sync window, and so does the unified inbox on both protocols
- ✅ Star per row, tappable; "Starred first" is one of the sort orders, so starred mail pins
  to the top when you ask for it and sorts normally the rest of the time; "Starred only" is a
  criterion of the advanced search, which gathers them across the whole account
- ✅ Report spam / not-spam, message overflow, context-aware (Report spam ↔ Not spam)
- ✅ Save a message as `.eml` (reader overflow menu): the file is the message exactly as the server holds it, over JMAP and IMAP, written where you choose through the system file picker; an OpenPGP message is saved as received, still encrypted
- ✅ One-gesture unsubscribe (RFC 2369 / RFC 8058), a reader banner appears when the sender publishes an unsubscribe address; Sterna shows the exact request or email it would send before sending it, does not send it twice while the message stays open, refuses a redirect that would turn the request into a page load, and blames the sender's host rather than the network when it fails. A list that offers only a web page is opened in a browser, after a warning that this can expose the reader's IP address

## Organisation & search

- ✅ Mailbox listing
- ✅ Server-side search, inline on the mailbox (search-as-you-type; JMAP query / IMAP SEARCH, with instant local-cache results); the search field names its scope (current folder, or "All inboxes"). In the unified inbox the search fans out to **every** account in parallel (not just the active one), merges + de-duplicates the results, and each result row carries its account name/address chip like the unified list
- ✅ Unified inbox across multiple accounts (merged, date-sorted; per-row account; JMAP + IMAP), switching the active account refreshes the list, unless that inbox was already reconciled in the last 30 seconds; archive/delete from the unified inbox resolve the target folder on each message's own account
- ✅ Richer search filters (from, subject, has-attachment, date, starred-only), advanced panel in Search, JMAP Email/query AND filter; 💡 `SearchSnippet` highlights
- ✅ Mail by sender (inbox ⋮ → By sender), gathers the cached mail from one address, shows how much there is, and deletes it in one go (to the Trash). The count and the delete cover every cached folder of the account except Sent, Drafts, Trash and Spam, and leave snoozed messages out of both. From that screen, or from a message being read, a server-side rule can be added that files this sender's future mail to the Trash, marked read; it asks first and says what it will do
- ✅ Move a message to a folder of ANOTHER account (reader ⋮, and a multi-selection that stays inside one account): the move picker opens on the message's own account and offers the others above the folder list. Across accounts this is a copy confirmed on the target, then the original to the source's Trash — never a delete on an unconfirmed copy — so it has no Undo; the raw message travels byte for byte, keeping its read and starred state and its date. If the copy lands but cannot be named back (an IMAP target without UIDPLUS), the original stays where it is and the app says so
- ✅ Auto-create an Archive folder on first archive (when the account has none)
- ✅ Folder management, create / rename / delete custom folders from the drawer, including nested subfolders (JMAP parentId / IMAP path), shown as a collapsible tree; 💡 subscribe + per-folder settings, drag-to-reorder
- ✅ Quick filter: unread-only toggle on the current view
- 💡 Quick filters: starred-only, has-attachment

## Composing & sending

- ✅ Compose and send (JMAP `EmailSubmission/set`, or SMTP submit + APPEND-to-Sent for IMAP)
- ✅ Opens `mailto:` links (registered as an email app, from browsers and other apps); the link's addresses, subject, body, cc and bcc prefill compose
- ✅ Reply / reply-all / forward with quoting (threaded via `inReplyTo`/`references`)
- ✅ Forward as attachment: the original message attached as a `.eml` file (`message/rfc822`), from the message menu
- ✅ Save drafts (JMAP, or IMAP APPEND to Drafts); closing compose with unsaved edits prompts to save the draft, discard the changes, or cancel and keep editing (intercepts the Close button and system back). The prompt says it drops the *changes*, not the message, when a saved draft stays on the server; Save draft is greyed out on an empty composer (saving nothing would delete the draft); and the draft itself can be deleted from the composer, on confirmation, to the Trash
- ✅ Attachments: pick & send, view/download/open incoming (JMAP blobs / IMAP multipart MIME + BODY-section fetch)
- ✅ Attached messages (message/rfc822): a tap opens a sheet with the attached message's From/To/Cc/Date/Subject, its text body and the names of its own attachments (JMAP and IMAP, nothing written to disk)
- ✅ Inline images (`cid:`) rendered in the body (downloaded as data URIs)
- ✅ Rich-text editor: bold, italic, underline, strikethrough, bulleted and numbered lists, links (http, https, mailto), clear formatting, in a bar above the keyboard; drafts keep it
- ✅ Undo send (hold-back window), held in an app-scoped outbox; ✅ full Outbox, a
  Room-backed send queue that survives app death and auto-retries with exponential
  backoff when the network returns; every send path (compose, reply/forward, RSVP,
  scheduled, notification quick-reply) routes through it. A dedicated Outbox screen
  (inbox overflow) lists waiting/sending/failed items with retry / edit / delete, with a
  discreet inbox badge. IMAP attachments are persisted so a deferred retry keeps them
- ✅ Schedule send, quick presets; persisted in Room, fired by WorkManager (survives app close); v1 carries no attachments. A "Scheduled" screen (inbox ⋮ → Scheduled messages) lists pending sends and cancels them
- ✅ "Forgot attachment?" reminder, sending a message that mentions an attachment (in any of the 9 UI languages) with none added prompts to confirm
- ✅ Multiple sending identities per account (name + address), **each with its own
  signature** (plain text or HTML, with HTML-file import); a "From" picker in compose
  chooses which to send as (matched to a server `Identity` for JMAP submission)
- ✅ Read-receipt request and response: asking for one is an entry in the composer's ⋮ menu (a `Disposition-Notification-To` header on the message you send); answering someone else's is a strip on the opened message, governed by a single switch (Settings → Privacy & security → Read-receipt requests) that is off by default, and nothing is sent without a tap

## Accounts & setup

- ✅ Encrypted account persistence (AndroidKeyStore)
- ✅ Multiple accounts, add / switch / sign out, with migration
- ✅ Multiple JMAP accounts under one login (RFC 8620 §1.6.2), when a single sign-in exposes several mail accounts (delegated / shared / team mailboxes), each is surfaced as its own account in the drawer switcher, with its own inbox, folders, unread count, mail cache and new-mail notifications, all sharing the one stored credential. Sending from a sub-account uses that account's own server identities. Discovered automatically on connect: Sterna asks the server for that account's mailboxes before adding it and leaves it out when the server refuses, so a share that carries no mail is not turned into an account, and an account the login stops exposing is dropped on the next connect. An account already added by an earlier version stays until you sign out of it and add it again. One push subscription per login carries changes for every account it reaches. A login that exposes a single mail account behaves exactly as before
- ✅ JMAP **and** IMAP/SMTP account setup, led by the address: the first step asks for the email address alone and probes what its domain publishes, the next step asks only for the secret that answer needs, and the protocol is never a question put to the reader (JMAP is tried first on that secret, the published IMAP/SMTP settings only if no JMAP server answers). The manual form (protocol picker; host/port/security) is the fallback, and what decides whether it is offered is the IMAP/SMTP autoconfig verdict alone: a domain that publishes an OAuth server but no autoconfig and no TLS-valid `imap.`/`mail.` host still lands there. IMAP setup has quick-setup presets (Gmail, Yahoo, iCloud, Fastmail, Proton Bridge, Yandex, Mail.ru) that prefill host/port/security, with a reminder that most providers need an app-specific password (not the normal one); a rejected IMAP login repeats that hint. Password fields have a show/hide toggle. (Outlook/Microsoft uses OAuth instead of a password, see the XOAUTH2 item below.)
- ✅ Account management panel, per-account editable server settings (protocol-aware: JMAP URL, or IMAP/SMTP host/port/security; username, password), with a "Test connection" button that validates the (edited) settings before saving
- ✅ Optional account display name (falls back to the address when unset)
- ✅ Onboarding by autodiscovery, enter just email + password; Sterna probes the email domain's `/.well-known/jmap` endpoint on four candidates (the bare domain, then the `mail.`, `jmap.` and `api.` subdomains, following redirects, which can legitimately land on another host, which is then given the credentials over HTTPS) to find the JMAP server, and it also reads the IMAP/SMTP settings a domain publishes for itself (Thunderbird autoconfig format, at `autoconfig.<domain>` then `<domain>/.well-known/autoconfig/…`, both on your own domain and nowhere else: no third-party configuration directory is ever contacted), with a manual-server fallback; 💡 DNS SRV (`_jmap._tcp`)
- ✅ OAuth2 / Bearer auth, Sterna reads the server's `/.well-known/oauth-authorization-server` and drives whichever grant it advertises. The **Device Authorization Grant** (RFC 8628) wins whenever a server offers one: a user code is shown to type into a browser, the address you typed rides along on that browser link (as `login_hint`), and only over an encrypted one, so the server's page can fill the field for you, and tokens are polled for (verified against Stalwart). A server with **no** device endpoint is signed into with the **Authorization Code Grant + PKCE** (RFC 6749 §4.1 + RFC 7636) instead: a browser opens on the server's own sign-in page and the answer comes back to the app on its own redirect (`<application id>://oauth`). Either way the refresh token is stored encrypted and auto-refreshed, and no password is handled by the app.
- ✅ **Outlook / Microsoft OAuth2 + XOAUTH2**, "Sign in with Microsoft" via the OAuth 2.0 Device Authorization Grant (a code typed into the browser), against a registered public Azure client; the access token is presented to the IMAP **and** SMTP servers with the **XOAUTH2** SASL mechanism (no password handled or stored; refresh token encrypted and auto-refreshed). Outlook is a provider chip in Add account, with server fields hidden. **Personal Outlook/Hotmail works.** Two limits, both gatekeeping rather than code: **work/school (org) accounts** need the organisation's admin to consent, or a Microsoft "verified publisher" badge I cannot obtain from a personal Microsoft account (investigated, paused); and a brand-new, not-yet-provisioned Outlook mailbox can fail (K-9 fails on it too, it's the account, not the client).
- 🔜 **Gmail / Google OAuth2 + XOAUTH2** *(planned)*, the XOAUTH2 plumbing above (IMAP + SMTP) is provider-agnostic and reusable; what is missing is a Google OAuth provider (Google client id + endpoints + the `https://mail.google.com/` scope). The real blocker is **Google's verification for restricted Gmail scopes** (a recurring third-party security assessment), not the code. Until then Gmail works with an **app-specific password**, like Yahoo/iCloud/Fastmail.
- ✅ Per-account colour coding (picker in account settings; tints the account avatar + the unified-inbox account chip)
- ✅ Settings export / import, app preferences (appearance, reading, notifications, privacy, language) to/from a JSON file via the Storage Access Framework (Settings → Backup); accounts and passwords are excluded (device-bound encryption)

## Sync, push & notifications

- ✅ ⭐ Incremental sync (`Email/queryChanges` + `Email/changes` + per-type `state`), JMAP; IMAP does a bounded full re-query
- ✅ ⭐ Push (foreground service, no Google/FCM): JMAP EventSource, or IMAP IDLE (a dedicated connection per account, refreshed within the ~29-min limit)
- ✅ New-mail notifications (per current account, or all accounts via a setting); the per-account switch (Settings → Accounts → [account] → Notifications → Sync new mail) governs the background fetch itself, not just the notice: with it off, that account's mail arrives when you open the app (a second opening within 30 seconds shows what the first one fetched) or when you pull to refresh
- ✅ Notification quick actions: reply (inline), mark read, delete
- ✅ Push reconnects automatically when the connection drops (catches missed mail)
- ✅ Bundled/grouped notifications per account, individual new-mail notifications collapse under a per-account summary
- ✅ Push/notifications beyond the Inbox (issue #16), per-folder watch switch in the drawer (Sieve-filtered folders included): JMAP account-wide changes filtered on the watched set; IMAP non-Inbox folders via the periodic poll (IDLE is single-folder)
- ✅ ⭐ UnifiedPush transport (issue #17), JMAP `PushSubscription` to a UnifiedPush endpoint (ntfy, NextPush…) removes the persistent connection and its permanent notification for JMAP accounts; an IMAP account can ride UnifiedPush too (issue #177: Settings → Accounts → [account] → Notifications → Relay push address, a block that shows on an IMAP account whose own notifications are on, and that needs a UnifiedPush app installed before there is an address to hand out): Sterna publishes an address you copy into whatever relay you already trust, and a POST to that address wakes the app to check for mail, with nothing of the mailbox travelling through it. The transport is picked automatically per account; the delivery setting is outcome-framed ("New mail delivery: Instant / Battery saver"), while the relay address block is phrased by mechanism instead, because an address has to be copied out by hand; IMAP IDLE gives way to UnifiedPush only once a payload has really arrived on that address, so an address nobody posts to never costs a working connection; a read-only per-account status line shows what's in use; a distributor picker appears only when several are installed
- ✅ Quiet hours, a nightly window (Settings → Notifications) during which new mail still arrives but silently (no sound/vibration/heads-up)

## Privacy & security

- ✅ Remote image / tracking-pixel blocking by default
- ✅ App lock, biometric / face, with screen PIN/pattern/password fallback
- ✅ Per-sender "always load images" allowlist (add from a message's ⋮ menu, or add/remove individual senders + clear all in Settings → Privacy & security)
- ✅ Visible no-telemetry stance, [PRIVACY.md](PRIVACY.md) + the README privacy section
- ✅ Strip tracking parameters from tapped links (Settings → Privacy & security, on by default); ✅ confirm before opening external links (Settings → Privacy & security → Links, opt-in; dialog shows the destination, with a Copy button)
- ✅ The composer asks the keyboard not to learn from what is being written (`IME_FLAG_NO_PERSONALIZED_LEARNING`), so names, addresses and medical or legal terms typed in a message do not end up in the keyboard's personal dictionary

## Encryption

- ✅ OpenPGP via an external OpenPGP app: read (decrypt + verify signatures)
  and send (sign and/or encrypt, PGP/MIME) on both JMAP and IMAP/SMTP.
  Per-account setup in Settings; a lock button in the composer cycles off /
  sign / encrypt, and a long press on it adds a fourth mode, encrypt without
  signing. Decrypted content is never written to disk (not cached, not
  search-indexed), except an attachment: one you open is written to the app's own
  storage until the cache is cleared, and one you save, with the button on its row
  in the reader, goes to the folder you pick and stays there. Printing a message,
  or saving it as a PDF, hands its decrypted text to the system print service.
  The message subject is not encrypted.
  User guide: [ENCRYPTION.md](ENCRYPTION.md).
- 💡 S/MIME (longer-term)

## UX & accessibility

- ✅ Material 3 with Sterna's own brand palette (Arctic light / Pelagic deep-teal dark) by default; Material You wallpaper colour is an opt-in toggle (Settings → Appearance); the system status/navigation-bar icons follow the in-app theme so they stay legible
- ✅ Contact avatars / sender initials (monograms)
- ✅ Settings hub (Appearance / Notifications / Privacy & Security / Storage), DataStore-backed; grouped into Accounts · App · "This account · <name>" so app-wide vs per-account (server-side) settings are clear at a glance
- ✅ Storage screen, on-device cache usage (DB + attachments, per-account breakdown) + Clear cache
- ✅ Attachment cache cap (LRU by size/age); sign-out purges that account's cached mail + attachments
- ✅ Per-account sync window, messages to sync by age (30/90 days, 1 year) or count (100/1000/10000), default 90 days
- ✅ Per-account "Clear this account's cache" + cached-message count (Settings → Accounts → detail)
- ✅ Theme toggle (auto / light / dark)
- ✅ Message-list density (compact / normal / spaced)
- ✅ Row preview length (subject only / 1 / 3 / 5 lines)
- ✅ Message text size (small / normal / large / huge), scales the message-body WebView (Settings → Reading and writing → Message)
- ✅ Reply bar toggle (Settings → Reading and writing → Message), hides the Reply and Forward buttons at the bottom of a message; Reply stays in the toolbar at the top, Forward in its menu
- ✅ Compact inbox top bar showing folder + account
- ✅ About section in Settings, version (with release date), source code, license and author links
- ✅ Home-screen widgets: an unread count for the unified inbox (with two or more accounts, a tap opens "All inboxes" and the enlarged cell breaks the total down per account, unless the app lock is on) and a recent-mail list whose rows follow the notification-content setting and name neither sender nor subject when the app lock is on; both follow the app's own theme, and a recent-mail row carries its account's colour once two or more accounts are set up and that account has been given one (the picker starts on "Auto", which draws no dot)
- ✅ Accessibility pass (v1): screen-reader labels for icon-only controls (e.g. the star reads "Add star"/"Remove star" instead of the ★ glyph), decorative icons left unlabelled to avoid double-announcement, and text scales with the system font size (Compose sp); 💡 fuller TalkBack audit, large-touch-target review

## "Complete app" extras

- ✅ ⭐ Vacation responder / auto-reply, JMAP `VacationResponse` (RFC 8621); per-account, server-side, Settings → Accounts → Vacation Answer (enable + subject + message + optional date range), IMAP/no-capability gated
- ✅ On-device storage usage + Clear cache (Settings → Storage); ✅ server mailbox quota via JMAP `Quota` (RFC 9425) shown in Settings → Storage when supported
- ✅ Server-side filters/rules where the server supports `SieveScript` (RFC 9661), form-based rule builder (condition → move/mark-read/star), compiled to Sieve and round-tripped via a JSON metadata comment. A script Sterna cannot read is never silently overwritten: the screen names the script that is actually running, says plainly when the rules are not being applied and offers to put them back, warns before replacing an unreadable script, and states what saving the filters costs while a vacation auto-reply is running. A rule that files mail into a nested folder names that folder in full
- ✅ Calendar invite (`.ics`) preview, a `text/calendar` part is detected (captured on
  IMAP even without a filename; JMAP lists it already), the first `VEVENT` is parsed by a
  small dependency-free iCalendar reader (timezones, all-day, recurrence, `DURATION`), and
  an event card above the body shows title / when / where / organiser / guests. "Add to
  calendar" opens the user's calendar app prefilled via an Intent, so **no calendar
  permission** is taken; a parse failure falls back to opening the raw `.ics`.
- ✅ RSVP to an invite, Accept / Decline / Tentative on a `REQUEST` invite sends an iTIP
  `REPLY` email (`text/calendar; METHOD:REPLY`) to the organiser, built without any
  dependency and sent over the existing JMAP/IMAP path (so still **no calendar
  permission**). 💡 Conflict detection later (deferred: it would need calendar read access)
