# Screen Recorder Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A minimal native Android app that records the screen with mixed system + microphone audio (mic toggleable) and saves an MP4 visible in the Gallery.

**Architecture:** Single Compose screen (mic toggle + Start) launches a `mediaProjection` foreground service. The service drives a recording pipeline: screen → VirtualDisplay → H.264 `MediaCodec`; system audio (`AudioPlaybackCapture`) optionally mixed with mic → AAC `MediaCodec`; both muxed into one MP4 written to a `MediaStore` entry under `Movies/ScreenRec`.

**Tech Stack:** Kotlin, Jetpack Compose, Android `MediaProjection` / `MediaCodec` / `MediaMuxer` / `AudioRecord` / `MediaStore`. No third-party libraries. `minSdk` 29, `targetSdk` 34.

---

## Notes for the implementer

- **Most of this pipeline cannot be unit-tested** — `MediaCodec`, `AudioRecord`, and `MediaProjection` require a real device/emulator and a live screen-capture consent. Only the PCM mixer is pure logic, so it gets full TDD (Task 2). Everything else is verified by **compiling** (`./gradlew assembleDebug`) after each task and a **manual on-device run** in the final task.
- **RECORD_AUDIO is mandatory**, even when the mic is off: `AudioPlaybackCapture` (system audio) also requires it. So the app treats audio permission as required; if denied, it tells the user and does not start. The mic toggle only controls whether the *microphone* stream is mixed in.
- Package used throughout: `com.example.screenrec`. Source root: `app/src/main/java/com/example/screenrec/`.

---

### Task 1: Create the project and declare permissions

**Files:**
- Create (via Android Studio): whole project skeleton
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Create the project from the Compose template**

In Android Studio: **New Project → Empty Activity (Compose)**.
- Name: `ScreenRec`
- Package name: `com.example.screenrec`
- Save location: `/Users/michaelplakhov/projects/gpwc/screenrec`
- Minimum SDK: **API 29 ("Android 10")**
- Build configuration language: Kotlin DSL

Let the template finish syncing. This generates the Gradle/Compose setup so we don't hand-maintain versions.

- [ ] **Step 2: Confirm the empty app builds**

Run: `cd /Users/michaelplakhov/projects/gpwc/screenrec && ./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Add permissions and the service to the manifest**

Edit `app/src/main/AndroidManifest.xml`. Add these `<uses-permission>` lines as direct children of `<manifest>`, immediately before the `<application>` tag:

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

Inside `<application>`, add the service declaration (alongside the generated `<activity>`):

```xml
<service
    android:name=".RecordingService"
    android:exported="false"
    android:foregroundServiceType="mediaProjection" />
