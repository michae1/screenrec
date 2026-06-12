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

    /**
     * Feeds [size] bytes of PCM into the encoder. An AudioRecord read can be larger than a
     * single codec input buffer (one AAC frame), so the data is split across as many input
     * buffers as needed instead of overflowing one.
     */
    private fun feed(data: ByteArray, size: Int, endOfStream: Boolean) {
        var offset = 0
        // Anchor each read to the system monotonic clock (microseconds). The video frames from
        // the projection Surface are timestamped with the same CLOCK_MONOTONIC, so both tracks
        // share one timeline and stay in A/V sync. Sub-chunks of one read advance by their own
        // sample duration to stay monotonic.
        ptsUs = System.nanoTime() / 1000
        while (running || endOfStream) {
            val inIndex = codec.dequeueInputBuffer(10_000)
            if (inIndex < 0) {
                if (offset >= size && !endOfStream) return
                continue
            }
            val inBuf = codec.getInputBuffer(inIndex)!!
            inBuf.clear()
            val chunk = minOf(size - offset, inBuf.remaining())
            if (chunk > 0) inBuf.put(data, offset, chunk)
            offset += chunk
            val eos = endOfStream && offset >= size
            val flags = if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
            codec.queueInputBuffer(inIndex, 0, chunk, ptsUs, flags)
            ptsUs += 1_000_000L * (chunk / 2) / sampleRate
            if (eos || offset >= size) return
        }
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
