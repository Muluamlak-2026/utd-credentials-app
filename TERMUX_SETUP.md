# UTD Credentials — Termux Setup & Update Guide

This is the file several code comments in this repo point to (`NotificationChannels.kt` /
`FullSiteScreen.kt` / `app/build.gradle.kts` / `.github/workflows/build.yml` all say
"see TERMUX_SETUP.md") — it now actually lives here instead of only in chat history.

Nothing on your server changes when you update this app. It talks to the exact same backend you
already have (`/admin/...` for the embedded panel, `/api/v1/...` for login + push notifications).

---

## Part A — One-time phone setup (skip if already done)

```bash
pkg update -y && pkg upgrade -y
pkg install -y git gh tar
termux-setup-storage
gh auth login
```

Choose **GitHub.com → HTTPS → Login with a web browser**, tap the link, approve.

## Part B — First-time install (skip if you already have this app installed and working)

```bash
gh repo create utd-credentials-app --private --description "UTD Credentials -- Android admin app"
cd ~
tar xzf /sdcard/Download/utd-credentials-app-source.tar.gz
cd utd-credentials-app
git init -b main
git add -A
git commit -m "Initial UTD Credentials app"
git remote add origin https://github.com/YOUR-USERNAME/utd-credentials-app.git
git push -u origin main
gh run watch
gh run download --name utd-credentials-debug-apk --dir ~/storage/downloads/utd-credentials-apk
termux-open ~/storage/downloads/utd-credentials-apk/app-debug.apk
```

Log in with your normal admin panel URL/username/password — one login covers both push
notifications and the embedded panel.

## Part C — Applying a NEW round's update to an app you already have installed

Whenever a new source tarball is sent to you in chat (e.g. `round34-utd-credentials-app-full.tar.gz`),
this is the whole update procedure — no gradlew, no Android Studio, no SDK on the phone:

```bash
# 1) Get the new tarball onto the phone (same as before -- it lands in Downloads)
cd ~
tar xzf /sdcard/Download/round34-utd-credentials-app-full.tar.gz -C /tmp/round34-new

# 2) Overwrite your existing project with the new source (this REPLACES source files;
#    it never touches your app/google-services.json if that file isn't in the new
#    tarball's app/ folder -- check with `git status` before committing if you're unsure)
cd ~/utd-credentials-app
cp -rf /tmp/round34-new/utd-credentials-app/. .
rm -rf /tmp/round34-new

# 3) Review, commit, push -- this triggers the cloud build automatically
git status
git add -A
git commit -m "Round 34 update"
git push

# 4) Watch the build and grab the new APK
gh run watch
gh run download --name utd-credentials-debug-apk --dir ~/storage/downloads/utd-credentials-apk
termux-open ~/storage/downloads/utd-credentials-apk/app-debug.apk
```

Android will offer to **update** the existing app (same package name, same signing key since it's
always the debug keystore) rather than installing a second copy — tap Update, your login session
and saved app settings (PIN, theme, notification preferences) all survive because they're stored
in this app's own private storage, untouched by the reinstall.

**If `google-services.json` in the new tarball is still the placeholder** (i.e. you already did
Part D below for a previous round) and you don't want to lose your real Firebase config, skip
copying that one file: replace step 2 with
`rsync -a --exclude='app/google-services.json' /tmp/round34-new/utd-credentials-app/ .`
(install `rsync` first with `pkg install -y rsync` if you don't have it), or just re-copy your
saved real `google-services.json` back into place before step 3.

## Part D — Turn on push notifications (optional, one-time, independent of any update)

1. https://console.firebase.google.com → **Add project** → skip Analytics → Create.
2. Click the **Android icon** → package name exactly `com.healthdataet.utdcredentials` →
   nickname anything → skip SHA-1 → **Register app** → **Download google-services.json**.
3. In Termux:
   ```bash
   cd ~/utd-credentials-app
   cp ~/storage/downloads/google-services.json app/google-services.json
   git add app/google-services.json
   git commit -m "Add real Firebase config"
   git push
   ```
   This triggers a new build — repeat the download/install steps from Part C.
4. Firebase Console → ⚙️ **Project Settings → Service Accounts** → **Generate new private key** →
   upload that `.json` to your server via cPanel File Manager as
   `firebase-service-account.json` in your app's root folder (the exact filename `admin/push.py`
   already looks for — no server code change needed). **Never** commit this one to GitHub.
5. Restart the Python app on the server (Setup Python App → Restart). Log back into the phone app
   once to register the device for push.

Push is entirely optional — the app works fully without it (in-app polling, sounds, and vibration
per Round 33's Notifications screen all work regardless of Firebase being configured).

## What's deliberately different about this build (for context)

- New package name `com.healthdataet.utdcredentials`, no native Dashboard/Payments/Users screens —
  the entire admin panel is shown live, full-screen, inside the app (a WebView onto the same site).
- **No committed Gradle wrapper on purpose.** A hand-transferred wrapper jar corrupted an earlier
  build during a phone-side zip extraction. GitHub Actions installs a real, verified Gradle
  distribution directly in the cloud runner every time instead (`.github/workflows/build.yml`) —
  nothing binary ever has to survive a trip through Termux, and you never need Gradle or the
  Android SDK on your phone.
- **Always extract with `tar`, never `unzip`**, for the same reason.

## Making a small manual change yourself

```bash
cd ~/utd-credentials-app
nano app/src/main/kotlin/com/healthdataet/utdcredentials/ui/screens/FullSiteScreen.kt
# ...edit, Ctrl+O, Enter, Ctrl+X...
git add -A
git commit -m "describe what you changed"
git push
gh run watch
gh run download --name utd-credentials-debug-apk --dir ~/storage/downloads/utd-credentials-apk
termux-open ~/storage/downloads/utd-credentials-apk/app-debug.apk
```

For anything beyond a small tweak, it's easier to come back to this chat, describe the change, and
get an updated source tarball the same way this one was delivered.

## Troubleshooting

**"Install blocked."** Settings → Apps → Termux → "Install unknown apps" → allow (once only).

**`gh run watch` says no runs found.** Check `gh run list`; if empty, re-run `git push` and confirm
"main -> main" with no errors.

**Build fails in Actions.** `gh run view --log-failed` in Termux, or open the failed run on
github.com. Paste the exact error back into this chat.

**`cp: cannot stat '/sdcard/Download/...'`** — the file landed somewhere other than Downloads;
find it with `find /sdcard -iname "*.tar.gz"`.
