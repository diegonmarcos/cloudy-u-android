# Privacy

**Short version: Sterna collects nothing and has no server of its own. It talks
to the mail server you configure, and to a short list of other hosts, listed
below, each of them because you asked for it. Everything else stays on your
device.**

Sterna is free software (GPLv3) with no business model built on your data. There
is no Sterna account, no Sterna server, and no company in the middle. This document
explains exactly what that means, in plain terms. Last updated: 2026-09-04.

## What Sterna does NOT do

- **No analytics or telemetry.** No usage statistics, no event tracking, nothing
  phoned home, ever.
- **No advertising and no ad SDKs.**
- **No third-party trackers.** The app contains no tracking or profiling
  libraries.
- **No crash reporting to anyone.** Crashes are not sent off your device.
- **No Google services.** Sterna does **not** use Firebase, Google Play Services,
  or Firebase Cloud Messaging (FCM). New mail is pushed from your own server to
  your phone over JMAP EventSource, IMAP IDLE or UnifiedPush, so the app runs
  fully on de-Googled devices.
- **No proprietary dependencies.** Sterna is built only from free/open-source
  libraries.

## Data stored on your device

Everything Sterna keeps lives locally on your phone. None of it is collected, and none
of it reaches the Sterna project, which has no server to receive it. What does leave the
device goes only to the hosts listed under **Network connections** below: the address you
type, which reaches the approval page your own server puts up when it signs you in with
the device flow, before there is any token, and only over an encrypted link; your
credentials to your mail server, which is how it knows you; and, on an OAuth account, the
stored refresh token to your provider's sign-in service, which is where a new access token
comes from. The rest stays here:

- **Account credentials**: stored encrypted, protected by the Android KeyStore.
- **Cached mail**: messages, threads, and mailboxes are cached locally so the
  app works offline. This cache is removed when you sign the account out.
- **Attachments you open**: an attachment you open is written to the app's own
  storage until the cache is cleared, including one that arrived encrypted, since
  it has to be decrypted to be opened. The cache is pruned when you open the next
  attachment, and only then: files older than 30 days go first, then the oldest
  files until what is left fits under 200 MB. Nothing prunes it in the background,
  so if you open no further attachment, the last one stays on the device until you
  clear the cache or sign the account out. You can clear it at any time from
  Settings, under Storage, with **Clear cache**, and signing the account out
  clears it too.
- **Attachments you save**: saving an attachment writes it into the folder you
  pick in the system's own file picker, outside the app, including one that
  arrived encrypted, which is saved decrypted since that is what you asked for.
  Its bytes go from your server into that file directly, without a copy in the
  app's cache. Nothing prunes it and nothing clears it: neither **Clear cache**,
  nor signing the account out, nor uninstalling the app reaches a file that is no
  longer the app's, so it stays on the device until you delete it yourself.
- **A message you save as `.eml`**: **Save as .eml** writes the message exactly
  as your server holds it, its headers and everyone it was addressed to
  included, into the folder you pick in the system's own file picker, outside
  the app. A message that arrived encrypted is written as it arrived, still
  encrypted. Nothing prunes it and nothing clears it: neither **Clear cache**,
  nor signing the account out, nor uninstalling the app reaches a file that is
  no longer the app's, so it stays on the device until you delete it yourself.
- **A message you print, or save as PDF**: **Print** hands the message, laid out
  as you are reading it, to Android's own print service, including one that
  arrived encrypted, which is handed over decrypted since what goes on paper is
  what is on screen. What stays on the device then depends on what you pick
  there. Choosing **Save as PDF** writes a document into the folder you pick in
  the system's own file picker, outside the app, and nothing prunes it and
  nothing clears it: neither **Clear cache**, nor signing the account out, nor
  uninstalling the app reaches it either, so it too stays until you delete it
  yourself. Choosing a real printer leaves no file of Sterna's behind: the
  decrypted message goes to the print service and on to that printer instead.
  What Android's own print service keeps of a job while it is queued, and for
  how long, belongs to that service rather than to the app.
