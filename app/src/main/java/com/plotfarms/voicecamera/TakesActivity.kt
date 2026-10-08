package com.plotfarms.voicecamera

import android.app.RecoverableSecurityException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.format.DateUtils
import android.text.format.Formatter
import android.util.LruCache
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.MediaController
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Review everything the camera took: play the videos, look at the photos, mark the ones you like with a heart,
 * delete the rest, and send ("upload") the one you pick through Android's share sheet.
 *
 * Opened from the "My takes" button on the camera screen (or by saying "show my videos"), so several videos and
 * photos can be taken first and reviewed together.
 */
class TakesActivity : AppCompatActivity() {

    private lateinit var store: TakeStore
    private lateinit var video: VideoView
    private lateinit var photo: ImageView
    private lateinit var emptyView: TextView
    private lateinit var countView: TextView
    private lateinit var infoView: TextView
    private lateinit var playButton: Button
    private lateinit var loveButton: Button
    private lateinit var deleteButton: Button
    private lateinit var shareButton: Button
    private lateinit var cleanupButton: Button
    private lateinit var adapter: TakeAdapter

    // trimming
    private lateinit var trimButton: Button
    private lateinit var trimPanel: View
    private lateinit var trimSummary: TextView
    private lateinit var trimStartLabel: TextView
    private lateinit var trimEndLabel: TextView
    private lateinit var trimStartBar: SeekBar
    private lateinit var trimEndBar: SeekBar
    private lateinit var trimPreviewButton: Button
    private lateinit var trimSaveButton: Button
    private lateinit var trimmer: VideoTrimmer
    private var trimming = false
    private var trimStartMs = 0
    private var trimEndMs = 0
    private var trimTotalMs = 0
    private var previewing = false

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var takes: List<Take> = emptyList()
    private var selectedKey = ""
    private var completed = false
    private var pendingDelete: List<Take> = emptyList()

