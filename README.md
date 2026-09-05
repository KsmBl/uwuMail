# uwuMail

A multi-account IMAP mail client for Android 12+ (API 31), built around a rules
engine that can act on mail before it ever reaches your notification shade.

## What it does

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

**Sending with a custom From**
- The From address in the composer is free text, not a dropdown of your own
  addresses. Type `xyz@mail.de` and that is what goes on the wire — your server
  decides whether to accept it.
- Frequently used addresses can be saved as *identities* per account and picked
  from a menu.
- `Envelope sender follows the From address` (per account) controls whether
  `MAIL FROM` tracks the From header or stays on the account address, since some
  servers require one or the other.

**Folders**
- Create, rename and delete IMAP folders on the server, including nested paths.
- **Device folders**: a local folder that lives only on the phone. Moving mail
  into one downloads the full message as `.eml`, stores it in app storage, and
  deletes the server copy. Moving a message back out re-uploads it via `APPEND`.
- Move, copy, archive, trash, delete permanently, and save any message as `.eml`.

**Rules**
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

**Building a rule without knowing regex**

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

**Sync and notifications**
- Periodic background sync per account through WorkManager (Android enforces a
  15 minute floor).
- Optional push: a foreground service holding an IMAP IDLE connection per
  push-enabled account, with exponential backoff on failure.
- One notification channel group per account, with default / silent / high
  channels so rules can downgrade or mute specific mail.

## Building

Requires JDK 17+ and the Android SDK (compileSdk 35, build-tools 35.0.0). Point
`ANDROID_HOME` at the SDK or set `sdk.dir` in `local.properties`.

```sh
./build.sh                  # debug APK -> ./uwuMail-debug.apk
./build.sh release          # release APK (R8 + resource shrinking)
./build.sh debug --install  # build, then adb install
./build.sh --clean          # clean first
```

`build.sh` finds the SDK from `ANDROID_HOME`, `local.properties` or
`~/Android/Sdk`, picks `./gradlew` (falling back to a `gradle` on `PATH` or a
cached distribution), builds, and copies the APK into the project root.

Or drive Gradle directly:

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

The release build is unsigned unless you add your own signing config; the debug
build uses the standard debug keystore and installs as-is.

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
   - SHA-1: shown in uwuMail under **Settings → Google sign-in** (tap to copy).
     For the debug build produced by `build.sh` on this machine it is the debug
     keystore's fingerprint; a release build signed with your own key has a
     different one, so read it from the app rather than assuming.
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

34 JVM unit tests, run with `./gradlew :app:testDebugUnitTest`:

- `rules/` — the rule engine (scoping, priority, stop-processing, negation,
  invalid regex), the regex builder, and the similarity suggester, including a
  GitHub-CI-shaped scenario asserting the wizard's recommendation catches the
  selected mails and none of the sibling notifications from the same sender and
  mailing list.
- `mail/oauth/` — PKCE challenge derivation against RFC 7636, token response
  parsing, expiry handling, and id_token address extraction.

## Layout

```
app/src/main/java/de/uwumail/
  core/        enums (fields, operators, actions) and JSON helpers
  data/db/     Room entities, DAOs, database
  data/crypto/ keystore-backed credential storage
  data/repo/   account + identity repository, connection testing
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
  all disabled — mail is untrusted input.
- Message moves use `COPY` + `\Deleted` + `UID EXPUNGE`, which every IMAP server
  supports, rather than depending on RFC 6851 `MOVE`.
- Android 15 caps `dataSync` foreground services at 6 hours per day, so push may
  pause on very long uptimes; periodic sync continues regardless.
- Attachments can be sent from files already on disk; the composer does not yet
  have a file picker.
- OAuth2 is implemented for Google. The provider definition in
  `mail/oauth/OAuthModels.kt` is generic, so Microsoft/Outlook would be a matter
  of adding endpoints and scopes, but it is untested.
