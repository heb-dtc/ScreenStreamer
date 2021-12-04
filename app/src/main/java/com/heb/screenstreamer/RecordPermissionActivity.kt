package com.heb.screenstreamer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log

class RecordPermissionActivity : Activity() {

    companion object {
        private val TAG = RecordPermissionActivity::class.java.simpleName
    }

    lateinit var mediaProjectionManager: MediaProjectionManager
    private var nextActionIntent: Bundle? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mediaProjectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val action = intent.action
        Log.d(TAG, "Received request $action")

        nextActionIntent = intent.extras
        startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), 1)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == 1) {
            //TODO handle case where permission is refused

            Log.d(TAG, "Starting screen capture")

            val metrics = DisplayMetrics()
            windowManager.defaultDisplay.getMetrics(metrics)

            val nextAction = nextActionIntent?.getString("next_action")
            if (nextAction == "start_streaming") {
                RecorderService.startScreenStreaming(this, metrics, resultCode, data)
            } else {
                val recordMethod = nextActionIntent?.getString("record_method") ?: "media_recorder"
                RecorderService.startLocalRecording(this, recordMethod, metrics, resultCode, data)
            }
        }
    }
}