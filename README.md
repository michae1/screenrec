# ScreenRec

Простий нативний Android-додаток для запису екрана з відео та звуком (системний звук + опційний мікрофон) в один MP4, що зберігається в Галереї.

- Kotlin + Jetpack Compose, лише стандартні Android API (MediaProjection / MediaCodec / MediaMuxer / AudioRecord / MediaStore)
- minSdk 29, compileSdk 35
- Старт у додатку, зупинка зі шторки сповіщень

Політика конфіденційності: https://michae1.github.io/screenrec/privacy.html

## Збірка
```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug          # debug APK
./gradlew :app:bundleRelease     # signed release AAB (потрібен keystore у local.properties)
```
