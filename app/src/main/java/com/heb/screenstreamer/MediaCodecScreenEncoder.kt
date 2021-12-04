package com.heb.screenstreamer

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection

class MediaCodecScreenEncoder(val width: Int, val height: Int, val density: Int) {
    val encoder = MediaCodec.createEncoderByType("video/avc")
    private var virtualDisplay: VirtualDisplay? = null

    init {
        val format = MediaFormat.createVideoFormat("video/avc", width, height)
        val frameRate = 30

        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        )
        format.setInteger(MediaFormat.KEY_BIT_RATE, 6000000) // 6Mbps
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
        format.setInteger(MediaFormat.KEY_CAPTURE_RATE, frameRate)
        format.setInteger(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1000000 / frameRate)
        format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1 seconds between I-frames

        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }

    fun encode(mediaProjection: MediaProjection) {
        val inputSurface = encoder.createInputSurface()

        encoder.start()

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "MediaCodecScreenEncoder",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface, null, null
        )
    }
}