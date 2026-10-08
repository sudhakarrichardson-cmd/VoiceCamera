package com.plotfarms.voicecamera

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Cuts a part out of a video and saves it as a NEW video in Movies/VoiceCamera (the original is left alone).
 * Call from the main thread; the callbacks also arrive on the main thread.
 */
class VideoTrimmer(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var transformer: Transformer? = null
    private var temp: File? = null

    val isRunning: Boolean get() = transformer != null

    /** 0..100 while a trim is running, or -1 when unknown. */
    fun progressPercent(): Int {
        val holder = ProgressHolder()
        return if (transformer?.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress else -1
    }

    fun trim(source: Uri, startMs: Long, endMs: Long, onSaved: (Uri) -> Unit, onFailed: (String) -> Unit) {
        if (transformer != null) return
        val out = File(context.cacheDir, "trim_${System.currentTimeMillis()}.mp4").also { temp = it }

        val clip = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(startMs)
            .setEndPositionMs(endMs)
            .build()
        val item = EditedMediaItem.Builder(
            MediaItem.Builder().setUri(source).setClippingConfiguration(clip).build()
        ).build()

        val t = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    transformer = null
                    publish(out, onSaved, onFailed)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    transformer = null
                    out.delete()
                    onFailed(exportException.message ?: "The video could not be trimmed.")
                }
            })
            .build()
        transformer = t
        try {
            t.start(item, out.absolutePath)
        } catch (e: Exception) {
            transformer = null
            out.delete()
            onFailed(e.message ?: "The video could not be trimmed.")
        }
    }

    /** Copies the finished file into the gallery folder as a new video. */
    private fun publish(file: File, onSaved: (Uri) -> Unit, onFailed: (String) -> Unit) {
        io.execute {
            val resolver = context.contentResolver
            val name = "VoiceCamera_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + "_trim"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/VoiceCamera")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            var uri: Uri? = null
            try {
                uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: error("Could not create the new video.")
                resolver.openOutputStream(uri)!!.use { sink -> file.inputStream().use { it.copyTo(sink) } }
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                val saved = uri
                main.post { onSaved(saved) }
            } catch (e: Exception) {
                uri?.let { runCatching { resolver.delete(it, null, null) } }
                main.post { onFailed(e.message ?: "Could not save the trimmed video.") }
            } finally {
                file.delete()
            }
        }
    }

    fun cancel() {
        transformer?.cancel()
        transformer = null
        temp?.delete()
    }

    fun shutdown() {
        cancel()
        io.shutdown()
    }
}
