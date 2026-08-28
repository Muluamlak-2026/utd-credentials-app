# UTD Credentials Android App — Full Current Build (Rounds 32–37 combined)

This is the **complete app source**, not a small patch — because you confirmed you never actually
deployed an app update since Round 32. Applying this once brings your phone's installed app fully
current with everything shipped since then, in one shot.

Current version in this package: **versionCode 4 / versionName "1.3"**.

## What's actually inside, round by round

- **Round 32** (real Kotlin feature work): background notification polling via WorkManager so
  push/poll notifications keep working even when the app isn't open; encrypted on-device storage
  for saved site credentials (autofill); app-lock via BiometricPrompt (fingerprint) or PIN/pattern
  fallback; native calls to `/api/v1/login`, `/api/v1/logout`, `/api/v1/device-token`,
  `/api/v1/notifications/poll`.
- **Round 33** (real Kotlin feature work): per-category notification on/off switches; an
  App Settings screen (appearance/theme, sound settings); secondary-phone-aware panel access
  groundwork.
- **Round 34**: consolidated full clean codebase drop (no new features beyond 32/33 — this was the
  "you haven't deployed yet" full package at that time).
- **Round 35, 36, 37**: **no Kotlin changes**. All three rounds' work (dashboard approvals list +
  copy-to-clipboard/profile-link consistency, backup xlsx column/expiry-flag changes, and the
  verified phone-number security fix + admin toggles + Mini App animated reveal popups) lives
  entirely in the admin panel (which this app just shows in its WebView screen) or in the Telegram
  bot/Mini App — none of which this app's own Kotlin code touches. Only the version number was
  bumped each of those rounds so "Update" on your phone means something.

Net effect: this one package = Round 32's and Round 33's real app features, fully intact, at the
current version number, with every later round's WebView/Mini-App-only content already "included"
automatically the moment the WebView loads your live site (no app code needed for those).

**Before this app update does anything for Round 37's security fix**, make sure you've deployed
`round37-web-panel-patch.tar.gz` to your server — the app's WebView just reflects whatever the
server is running.

## Apply it in Termux — full replace (your `/tmp` is read-only, so we use `~` for scratch)

1. Put `round32-37-utd-credentials-app-full.tar.gz` into your phone's Downloads folder (however you
   got the last one there — Telegram "Save to Downloads", browser download, etc.).

2. In Termux:

```bash
cd ~
rm -rf ~/utd-full-update
mkdir -p ~/utd-full-update
tar xzf /sdcard/Download/round32-37-utd-credentials-app-full.tar.gz -C ~/utd-full-update

cd ~/utd-credentials-app
# Copy every file from the fresh package over your existing checkout,
# but do NOT touch .git or your real google-services.json if you've
# already swapped in a real Firebase one — the package only ships the
# placeholder, and this rsync command deliberately skips both.
rsync -a --exclude='.git' --exclude='app/google-services.json' ~/utd-full-update/ ~/utd-credentials-app/

rm -rf ~/utd-full-update
```

   If `rsync` isn't installed, run `pkg install rsync -y` first. If you'd rather not install
   anything new, `cp -rf ~/utd-full-update/. ~/utd-credentials-app/` works too, but then manually
   restore your real `app/google-services.json` afterward if you've already replaced the
   placeholder with a real one from the Firebase console.

3. Check what actually changed before committing (should show only genuinely different files —
   likely nothing outside `app/build.gradle.kts` if your tree was already current, or the full set
   if it wasn't):

```bash
git status
```

4. Commit and push:

```bash
git add -A
git commit -m "Full sync to Round 32-37 current build (versionCode 4 / 1.3)"
git push
```

5. Watch the cloud build and pull down the new APK:

```bash
gh run watch
gh run download --name utd-credentials-debug-apk --dir ~/storage/downloads/utd-credentials-apk
termux-open ~/storage/downloads/utd-credentials-apk/app-debug.apk
```

6. Android will offer **Update** (same package name, same debug signing key) — tap Update. Your
   login session, saved site password, app-lock PIN/pattern, theme, and notification preferences
   all survive the update, same as every prior round.

## If `git status` in step 3 shows nothing at all

That means your Termux checkout was already fully current (all of Round 32–37's app-relevant
content was already there, just under the old version number in some cases). In that case skip
straight to confirming your installed app already reports version 1.3 under
**Settings → Apps → UTD Credentials**. If it doesn't, just bump `versionCode`/`versionName` isn't
needed since this package already has 4/"1.3" — re-run steps 3–6 anyway; git will simply have
nothing new to push and `gh run` will reuse the last successful build, or you can force a rebuild
by re-running the workflow from the Actions tab if needed.

## First-time setup reminder

If you ever need to redo initial Termux/Android SDK/Firebase setup from scratch, that's unchanged
and still documented in `TERMUX_SETUP.md`, which is included in this package.
