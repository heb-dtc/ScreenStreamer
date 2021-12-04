package com.heb.screenstreamer

import android.media.MediaCodec
import android.util.Log
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer

class VideoStreamer(private val host: String, private val port: Int, encoder: MediaCodec) {

    private val framePacketWriter = FramePacketWriter(encoder = encoder)
    private var socket: Socket? = null

    fun start() {
        val thread = Thread {
            socket = Socket(host, port)
            val outputStream = socket?.getOutputStream()

            // write frames until told to stop
            framePacketWriter.writeFrames(outputStream!!)
        }
        thread.start()
    }

    fun stop() {
        framePacketWriter.stop()
        socket?.close()
    }
}

class FramePacketWriter(private val encoder: MediaCodec) {

    private var ptsOrigin: Long = 0
    private var done = false

    fun writeFrames(outputStream: OutputStream) {
        val videoBufferInfo = MediaCodec.BufferInfo()

        while (!done) {
            Log.d("FramePacketWriter", "get next frame index")

            // get next buffer index
            val bufferIndex = encoder.dequeueOutputBuffer(videoBufferInfo, 0)
            if (bufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                //ignore
            } else if (bufferIndex < 0) {
                //ignore
            } else {
                // get data from buffer
                Log.d("FramePacketWriter", "read frame from encoder")
                val encodedData = encoder.getOutputBuffer(bufferIndex)
                    ?: throw java.lang.RuntimeException("couldn't fetch buffer at index $bufferIndex")

                if (videoBufferInfo.size != 0) {
                    Log.d("FramePacketWriter", "frame has data")

                    val outData = ByteArray(encodedData.remaining())
                    val frameData = buildFrameMetadata(videoBufferInfo, encodedData.remaining())

                    // write frame metadata
                    Log.d("FramePacketWriter", "write frame metadata")
                    outputStream.write(frameData)

                    // write frame
                    encodedData.get(outData)
                    Log.d("FramePacketWriter", "write raw frame")
                    outputStream.write(outData)
                }
                // release buffer
                encoder.releaseOutputBuffer(bufferIndex, false)

                if (videoBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM !== 0) {
                    done = true
                }
            }
        }
    }

    fun stop() {
        done = true
    }

    private fun buildFrameMetadata(
        videoBufferInfo: MediaCodec.BufferInfo,
        frameSize: Int
    ): ByteArray {
        val pts: Long
        if (videoBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            pts = -1 // non-media data packet
        } else {
            if (ptsOrigin == 0L) {
                ptsOrigin = videoBufferInfo.presentationTimeUs
            }
            pts = videoBufferInfo.presentationTimeUs - ptsOrigin
        }

        Log.d("FramePacketWriter", "pts $pts / packet size $frameSize")

        val headerBuffer = ByteBuffer.allocate(12)
        //TODO could allocate only once and use clear here
        //headerBuffer.clear()
        headerBuffer.putLong(pts)
        headerBuffer.putInt(frameSize)
        // need to flip before writing headerBuffer into frameData
        headerBuffer.flip()

        val frameData = ByteArray(12)
        headerBuffer.get(frameData)
        return frameData
    }
}