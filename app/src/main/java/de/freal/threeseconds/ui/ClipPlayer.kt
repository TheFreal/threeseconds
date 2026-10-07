package de.freal.threeseconds.ui

import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.freal.threeseconds.data.Clip
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Plays clips full screen straight from their MediaStore URIs, one after another and
 * round again. Tap to pause.
 *
 * A plain [VideoView] is enough for three second local files and avoids pulling in a
 * player library.
 */
@Composable
fun ClipPlayerDialog(clips: List<Clip>, onDismiss: () -> Unit) {
    if (clips.isEmpty()) return
    var view by remember { mutableStateOf<VideoView?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var index by remember { mutableIntStateOf(0) }
    val clip = clips[index.coerceIn(clips.indices)]

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { view?.let { if (it.isPlaying) it.pause() else it.start() } }
        ) {
            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        setOnPreparedListener { start() }
                        setOnCompletionListener {
                            if (clips.size == 1) {
                                seekTo(0)
                                start()
                            } else {
                                index = (index + 1) % clips.size
                                setVideoURI(Uri.parse(clips[index].uri))
                            }
                        }
                        // Returning true suppresses the platform's own error dialog.
                        setOnErrorListener { _, what, extra ->
                            error = "This clip can't be played (error $what/$extra)"
                            true
                        }
                        setVideoURI(Uri.parse(clips[0].uri))
                        view = this
                    }
                },
                onRelease = { it.stopPlayback() },
                // Loose constraints let VideoView size itself to the clip's aspect ratio.
                modifier = Modifier.fillMaxSize().wrapContentSize(Alignment.Center),
            )

            Column(
                Modifier
                    .fillMaxWidth()
                    .safeDrawingPadding()
                    .padding(start = 16.dp, top = 8.dp)
            ) {
                Text(
                    PLAYER_STAMP.format(Instant.ofEpochMilli(clip.recordedAt).atZone(ZoneId.systemDefault())),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                )
                val sub = listOfNotNull(
                    clip.deviceName,
                    if (clips.size > 1) "${index + 1} of ${clips.size}" else null,
                ).joinToString("  ·  ")
                if (sub.isNotEmpty()) {
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
                }
            }

            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding(),
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }

            error?.let {
                Text(
                    it,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
            }
        }
    }
}

private val PLAYER_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm")
