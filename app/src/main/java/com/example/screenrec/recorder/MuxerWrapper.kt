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
