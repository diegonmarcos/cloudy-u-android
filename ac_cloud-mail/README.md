<p align="center">
  <img src="docs/logo.png" width="112" alt="Sterna Mail logo">
</p>

<h1 align="center">Sterna Mail</h1>

<p align="center">
  <b>All your mail. None of the baggage.</b><br>
  A fast, private <b>JMAP email client for Android</b>, fluent in classic <b>IMAP/SMTP</b> too.<br>
  No ads. No tracking. No Google. ✨
</p>

<p align="center">
  <a href="https://sternamail.org"><b>sternamail.org</b></a>
</p>

<p align="center">
  <a href="https://sternamail.org"><img alt="Website" src="https://img.shields.io/badge/website-sternamail.org-2F5E59"></a>
  <a href="https://codeberg.org/emon/sterna-mail/actions"><img alt="CI" src="https://codeberg.org/emon/sterna-mail/actions/workflows/ci.yml/badge.svg?branch=main"></a>
  <a href="https://f-droid.org/packages/app.sterna/"><img alt="F-Droid" src="https://img.shields.io/f-droid/v/app.sterna?logo=fdroid&color=1976D2"></a>
  <a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22app.sterna%22%2C%22url%22%3A%22https%3A%2F%2Fcodeberg.org%2Femon%2Fsterna-mail%22%2C%22author%22%3A%22emon%22%2C%22name%22%3A%22Sterna%20Mail%22%7D"><img alt="Obtainium" src="https://img.shields.io/badge/Obtainium-add-43A047"></a>
  <a href="https://codeberg.org/emon/sterna-mail/releases/latest"><img alt="Latest release" src="https://img.shields.io/badge/download-latest%20APK-2F5E59"></a>
  <a href="LICENSE"><img alt="License: GPLv3" src="https://img.shields.io/badge/license-GPLv3-blue"></a>
  <a href="https://ko-fi.com/emoncode"><img alt="Support on Ko-fi" src="https://img.shields.io/badge/Ko--fi-support-FF6A4D?logo=ko-fi&logoColor=white"></a>
</p>

Sterna Mail is open-source and built for **JMAP**, the new and faster internet
standard for email, with full support for classic **IMAP/SMTP** too. Just your
mail, on the server *you* choose.

> _Named after **Sterna**, the genus of terns 🐦, the Arctic tern flies the
> longest migration of any animal, carrying itself clear across the world and back
> every single year. I liked the idea of mail that travels light and always finds
> its way home._

> **Status: released, and under active development.** Sterna is a complete,
> daily-drivable email client: read, organise, search, write, schedule, and send
> mail (with attachments) over JMAP or IMAP/SMTP, across multiple accounts, fully
> offline-capable, with OpenPGP encryption, push notifications, and remote
> content blocked by default. Hit a rough edge? Please tell me about it!

## 📸 A look

| Inbox (light) | Inbox (dark) | Reading | Compose | Appearance |
|:---:|:---:|:---:|:---:|:---:|
| ![Inbox, light theme](docs/screenshots/inbox-light.png) | ![Inbox, dark theme](docs/screenshots/inbox-dark.png) | ![Reading a message](docs/screenshots/message.png) | ![Composing](docs/screenshots/compose.png) | ![Appearance settings](docs/screenshots/settings.png) |

*Light "Arctic" and dark "Pelagic" themes, a calm sea-teal accent with a warm
coral touch, the tern's beak. 🪸*

## 📲 Download & install

