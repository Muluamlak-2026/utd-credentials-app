# UTD Credentials Android App — v1.4.0 (crash fix + built-in diagnostics)

This is a full source replacement, same as last time — cleanest option after everything that's
happened getting the last build onto your phone.

## What changed and why

The 1.3.1 hotfix guarded the one specific line (`RingtoneManager.getActualDefaultRingtoneUri`)
most likely to crash on your device, but the crash persisted — meaning something else uncaught is
also at fault somewhere in this app. Rather than guess a fourth or fifth specific line one at a
time (each round needing another full Termux/GitHub Actions round-trip), **v1.4.0 adds a real
global safety net**, so this never has to happen blind again:

- A custom `Application` class (`UtdCredentialsApp`) installs a crash handler
  (`util/CrashHandler.kt`) before *any* screen even starts loading.
- From now on, **any** uncaught error anywhere in the app — on launch, mid-use, anywhere — gets
  written to a small text file in the app's own storage, and the app immediately reopens straight
  into a **"Something went wrong"** screen showing the full error, selectable and copyable, right
  on your phone.
- That screen has a **Copy details** button and a **Restart app** button.
- Every past crash also stays readable afterward from **App Settings → Diagnostics** — even if you
  dismissed the crash screen already, or it happened days ago.

**In practice: if this app ever crashes again, open Diagnostics (or the crash screen itself that
pops up), copy the text, and paste it to me directly.** That replaces the entire Wireless
Debugging / `adb pair` process we just went through — one screenshot does what 40 minutes of ADB
pairing was trying to do.

Also added: a friendly **"Can't reach the admin panel — Retry"** page in the WebView screen
instead of a blank white page when the server can't be reached (offline, DNS hiccup, server
restart) — tap Retry to try again right there.

Version in this package: **versionCode 6 / versionName "1.4.0"**.

## Deploy it in Termux — one command at a time this round

Learned from this session: paste commands **one at a time**, waiting for each prompt to return,
rather than as one big block — a stuck command earlier silently queued everything after it with no
warning, which cost a lot of back-and-forth. Every command below is meant to be run and confirmed
individually.

```bash
mkdir -p ~/utd-full-update
```

```bash
tar xzf /sdcard/Download/utd-credentials-app-v1.4.0-full.tar.gz -C ~/utd-full-update
```

```bash
cd ~/utd-credentials-app
```

```bash
rsync -a --exclude='.git' --exclude='app/google-services.json' ~/utd-full-update/ ~/utd-credentials-app/
```

```bash
rm -rf ~/utd-full-update
```

```bash
git status
```

Check that output before continuing — it should show the changed/new files (including
`UtdCredentialsApp.kt`, `CrashReportActivity.kt`, `util/CrashHandler.kt`,
`ui/screens/CrashLogScreen.kt` as new). Then:

```bash
git add -A
```

```bash
git commit -m "v1.4.0: global crash handler + in-app diagnostics + WebView retry page"
```

```bash
git push
```

```bash
gh run watch
```

Wait for that to say **Success**, then:

```bash
gh run download --name utd-credentials-debug-apk --dir ~/storage/downloads/utd-credentials-apk
```

```bash
termux-open ~/storage/downloads/utd-credentials-apk/app-debug.apk
```

Tap **Update** when Android prompts.

## What happens when you open the app this time

- **If it opens normally** — great, the underlying bug (whatever it turns out to have been) is
  actually fixed, or was never hit again. Everything (login, WebView, notifications, app lock,
  etc.) works exactly as before, just on top of the new safety net.
- **If it still crashes** — instead of nothing happening or a bare system dialog, you should now
  see the new **"Something went wrong"** screen with the actual error text on it. Tap **Copy
  details**, paste that text back to me here, and I'll fix the exact real cause directly — no more
  guessing, no more ADB.