    private val deleteLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val asked = pendingDelete
        pendingDelete = emptyList()
        if (result.resultCode == RESULT_OK && asked.isNotEmpty()) {
            if (Build.VERSION.SDK_INT >= 30) {
                // one system prompt covered every file
                store.forget(asked.map { it.key })
                toast("Deleted ${asked.size}.")
                refresh()
            } else {
                // Android 10 asks about one file at a time: carry on with the rest
                store.forget(listOf(asked.first().key))
                val rest = asked.drop(1)
                if (rest.isEmpty()) { toast("Deleted."); refresh() } else deleteTakes(rest)
            }
        } else {
            toast("Nothing was deleted.")
            refresh()
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_takes)
        findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0).padForSystemBars()
        store = TakeStore(this)

        video = findViewById(R.id.video)
        photo = findViewById(R.id.photo)
        emptyView = findViewById(R.id.emptyView)
        countView = findViewById(R.id.takeCount)
        infoView = findViewById(R.id.selectedInfo)
        playButton = findViewById(R.id.btnPlay)
        loveButton = findViewById(R.id.btnLove)
        deleteButton = findViewById(R.id.btnDelete)
        shareButton = findViewById(R.id.btnShare)
        cleanupButton = findViewById(R.id.btnCleanup)
        trimButton = findViewById(R.id.btnTrim)
        trimPanel = findViewById(R.id.trimPanel)
        trimSummary = findViewById(R.id.trimSummary)
        trimStartLabel = findViewById(R.id.trimStartLabel)
        trimEndLabel = findViewById(R.id.trimEndLabel)
        trimStartBar = findViewById(R.id.trimStart)
        trimEndBar = findViewById(R.id.trimEnd)
        trimPreviewButton = findViewById(R.id.btnTrimPreview)
        trimSaveButton = findViewById(R.id.btnTrimSave)
        trimmer = VideoTrimmer(this)

        val controller = MediaController(this)
        controller.setAnchorView(video)
        video.setMediaController(controller)
        video.setOnCompletionListener { completed = true; updateButtons() }

        adapter = TakeAdapter(
            context = this,
            onSelect = { selectTake(it, play = it.isVideo) },
            onHeart = { toggleLove(it) }
        )
        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@TakesActivity)
            adapter = this@TakesActivity.adapter
        }

        playButton.setOnClickListener { togglePlay() }
        loveButton.setOnClickListener { current()?.let { toggleLove(it) } }
        deleteButton.setOnClickListener { current()?.let { confirmDelete(listOf(it)) } }
        shareButton.setOnClickListener { current()?.let { share(it) } }
        cleanupButton.setOnClickListener { confirmCleanup() }
        findViewById<Button>(R.id.btnRecordMore).setOnClickListener { finish() }

        trimButton.setOnClickListener { startTrim() }
        findViewById<Button>(R.id.btnStartHere).setOnClickListener { setTrimStart(video.currentPosition, seek = false) }
        findViewById<Button>(R.id.btnEndHere).setOnClickListener { setTrimEnd(video.currentPosition, seek = false) }
        trimPreviewButton.setOnClickListener { togglePreview() }
        findViewById<Button>(R.id.btnTrimCancel).setOnClickListener { closeTrim() }
        trimSaveButton.setOnClickListener { saveTrim() }
        trimStartBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) { if (fromUser) setTrimStart(value, seek = true) }
            override fun onStartTrackingTouch(bar: SeekBar) = stopPreview()
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })
        trimEndBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) { if (fromUser) setTrimEnd(value, seek = true) }
            override fun onStartTrackingTouch(bar: SeekBar) = stopPreview()
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (trimming) closeTrim() else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })

        refresh()
    }

    override fun onResume() {
        super.onResume()
        handler.post(playLabelTicker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(playLabelTicker)
        stopPreview()
        if (video.isPlaying) video.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        video.stopPlayback()
        adapter.shutdown()
        trimmer.shutdown()
        io.shutdownNow()
    }

    /** Keeps the Play / Pause / Replay label right even when the on-video controls are used. */
    private val playLabelTicker = object : Runnable {
        override fun run() {
            updateButtons()
            handler.postDelayed(this, 500)
        }
    }

    // ------------------------------------------------------------------ list + selection

    private fun current(): Take? = takes.firstOrNull { it.key == selectedKey }

    private fun refresh() {
        takes = store.loadTakes()
        // forget hearts for items deleted somewhere else (e.g. the Gallery)
        store.forget(store.lovedKeys() - takes.map { it.key }.toSet())

        val videos = takes.count { it.isVideo }
        val photos = takes.size - videos
        val parts = mutableListOf<String>()
        if (videos > 0) parts += "$videos video${if (videos == 1) "" else "s"}"
        if (photos > 0) parts += "$photos photo${if (photos == 1) "" else "s"}"
        countView.text = (if (parts.isEmpty()) "nothing yet" else parts.joinToString(", ")) + "  ·  ${takes.count { it.loved }} ❤"
        emptyView.visibility = if (takes.isEmpty()) View.VISIBLE else View.GONE

        val target = takes.firstOrNull { it.key == selectedKey } ?: takes.firstOrNull()
        adapter.submit(takes, target?.key ?: "")
        when {
            target == null -> {
                selectedKey = ""
                video.stopPlayback()
                video.visibility = View.INVISIBLE
                photo.visibility = View.GONE
                infoView.text = ""
            }
            target.key != selectedKey -> selectTake(target, play = false)
            else -> infoView.text = describe(target)
        }
        updateButtons()
    }

    private fun selectTake(take: Take, play: Boolean) {
        selectedKey = take.key
        completed = false
        infoView.text = describe(take)
        adapter.select(take.key)
        if (take.isVideo) {
            photo.visibility = View.GONE
            video.visibility = View.VISIBLE
            video.setVideoURI(take.uri)
            video.setOnPreparedListener { if (play) video.start() else video.seekTo(1) }   // seekTo(1) shows the first picture
        } else {
            video.stopPlayback()
            video.visibility = View.INVISIBLE
            photo.visibility = View.VISIBLE
            photo.setImageBitmap(null)
            io.execute {
                val bitmap = try { contentResolver.loadThumbnail(take.uri, Size(1440, 1920), null) } catch (_: Exception) { null }
                runOnUiThread { if (selectedKey == take.key && bitmap != null) photo.setImageBitmap(bitmap) }
            }
        }
        updateButtons()
    }

    private fun describe(take: Take): String {
        val whenText = DateUtils.formatDateTime(
            this, take.dateAddedSeconds * 1000L,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH
        )
        val size = Formatter.formatShortFileSize(this, take.sizeBytes)
        return if (take.isVideo) "$whenText  ·  ${formatDuration(take.durationMs)}  ·  $size" else "$whenText  ·  Photo  ·  $size"
    }

    // ------------------------------------------------------------------ actions

    private fun togglePlay() {
        val take = current() ?: return
        if (!take.isVideo) return
        if (video.isPlaying) {
            video.pause()
        } else {
            if (completed) { video.seekTo(0); completed = false }
            video.start()
        }
        updateButtons()
    }

    private fun toggleLove(take: Take) {
        store.setLoved(take.key, !take.loved)
        toast(if (!take.loved) "Marked ❤" else "Heart removed")
        refresh()
    }

    private fun share(take: Take) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = if (take.isVideo) "video/mp4" else "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, take.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Upload or share this ${if (take.isVideo) "video" else "photo"}"))
    }

    private fun confirmDelete(list: List<Take>) {
        val what = if (list.size == 1) "this ${if (list.first().isVideo) "video" else "photo"}" else "${list.size} items"
        AlertDialog.Builder(this)
            .setTitle("Delete $what?")
            .setMessage("This removes ${if (list.size == 1) "it" else "them"} from your phone and cannot be undone.")
            .setPositiveButton("Delete") { _, _ -> deleteTakes(list) }
            .setNegativeButton("Keep", null)
            .show()
    }

    /** "Delete all except ❤": the clean-up after picking your favourite(s). */
    private fun confirmCleanup() {
        val loved = takes.count { it.loved }
        val others = takes.filterNot { it.loved }
        when {
            loved == 0 -> toast("Mark your favourite with ❤ first.")
            others.isEmpty() -> toast("Nothing to delete: everything is marked ❤.")
            else -> AlertDialog.Builder(this)
                .setTitle("Delete ${others.size} item${if (others.size == 1) "" else "s"}?")
                .setMessage("The $loved marked ❤ will be kept. This cannot be undone.")
                .setPositiveButton("Delete") { _, _ -> deleteTakes(others) }
                .setNegativeButton("Keep all", null)
                .show()
        }
    }

    private fun deleteTakes(list: List<Take>) {
        if (list.isEmpty()) return
        video.stopPlayback()                         // let go of the file first
        val denied = mutableListOf<Take>()
        var deleted = 0
        for (take in list) {
            try {
                if (contentResolver.delete(take.uri, null, null) > 0) deleted++
                store.forget(listOf(take.key))
            } catch (e: SecurityException) {
                denied += take                       // not created by this install of the app: Android must ask first
            }
        }
        if (denied.isEmpty()) {
            toast(if (deleted == 1) "Deleted." else "Deleted $deleted.")
            selectedKey = ""
            refresh()
        } else {
            askSystemToDelete(denied)
        }
    }

    private fun askSystemToDelete(list: List<Take>) {
        try {
            val sender = if (Build.VERSION.SDK_INT >= 30) {
                MediaStore.createDeleteRequest(contentResolver, list.map { it.uri }).intentSender
            } else {
                try {
                    contentResolver.delete(list.first().uri, null, null)
                    null
                } catch (e: RecoverableSecurityException) {
                    e.userAction.actionIntent.intentSender
                }
            }
            if (sender == null) {                    // Android 10, it worked after all
                store.forget(listOf(list.first().key))
                deleteTakes(list.drop(1))
                return
            }
            pendingDelete = list
            deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
        } catch (e: Exception) {
            toast("Could not delete: ${e.message}")
            refresh()
        }
    }

    // ------------------------------------------------------------------ trim

    private fun startTrim() {
        val take = current()?.takeIf { it.isVideo } ?: return
        video.pause()
        trimTotalMs = video.duration.takeIf { it > 0 } ?: take.durationMs.toInt()
        if (trimTotalMs < 2 * MIN_KEEP_MS) { toast("This video is too short to trim."); return }
        trimStartMs = 0
        trimEndMs = trimTotalMs
        trimStartBar.max = trimTotalMs
        trimEndBar.max = trimTotalMs
        trimStartBar.progress = 0
        trimEndBar.progress = trimTotalMs
        trimming = true
        showTrimPanel(true)
        updateTrimLabels()
        video.seekTo(1)
    }

    private fun closeTrim() {
        stopPreview()
        if (trimmer.isRunning) trimmer.cancel()
        handler.removeCallbacks(trimProgressTicker)
        trimming = false
        trimSaveButton.isEnabled = true
        showTrimPanel(false)
        updateButtons()
    }

    private fun showTrimPanel(show: Boolean) {
        trimPanel.visibility = if (show) View.VISIBLE else View.GONE
        val rest = if (show) View.GONE else View.VISIBLE
        findViewById<View>(R.id.mainButtons).visibility = rest
        findViewById<View>(R.id.moreButtons).visibility = rest
        findViewById<View>(R.id.list).visibility = rest
    }

    private fun setTrimStart(ms: Int, seek: Boolean) {
        trimStartMs = ms.coerceIn(0, trimEndMs - MIN_KEEP_MS)
        trimStartBar.progress = trimStartMs
        if (seek) video.seekTo(trimStartMs)
        updateTrimLabels()
    }

    private fun setTrimEnd(ms: Int, seek: Boolean) {
        trimEndMs = ms.coerceIn(trimStartMs + MIN_KEEP_MS, trimTotalMs)
        trimEndBar.progress = trimEndMs
        if (seek) video.seekTo(trimEndMs)
        updateTrimLabels()
    }

    private fun updateTrimLabels() {
        trimStartLabel.text = "Start  ${preciseTime(trimStartMs)}"
        trimEndLabel.text = "End  ${preciseTime(trimEndMs)}"
        trimSummary.text = "Keeps ${preciseTime(trimEndMs - trimStartMs)} of ${preciseTime(trimTotalMs)}"
    }

    private fun togglePreview() {
        if (previewing) { stopPreview(); return }
        video.seekTo(trimStartMs)
        video.start()
        previewing = true
        trimPreviewButton.text = "⏸ Stop"
        handler.post(previewTicker)
    }

    private fun stopPreview() {
        if (!previewing) return
        previewing = false
        handler.removeCallbacks(previewTicker)
        if (video.isPlaying) video.pause()
        trimPreviewButton.text = "▶ Preview"
    }

    /** Plays only the kept part: stops by itself at the End marker. */
    private val previewTicker = object : Runnable {
        override fun run() {
            if (!previewing) return
            if (video.currentPosition >= trimEndMs || !video.isPlaying) {
                stopPreview()
                video.seekTo(trimEndMs)
            } else {
                handler.postDelayed(this, 60)
            }
        }
    }

    private fun saveTrim() {
        val take = current() ?: return
        if (trimStartMs == 0 && trimEndMs >= trimTotalMs - 50) {
            toast("Move Start or End first, so there is something to cut.")
            return
        }
        stopPreview()
        trimSaveButton.isEnabled = false
        trimSummary.text = "Saving…"
        handler.post(trimProgressTicker)
        trimmer.trim(
            source = take.uri,
            startMs = trimStartMs.toLong(),
            endMs = trimEndMs.toLong(),
            onSaved = { uri ->
                val kept = trimEndMs - trimStartMs
                closeTrim()
                refresh()
                takes.firstOrNull { it.uri == uri }?.let { selectTake(it, play = false) }
                toast("Saved a ${preciseTime(kept)} copy. The original is still there.")
            },
            onFailed = { message ->
                handler.removeCallbacks(trimProgressTicker)
                trimSaveButton.isEnabled = true
                updateTrimLabels()
                toast("Trim failed: $message")
            }
        )
    }

    private val trimProgressTicker = object : Runnable {
        override fun run() {
            if (!trimmer.isRunning) return
            val percent = trimmer.progressPercent()
            trimSummary.text = if (percent >= 0) "Saving…  $percent%" else "Saving…"
            handler.postDelayed(this, 300)
        }
    }

    // ------------------------------------------------------------------ buttons

    private fun updateButtons() {
        val take = current()
        val has = take != null
        playButton.isEnabled = take?.isVideo == true
        loveButton.isEnabled = has
        deleteButton.isEnabled = has
        shareButton.isEnabled = has
        trimButton.isEnabled = take?.isVideo == true
        cleanupButton.isEnabled = takes.any { it.loved } && takes.any { !it.loved }
        playButton.text = when {
            take != null && !take.isVideo -> "🖼\nPhoto"
            video.isPlaying -> "⏸\nPause"
            completed -> "↻\nReplay"
            else -> "▶\nPlay"
        }
        loveButton.text = if (take?.loved == true) "❤\nLoved" else "🤍\nLove"
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        /** The shortest piece that can be kept. */
        const val MIN_KEEP_MS = 1000

        /** 0:03.4 : used on the trim handles, where tenths of a second matter. */
        fun preciseTime(ms: Int): String {
            val tenths = (ms + 50) / 100
            return String.format(Locale.US, "%d:%02d.%d", tenths / 600, (tenths / 10) % 60, tenths % 10)
        }

        fun formatDuration(ms: Long): String {
            val s = ((ms + 500) / 1000).toInt()
            return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
        }

        fun intentFor(context: Context): Intent = Intent(context, TakesActivity::class.java)
    }
}