```

- [ ] **Step 4: Verify it still builds**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`. (`RecordingService` is referenced but created in Task 8; the manifest reference compiles fine — it is only validated at install time. If your toolchain complains, finish Task 8 before installing.)

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: scaffold ScreenRec project and declare permissions"
```

---

### Task 2: PCM audio mixer (TDD)

Pure 16-bit little-endian PCM mixing with clamping. The one fully testable unit.

**Files:**
- Create: `app/src/main/java/com/example/screenrec/recorder/AudioMixer.kt`
- Test: `app/src/test/java/com/example/screenrec/recorder/AudioMixerTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/example/screenrec/recorder/AudioMixerTest.kt`:

```kotlin
package com.example.screenrec.recorder

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class AudioMixerTest {

    private fun samples(vararg s: Short): ByteArray {
        val out = ByteArray(s.size * 2)
        for (i in s.indices) {
            out[i * 2] = (s[i].toInt() and 0xFF).toByte()
            out[i * 2 + 1] = ((s[i].toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }

    @Test
    fun sumsTwoSignals() {
        val a = samples(100, -200)
        val b = samples(50, 200)
        assertArrayEquals(samples(150, 0), AudioMixer.mix(a, b, a.size))
    }

    @Test
    fun clampsPositiveOverflow() {
        val a = samples(30000)
        val b = samples(30000)
        assertArrayEquals(samples(Short.MAX_VALUE), AudioMixer.mix(a, b, a.size))
    }

    @Test
    fun clampsNegativeOverflow() {
        val a = samples(-30000)
        val b = samples(-30000)
        assertArrayEquals(samples(Short.MIN_VALUE), AudioMixer.mix(a, b, a.size))
    }

    @Test
    fun mixesOnlyRequestedLength() {
        val a = samples(100, 999)
        val b = samples(50, 999)
        // length covers only the first sample (2 bytes)
        assertArrayEquals(samples(150), AudioMixer.mix(a, b, 2))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "com.example.screenrec.recorder.AudioMixerTest"`
Expected: FAIL — `AudioMixer` is unresolved.

- [ ] **Step 3: Write the minimal implementation**

Create `app/src/main/java/com/example/screenrec/recorder/AudioMixer.kt`:

```kotlin
package com.example.screenrec.recorder

/** Mixes two 16-bit little-endian PCM buffers by summing samples with clamping. */
object AudioMixer {
    fun mix(a: ByteArray, b: ByteArray, length: Int): ByteArray {
        val out = ByteArray(length)
        var i = 0
        while (i + 1 < length) {
            val s1 = (((a[i + 1].toInt()) shl 8) or (a[i].toInt() and 0xFF)).toShort().toInt()
            val s2 = (((b[i + 1].toInt()) shl 8) or (b[i].toInt() and 0xFF)).toShort().toInt()
            var sum = s1 + s2
            if (sum > Short.MAX_VALUE) sum = Short.MAX_VALUE.toInt()
            if (sum < Short.MIN_VALUE) sum = Short.MIN_VALUE.toInt()
            out[i] = (sum and 0xFF).toByte()
            out[i + 1] = ((sum shr 8) and 0xFF).toByte()
            i += 2
        }
        return out
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "com.example.screenrec.recorder.AudioMixerTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add clamping PCM audio mixer with tests"
```

---

### Task 3: Thread-safe MediaMuxer wrapper

Two encoder threads write into one muxer. The muxer may only `start()` after **both** tracks are added, and `writeSampleData` must be synchronized.

**Files:**
- Create: `app/src/main/java/com/example/screenrec/recorder/MuxerWrapper.kt`

- [ ] **Step 1: Write the wrapper**

Create `app/src/main/java/com/example/screenrec/recorder/MuxerWrapper.kt`:

```kotlin
package com.example.screenrec.recorder

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.ParcelFileDescriptor
import java.nio.ByteBuffer

/**
 * Wraps MediaMuxer for two concurrent encoder threads.
 * The muxer starts only once [readyTrack] has been called [expectedTracks] times.
 */
class MuxerWrapper(pfd: ParcelFileDescriptor, private val expectedTracks: Int) {

    private val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val lock = Any()
    private var readyCount = 0

    @Volatile
    var isStarted = false
        private set

    fun addTrack(format: MediaFormat): Int = synchronized(lock) {
        muxer.addTrack(format)
    }

    /** Signals this track has been added and is ready; starts the muxer when all are ready. */
    fun readyTrack() = synchronized(lock) {
        readyCount++
        if (readyCount == expectedTracks && !isStarted) {
            muxer.start()
            isStarted = true
        }
    }

    fun writeSampleData(trackIndex: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) =
        synchronized(lock) {
            if (isStarted) muxer.writeSampleData(trackIndex, buffer, info)
        }

    fun release() = synchronized(lock) {
        try {
            if (isStarted) muxer.stop()
        } catch (_: Exception) {
        }
        isStarted = false
        try {
            muxer.release()
        } catch (_: Exception) {
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat: add thread-safe MediaMuxer wrapper"
```

---

### Task 4: Video encoder

Screen frames arrive on a `Surface`; an H.264 `MediaCodec` drains them into the muxer.

**Files:**
- Create: `app/src/main/java/com/example/screenrec/recorder/VideoEncoder.kt`

- [ ] **Step 1: Write the encoder**

Create `app/src/main/java/com/example/screenrec/recorder/VideoEncoder.kt`:

```kotlin
package com.example.screenrec.recorder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface

class VideoEncoder(
    private val width: Int,
    private val height: Int,
    private val muxer: MuxerWrapper,
) {
    private lateinit var codec: MediaCodec
    private val bufferInfo = MediaCodec.BufferInfo()
    private var trackIndex = -1

    @Volatile
    private var running = false
    private var drainThread: Thread? = null

    var inputSurface: Surface? = null
        private set

    fun prepare() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, (width * height * 5).coerceAtLeast(2_000_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
    }

    fun start() {
        codec.start()
        running = true
        drainThread = Thread { drainLoop() }.also { it.start() }
    }

    private fun drainLoop() {
        while (true) {
            val index = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.readyTrack()
                }
                index >= 0 -> {
                    val buf = codec.getOutputBuffer(index)!!
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0 && muxer.isStarted) {
                        buf.position(bufferInfo.offset)
                        buf.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, buf, bufferInfo)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
                index == MediaCodec.INFO_TRY_AGAIN_LATER && !running -> break
            }
        }
    }

    /** Stops capture; signals EOS so the drain loop flushes and exits. */
    fun stop() {
        running = false
        try {
            codec.signalEndOfInputStream()
        } catch (_: Exception) {
        }
        try {
            drainThread?.join(1_000)
        } catch (_: Exception) {
        }
        try {
            codec.stop()
        } catch (_: Exception) {
        }
        try {
            codec.release()
        } catch (_: Exception) {
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat: add H.264 video encoder"
```

---

### Task 5: Audio encoder (system audio + optional mic)

Captures system audio via `AudioPlaybackCapture`, optionally reads the mic, mixes with `AudioMixer`, and feeds an AAC `MediaCodec`.

**Files:**
- Create: `app/src/main/java/com/example/screenrec/recorder/AudioEncoder.kt`

- [ ] **Step 1: Write the encoder**

Create `app/src/main/java/com/example/screenrec/recorder/AudioEncoder.kt`:

```kotlin
package com.example.screenrec.recorder

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.projection.MediaProjection

class AudioEncoder(
    private val mediaProjection: MediaProjection,
    private val micEnabled: Boolean,
    private val muxer: MuxerWrapper,
) {
    private val sampleRate = 44_100
    private val channelMask = AudioFormat.CHANNEL_IN_MONO

    private lateinit var codec: MediaCodec
    private var systemRecord: AudioRecord? = null
    private var micRecord: AudioRecord? = null
    private val bufferInfo = MediaCodec.BufferInfo()
    private var trackIndex = -1
    private var ptsUs = 0L

    @Volatile
    private var running = false
    private var captureThread: Thread? = null
    private var drainThread: Thread? = null

    private val minBuf =
        AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)

    @SuppressLint("MissingPermission")
    fun prepare() {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1,
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()

        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        systemRecord = AudioRecord.Builder()
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(minBuf * 2)
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()

        if (micEnabled) {
            micRecord = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(minBuf * 2)
                .build()
        }
    }

    fun start() {
        codec.start()
        systemRecord?.startRecording()
        micRecord?.startRecording()
        running = true
        captureThread = Thread { captureLoop() }.also { it.start() }
        drainThread = Thread { drainLoop() }.also { it.start() }
    }

    private fun captureLoop() {
        val sysBuf = ByteArray(minBuf)
        val micBuf = ByteArray(minBuf)
        while (running) {
            val n = systemRecord?.read(sysBuf, 0, sysBuf.size) ?: 0
            if (n <= 0) continue
            val data = if (micRecord != null) {
                val m = micRecord!!.read(micBuf, 0, n)
                if (m > 0) AudioMixer.mix(sysBuf, micBuf, minOf(n, m)) else sysBuf.copyOf(n)
            } else {
                sysBuf.copyOf(n)
            }
            feed(data, data.size, endOfStream = false)
        }
        feed(ByteArray(0), 0, endOfStream = true)
    }

    private fun feed(data: ByteArray, size: Int, endOfStream: Boolean) {
        val inIndex = codec.dequeueInputBuffer(10_000)
        if (inIndex < 0) return
        val inBuf = codec.getInputBuffer(inIndex)!!
        inBuf.clear()
        if (size > 0) inBuf.put(data, 0, size)
        val flags = if (endOfStream) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
        codec.queueInputBuffer(inIndex, 0, size, ptsUs, flags)
        ptsUs += 1_000_000L * (size / 2) / sampleRate
    }

    private fun drainLoop() {
        while (true) {
            val index = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.readyTrack()
                }
                index >= 0 -> {
                    val buf = codec.getOutputBuffer(index)!!
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0 && muxer.isStarted) {
                        buf.position(bufferInfo.offset)
                        buf.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, buf, bufferInfo)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
                index == MediaCodec.INFO_TRY_AGAIN_LATER && !running -> break
            }
        }
    }

    fun stop() {
        running = false
        try {
            captureThread?.join(1_000)
        } catch (_: Exception) {
        }
        try {
            drainThread?.join(1_000)
        } catch (_: Exception) {
        }
        try {
            systemRecord?.stop()
        } catch (_: Exception) {
        }
        try {
            micRecord?.stop()
        } catch (_: Exception) {
        }
        systemRecord?.release()
        micRecord?.release()
        try {
            codec.stop()
        } catch (_: Exception) {
        }
        try {
            codec.release()
        } catch (_: Exception) {
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat: add AAC audio encoder with system+mic mixing"
```

---

### Task 6: MediaStore output

Creates a pending `Movies/ScreenRec/*.mp4` entry, hands a file descriptor to the muxer, and clears the pending flag on finish so the Gallery indexes it.

**Files:**
- Create: `app/src/main/java/com/example/screenrec/recorder/MediaOutput.kt`

- [ ] **Step 1: Write the output helper**

Create `app/src/main/java/com/example/screenrec/recorder/MediaOutput.kt`:

```kotlin
package com.example.screenrec.recorder

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore

/** Holds a pending MediaStore video entry plus its open descriptor. */
class MediaOutput private constructor(
    val uri: Uri,
    val pfd: ParcelFileDescriptor,
) {
    companion object {
        fun create(context: Context): MediaOutput {
            val name = "ScreenRec_${System.currentTimeMillis()}.mp4"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ScreenRec")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Failed to create MediaStore entry")
            val pfd = resolver.openFileDescriptor(uri, "rw")
                ?: error("Failed to open file descriptor")
            return MediaOutput(uri, pfd)
        }
    }

    /** Clears IS_PENDING so the file becomes visible in the Gallery. */
    fun finish(context: Context) {
        try {
            pfd.close()
        } catch (_: Exception) {
        }
        val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
        context.contentResolver.update(uri, values, null, null)
    }

    /** Removes a partial entry on failure. */
    fun discard(context: Context) {
        try {
            pfd.close()
        } catch (_: Exception) {
        }
        try {
            context.contentResolver.delete(uri, null, null)
        } catch (_: Exception) {
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat: add MediaStore output helper"
```

---

### Task 7: ScreenRecorder orchestrator

Wires the muxer, both encoders, the virtual display, and the output together behind a simple `start()`/`stop()`.

**Files:**
- Create: `app/src/main/java/com/example/screenrec/recorder/ScreenRecorder.kt`

- [ ] **Step 1: Write the orchestrator**

Create `app/src/main/java/com/example/screenrec/recorder/ScreenRecorder.kt`:

```kotlin
package com.example.screenrec.recorder

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection

class ScreenRecorder(
    private val context: Context,
    private val mediaProjection: MediaProjection,
    private val width: Int,
    private val height: Int,
    private val dpi: Int,
    micEnabled: Boolean,
) {
    private val output = MediaOutput.create(context)
    private val muxer = MuxerWrapper(output.pfd, expectedTracks = 2)
    private val video = VideoEncoder(width, height, muxer)
    private val audio = AudioEncoder(mediaProjection, micEnabled, muxer)
    private var virtualDisplay: VirtualDisplay? = null

    fun start() {
        video.prepare()
        audio.prepare()
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "ScreenRec",
            width,
            height,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            video.inputSurface,
            null,
            null,
        )
        video.start()
        audio.start()
    }

    fun stop() {
        try {
            virtualDisplay?.release()
            video.stop()
            audio.stop()
            muxer.release()
            output.finish(context)
        } catch (e: Exception) {
            output.discard(context)
            throw e
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat: add ScreenRecorder orchestrator"
```

---

### Task 8: Foreground recording service

Holds the `MediaProjection`, shows a notification with a **Stop** action, and runs the recorder. Must `startForeground` with type `mediaProjection` *before* obtaining the projection (Android 14 requirement).

**Files:**
- Create: `app/src/main/java/com/example/screenrec/RecordingService.kt`

- [ ] **Step 1: Write the service**

Create `app/src/main/java/com/example/screenrec/RecordingService.kt`:

```kotlin
package com.example.screenrec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.example.screenrec.recorder.ScreenRecorder

class RecordingService : Service() {

    companion object {
        const val ACTION_START = "com.example.screenrec.START"
        const val ACTION_STOP = "com.example.screenrec.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_MIC = "mic"
        private const val CHANNEL_ID = "screenrec"
        private const val NOTIF_ID = 1
    }

    private var recorder: ScreenRecorder? = null
    private var mediaProjection: MediaProjection? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent)
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startRecording(intent: Intent) {
        createChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }

        val code = intent.getIntExtra(EXTRA_CODE, RESULT_CANCELED)
        val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
        val micEnabled = intent.getBooleanExtra(EXTRA_MIC, true)
        if (data == null) {
            stopSelf()
            return
        }

        val manager = getSystemService(MediaProjectionManager::class.java)
        val projection = manager.getMediaProjection(code, data)
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, Handler(Looper.getMainLooper()))
        mediaProjection = projection

        val metrics = resources.displayMetrics
        recorder = ScreenRecorder(
            context = this,
            mediaProjection = projection,
            width = metrics.widthPixels,
            height = metrics.heightPixels,
            dpi = metrics.densityDpi,
            micEnabled = micEnabled,
        ).also { it.start() }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Запис екрана",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, RecordingService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Йде запис екрана")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .addAction(
                Notification.Action.Builder(null, "Зупинити", stopPending).build(),
            )
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        try {
            recorder?.stop()
        } catch (_: Exception) {
        }
        recorder = null
        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        mediaProjection = null
        super.onDestroy()
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat: add foreground recording service with stop action"
```

---

### Task 9: UI screen, permissions, and projection consent

The single Compose screen: a mic toggle and a Start/Stop button. Start requests `RECORD_AUDIO` (+ `POST_NOTIFICATIONS` on 13+), then launches the system screen-capture consent, then starts the service.

**Files:**
- Modify (replace generated body): `app/src/main/java/com/example/screenrec/MainActivity.kt`

- [ ] **Step 1: Replace MainActivity**

Replace the entire contents of `app/src/main/java/com/example/screenrec/MainActivity.kt` with:

```kotlin
package com.example.screenrec

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private var micEnabled by mutableStateOf(true)
    private var isRecording by mutableStateOf(false)

    private val projectionLauncher =
        registerForActivityResult(StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                val intent = Intent(this, RecordingService::class.java).apply {
                    action = RecordingService.ACTION_START
                    putExtra(RecordingService.EXTRA_CODE, result.resultCode)
                    putExtra(RecordingService.EXTRA_DATA, data)
                    putExtra(RecordingService.EXTRA_MIC, micEnabled)
                }
                startForegroundService(intent)
                isRecording = true
            }
        }

    private val permissionLauncher =
        registerForActivityResult(RequestMultiplePermissions()) { grants ->
            if (grants[Manifest.permission.RECORD_AUDIO] == true) {
                launchProjection()
            } else {
                toast("Потрібен дозвіл на звук для запису")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RecorderScreen(
                        micEnabled = micEnabled,
                        isRecording = isRecording,
                        onMicToggle = { micEnabled = it },
                        onStart = ::onStartClick,
                        onStop = ::onStopClick,
                    )
                }
            }
        }
    }

    private fun onStartClick() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            launchProjection()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun launchProjection() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun onStopClick() {
        val intent = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP
        }
        startService(intent)
        isRecording = false
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}

@androidx.compose.runtime.Composable
private fun RecorderScreen(
    micEnabled: Boolean,
    isRecording: Boolean,
    onMicToggle: (Boolean) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Мікрофон", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(0.dp))
            Switch(checked = micEnabled, onCheckedChange = onMicToggle, enabled = !isRecording)
        }
        Spacer(Modifier.height(48.dp))
        if (!isRecording) {
            Button(onClick = onStart) { Text("Почати запис") }
        } else {
            Text("Йде запис… зупинити можна зі шторки сповіщень")
            Spacer(Modifier.height(16.dp))
            Button(onClick = onStop) { Text("Зупинити") }
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat: add recorder UI with permission and projection flow"
```

---

### Task 10: Build, install, and verify on the device

Automated tests can't exercise the media pipeline — this task is the real verification.

**Files:** none (manual)

- [ ] **Step 1: Build the debug APK**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`; APK at `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 2: Install on the Samsung**

Enable Developer Options → USB debugging on the phone, connect via USB, accept the prompt, then:
Run: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
Expected: `Success`.
(Alternatively copy the APK file to the phone and tap it to install — allow "install from this source".)

- [ ] **Step 2b: If install fails on `RecordingService`**, confirm Task 8 is complete and rebuild. The manifest reference and the class must both exist.

- [ ] **Step 3: Record with mic ON**

Open the app, leave mic ON, tap **Почати запис**, accept "Start recording / casting", open a game or video with sound, speak into the mic, then pull the notification shade and tap **Зупинити**.
Expected: a notification was visible throughout; tapping Stop ends it.

- [ ] **Step 4: Verify the file in the Gallery**

Open the Gallery / Google Photos. Find `Movies/ScreenRec/ScreenRec_*.mp4`.
Expected: it plays, shows the screen, and you hear **both** the app sound and your voice.

- [ ] **Step 5: Record with mic OFF**

In the app, turn the **Мікрофон** switch OFF, record a short clip of a video with sound, stop from the shade.
Expected: the new MP4 plays with the **app sound only** (no mic).

- [ ] **Step 6: Permission-denial check**

Settings → Apps → ScreenRec → Permissions → revoke Microphone. Reopen the app, tap Start, deny the prompt.
Expected: a toast "Потрібен дозвіл на звук для запису" and no recording starts (no crash).

- [ ] **Step 7: Final commit (if any fixes were needed)**

```bash
git add -A
git commit -m "fix: device verification adjustments"
```

---

## Self-Review

**Spec coverage:**
- Video capture → Tasks 4, 7. ✓
- System audio always → Task 5 (`AudioPlaybackCapture` always built). ✓
- Mic optional via toggle → Task 5 (`micEnabled`), Task 9 (Switch). ✓
- Mixed into one track → Task 2 (`AudioMixer`), Task 5. ✓
- Start in app / stop from notification → Task 8 (notification action), Task 9 (Start). ✓
- MP4 in Gallery → Task 6 (`MediaStore`, `Movies/ScreenRec`). ✓
- Full-auto, single mic toggle, no quality settings → Task 9 UI. ✓
- Foreground service type `mediaProjection` (Android 14) → Task 1 manifest, Task 8 `startForeground`. ✓
- Permissions (RECORD_AUDIO, POST_NOTIFICATIONS, projection consent) → Task 1, Task 9. ✓
- Error handling (denied mic, partial file discard) → Task 9 toast, Task 7 `discard`. ✓
- Known limitation (protected audio) → documented in spec; no code path needed. ✓

**Note on a deliberate spec deviation:** the spec's error note said "mic denied → record without mic." In reality `AudioPlaybackCapture` also needs `RECORD_AUDIO`, so the app treats audio permission as required and aborts with a message if denied (Task 9, Step 6). This is reflected in the plan's "Notes for the implementer."

**Placeholder scan:** none — every code step contains full source.

**Type consistency:** `MuxerWrapper.readyTrack()`/`addTrack()`/`writeSampleData()`/`isStarted` used identically in Tasks 3, 4, 5. `ScreenRecorder(context, mediaProjection, width, height, dpi, micEnabled)` matches its call in Task 8. `RecordingService` action/extra constants used identically in Tasks 8 and 9. `MediaOutput.create/finish/discard` match Task 7 usage. ✓
