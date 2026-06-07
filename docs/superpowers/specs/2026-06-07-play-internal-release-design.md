# Дизайн: тест-реліз ScreenRec у Google Play (Internal testing)

**Дата:** 2026-06-07
**Передумова:** додаток зібрано й перевірено на пристрої. Акаунт Play Console є. GitHub-нік `michae1` (як у `mysummertodo`, що вже в Play).
**Мета:** довести ScreenRec до завантажуваного **внутрішнього тест-релізу** в Google Play і поставити його на телефон дитини через тест-лінк.

## Підхід
Дзеркалимо перевірений реліз-сетап сусіднього проєкту `mysummertodo` (підпис із `local`-проперті, R8/shrink, AAB, політика через GitHub Pages, Play App Signing).

## Рішення (зафіксовані)
- **applicationId:** `com.gpwc.screenrec`. Debug отримує суфікс `.debug` (ставиться поруч із релізом).
- **Видима назва:** `ScreenRec`.
- **Версія:** `versionCode 1`, `versionName "1.0"`.
- **GitHub:** окремий публічний репозиторій `michae1/screenrec` (не змішувати з іншими проєктами). Пуш коду + `docs/` для GitHub Pages.
- **Трек:** лише **Internal testing**. Без продакшну, без IAP, без CI (YAGNI).

## Робота в коді/проєкті (роблю я)

### 1. Збірка/підпис (`app/build.gradle.kts`)
- `applicationId = "com.gpwc.screenrec"` — це єдине, що бачить Play. **`namespace` і пакет вихідників лишаються `com.example.screenrec`** (це внутрішня річ Gradle/R-клас, Play її не бачить), тож НЕ перейменовуємо файли й `package`-декларації — менше ризику. У фінальному маніфесті `package` = `applicationId`, посилання `.MainActivity`/`.RecordingService` резолвляться через `namespace` і працюють без змін.
- `versionCode`/`versionName`.
- `signingConfigs { release { ... } }` читає `RELEASE_STORE_FILE/RELEASE_STORE_PASSWORD/RELEASE_KEY_ALIAS/RELEASE_KEY_PASSWORD` з `keystore.properties`; якщо файлу нема — реліз падає на debug-підпис (щоб збірка не ламалась без секретів).
- `buildTypes.release`: `isMinifyEnabled = true`, `isShrinkResources = true`, дефолтні ProGuard-файли; debug — `applicationIdSuffix = ".debug"`.
- `debug { applicationIdSuffix = ".debug" }`.

### 2. Keystore
- Згенерувати `keystore/release.jks` (alias `screenrec`) через `keytool`.
- `keystore.properties` і `keystore/` — у `.gitignore`. `.jks` і паролі НІКОЛИ не комітяться.
- Креденшали показати користувачу один раз для збереження («втратиш — не оновиш додаток»).

### 3. Іконка
- Замінити дефолтну системну іконку на власну адаптивну: `ic_launcher_background` (суцільний колір) + `ic_launcher_foreground` (проста піктограма запису — червоний кружок) у `mipmap-anydpi-v26/`, плюс растрові fallback-и. Прибрати `android:icon="@android:drawable/sym_def_app_icon"` → `@mipmap/ic_launcher`.
- Окремий PNG **512×512** для лістингу Play.

### 4. Документи
- `docs/PRIVACY.md` — чесна політика: запис відео/звуку зберігається **лише локально** на пристрої, нічого не надсилається, **дані не збираються і не передаються**; контакт `qotsaman@gmail.com`. Адаптувати з `mysummertodo/docs/PRIVACY.md`.
- `docs/DATA-SAFETY.md` — відповіді для форми Data safety: no data collected, no data shared.
- `docs/RELEASE-CHECKLIST.md` — покроковий чеклист (дзеркало стилю mysummertodo) з усіма ручними кроками Play Console.

### 5. Матеріали лістингу (генерую)
- Іконка 512×512 (PNG).
- Банер 1024×500 (PNG, простий).
- 2–3 скріншоти телефона з емулятора (екран додатка, екран «Йде запис»).
- Тексти укр.: короткий опис (до 80 символів), повний опис.

### 6. AAB
- `./gradlew :app:bundleRelease` → `app/build/outputs/bundle/release/app-release.aab`. Перевірити підпис (`jarsigner -verify` або `bundletool`).

### 7. GitHub
- Створити публічний репозиторій `michae1/screenrec` (через `gh`), додати remote, запушити.

## Ручні кроки (робить користувач — я даю точний чеклист)
1. **GitHub Pages**: увімкнути Pages на репо (branch `main`, теку `/docs`) → отримати URL → знайти `PRIVACY` сторінку. Або окрема сторінка для PRIVACY.md.
2. **Play Console → Create app**: `com.gpwc.screenrec`, мова укр., безкоштовний.
3. **Internal testing**: створити реліз, завантажити `app-release.aab`.
4. **App content**: політика (URL), реклами немає, content rating (анкета), target audience (18+), Data safety (no data), **декларація foreground-service `mediaProjection`** з поясненням «користувач явно запускає запис власного екрана».
5. **Store listing**: іконка 512×512, банер, скріншоти, описи.
6. **Тестери**: додати `qotsaman@gmail.com`, зберегти трек, відкрити **opt-in URL**, поставити додаток на телефон.

## Ризики / відомі моменти
- Запис екрана+звуку інколи привертає увагу модерації Play, але для **внутрішнього треку** перевірка мінімальна.
- Internal testing все одно вимагає заповнити «App content» і базовий лістинг — це не «production», але й не порожньо.
- `applicationId` після першої публікації **незмінний** — фіксуємо `com.gpwc.screenrec` остаточно.

## Поза межами
Продакшн-реліз, закритий/відкритий тест, покупки, CI/CD, аналітика, кілька мов лістингу.
