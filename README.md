<div align="center">

# uwuMail

**A multi-account IMAP client for Android that treats every message as untrusted input.**

Regex rules that act on mail before it reaches your notification shade · tracking
pixels that are never requested · folders that live only on your phone.

<p>
  <img alt="Platform" src="https://img.shields.io/badge/platform-Android%2012%2B-3DDC84?logo=android&logoColor=white">
  <img alt="minSdk" src="https://img.shields.io/badge/minSdk-31-3DDC84">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white">
  <img alt="Jetpack Compose" src="https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?logo=jetpackcompose&logoColor=white">
  <img alt="Tests" src="https://img.shields.io/badge/tests-182%20passing-brightgreen">
  <img alt="Licence" src="https://img.shields.io/badge/licence-MIT-blue">
</p>

<p align="center">
  <img src="docs/screenshots/message-list.png" width="235"
       alt="The message list, grouped by day">
  <img src="docs/screenshots/message-banners.png" width="235"
       alt="A message showing the unsubscribe and blocked-image banners">
  <img src="docs/screenshots/folder-picker.png" width="235"
       alt="The folder picker, listing every account's folders">
  <img src="docs/screenshots/settings-reading.png" width="235"
       alt="The reading, image and script settings">
</p>

<sub>Sender addresses and subjects are blurred in these screenshots; nothing in the app is.</sub>

</div>

---

## Contents

