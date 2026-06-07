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
