package com.nuvio.tv.debug

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Bitmap
import android.view.PixelCopy
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.nuvio.debug.*
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.ui.components.ContentCard
import com.nuvio.tv.ui.components.PosterCardStyle
import com.nuvio.tv.ui.screens.player.PlayerMediaSourceFactory
import com.nuvio.tv.ui.theme.NuvioTheme
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*

/** Debug-only Activity and process, driven through ADB-forwarded localhost TCP. */
class DebugCliActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var server: DebugCommandServer
    private var mode by mutableStateOf("idle")
    private var previewUrl by mutableStateOf("")
    private var previewShape by mutableStateOf(PosterShape.POSTER)
    private var previewWidth by mutableStateOf(320)
    private var previewWatched by mutableStateOf(false)
    private var firstFrame = false
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = ExoPlayer.Builder(this).build().apply {
            volume = 0f
            addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() { firstFrame = true }
            })
        }
        val mediaSourceFactory = PlayerMediaSourceFactory(this)
        server = DebugCommandServer(File(filesDir, "debug-cli/session.json")) { request ->
            val result = CompletableFuture<JsonObject>()
            main.post {
                if (result.isCancelled) return@post
                if (request["command"]?.jsonPrimitive?.content == "render") {
                    if (android.os.Build.VERSION.SDK_INT < 26) {
                        result.complete(debugError("capture-requires-api-26"))
                        return@post
                    }
                    runCatching {
                        val view = window.decorView
                        require(view.width in 1..4096 && view.height in 1..4096)
                        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                        PixelCopy.request(window, bitmap, { code ->
                            try {
                                if (code != PixelCopy.SUCCESS) result.complete(debugError("capture-failed"))
                                else {
                                    val output = File(filesDir, "debug-cli/preview.png")
                                    output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                                    result.complete(buildJsonObject { put("ok", true); put("file", "files/debug-cli/preview.png") })
                                }
                            } finally { bitmap.recycle() }
                        }, main)
                    }.onFailure { result.complete(debugError("capture-failed")) }
                } else result.complete(runCatching { command(request, mediaSourceFactory) }
                    .getOrElse { debugError("command-failed") })
            }
            runCatching { result.get(10, TimeUnit.SECONDS) }.getOrElse {
                result.cancel(false)
                debugError("command-timeout")
            }
        }
        setContent {
            NuvioTheme {
                Box(Modifier.fillMaxSize().background(Color(0xff0d0d0d))) {
                    if (mode == "player") {
                        AndroidView(factory = { context -> PlayerView(context).apply {
                            player = this@DebugCliActivity.player; useController = false
                        } }, modifier = Modifier.fillMaxSize())
                    } else if (mode == "preview") {
                        val density = LocalDensity.current.density
                        ContentCard(
                            item = MetaPreview(id = "debug-preview", type = ContentType.MOVIE,
                                name = "Debug preview", poster = previewUrl, posterShape = previewShape,
                                background = null, logo = null, description = null, releaseInfo = null,
                                imdbRating = null, genres = emptyList()),
                            posterCardStyle = PosterCardStyle(width = (previewWidth / density).dp,
                                height = (previewWidth * 1.5f / density).dp),
                            isWatched = previewWatched, showImdbRatings = false,
                        )
                    }
                }
            }
        }
    }

    private fun status() = buildJsonObject {
        put("ok", true); put("platform", "tv"); put("engine", "nuvio-exoplayer")
        put("mode", mode); put("isPlaying", player.isPlaying)
        put("isLoading", player.playbackState == Player.STATE_BUFFERING)
        put("isEnded", player.playbackState == Player.STATE_ENDED)
        put("playbackState", player.playbackState); put("firstFrameRendered", firstFrame)
        put("positionMs", player.currentPosition); put("durationMs", player.duration.takeIf { it >= 0 } ?: 0L)
        put("bufferedPositionMs", player.bufferedPosition)
        put("videoWidth", player.videoSize.width); put("videoHeight", player.videoSize.height)
        put("playbackSpeed", player.playbackParameters.speed)
        put("playerError", player.playerError?.errorCodeName ?: "")
        put("audioTracks", tracks(C.TRACK_TYPE_AUDIO))
        put("subtitleTracks", tracks(C.TRACK_TYPE_TEXT))
    }

    private fun tracks(type: Int) = buildJsonArray {
        var index = 0
        player.currentTracks.groups.filter { it.type == type }.forEach { group ->
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                add(buildJsonObject {
                    put("index", index++); put("label", format.label ?: "")
                    put("language", format.language ?: ""); put("selected", group.isTrackSelected(i))
                    put("supported", group.isTrackSupported(i)); put("codec", format.codecs ?: "")
                })
            }
        }
    }

    private fun selectTrack(type: Int, index: Int) {
        require(index >= -1)
        val builder = player.trackSelectionParameters.buildUpon().clearOverridesOfType(type)
            .setTrackTypeDisabled(type, index == -1)
        if (index >= 0) {
            var offset = 0
            var found = false
            player.currentTracks.groups.filter { it.type == type }.forEach { group ->
                if (index in offset until offset + group.length) {
                    require(group.isTrackSupported(index - offset))
                    builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index - offset))
                    found = true
                }
                offset += group.length
            }
            require(found)
        }
        player.trackSelectionParameters = builder.build()
    }

    private fun command(request: JsonObject, factory: PlayerMediaSourceFactory): JsonObject {
        fun text(key: String) = request[key]?.jsonPrimitive?.content ?: error("missing-field")
        when (text("command")) {
            "status" -> return status()
            "load" -> {
                val source = text("url")
                require(java.net.URI(source).scheme in listOf("http", "https", "file"))
                val headers = request["headers"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
                firstFrame = false; mode = "player"
                player.setMediaSource(factory.createMediaSource(this, source, headers))
                player.prepare(); player.play()
            }
            "play" -> player.play()
            "pause" -> player.pause()
            "seek" -> player.seekTo(text("positionMs").toLong().also { require(it >= 0) })
            "speed" -> player.setPlaybackSpeed(text("speed").toFloat().also { require(it in 0.25f..4f) })
            "audio" -> selectTrack(C.TRACK_TYPE_AUDIO, text("index").toInt())
            "subtitle" -> selectTrack(C.TRACK_TYPE_TEXT, text("index").toInt())
            "mute" -> player.volume = if (text("muted").toBooleanStrict()) 0f else 1f
            "stop" -> { player.stop(); player.clearMediaItems(); mode = "idle"; firstFrame = false }
            "preview" -> {
                val imageUrl = text("url")
                require(java.net.URI(imageUrl).scheme in listOf("http", "https", "file"))
                val width = request["width"]?.jsonPrimitive?.int ?: 320
                require(width in 32..4096)
                val shape = when (request["shape"]?.jsonPrimitive?.content ?: "poster") {
                    "poster" -> PosterShape.POSTER; "landscape" -> PosterShape.LANDSCAPE
                    "square" -> PosterShape.SQUARE; else -> error("invalid-shape")
                }
                val watched = request["watched"]?.jsonPrimitive?.boolean ?: false
                player.stop(); player.clearMediaItems(); firstFrame = false
                previewUrl = imageUrl; previewWidth = width; previewShape = shape
                previewWatched = watched; mode = "preview"
            }
            "shutdown" -> { main.postDelayed({ finish() }, 300) }
            else -> return debugError("unknown-command")
        }
        return debugOk()
    }

    override fun onDestroy() {
        if (::server.isInitialized) server.close()
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }
}
