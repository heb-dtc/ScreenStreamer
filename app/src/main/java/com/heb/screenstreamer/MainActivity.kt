package com.heb.screenstreamer

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.heb.screenstreamer.ui.theme.ScreenStreamerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ScreenStreamerTheme {
                // A surface container using the 'background' color from the theme
                Surface(color = MaterialTheme.colors.background) {
                    RecordActionsView(this::onRecordClicked)
                }
            }
        }
    }

    private fun onRecordClicked(recordMethod: String) {
        val intent = Intent(this, RecordPermissionActivity::class.java)
        intent.putExtra("record_method", recordMethod)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }
}

@Composable
fun RecordActionsView(onRecord: (method: String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Button(onClick = {
            onRecord.invoke("media_recorder")
        }, modifier = Modifier.fillMaxWidth()) {
            Text(text = "Start Local Recording (with MediaRecorder")
        }
        Button(
            onClick = { onRecord.invoke("media_codec") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
        ) {
            Text(text = "Start Local Recording (with MediaCodec")
        }
    }
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    ScreenStreamerTheme {
        RecordActionsView({})
    }
}