- [Reading mail](#reading-mail) — what a message body is and is not allowed to do
- [Rules](#rules) — conditions, actions, and building one without knowing regex
- [Searching](#searching) — every folder, and the server too
- [Backup](#backup) — the rules are the part that exists nowhere else
- [Folders and mailboxes](#folders-and-mailboxes) — device folders, moving mail across accounts
- [The message list](#the-message-list) — grouped by day, and where new mail lands
- [Accounts and sending](#accounts-and-sending) — OAuth2, and a From address you choose
- [Drafts](#drafts) — save an unfinished message and come back to it
- [Appearance and language](#appearance-and-language) — themes, German and English
- [Background mail](#background-mail) — push, and the hours you allow it
- [Building](#building) · [Google sign-in](#google-sign-in) · [Tests](#tests) · [Layout](#layout) · [Notes and limits](#notes-and-limits)

---

## Reading mail

A mail body is untrusted input that would like to know when it was opened, so
uwuMail starts closed and offers to open up. Everything here is a setting, and
each one starts where it is described.

- **Unsubscribe banner** (on). When a message says how to unsubscribe, a strip
  above it does it in one tap. `List-Unsubscribe` is the authority and its
  HTTPS entry beats its `mailto:` entry; senders that skip the header almost
  always still put a link in the body, so that is scanned as a fallback in
  English, German, French, Spanish, Portuguese, Italian, Dutch and Swedish.
  A one-click sender (RFC 8058) is named as one.
- **Ask before opening tracked links** (on). Links in a body always leave for
  the browser rather than navigating inside the message. One carrying `utm_*`,
  `fbclid`, `gclid`, `mc_eid`, `mkt_tok` and the like first offers to open a
  cleaned copy of itself; both answers open the link, the question is only
  which version. Only query parameters are ever removed. Path segments are left
  alone, because senders routinely encode the real destination in the path and
  a broken link is worse than a leaked click.
- **Preload unread mail** (on). While a list is on screen, the bodies of up to
  30 unread messages are fetched in the background, one at a time with a pause
  between them, so opening them is instant and works with no connection.
- **Block remote images** (on). Each message says how many remote images it is
  holding back and loads them on request, for that one viewing — reopening the
  message blocks them again.
- **Skip tiny images** (on, 10x10). An image smaller than this in either
  dimension is a tracking pixel — a 1x600 spacer reports an open just as
  reliably as a 1x1 — and it is **never requested from the server**. Blocking a
  beacon after downloading it would be pointless: the download is the thing the
  sender is watching for. See [how the beacons are found](#how-tracking-pixels-are-found).
- **Images the page never draws** are not fetched either, whatever the size
  filter is set to. There is no reason to ask a server for something that is
  not going to be shown.
- **JavaScript** (off). Nothing a mail needs to be read requires scripts.
- **Inline pictures** — the ones a message carries with it, referenced as
  `cid:` — always show. They arrived with the mail, cost no request, and tell
  the sender nothing, so the remote-image setting does not hold them back.
- **Mail is darkened** to match a dark theme: bodies with no dark styling of
  their own are darkened by the WebView, and senders who wrote
  `prefers-color-scheme` styling get to use it instead.

<a id="how-tracking-pixels-are-found"></a>
<details>
<summary><b>How tracking pixels are found</b></summary>

<br>

Almost every beacon says what it is in the markup, so the body is read before it
is rendered and those images are taken out of it entirely — the request is then
never made. uwuMail looks at:

| Where | What it reads |
|---|---|
| `<img>` attributes | `width` / `height`, including a literal `0` |
| Inline styles | `width`, `height`, `display`, `visibility`, `opacity` |
| `<style>` blocks | rules applied to the elements their selectors match |
| Ancestors | a `display:none` wrapper around the image |

Two things it deliberately does **not** do. The stylesheet is *read*, not
executed, so this is a reading of the CSS and not the CSS engine: specificity is
not weighed, and `@media` blocks are skipped, since a body hidden in some other
viewport should still show in this one. And anything it cannot read it leaves
alone — an image wrongly kept is merely shown, an image wrongly removed is gone.

An image that only reveals its size in its own bytes cannot be judged without
asking for it. Those still go through a second check at fetch time, which also
refuses anything that turns out not to be an image, keeps the request out of the
WebView's cookie store, and sends no referrer.

</details>

## Rules

Each rule is a set of conditions plus a set of actions.

*Conditions* match on From address, From display name, To/Cc, subject, body text,
any raw header, `List-Id`, attachment filename, size, or folder — with the
operators `matches regex`, `contains`, `is exactly`, `starts with`, `ends with`,
`domain is`, `greater/less than`. Every condition can be inverted or made case
sensitive, and conditions combine with all/any.

*Actions*: mark read/unread, star/unstar, archive, move to trash, delete
permanently, move or copy to an IMAP folder, move or copy to a device folder,
download the full message, and the three notification outcomes — **don't
notify**, notify silently, notify with high priority.

Rules run during sync, before any notification is posted, and the server-side
effects are batched per folder. A rule can be scoped to one account and/or one
folder, given a priority, and told to stop later rules from running. Everything a
rule does is written to an activity log you can read on the Rules screen.

<details>
<summary><b>Building a rule without knowing regex</b></summary>

<br>

Select several similar messages in the list and tap the label icon. uwuMail then:

1. Looks for everything those messages share — identical sender, shared sender
   domain, headers present in all of them with the same or overlapping values
   (this is what catches `X-GitHub-Event`-style mail), common subject prefix,
   suffix or substring, shared recipients.
2. Generalises the subjects into a readable regex when they share structure:
   the longest common token subsequence is kept literal and what varies between
   samples becomes the tightest class the samples justify (`\d+` before
   `[0-9a-fA-F]+` before `\S+` before `.*?`).
3. **Scores every candidate against the rest of your cached mail** and tells you
   how much else it would catch — "matches nothing else in 1,240 cached mails"
   versus "also matches 312 of 1,240".
4. Pre-ticks the smallest combination of conditions that catches your selection
   and nothing else, then lets you adjust it.

You pick the actions, name it, save. The generated rule is a normal rule and can
be opened in the editor afterwards.

The manual rule editor has the same safety net: **Test against cached mail**
reports how many messages the draft would hit before you save it, and
**Apply rules to mail already synced** replays your rules over mail you already
have.

</details>

### Spam lists

- Settings holds a set of public sender blocklists (disposable-mail providers,
  StopForumSpam's toxic domains, FakeFilter) plus any list URL you add and your
  own blocked senders. Lists are plain text, one domain per line.
- **Blocked senders** get a screen of their own: every entry, a filter, and each
  one removable. A blocklist you cannot read back is one you cannot trust.
- Mail from a listed sender is drawn in red in the message list. Nothing is
  deleted, moved or hidden on the strength of a list — the mail is still there
  and the match is visible. Use a rule if you want an action.
- Matching is done in the list query against an indexed sender domain, so
  toggling a list takes effect immediately without rewriting cached mail.

## Folders and mailboxes

- Unified views across every account: **All inboxes**, **All sent mail** and
  **All deleted mails**. Pull to refresh in any of them syncs that
  folder across every account, whether or not those folders are set to sync in
  the background.
- Long-press any folder in the drawer to reorder it, hide it on this device,
  mark it all read, or toggle background sync. Hiding leaves the folder
  untouched on the server; the order is per-device and can be reset. Hidden
  folders stay listed in the folder manager, which is where you unhide them.
- Folders that are synced in the background are marked; the rest are not, since
  background sync is off by default for everything but the inbox.
- The inbox always sorts first, then Drafts/Sent/Archive/Spam/Trash, then custom
  folders A-Z, then device folders.
- Create, rename and delete IMAP folders on the server, including nested paths.
- **Device folders**: a local folder that lives only on the phone. Moving mail
  into one downloads the full message as `.eml`, stores it in app storage, and
  deletes the server copy — only once the download has actually landed, so a
  failed fetch cannot lose the mail. Moving a message back out re-uploads it
  via `APPEND`. Copying into one takes a row and an `.eml` of its own, so
  reading or deleting the copy leaves the original alone, and mail can be moved
  between device folders. Rules can move or copy into them too.
- **Move and copy across mailboxes.** The picker lists every account's folders
  as `[address] Folder`, with the mail's own mailbox first — every mailbox has
  an Archive, so the account is the part that tells them apart. IMAP cannot
  copy between servers, so a cross-mailbox move downloads the message whole and
  appends it to the destination; the source copy is removed only once that
  append has been acknowledged, and a message whose source could not be fetched
  stays where it is. The destination folder is synced straight afterwards, so
  the mail shows up where it landed.
- Archive, trash, delete permanently, and save any message as `.eml` — the
  system picker chooses where it goes.
- Tapping an attachment asks whether to open it or save it, and saving asks
  where. **Hold one to start picking**: tap the rest, then save the lot into a
  folder chosen once, rather than answering the same picker for every file.
- A move or a copy is **checked, not assumed**: the target is asked whether it
  really holds the message before the local copy is discarded. A server can
  accept a `COPY` and still not keep it, and mail that quietly went nowhere is
  the worst outcome there is.
- **Share to uwuMail.** A photo, a file, several files or a piece of text
  shared from any other app opens the composer with them already attached.
- **Attach files** to a message from the system document picker. The bytes are
  copied into app storage rather than the content URI being kept, since a URI
  is a loan from the app that produced it and may not outlive a spell in the
  outbox.
- **Save the attachments** of one message or twenty at once, from the selection
  menu. They go to `Downloads/uwuMail` through MediaStore rather than into the
  app's private storage, because a download you cannot open from a file manager
  is not one.
- **Undo.** Archiving, trashing, moving and deleting wait five seconds before
  touching the server, and offer an Undo in the meantime. Holding the work back
  is the only honest way to do it: a permanent deletion cannot be reversed once
  the server has been told. The rows disappear at once regardless, so nothing
  feels slower, and anything a crash left hidden comes back on the next start.
  Several can be waiting at once — swiping through a few messages is exactly
  how — so the offers stack, each with its own few seconds to answer it.
- Removals are otherwise optimistic: the message goes from the list at once and
  the server catches up behind it. If the server refuses, the message comes back
  and the failure is reported rather than the mail going quietly missing.

## Searching

- **The search box narrows whatever is on screen.** In a folder it searches that
  folder, in a unified view every folder feeding it, and *Search every folder*
  in the overflow widens it to all of them at once.
- Searches **every folder of every account**, over subject, sender, recipients,
  preview and body text — not just the folder you happen to be looking at.
- Typing does not put the database to work on every letter: the list catches up
  a moment after the typing stops, and clearing the box puts it back at once.
- **Also search the server** reaches mail that was never synced, which for
  anything older than the last few hundred messages is all of it. Hits are
  cached like any other message, so the list picks them up and asking twice is
  instant. Subject, sender and recipients only: a full-text search over every
  message is expensive on the server and slow on a phone.

## The message list

- **Swipe either way**, with each direction set separately in
  *Settings → Swipe actions*: select, archive, trash, move, delete, mark
  read/unread, star, or nothing at all. Left selects and right archives to
  begin with, and a swipe must cross **half the row** before it counts — a
  quick flick should not be able to delete mail. Only
  the actions that empty the row carry it off the screen — the toggles spring
  back, since the row is still there — and permanent deletion asks first,
  because a swipe is far too easy to do by accident for something irreversible.
- **Grouped by day.** Every run of mail from one day sits under a heading like
  *Tuesday, 01.09.2026*, pinned to the top of the list while that day is on
  screen, so a long scroll always says how much time it has covered. The
  headings carry the full date rather than *Today* and *Yesterday*: two
  relative labels among absolute ones make that harder to read, not easier.
- The row answers the finger: a knock the moment a swipe has gone far enough
  to act, and rows that fade out and close the gap behind them rather than the
  list jumping to its new shape.
- New mail lands above what is on screen. If you are already at the top the list
  follows it up so the new message is visible; if you had scrolled down, your
  place is kept and nothing jumps. It also will not move during a fling, in a
  selection, or while you are reading further down.

## Accounts and sending

- **Configure folders** per account: which folder is archive, sent, drafts and
  trash. These are guessed from what the server advertises when an account is
  first seen, which is right for most and wrong for the rest — a server with no
  SPECIAL-USE attributes, or folders named in another language, leaves them
  pointing nowhere.

**Accounts**
- Any number of IMAP/SMTP accounts, each with its own sync interval, colour and
  notification channels.
- Password or **OAuth2 (XOAUTH2)** authentication per account. Gmail no longer
  accepts account passwords over IMAP, so Google accounts sign in through the
  browser — see [Google sign-in](#google-sign-in) below.
- Server settings are discovered from the domain's autoconfig
  (`autoconfig.<domain>`, `.well-known`, Mozilla's ISPDB) and stay fully editable.
- Passwords are encrypted with a hardware-backed AES-GCM key from the Android
  keystore; only the ciphertext is stored on disk.

### Sending with a custom From

- The From address in the composer is free text, not a dropdown of your own
  addresses. Type `xyz@mail.de` and that is what goes on the wire — your server
  decides whether to accept it.
- Frequently used addresses can be saved as *identities* per account and picked
  from a menu in the composer, where they can also be deleted. Account settings
  lists them too, and is where you choose which one new messages start from.
  Saving an address that is already saved updates it rather than duplicating it.
- `Envelope sender follows the From address` (per account) controls whether
  `MAIL FROM` tracks the From header or stays on the account address, since some
  servers require one or the other.

### The outbox

- **A send that cannot get through is queued, not lost.** Press send on a train
  and the message goes to the outbox with its attachments and the draft it was
  written in; every sync tries again, and *Settings → Retry outbox* tries now
  and says how many went.
- The draft it grew from is kept until the message is actually accepted. Until
  then the draft is the only copy, and deleting it first is how the only copy
  disappears.

## Replying

- Replying flags the original `\Answered` on the server, so the reply arrow
  appears here and in every other client reading the same mailbox. A reply that
  had to wait in the outbox flags it when it actually goes.
- **Reply, reply all and forward** sit on the message's bottom bar rather than
  in a menu.

## Drafts

- **Save an unfinished message** to the account's Drafts folder, and leaving the
  composer with something written asks rather than discarding it quietly.
- Tapping a message in Drafts reopens it in the composer where it can be
  finished, not in the reader where it can only be looked at. Saving again
  replaces the earlier copy; sending deletes the draft it grew from.
- **Whatever was attached comes back with it**, so a draft finished later is the
  message you left rather than the message minus its files. Forwarding carries
  the attachments on for the same reason; the inline pictures a body draws with
  `cid:` stay where they belong, in the quoted body.
- The old copy goes only once the new one has been accepted, so a failure
  halfway leaves the earlier draft rather than nothing at all.
- Saving needs no SMTP password: the draft goes to the IMAP server.

## Reading between messages

- **Swipe sideways in an open message** to move to the next or previous one,
  following the order the list was showing. Opened from a notification, with no
  list behind it, there is nowhere to swipe and nothing happens.

## Backup

- **Save rules and settings** to a file in `Downloads/uwuMail`, and restore from
  one. Rules are hand-built regex that exist nowhere else — not on the mail
  server, not in any account — so a lost phone loses them for good.
- Accounts and passwords are deliberately **not** included: the credentials are
  sealed to the device's keystore and could not be restored elsewhere anyway,
  and a file of mail passwords is not a thing to leave in a Downloads folder.
- Restoring adds to what is there rather than replacing it, and a file that is
  not a uwuMail backup is refused rather than half read.

## Appearance and language

- **Themes**: follow the system (with Material You dynamic colour where the
  device offers it), or pick Light, Dark or **Catppuccin Mocha**. Choosing one
  by name turns dynamic colour off, since a wallpaper overriding your choice
  would make it meaningless.
- **German and English.** Every user-facing string is a resource with a German
  translation beside it, so the app follows the phone's language — including the
  rule wizard's descriptions of what it found, the background-mail notification,
  and the header of a quoted reply. A test walks both files and fails on any
  string present in one and not the other.
- Text quoted from a server — a rejection, an authentication failure — is passed
  through as the server sent it rather than translated around.
- The rule activity log deliberately keeps the English action names: it is a
  record of what happened, not a screen, and should not change meaning with the
  display language.

## Background mail

- Push is **on by default**: a foreground service holds an IMAP IDLE connection
  per account, so new mail notifies you without the app being opened. IMAP has
  no push service to delegate to the way FCM-based messengers do, so the app
  holds the connection itself — the same approach Thunderbird and K-9 take.
- The service restarts after a reboot, after an app update, and after being
  killed; it wakes early when the network returns instead of waiting out its
  backoff, and holds a wake lock across the fetch so a sync started during Doze
  is not suspended half-way.
- Periodic WorkManager sync per account runs alongside it as a safety net
  (Android enforces a 15 minute floor) and restarts the service if it died.
- **Settings → Background mail** reports whether the connection is up and offers
  the battery-optimisation exemption. Without that exemption Doze suspends the
  connection while the screen is off, which is the usual reason background mail
  stops arriving. Manufacturer battery managers (Samsung, Xiaomi, OnePlus) sit
  on top of Android's and may need the app marked unrestricted there too.
- **Only check at set times** (off by default). Pick the weekdays and a start
  and stop time — 06:00 to 18:00, say — and unattended checking is confined to
  them. It governs both the periodic worker and the IDLE connection, since
  holding a socket open outside the window would be checking for mail; the
  watcher drops the connection and waits for the window to come round. A window
  whose end is at or before its start reads as spanning midnight, so 22:00-06:00
  means the night, and the part after midnight belongs to the day it opened on.
  Anything you ask for yourself — opening the app, pulling to refresh, sending —
  is never held back.
- **Mark read, Archive and Trash straight from the notification.** The point of
  the rules engine is to handle mail before it asks for attention; finishing
  the job without opening the app is the end of the same idea.
- One notification channel group per account, with default / silent / high
  channels so rules can downgrade or mute specific mail. An account's
  notifications gather under one summary line as soon as there are two of them
  standing, however far apart they arrived; the summary itself never makes a
  sound, since the mail under it already did or was deliberately told not to.

<p align="center">
  <img src="docs/screenshots/sync-window.png" width="300"
       alt="Choosing the days and the hours mail is checked">
</p>

## Building

Requires JDK 17+ and the Android SDK (compileSdk 35, build-tools 35.0.0). Point
`ANDROID_HOME` at the SDK or set `sdk.dir` in `local.properties`.

```sh
./build.sh                  # signed release APK -> ./uwuMail-release.apk
./build.sh debug            # debug APK -> ./uwuMail-debug.apk
./build.sh --install        # build, then adb install
./build.sh --clean          # clean first
./build.sh release --no-sign
```

`build.sh` finds the SDK from `ANDROID_HOME`, `local.properties` or
`~/Android/Sdk`, picks `./gradlew` (falling back to a `gradle` on `PATH` or a
cached distribution), builds, copies the APK into the project root and reports
the signing certificate.

**Signing.** The first release build generates `keystore/uwumail-release.jks`
and `keystore.properties` with a random password, and Gradle picks them up from
there. Both are gitignored. **Back them up** — Android will not install an
update signed with a different key, so losing the keystore means uninstalling
(and losing local mail) to move forward.

Release is minified and shrunk with R8; the ProGuard rules keep JavaMail whole,
since it resolves providers and content handlers by name at runtime and R8
cannot see those references.

Debug and release share the application id `de.uwumail` but not the signing key,
so a release APK will not install over a debug one. Uninstall first:

```sh
adb uninstall de.uwumail
```

Or drive Gradle directly:

```sh
./gradlew :app:assembleRelease
./gradlew :app:testDebugUnitTest
```

## Google sign-in

Gmail rejects plain account passwords over IMAP with
`Application-specific password required`. uwuMail handles this with a real OAuth2
sign-in: Settings holds the client id, the account screen has **Sign in with
Google**, and the app refreshes access tokens on its own from then on.

uwuMail ships with no OAuth client id, because Google binds a client to a
specific app package and signing certificate. Register your own once:

1. **console.cloud.google.com** → new project → **APIs & Services → Credentials**
2. **Create credentials → OAuth client ID → Android**
   - Package name: `de.uwumail`
   - SHA-1: shown in uwuMail under **Settings → Google sign-in** (tap to copy),
     and printed by `build.sh` after each build. Debug and release are signed
     with different keys and so have different fingerprints — add both to the
     OAuth client if you use both builds.
3. **OAuth consent screen** → External → add the scope
   `https://mail.google.com/` and add your own address as a test user
4. Paste the client id into **Settings → Google sign-in**, or put it in
   `local.properties` so it is compiled in:

   ```properties
   google.oauth.client.id=xxxxxxxx.apps.googleusercontent.com
   ```

Then open **Accounts → + → Sign in with Google**. Server settings and the address
fill themselves in; no password is stored.

**Keep the consent screen out of "Testing".** Google expires refresh tokens
issued by apps in testing status after 7 days, which means signing in again every
week. Setting the publishing status to *In production* avoids that; the app stays
unverified, so the first sign-in shows a "Google hasn't verified this app" screen
— *Advanced → Go to uwuMail (unsafe)* — and unverified apps using this scope are
capped at 100 users, which is irrelevant for personal use.

**Sending as a custom address will not work through Gmail.** Google rewrites the
From header to the authenticated address unless the alias is registered under
Gmail's "Send mail as". Use your own server for that; Gmail is fine as an account
to read and to run rules against.

## Tests

182 JVM unit tests, run with `./gradlew :app:testDebugUnitTest`:

- `rules/` — the rule engine (scoping, priority, stop-processing, negation,
  invalid regex, copies alongside a move), the regex builder, and the
  similarity suggester, including a GitHub-CI-shaped scenario asserting the
  wizard's recommendation catches the selected mails and none of the sibling
  notifications from the same sender and mailing list.
- `mail/UnsubscribeTest` — `List-Unsubscribe` parsing (HTTPS over `mailto:`,
  one-click, comment text outside the brackets), the body-link fallback in
  several languages, and refusing to follow a `javascript:` href.
- `mail/TrackingParamsTest` — which parameters go and which stay, fragments,
  case, and the path being left alone even when it is obviously a redirect.
- `mail/ImagePrefilterTest` — finding beacons in the markup before they can be
  requested: size attributes, inline styles, stylesheet rules by class and id,
  hidden wrappers, and the things it must leave alone, such as an image sized
  only in a media query or in units it cannot read.
- `mail/RemoteImagePolicyTest` — the tracking-pixel threshold in either
  dimension, custom limits, images that could not be measured, and refusing
  anything that is not an image.
- `mail/oauth/` — PKCE challenge derivation against RFC 7636, token response
  parsing, expiry handling, and id_token address extraction.
- `mail/FolderClassifierTest` — INBOX detection across casings and nesting,
  SPECIAL-USE attributes, delimiter handling, and folder ordering.
- `data/settings/SyncWindowTest` — the checking window, including overnight
  windows and which day their small hours belong to.
- `data/repo/BlocklistParserTest` — blocklist line parsing, including the
  entries that must be rejected because they would match everything.
- `core/` — swipe actions and themes surviving a setting written by a version
  that knew names this one does not, the wording of a partial attachment save,
  which must not report a failure as a smaller success, and the waiting that
  keeps a search off the database until the typing stops.
- `ui/` — day grouping of the message list, `[mailbox] folder` labelling, and
  the order swiping between messages follows.
- `ui/NavigationGuardTest` — runs a real NavHost under Robolectric and asserts
  the back stack can never be emptied by a second tap on a screen already left.
  A blank, unresponsive window is not something a test over pure functions can
  see coming; removing the guard fails two of these four.
- `mail/ContentIdTest` — matching a body's `cid:` reference to the part that
  carries it, across angle brackets, case and percent-encoding.
- `data/repo/BackupFormatTest` — refusing a file that is not a backup, or is
  from a later format, before anything is written.

## Layout

```
app/src/main/java/de/uwumail/
  core/        enums (fields, operators, actions), JSON and flow helpers
  data/db/     Room entities, DAOs, database
  data/crypto/ keystore-backed credential storage
  data/settings/ app-wide preferences
  data/repo/   account + identity repository, blocklists, connection testing
  mail/        IMAP client, connection pool, SMTP sender, MIME parsing, autoconfig
  mail/oauth/  OAuth2 + PKCE, token refresh, provider definitions
  rules/       matcher, rule engine, regex builder, similarity suggester
  sync/        sync orchestration, WorkManager, IMAP IDLE service
  notify/      notification channels and posting
  ui/          Compose screens and view models
  di/          hand-rolled dependency container
```

## Notes and limits

- HTML bodies render in a WebView with JavaScript, remote loads and file access
  all disabled — mail is untrusted input. The one exception is the gravity
  easter egg, which needs scripting for a single call to measure where the
  characters sit; the network is shut for the length of that call and both
  settings are put back however it ends. Remote images load only when the
  banner in that message is tapped, and uwuMail fetches them itself so it can
  refuse the ones that are only there to report the open.
- Message moves use `COPY` + `\Deleted` + `UID EXPUNGE`, rather than depending on
  RFC 6851 `MOVE`. A server too old for `UID EXPUNGE` (RFC 4315) leaves only the
  blanket `EXPUNGE`, which removes every `\Deleted` message in the mailbox — so
  it is used only when ours are demonstrably the only ones flagged, and the
  removal is refused rather than taking another client's mail with it.
- Android 15 caps `dataSync` foreground services at 6 hours per day, so push may
  pause on very long uptimes; periodic sync continues regardless and the worker
  restarts the service on its next run.
- The IDLE connection uses a 28 minute socket read timeout, just under the point
  RFC 2177 tells clients to re-issue IDLE and where servers drop it, so a
  half-open socket is noticed without churning the connection.
- Attachments can be sent from files already on disk; the composer does not yet
  have a file picker.
- OAuth2 is implemented for Google. The provider definition in
  `mail/oauth/OAuthModels.kt` is generic, so Microsoft/Outlook would be a matter
  of adding endpoints and scopes, but it is untested.

## Licence

[MIT](LICENSE). Use it, change it, ship it — just keep the copyright notice.