/** The list of videos and photos, with a thumbnail, details, and a tappable heart on every row. */
private class TakeAdapter(
    private val context: Context,
    private val onSelect: (Take) -> Unit,
    private val onHeart: (Take) -> Unit
) : RecyclerView.Adapter<TakeAdapter.Holder>() {

    private var items: List<Take> = emptyList()
    private var selectedKey = ""
    private val io = Executors.newFixedThreadPool(2)
    private val ui = Handler(Looper.getMainLooper())
    private val cache = LruCache<String, Bitmap>(80)

    fun submit(list: List<Take>, selected: String) {
        items = list
        selectedKey = selected
        notifyDataSetChanged()
    }

    fun select(key: String) {
        if (key == selectedKey) return
        val old = items.indexOfFirst { it.key == selectedKey }
        val new = items.indexOfFirst { it.key == key }
        selectedKey = key
        if (old >= 0) notifyItemChanged(old)
        if (new >= 0) notifyItemChanged(new)
    }

    fun shutdown() = io.shutdownNow()

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.thumb)
        val title: TextView = view.findViewById(R.id.title)
        val subtitle: TextView = view.findViewById(R.id.subtitle)
        val heart: TextView = view.findViewById(R.id.heart)
        var boundKey = ""
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_take, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val take = items[position]
        holder.boundKey = take.key
        holder.title.text = (if (take.isVideo) "🎞  " else "🖼  ") + DateUtils.formatDateTime(
            context, take.dateAddedSeconds * 1000L,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH
        )
        val size = Formatter.formatShortFileSize(context, take.sizeBytes)
        holder.subtitle.text = if (take.isVideo) "${TakesActivity.formatDuration(take.durationMs)}  ·  $size" else "Photo  ·  $size"
        holder.heart.text = if (take.loved) "❤" else "🤍"
        holder.itemView.setBackgroundColor(if (take.key == selectedKey) context.getColor(R.color.selected) else 0)
        holder.itemView.setOnClickListener { onSelect(take) }
        holder.heart.setOnClickListener { onHeart(take) }

        val cached = cache.get(take.key)
        if (cached != null) {
            holder.thumb.setImageBitmap(cached)
        } else {
            holder.thumb.setImageBitmap(null)
            io.execute {
                val bitmap = try {
                    context.contentResolver.loadThumbnail(take.uri, Size(192, 192), null)
                } catch (_: Exception) { null }
                if (bitmap != null) {
                    cache.put(take.key, bitmap)
                    ui.post { if (holder.boundKey == take.key) holder.thumb.setImageBitmap(bitmap) }
                }
            }
        }
    }
}