- **Drafts you write on the phone.** A draft's subject, text and recipients are
  written to the device before anything goes to your server, and they stay there
  until it has been uploaded, which with no network can be days. Until that
  upload your server has not seen those. A file you attach is treated
  differently depending on the account: on a JMAP account it is uploaded to your
  server the moment you attach it, so the server holds it from then on and the
  draft keeps only a reference; on an IMAP account, and on a JMAP account with
  PGP switched on, its bytes are copied into the app's own storage beside the
  draft instead, so the draft still has them when it finally leaves. The draft
  and any copies beside it are removed once it reaches the server. One thing
  outlives that: on those same two kinds of account, the bytes were first staged
  in the app's cache as you attached them, and that staged copy is cleared when
  you use **Clear cache** or sign the account out, not when the message goes.
  Signing out removes all of it.
- **Messages waiting to go out.** Every message you send is written to the device
  before it leaves: the subject, everyone it is addressed to, copies and blind
  copies included, and the text you wrote, or, when the message is encrypted, only
  its encrypted form. Most are gone in seconds. Some wait, and can wait a long
  time: one you scheduled for later, since nothing caps how far ahead you may
  schedule it; one whose send has failed for good; one you reopened and saved on
  the phone; one parked because the app was interrupted mid-send. A message in the
  Outbox can have a file beside it, though a scheduled one never can: the bytes of
  what you attached on an IMAP account, or the finished message itself when it was
  built ahead of the send, as a signed or encrypted one is. Removing the account
  does not delete any of this. Some of it goes later, on its own, when the app next
  tries to send and finds the account gone; the rest stays until you remove it,
  because it is text you wrote that your server has never received, and taking it
  away with the account would destroy the only copy there is. As long as one
  account is left on the phone you can see it and remove it: a message that is
  waiting or that failed is on the **Outbox** screen, where you can delete it, and
  a scheduled one is on the **Scheduled** screen, where you can cancel it.
- **Settings**: your preferences (e.g. notification scope). They belong to the app
  rather than to any one account, so removing an account leaves them as they are;
  uninstalling clears them.
- **Senders you allow to load their images.** Choosing **Always show images from
  this sender** keeps that address on the device, so later messages from it load
  their remote content without asking. The list holds other people's addresses, and
  it is filed under no account, so removing an account leaves it in place. You can
  edit it or empty it at any time in Settings, under Privacy & security, as **Remote
  images**; see **Remote content is blocked by default** below.
- **Addresses you have written to.** When you send a message, its recipients are
  kept in a local list so the To field can complete them as you type. The list holds
  an address, the display name that came with it when the message carried one, and
  when you last wrote to it; never message content. It is filed under no account.

Everything above is removed when you remove the account or uninstall the app,
subject to normal Android storage behaviour, except for what stays behind: your
settings, the list of addresses you have written to and the list of senders you
allow to load their images are filed under no account, so removing one account
leaves them in place; a message still waiting to go out is not taken with the
account, since your server never received it; and the files written outside the
app (an attachment you saved, a message you saved as `.eml`, a document you
saved as PDF) belong to the folder you picked rather than to the app, so nothing
Sterna does reaches them. Uninstalling the app removes the rest, but not those:
they stay on the device until you delete them yourself.

## Network connections

Sterna has no server of its own, so every connection it makes goes to a host you
chose, or to one named in a message you received. There are six, each with what
sets it off.

1. **Your mail server**, over **JMAP** or over **IMAP/SMTP**, depending on what
   your account uses. This is where mail is read, searched, sent and synced, and
   where a JMAP EventSource or an IMAP IDLE connection is held open, or a
   periodic check made, to learn about new mail. On IMAP, Sterna also tells the
   server its own name and version once per connection (the `ID` command, RFC
   2971), because some providers refuse to open a mailbox for a client that
   stays anonymous. It is skipped at the least doubt: when the server's own
   list of supported commands says it would not understand it, and equally when
   that list cannot be read or the command itself cannot be sent.
