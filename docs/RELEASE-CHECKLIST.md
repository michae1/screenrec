# ScreenRec — Release Checklist (Internal testing, v1.0)

## Code-side: DONE (by Claude)
- `applicationId = com.gpwc.screenrec` (permanent — never changes after publish). Namespace stays `com.example.screenrec` internally (invisible to Play).
- `versionCode 1`, `versionName "1.0"`.
- Release signing from `RELEASE_*` keys in `local.properties` (gitignored); keystore at `keystore/release.jks`, alias `screenrec`. **Keep the keystore + password forever — losing them means you can never update the app.**
- R8 + resource shrinking enabled; debug build gets `.debug` suffix so it installs alongside release.
- Real adaptive launcher icon (record symbol) with raster fallbacks for all densities → fixes the Play "needs an icon" error.
- Signed release bundle: `app/build/outputs/bundle/release/app-release.aab` (~1.7 MB).
- Verified on a device: builds, installs, records screen + audio → valid MP4 in Gallery; stop from notification works.

## Listing assets: READY (in `docs/play/`)
- `icon_512.png` — 512×512 app icon for the listing.
- `feature_graphic_1024x500.png` — feature graphic.
- `screenshots/01-main.png`, `screenshots/02-recording.png` — phone screenshots (1080×2400).
- `LISTING.md` — app name, short + full description (uk), category, contact, privacy URL.
- `../privacy.html` — privacy policy page (hosted via GitHub Pages).
- `../DATA-SAFETY.md` — answers for the Data safety form.

## Manual steps (you, in Play Console + GitHub)

### 1. Host the privacy policy (GitHub Pages)
1. Repo `michae1/screenrec` is pushed.
2. GitHub → repo → **Settings → Pages** → Source: **Deploy from a branch**, Branch: `main`, Folder: `/docs` → Save.
3. After ~1 min the URL is live: **https://michae1.github.io/screenrec/privacy.html** — open it to confirm.

### 2. Create the app in Play Console
- [play.google.com/console](https://play.google.com/console) → **Create app**.
- App name **ScreenRec**, language Ukrainian, type **App**, **Free**.
- Accept declarations.

### 3. Internal testing — upload the bundle
- **Testing → Internal testing → Create new release**.
- Upload `app-release.aab`.
- Let Play App Signing manage the key (default). Save → review → roll out to internal testing.

### 4. App content (required even for internal testing)
- **Privacy policy:** paste `https://michae1.github.io/screenrec/privacy.html`.
- **Ads:** No ads.
- **App access:** All functionality available without special access.
- **Content rating:** fill the questionnaire (Tools, no objectionable content) → likely Everyone/PEGI 3.
- **Target audience:** 18+ (a utility, not child-directed) to avoid Families policy.
- **Data safety:** use `docs/DATA-SAFETY.md` → "No data collected/shared".
- **Foreground service permissions:** declare `FOREGROUND_SERVICE_MEDIA_PROJECTION` → reason: "User explicitly starts recording their own screen; the service keeps the recording running and shows a Stop notification."

### 5. Store listing
- Short + full description from `LISTING.md`.
- App icon `icon_512.png`, feature graphic, 2 phone screenshots.

### 6. Add yourself as tester & install
- Internal testing → **Testers** → add `qotsaman@gmail.com` (or a list).
- Copy the **opt-in URL**, open it on the child's Samsung, accept testing, install from Play.

## Notes
- Internal testing has minimal review, so the screen+audio recording use case should pass without the scrutiny a production release gets.
- If you later want a public/production release, Google now requires a closed test with 12+ testers for 14 days first — out of scope here.
