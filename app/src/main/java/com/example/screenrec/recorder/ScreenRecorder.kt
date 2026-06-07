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