2. **The domain of the address you type, and its mail-named subdomains**, asked
   when you leave the address field or tap Continue, so you do not have to know
   your server's name. This happens at the address step, before there is any
   password to send, and all three questions below are asked again if you open
   the manual server form without filling it in. **None of these requests
   carries a credential**: they ask the domain how mail is configured for it,
   not who you are. Three questions are asked, all of them derived from your own
   address's domain, and each of them may find nothing, in which case you fill
   the server details in yourself:
   - **What the domain publishes for mail clients**, fetched over HTTPS from
     `autoconfig.<domain>/mail/config-v1.1.xml`, then from
     `<domain>/.well-known/autoconfig/mail/config-v1.1.xml`. A redirect is
     followed only over HTTPS and only to `<domain>` itself or a subdomain of it,
     at most five hops, so this fetch cannot be carried off to a third party's
     configuration database. A refused redirect is never contacted at all.
   - **The conventional server names**, tried only when the domain published
     nothing usable: Sterna opens a TLS connection to `imap.<domain>` then
     `mail.<domain>` on port 993, and to `smtp.<domain>` then `mail.<domain>` on
     port 465, and keeps a name only if the certificate offered is valid for that
     exact name. These are connections rather than web requests, and nothing of
     yours travels over them: the handshake is the whole of the question, and it
     is asked so that a password is never prefilled towards a host that cannot
     prove its own name.
   - **How the domain signs users in**, an HTTPS `GET` of
     `/.well-known/oauth-authorization-server` on `mail.<domain>`, `jmap.<domain>`
     and `api.<domain>`. At this step the bare domain is deliberately not asked,
     because it is usually the organisation's website and its answer would speak
     for the website rather than for the mail; the "Sign in with OAuth" button on
     the manual server form does ask it, along with the other three, because
     there you have said yourself that the server signs in that way. This one is
     asked for **every** address,
     whatever it turns out to sign in with, and its answer is what decides
     whether the next screen offers you a password field or a hand-over to your
     provider. Redirects are followed here, on purpose, so the answer may come
     from wherever those three hosts point.
3. **The same domain again, with your credentials, once you have typed a
   password.** Sterna requests `/.well-known/jmap` over HTTPS on that domain and
   on its conventional `mail.`, `jmap.` and `api.` subdomains, and each of those
   requests carries the address and password you just typed, in an HTTPS
   `Authorization` header. This is how JMAP autodiscovery works: a candidate only
   counts if it hands back a session for your account, which it cannot do without
   authenticating you. So each of those four hosts, all derived from your own
   address's domain, is offered your credentials, and so is any host one of them
   redirects to. The probe stops at the first candidate that returns a session
   holding a mail account. Answering is not enough to stop it: a host that
   refuses the sign-in, or that serves something which is not a JMAP session, is
   passed over and the next candidate is tried. It happens on the setup screen
   only, never afterwards.
4. **Your provider's sign-in service**, on an account that signs in with OAuth.
   It is the service that asks you to approve the sign-in and hands back the
   token. Which host that is comes from the discovery in point 2, which is run
   for every address; what only ever happens on an OAuth account is the token
   itself travelling, and, where your own server signs you in with the device
   flow, the address you typed going to its approval page so it can fill the
   field for you. Worth stating plainly: that host is the one the discovery
   document named, and the discovery still follows redirects, but the document
   can no longer name just anyone. An endpoint is used only over HTTPS, and
   only on the host that was asked, on the domain of your own address, or on a
   subdomain of one of those; anything else is dropped and the sign-in is
   refused instead of being sent elsewhere. That check happens when the account
   is added, so an account already set up keeps the endpoint it was given.
   The one exception is the Outlook / Microsoft option, which is not
   discovered at all: it signs in against Microsoft's own fixed address,
   `login.microsoftonline.com`, and is not handed the address you typed.
   Sterna also goes back to it on its own: an access token expires, so
   the stored refresh token is sent to the same token endpoint for a fresh one
   whenever a sync, a send or a push check needs a valid token, screen off and
   nobody watching. An account using a password or an app password sends it
   nothing, though its domain was still asked point 2's question.
