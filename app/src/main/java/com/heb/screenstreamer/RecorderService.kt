package com.heb.screenstreamer

import android.app.*
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import java.io.File
import android.media.MediaCodec
import android.media.projection.MediaProjection
import android.os.*


class RecorderService : Service() {

    private var mediaProjection: MediaProjection? = null

    private var videoEncoder: MediaCodec? = null
    private var videoBufferInfo: MediaCodec.BufferInfo? = null
    private var muxer: MediaMuxer? = null
    var trackIndex = -1
    var muxerStarted = false

    private val TAG = RecorderService::class.java.simpleName

    private var mediaProjectionManager: MediaProjectionManager? = null
    var virtualDisplay: VirtualDisplay? = null
    var mediaRecorder: MediaRecorder? = null

    val handler = Handler(Looper.getMainLooper())
    val drainEncoderRunnable = Runnable {
        drainEncoder()
    }

    companion object {
        fun startLocalRecording(
            context: Context,
            method: String,
            metrics: DisplayMetrics,
            resultCode: Int,
            data: Intent
        ) {

            val intent = Intent(context, RecorderService::class.java)
            //TODO: extract names to CONST
            intent.action = "START_LOCAL_RECORDING_SERVICE"
            intent.putExtra("record_method", method)
            intent.putExtra("width", metrics.widthPixels)
            intent.putExtra("height", metrics.heightPixels)
            intent.putExtra("density", metrics.densityDpi)
            intent.putExtra("code", resultCode)
            intent.putExtra("data", data)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, RecorderService::class.java)
            //TODO: extract names to CONST
            intent.action = "STOP_SCREEN_RECORD_SERVICE"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        Log.d(TAG, "Some component want to bind with the service")
        // We don't provide binding, so return null
        return null
    }