**The easy way, [get it on F-Droid](https://f-droid.org/packages/app.sterna/)** 🎉
Install it from the F-Droid app (or its website) and updates arrive automatically.

Using [Obtainium](https://github.com/ImranR98/Obtainium)? [Add Sterna in one tap](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22app.sterna%22%2C%22url%22%3A%22https%3A%2F%2Fcodeberg.org%2Femon%2Fsterna-mail%22%2C%22author%22%3A%22emon%22%2C%22name%22%3A%22Sterna%20Mail%22%7D)
and it tracks your Codeberg releases, updating as soon as a new one lands.

Prefer a direct download? Grab the **latest APK** yourself:

1. **[⬇️ Download the latest APK](https://codeberg.org/emon/sterna-mail/releases/latest)** (under *Assets*).
2. Open the downloaded `.apk` on your phone. Android will ask whether to **allow
   installing apps from this source**, accept it for your browser or file manager.
3. Tap **Install**, then open Sterna and add your account.

> ℹ️ Sterna's builds are **reproducible**, and F-Droid verifies them: the APK it
> distributes is byte-for-byte the one released here, signed with Sterna's own
> key, so the two install sources update cleanly over each other. No Google
> account or app store needed, ever.

> 🔑 **Verify the signature.** Every release APK is signed with Sterna's own key.
> Its SHA-256 certificate fingerprint is:
>
> ```
> CF:2D:00:7F:7B:FA:44:E0:8E:20:94:3D:E4:FE:17:FB:08:73:70:7F:D7:59:D6:DB:9C:AD:B1:F4:B2:AA:ED:C4
> ```
>
> Obtainium (verify signature) and [AppVerifier](https://github.com/soupslurpr/AppVerifier)
> can check a downloaded APK against it before you install.

Prefer to build it yourself? See [CONTRIBUTING.md](CONTRIBUTING.md).

## 🤔 Why JMAP?

Most email apps talk to your server using an old protocol called IMAP. **JMAP** is
its modern successor: it syncs faster, uses less battery and data, and was designed
for today's phones. Servers like [Stalwart](https://stalw.art/) speak JMAP, and
Sterna is built to make the most of it. ⚡

Sterna also speaks classic **IMAP and SMTP**, so it works with any standard mail
provider, pick the protocol when you add an account, or just type your email and
let autodiscovery find the server for you.

Running your own server, with a free certificate or one you issued yourself? See
[Self-hosted server: getting its certificate accepted](docs/SELF-HOSTED-TLS.md).

## 💚 What Sterna stands for

- **🔒 Privacy first.** No tracking, no ads, no analytics, no Google services.
  Remote images load by default like Gmail (Settings > Load remote images turns that off, and tracking pixels are then blocked); tapped links can be
  stripped of tracking parameters and confirmed before opening.
- **🆓 Free and open.** Licensed under the GPLv3, anyone can read, audit, and
  improve the code.
- **🎨 Modern and calm.** A clean, fast Material 3 interface with its own brand
  identity that follows your phone's theme and language.

## ✨ Features

**📥 Reading & organising**
- Unified inbox across multiple accounts (JMAP and/or IMAP/SMTP), fully offline
  (local cache); the unified list shows what is cached, and a single JMAP folder
  pages in older mail from the server as you scroll (on IMAP a folder stops at
  the sync window)
- Conversation view, threads collapse into one row with a message count
- Nested folders shown as a collapsible tree; create / rename / delete, plus
  subfolders
- Configurable swipe actions, star, archive & delete with undo, multi-select
  bulk actions, snooze, report spam, empty trash (with undo), sort and unread-only
  filter
- Search-as-you-type (server-side + instant local results) with advanced filters
  (from, recipient, subject, has-attachment, date); Trash and Spam are left out
- Calendar invites preview as an event card; one tap adds them to your calendar
  app (no calendar permission needed)

**✍️ Writing & sending**
- Compose with recipient chips + autocomplete and email validation, multiple
  identities each with its own signature, attachments, and drafts
- Undo send, schedule send (with a screen to view/cancel pending sends), and
  "forgot attachment?" / large-recipient-list reminders
- Opens `mailto:` links from any app or browser, prefilled (addresses, subject,
  body, cc/bcc)

**🔑 Accounts & setup**
- Add an account with just email + password (`/.well-known/jmap` autodiscovery),
  password-free **OAuth 2.0** sign-in (device flow, or the authorization code
  grant with PKCE through the browser where a server has no device endpoint), or
  manual IMAP/SMTP with provider presets; a "test connection" button before saving
- Per-account colour, sync window, and a "Sync new mail" switch governing that
  account's background fetch and its notifications; encrypted credential
  storage (AndroidKeyStore); app lock (biometric / PIN)

**🔒 Encryption**
- **OpenPGP** (via an external OpenPGP app such as
  [OpenKeychain](https://f-droid.org/packages/org.sufficientlysecure.keychain/)):
  read and send signed and/or encrypted mail (PGP/MIME) on both JMAP and
  IMAP/SMTP. Decrypted content is never written to disk, with attachments as the
  exception: one you open is written to the app's own storage until the cache is
  cleared, and one you save goes to the folder you pick and stays there.
  Printing a message, or saving it as a PDF, hands its decrypted text to the
  system print service. See the [encryption guide](ENCRYPTION.md) to get started.

**🔔 Notifications & server power**
- Push for new mail with **no Google / FCM** (JMAP EventSource, IMAP IDLE or
  **UnifiedPush**), grouped per account, with quiet hours
- Two home-screen widgets, an unread count for the unified inbox and a short
  list of recent mail; rows follow the notification-content setting, and name
  neither sender nor subject when the app lock is on
- Server-side **vacation responder** and **Sieve filter rules**, mailbox quota
  display

**🎨 Polish**
- Sterna's own Arctic (light) / Pelagic (dark) palette, optional Material You,
  adjustable message text size, settings export/import, a screen-reader-friendly
  UI, and coastal empty-state illustrations

The full, tracked feature set lives in [FEATURES.md](FEATURES.md).

**🛠️ Planned:** S/MIME, and more.

## 🌍 Languages

Sterna's interface is fully translated into nine languages:

🇬🇧 English · 🇫🇷 French · 🇩🇪 German · 🇪🇸 Spanish · 🇮🇹 Italian · 🇵🇹 Portuguese ·
🇳🇱 Dutch · 🇷🇺 Russian · 🇵🇱 Polish

It also respects your system language out of the box, and you can override it
per-app in Settings → Appearance.

## 👩‍💻 For developers

Architecture and build instructions live in [ARCHITECTURE.md](ARCHITECTURE.md)
and [CONTRIBUTING.md](CONTRIBUTING.md). The planned and shipped feature set is
tracked in [FEATURES.md](FEATURES.md). Contributions, bug reports and ideas are
very welcome. 🙌

Sterna Mail is written and maintained by one developer. Every change is
reviewed and tested before it ships, on an emulator and on real hardware
according to what the change needs. Issues are closed on reporter
confirmation, and release builds are reproducible and checked by F-Droid
against the published source.

AI coding assistants are part of that toolchain. They do not decide what Sterna
does and they do not review their own output. Nothing reaches a release without
being read, built and tested by a human who is accountable for it.

## ☕ Support

Sterna is free and open, and always will be. If it's useful to you and you'd like
to help fuel its development, you can **[buy me a coffee on Ko-fi](https://ko-fi.com/emoncode)**.
Entirely optional, thank you for even considering it. 💚

## 🙏 Acknowledgements

Sterna's interface is freely inspired by **[K-9 Mail](https://github.com/thunderbird/thunderbird-android)** (now Thunderbird for Android), the venerable open-source Android mail client. Many of its interaction patterns, the way you swipe, triage, and read your mail, are the fruit of years of work by the K-9 community, and Sterna is better for standing on their shoulders. Heartfelt thanks to the K-9 / Thunderbird for Android developers for building, and freely sharing, such a fine app. 💚

## 📄 License

[GNU General Public License v3.0](LICENSE).