5. **The unsubscribe address the sender put in a newsletter**, and only when you
   tap Unsubscribe on that message. Where the sender offers one-click unsubscribe
   (RFC 8058), Sterna sends that address one HTTPS request whose content is fixed
   by the standard: it carries none of your credentials, nothing is added to it
   about you, and no page is loaded from it. Where the sender offers only a web
   page, the button reads "Open page" instead, and Sterna hands that address to
   your browser, which does load it. Sterna asks first, and says in the same
   breath that the page can show the sender your IP address.
6. **The hosts a sender chose for the remote images in their message**, and only
   for a message whose remote content you allowed. That is your doing, once per
   message or once per sender; see "Remote content is blocked by default" below
   for what allowing lets through.

Beyond these six, Sterna opens no connection of its own initiative: no update
check, no license check, no reporting endpoint.

**A UnifiedPush distributor** (ntfy, NextPush…) sits on the path too, when your
account uses that push transport, but it is not a host Sterna connects to. On the
phone, the distributor app passes the signal to Sterna through Android, not over
the network. The signal holds no message content, so the distributor cannot read
your mail or see who wrote to you; it does see that push traffic is going to that
account, and when. The mail itself is always fetched from your own server. What
Sterna does with the endpoint address the distributor issued depends on the
account.

On a **JMAP account**, Sterna hands that endpoint address to your mail server, and
it is your server that posts the new-mail signal there. Whether the signal is
encrypted is your server's doing: Sterna gives the server WebPush keys for it, and
refuses the distributor outright when the keys are missing, falling back to the
direct connection, but it does accept a signal that arrives unencrypted.

On an **IMAP account**, the address is a relay address, and Sterna hands it to
nobody. There is one only if you asked for it: that account's notification
settings offer to get an address, and only while a UnifiedPush app is installed.
Once there is one, the same screen shows it with a button to copy it, and you
are the one who gives it to whatever you already trust. Sterna gives no WebPush
keys to anyone on this path, and asks your mail server for no push key either.
Nothing of your mailbox travels through that address: a POST to it carries no
mail, and what it sets off is Sterna going to check your own server over its own
connection. It is not nothing, though. The first POST to arrive is what tells
Sterna the address works, so that account starts being woken this way and lets
its permanent IMAP connection go; later ones stamp the last wake time that
account's notification settings show you. Anyone who has the address can do
that, not only whatever you gave it to. SECURITY.md follows it through.

Some addresses in the app are **links, not connections**: your provider's
app-password page, the OAuth approval page, an unsubscribe page offered without
one-click, and the project's repository and F-Droid pages open in your browser
when you tap them. That is your browser talking to those sites; Sterna never
contacts them in the background.

There is no intermediary. Your mail never passes through any infrastructure
operated by the Sterna project.

## Remote images load by default (since the Gmail-like reader)

Many emails contain remote images and **tracking pixels** that report back to the
sender when (and sometimes where) you open a message. Like Gmail, Sterna now
**loads remote images by default**, so a message looks the way its sender made
it: this is the **Load remote images** switch in Settings, on by default (it was
off before; a stored "off" of the old default was turned on once, by the update
that made the reader Gmail-like). Turn it off to get the old behaviour described
below: remote content blocked until you ask, so simply opening an email does not
leak that you read it. Only the message you are actually reading fetches images,
never a neighbouring message the pager prepared. Inline images that are part of
the message itself are rendered from the message, not fetched from the network.

