package dev.cvkulkarnidev.melodyvisualizer.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri

/** Plays the original recording/upload/example for comparison with the detected melody. */
class SourceAudioPlayer(private val context: Context) : AutoCloseable {
    private var player: MediaPlayer? = null

    @Synchronized
    fun play(
        uri: Uri,
        onStarted: () -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit,
    ) {
        stop()
        val created = MediaPlayer()
        player = created
        runCatching {
            created.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            created.setDataSource(context, uri)
            created.setOnPreparedListener { prepared ->
                if (player !== prepared) return@setOnPreparedListener
                prepared.start()
                onStarted()
            }
            created.setOnCompletionListener { completed ->
                if (player === completed) {
                    stop()
                    onComplete()
                }
            }
            created.setOnErrorListener { failed, _, _ ->
                if (player === failed) {
                    stop()
                    onError("The original audio could not be played on this device.")
                }
                true
            }
            created.prepareAsync()
        }.onFailure { error ->
            stop()
            onError(error.message ?: "The original audio could not be played.")
        }
    }

    @Synchronized
    fun currentPositionMillis(): Long? = runCatching {
        player?.currentPosition?.toLong()
    }.getOrNull()

    @Synchronized
    fun isPlaying(): Boolean = runCatching { player?.isPlaying == true }.getOrDefault(false)

    @Synchronized
    fun stop() {
        val active = player
        player = null
        runCatching { active?.stop() }
        runCatching { active?.reset() }
        runCatching { active?.release() }
    }

    override fun close() = stop()
}