    private fun getStorageFileDescriptor(
        filename: String = "rec.mp4",
        mimeType: String = "video/mpeg",
        directory: String = Environment.DIRECTORY_MOVIES,
        mediaContentUri: Uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    ): ParcelFileDescriptor? {
        val parcelFileDescriptor: ParcelFileDescriptor

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, filename)
                put(MediaStore.Video.Media.MIME_TYPE, mimeType)
                put(MediaStore.Video.Media.RELATIVE_PATH, directory)
            }

            contentResolver.run {
                val uri =
                    contentResolver.insert(mediaContentUri, values)
                        ?: return null
                parcelFileDescriptor = openFile(uri, "rw", null) ?: return null
            }
        } else {
            val path = Environment.getExternalStoragePublicDirectory(directory).absolutePath
            val file = File(path, filename)
            parcelFileDescriptor =
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE)
        }

        return parcelFileDescriptor
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand executed with startId: $startId and action ${intent?.action}")

        if (intent != null) {
            if (intent.action == "START_LOCAL_RECORDING_SERVICE") {
                startForeground(1, createNotification())

                val resultCode = intent.getIntExtra("code", -1)
                val resultData = intent.getParcelableExtra("data") as Intent?
                val recordMethod = intent.getStringExtra("record_method")
                val screenWidth = intent.getIntExtra("width", 720)
                val screenHeight = intent.getIntExtra("height", 1280)
                val screenDensity = intent.getIntExtra("density", 1)

                when {
                    recordMethod.equals("media_recorder") -> {
                        startMediaRecorder(
                            resultCode,
                            resultData,
                            screenWidth,
                            screenHeight,
                            screenDensity
                        )
                    }
                    recordMethod.equals("media_codec") -> {
                        startMediaCodec(
                            resultCode,
                            resultData,
                            screenWidth,
                            screenHeight,
                            screenDensity
                        )
                    }
                }

                //TODO change that, SELinux does not allow to use a LocalSocket :(
                //val localSocket = LocalSocket()
                //TODO: inject socket name
                //localSocket.connect(LocalSocketAddress("gnirehtet"))

                //val pipe = ParcelFileDescriptor.createPipe()
                //val parcelRead = ParcelFileDescriptor(pipe[0])
                //val parcelWrite = ParcelFileDescriptor(pipe[1])

                /*
                // clear mp4 headers before streaming?? might be useless for now?
                try {
                    val buffer = ByteArray(4)
                    // Skip all atoms preceding mdat atom
                    while (!Thread.interrupted()) {
                        while (inputStream.read().toChar() != 'm')
                        inputStream.read(buffer, 0, 3)
                        if (buffer[0].toInt().toChar() == 'd' && buffer[1].toInt().toChar() == 'a' && buffer[2].toInt().toChar() == 't') break
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Couldn't skip mp4 header :/")
                    //TODO stop everything probably
                    throw e
                }

                var offset = 0
                val data = ByteArray(1024)

                while (true) {
                    val length = inputStream.read(data)
                    localSocket.outputStream.write(data, offset, length)
                    offset += length
                }
                */
            } else if (intent.action == "START_STREAM_RECORDING_SERVICE") {
                startForeground(1, createNotification())
            } else if (intent.action == "STOP_SCREEN_RECORD_SERVICE") {
                mediaRecorder?.stop()
                mediaRecorder?.release()
                virtualDisplay?.release()

                mediaProjection?.stop()

                handler.removeCallbacks(drainEncoderRunnable)
                if (muxerStarted) {
                    muxer?.stop()
                }
                muxer?.release()
                muxer = null
                muxerStarted = false

                videoEncoder?.stop()
                videoEncoder?.release()

                stopForeground(true)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun startMediaRecorder(
        resultCode: Int,
        resultData: Intent?,
        screenWidth: Int,
        screenHeight: Int,
        screenDensity: Int
    ) {
        mediaProjectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        mediaProjection =
            mediaProjectionManager?.getMediaProjection(resultCode, resultData!!)

        mediaRecorder = MediaRecorder()
        mediaRecorder?.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        mediaRecorder?.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        mediaRecorder?.setVideoEncodingBitRate(100000000)
        mediaRecorder?.setVideoFrameRate(30)
        mediaRecorder?.setVideoSize(screenWidth, screenHeight)
        mediaRecorder?.setVideoEncoder(MediaRecorder.VideoEncoder.H264)

        val fd = getStorageFileDescriptor()
        mediaRecorder?.setOutputFile(fd!!.fileDescriptor)
        mediaRecorder?.prepare()

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            mediaRecorder?.surface, null, null
        )

        mediaRecorder?.start()
    }

    private fun startMediaCodec(
        resultCode: Int,
        resultData: Intent?,
        screenWidth: Int,
        screenHeight: Int,
        screenDensity: Int
    ) {
        mediaProjectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        mediaProjection =
            mediaProjectionManager?.getMediaProjection(resultCode, resultData!!)

        videoBufferInfo = MediaCodec.BufferInfo()
        val format = MediaFormat.createVideoFormat("video/avc", screenWidth, screenHeight)
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

        videoEncoder = MediaCodec.createEncoderByType("video/avc")
        videoEncoder?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = videoEncoder?.createInputSurface()
        videoEncoder?.start()

        val fd = getStorageFileDescriptor()

        muxer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            MediaMuxer(fd!!.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } else {
            MediaMuxer("/sdcard/rec.mp4", MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        }

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface, null, null
        )

        drainEncoder()
    }

    private fun drainEncoder() {
        handler.removeCallbacks(drainEncoderRunnable)

        while (true) {
            val bufferIndex = videoEncoder!!.dequeueOutputBuffer(videoBufferInfo!!, 0)
            if (bufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break
            } else if (bufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (trackIndex >= 0) {
                    throw RuntimeException("format changed twice");
                }
                trackIndex = muxer!!.addTrack(videoEncoder!!.outputFormat)
                if (!muxerStarted && trackIndex >= 0) {
                    muxer?.start();
                    muxerStarted = true;
                }
            } else if (bufferIndex < 0) {
                //ignore
            } else {
                val encodedData = videoEncoder!!.getOutputBuffer(bufferIndex)
                    ?: throw java.lang.RuntimeException("couldn't fetch buffer at index $bufferIndex")

                if (videoBufferInfo!!.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    videoBufferInfo!!.size = 0
                }

                if (videoBufferInfo!!.size != 0) {
                    if (muxerStarted) {
                        encodedData.position(videoBufferInfo!!.offset)
                        encodedData.limit(videoBufferInfo!!.offset + videoBufferInfo!!.size)
                        muxer!!.writeSampleData(trackIndex, encodedData, videoBufferInfo!!)
                    } else {
                        // muxer not started
                    }
                }

                videoEncoder!!.releaseOutputBuffer(bufferIndex, false)

                if (videoBufferInfo!!.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM !== 0) {
                    break
                }
            }
        }

        handler.postDelayed(drainEncoderRunnable, 10)
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "The service has been created")

        startForeground(1, createNotification())
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "The service has been destroyed")
    }

    private fun createNotification(): Notification {
        val notificationChannelId = "RECORD SERVICE CHANNEL"

        // depending on the Android API that we're dealing with we will have
        // to use a specific method to create the notification
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager;
            val channel = NotificationChannel(
                notificationChannelId,
                "RecordService notifications channel",
                NotificationManager.IMPORTANCE_HIGH
            ).let {
                it.description = "RecordService channel"
                it
            }
            notificationManager.createNotificationChannel(channel)
        }

        val pendingIntent: PendingIntent =
            Intent(this, MainActivity::class.java).let { notificationIntent ->
                PendingIntent.getActivity(this, 0, notificationIntent, 0)
            }

        val builder: Notification.Builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(
                this,
                notificationChannelId
            ) else Notification.Builder(this)

        return builder
            .setContentTitle("Recorder Service")
            .setContentIntent(pendingIntent)
            .addAction(createStopAction())
            .setSmallIcon(R.drawable.ic_launcher_background)
            .setPriority(Notification.PRIORITY_HIGH) // for under android 26 compatibility
            .build()
    }

    private fun createStopAction(): Notification.Action? {
        val stopIntent = Intent(this, RecorderService::class.java)
        stopIntent.action = "STOP_SCREEN_RECORD_SERVICE"
        val stopPendingIntent =
            PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_ONE_SHOT)
        // the non-deprecated constructor is not available in API 21
        val actionBuilder = Notification.Action.Builder(
            android.R.drawable.ic_menu_delete, "Stop Screen record",
            stopPendingIntent
        )
        return actionBuilder.build()
    }
}