You can lift the block yourself, in two ways. **Show images** loads the remote
content of the message you are reading, that once only. **Always show images from
this sender** adds the sender to a list, kept in Settings under Privacy &
security as **Remote images**, and every later message from that address shows
its remote content without asking. That list can be edited or emptied there at
any time.

Allowing a sender lifts the block for **every** message they send you. Every
remote image those messages contain is then fetched, tracking pixels
included: Sterna does not sort the good from the bad once loading is allowed, and
nothing in the app filters those pixels out. Allow a sender only when you accept
that they learn you opened the message.

Reading a message as plain text loads nothing remote at all, including from a
sender you have allowed: the block stays on for that message, and the text
Sterna renders in place of the HTML carries no image to fetch in the first
place. That reading mode is a switch in Settings, under **Reading and writing**,
off until you turn it on, and the menu in an open message switches the message
in hand either way.

The rest of a message's remote content stays blocked whatever you allow. Sterna
puts a content policy on every message it renders, which permits images and media
and nothing else: no scripts, no iframes, no plugins, no remote fonts, no form
submissions. JavaScript is off in the message view at all times.

## What your mail server can see

Sterna cannot make your email provider private. Whatever server you connect to
necessarily processes your messages and sees your requests, and that is true of any
email client. If this matters to you, connect Sterna to a server you trust or one
you run yourself (for example, a self-hosted [Stalwart](https://stalw.art/)
instance). Choosing and trusting your provider is the one privacy decision Sterna
leaves in your hands.

## Permissions and why they are needed

Sterna's own manifest declares seven permissions, the minimum the app needs:

| Permission | Why |
|---|---|
| `INTERNET` | Reach your mail provider over the network. |
| `ACCESS_NETWORK_STATE` | Notice whether the device is online. It drives the offline state, the resync once the connection comes back, and whether a failed request is reported as being offline. Sterna reads the connection status, never what travels over it. |
| `POST_NOTIFICATIONS` | Show new-mail notifications (Android 13+). Optional; you can deny it. |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` | Keep the push connection alive to deliver new mail without Google's FCM. |
| `RECEIVE_BOOT_COMPLETED` | Hear that the device has finished booting, or that Sterna was updated in place, so the push connection is restored without opening the app. Granted at install; nothing is asked of you. |
| `READ_CONTACTS` | Optional and **off by default.** It is requested in two places, both on your say-so: the recipient-autocomplete switch in Settings, and a one-time offer the first time you write a message, which you can decline (it is then never offered again). Contacts are only ever read locally to suggest addresses while composing, and are never uploaded. |

The installed app carries three permissions more than that table, and the installed
list is the one F-Droid and Android's app-info screen show you. They are not written
in Sterna's manifest: the build merges the manifests of the libraries Sterna is
built from into the app's own, and these three arrive that way.

- `USE_BIOMETRIC`, from the `androidx.biometric` library, is the one Sterna
  genuinely uses. It backs the optional app lock, which asks for your fingerprint,
  face or screen PIN before Sterna opens. The lock is off until you turn it on in
  Settings, and no biometric data ever reaches the app: Android runs the check and
  answers yes or no.
- `USE_FINGERPRINT` comes from the same library. It is the pre-Android 9 form of
  that prompt, kept by the library for the Android releases that predate it.
- `WAKE_LOCK`, from the `androidx.work` library, lets a scheduled background check
  finish with the screen off. The library takes the lock for the length of the
  check and releases it.

None of the three reads anything about you. One more entry, ending in
`DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, is generated from Sterna's own package
name; it is signature-level, it guards Sterna's internal broadcasts, and no other
app can hold it.

Beyond all these, nothing is asked: no location, no storage outside the app's own
sandbox, no microphone, no camera.

## Changes to this policy

If this policy changes, the change will be visible in the project's Git history,
and the "Last updated" date above will be revised.

## Contact

Questions about privacy can be raised as an issue on the project repository. For
security-sensitive reports, see [SECURITY.md](SECURITY.md) when available.
