package com.example.voicecamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.ZoomState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Voice Camera, laid out like a normal camera app:
 *   top:     settings | recording timer | voice on/off
 *   bottom:  zoom pills, VIDEO / PHOTO, then  [last take]  (shutter)  [flip camera]
 *
 * It also listens for voice commands the whole time it is open:
 *   "record video after 5 seconds till 30 seconds"  -> waits 5 s, records, stops by itself after 30 s
 *   "use the front camera" / "back camera"          -> switches camera
 *   "zoom in", "zoom to 3", "take a photo after 3 seconds", "stop", "show my videos" ...
 *
 * The shutter does the same as saying "record video" (or "take a photo" in PHOTO mode): it waits and records for the
 * defaults chosen in settings. Listening is paused while a countdown, recording or photo is running (the microphone
 * is needed for the video) and resumes by itself afterwards. Videos go to Movies/VoiceCamera, photos to
 * Pictures/VoiceCamera.
 */
class MainActivity : AppCompatActivity(), VoiceListener.Callbacks {

    private enum class State { IDLE, COUNTDOWN, RECORDING, PHOTO }
    private enum class Mode { VIDEO, PHOTO }

    // views
    private lateinit var previewView: PreviewView
    private lateinit var recIndicator: TextView
    private lateinit var countdownView: TextView
    private lateinit var statusView: TextView
    private lateinit var heardView: TextView
    private lateinit var micButton: ImageView
    private lateinit var settingsButton: ImageView
    private lateinit var flipButton: ImageView
    private lateinit var shutter: View
    private lateinit var shutterInner: View
    private lateinit var takesButton: View
    private lateinit var thumbImage: ImageView
    private lateinit var thumbBadge: TextView
    private lateinit var zoomRow: LinearLayout
    private lateinit var modeVideoView: TextView
    private lateinit var modePhotoView: TextView
    private lateinit var takeStore: TakeStore
    private lateinit var settings: SettingsStore

    // camera
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var previewUseCase: Preview? = null
    private var zoomLive: LiveData<ZoomState>? = null
    private val zoomObserver = Observer<ZoomState> { onZoomState(it) }
    private lateinit var pinchDetector: ScaleGestureDetector
    private var useFront = false
    private var videoCapture: VideoCapture<Recorder>? = null
    private var imageCapture: ImageCapture? = null
    private var recording: Recording? = null
    private var orientationListener: OrientationEventListener? = null
    private var pillMin = -1f
    private var pillMax = -1f
    private var pillPresets: List<Float> = emptyList()

    // behaviour
    private lateinit var voice: VoiceListener
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var state = State.IDLE
    private var mode = Mode.VIDEO
    private var autoListen = true          // the person wants voice control on
    private var tone: ToneGenerator? = null
    private var recordingStartedAt = 0L
    private var recordingSeconds = 0
    private var foreground = false          // never listen or restart anything while the app is in the background
    private var listeningActive = false     // the microphone is open and waiting for speech right now

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onPermissionResult() }

    // ------------------------------------------------------------------ lifecycle

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        takeStore = TakeStore(this)
        settings = SettingsStore(this)
        // zoom: pinch on the picture, or tap a zoom pill (both also work while recording)
        pinchDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoomBy(detector.scaleFactor)
                return true
            }
        })
        bindViews()

        voice = VoiceListener(this, this)
        tone = try { ToneGenerator(AudioManager.STREAM_MUSIC, 70) } catch (_: Exception) { null }

        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val rotation = when {
                    orientation >= 315 || orientation < 45 -> Surface.ROTATION_0
                    orientation < 135 -> Surface.ROTATION_270
                    orientation < 225 -> Surface.ROTATION_180
                    else -> Surface.ROTATION_90
                }
                videoCapture?.targetRotation = rotation
                imageCapture?.targetRotation = rotation
            }
        }

        refreshButtons()
        if (hasPermissions()) onReady() else {
            setStatus("Camera and microphone permission are needed.")
            permissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    /** Find the views of the layout that is on screen now (portrait or landscape) and hook up their buttons. */
    @SuppressLint("ClickableViewAccessibility")
    private fun bindViews() {
        previewView = findViewById(R.id.preview)
        recIndicator = findViewById(R.id.recIndicator)
        countdownView = findViewById(R.id.countdown)
        statusView = findViewById(R.id.status)
        heardView = findViewById(R.id.heard)
        micButton = findViewById(R.id.btnMic)
        settingsButton = findViewById(R.id.btnSettings)
        flipButton = findViewById(R.id.btnFlip)
        shutter = findViewById(R.id.btnShutter)
        shutterInner = findViewById(R.id.shutterInner)
        takesButton = findViewById(R.id.btnTakes)
        thumbImage = findViewById(R.id.thumb)
        thumbBadge = findViewById(R.id.thumbBadge)
        zoomRow = findViewById(R.id.zoomRow)
        modeVideoView = findViewById(R.id.modeVideo)
        modePhotoView = findViewById(R.id.modePhoto)

        settingsButton.setOnClickListener { openSettings() }
        micButton.setOnClickListener { onMicButton() }
        flipButton.setOnClickListener { onFlip() }
        shutter.setOnClickListener { onShutter() }
        takesButton.setOnClickListener { openTakes() }
        modeVideoView.setOnClickListener { setMode(Mode.VIDEO) }
        modePhotoView.setOnClickListener { setMode(Mode.PHOTO) }
        previewView.setOnTouchListener { view, event ->
            pinchDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP) view.performClick()
            true
        }
    }

    /**
     * The phone was turned: switch between the portrait and landscape layouts. The camera is not restarted
     * (a recording keeps going); only the picture is pointed at the new preview view.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val status = statusView.text
        val heard = heardView.text
        val heardTag = heardView.tag
        val countdownShown = countdownView.visibility == View.VISIBLE
        val countdownText = countdownView.text
        val recShown = recIndicator.visibility == View.VISIBLE
        val recText = recIndicator.text

        setContentView(R.layout.activity_main)
        bindViews()

        statusView.text = status
        heardView.text = heard
        heardView.tag = heardTag
        countdownView.text = countdownText
        countdownView.visibility = if (countdownShown) View.VISIBLE else View.GONE
        recIndicator.text = recText
        recIndicator.visibility = if (recShown) View.VISIBLE else View.GONE

        previewUseCase?.setSurfaceProvider(previewView.surfaceProvider)
        onCameraBound()                    // flip label + zoom pills for the new layout
        updateThumbnail()
        refreshButtons()
    }

    override fun onStart() {
        super.onStart()
        foreground = true
        updateThumbnail()
        if (heardView.tag == null) heardView.text = defaultsHint()      // until the first command, show what "record video" will do
        orientationListener?.enable()
        if (hasPermissions() && cameraProvider != null && state == State.IDLE && autoListen) voice.start()
    }

    override fun onStop() {
        super.onStop()
        foreground = false
        orientationListener?.disable()
        // the camera and microphone belong to the foreground app: end whatever is running
        when (state) {
            State.COUNTDOWN, State.PHOTO -> finishAction("Cancelled because the app was left.")
            State.RECORDING -> recording?.stop()          // Finalize event saves what was recorded
            State.IDLE -> {}
        }
        voice.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        voice.stop()
        recording?.stop()
        tone?.release()
        tone = null
        io.shutdownNow()
    }

    // ------------------------------------------------------------------ permissions

    private fun hasPermissions() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun onPermissionResult() {
        if (hasPermissions()) onReady() else {
            setStatus("Camera and microphone permission are required. Tap the microphone icon to allow them.")
            refreshButtons()
        }
    }

    private fun onReady() {
        setStatus("Starting the camera…")
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            bindCamera()
            setStatus("Ready.")
            if (autoListen) voice.start()
            refreshButtons()
        }, ContextCompat.getMainExecutor(this))
    }

    // ------------------------------------------------------------------ camera

    private fun selector(front: Boolean) = CameraSelector.Builder()
        .requireLensFacing(if (front) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK)
        .build()

    private fun hasCamera(front: Boolean): Boolean = try {
        cameraProvider?.hasCamera(selector(front)) == true
    } catch (_: Exception) { false }

    private fun bindCamera(): Boolean {
        val provider = cameraProvider ?: return false
        val rotation = previewView.display?.rotation ?: Surface.ROTATION_0

        val preview = Preview.Builder().setTargetRotation(rotation).build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }
        previewUseCase = preview
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
            .build()
        val video = VideoCapture.withOutput(recorder).also { it.targetRotation = rotation }
        val image = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetRotation(rotation)
            .build()

        provider.unbindAll()
        return try {
            camera = provider.bindToLifecycle(this, selector(useFront), preview, image, video)
            imageCapture = image
            videoCapture = video
            onCameraBound()
            true
        } catch (_: Exception) {
            // some phones cannot run preview + photo + video together: keep video, drop photos
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, selector(useFront), preview, video)
                imageCapture = null
                videoCapture = video
                onCameraBound()
                true
            } catch (e: Exception) {
                setStatus("Could not open the camera: ${e.message}")
                false
            }
        }
    }

    /** @return true if the camera is now the one asked for. */
    private fun selectCamera(choice: CameraChoice): Boolean {
        val wantFront = when (choice) {
            CameraChoice.FRONT -> true
            CameraChoice.BACK -> false
            CameraChoice.TOGGLE -> !useFront
        }
        if (wantFront == useFront) {
            setStatus("Already using the ${if (useFront) "front" else "back"} camera.")
            return true
        }
        if (!hasCamera(wantFront)) {
            setStatus("This phone has no ${if (wantFront) "front" else "back"} camera.")
            return false
        }
        useFront = wantFront
        if (!bindCamera()) { useFront = !useFront; bindCamera(); return false }
        setStatus("${if (useFront) "Front" else "Back"} camera selected.")
        return true
    }

    private fun currentChoice() = if (useFront) CameraChoice.FRONT else CameraChoice.BACK

    private fun onCameraBound() {
        flipButton.contentDescription = "Switch to the ${if (useFront) "back" else "front"} camera"
        observeZoom()
    }

    private fun onFlip() {
        if (state != State.IDLE) { setStatus("Finish or stop first, then switch camera."); return }
        selectCamera(CameraChoice.TOGGLE)
    }

    // ------------------------------------------------------------------ zoom

    /** Show the current zoom of whichever camera is bound now (called again after every camera switch). */
    private fun observeZoom() {
        zoomLive?.removeObserver(zoomObserver)
        pillMin = -1f                                  // force the pills to be rebuilt for the new camera's range
        zoomLive = camera?.cameraInfo?.zoomState
        zoomLive?.observe(this, zoomObserver)
    }

    private fun currentZoom(): ZoomState? = camera?.cameraInfo?.zoomState?.value

    private fun setZoom(ratio: Float) {
        val control = camera?.cameraControl ?: return
        val zoom = currentZoom() ?: return
        control.setZoomRatio(ratio.coerceIn(zoom.minZoomRatio, zoom.maxZoomRatio))
    }

    private fun zoomBy(factor: Float) {
        val zoom = currentZoom() ?: return
        setZoom(zoom.zoomRatio * factor)
    }

    private fun applyZoom(change: ZoomChange) {
        val zoom = currentZoom()
        if (zoom == null) { setStatus("Zoom is not available on this camera."); return }
        val target = when (change) {
            ZoomChange.In -> zoom.zoomRatio * ZOOM_STEP
            ZoomChange.Out -> zoom.zoomRatio / ZOOM_STEP
            ZoomChange.Max -> zoom.maxZoomRatio
            ZoomChange.Reset -> 1f
            is ZoomChange.To -> change.ratio
        }.coerceIn(zoom.minZoomRatio, zoom.maxZoomRatio)
        setZoom(target)
        setStatus(String.format(Locale.US, "Zoom %.1f× (this camera: %.1f× to %.1f×).", target, zoom.minZoomRatio, zoom.maxZoomRatio))
    }

    /** The row of zoom pills above the shutter: 0.5  [1×]  2  3 ... only the steps this camera can really do. */
    private fun onZoomState(zoom: ZoomState) {
        if (zoom.minZoomRatio != pillMin || zoom.maxZoomRatio != pillMax) {
            pillMin = zoom.minZoomRatio
            pillMax = zoom.maxZoomRatio
            pillPresets = ZOOM_PRESETS
                .filter { it >= zoom.minZoomRatio - 0.01f && it <= zoom.maxZoomRatio + 0.01f }
                .ifEmpty { listOf(1f) }
            zoomRow.removeAllViews()
            for (preset in pillPresets) {
                val pill = TextView(this).apply {
                    gravity = Gravity.CENTER
                    setTypeface(typeface, Typeface.BOLD)
                    setOnClickListener { setZoom(preset) }
                    contentDescription = "Zoom ${formatZoom(preset)} times"
                }
                zoomRow.addView(pill, LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                    marginStart = dp(5)
                    marginEnd = dp(5)
                })
            }
        }
        paintZoomPills(zoom.zoomRatio)
    }

    /** The pill at or below the current zoom is highlighted and shows the exact value ("2.3×"); the others show their step. */
    private fun paintZoomPills(current: Float) {
        var selected = 0
        pillPresets.forEachIndexed { i, preset -> if (preset <= current + 0.02f) selected = i }
        for (i in pillPresets.indices) {
            val pill = zoomRow.getChildAt(i) as? TextView ?: continue
            val on = i == selected
            pill.text = if (on) "${formatZoom(current)}×" else formatZoom(pillPresets[i])
            pill.setTextColor(getColor(if (on) R.color.gold else R.color.white))
            pill.textSize = if (on) 14f else 13f
            pill.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (on) 0xCC000000.toInt() else 0x66000000)
            }
            val size = dp(if (on) 46 else 40)
            val params = pill.layoutParams as LinearLayout.LayoutParams
            if (params.width != size) {
                params.width = size
                params.height = size
                pill.layoutParams = params
            }
        }
    }

    private fun formatZoom(value: Float) =
        if (abs(value - value.roundToInt()) < 0.05f) value.roundToInt().toString() else String.format(Locale.US, "%.1f", value)

    // ------------------------------------------------------------------ voice commands

    override fun onResult(alternatives: List<String>) {
        runOnUiThread {
            heardView.tag = "heard"
            heardView.text = "Heard: \"${alternatives.first()}\""
            handleText(alternatives)
        }
    }

    override fun onPartial(text: String) {
        runOnUiThread { heardView.tag = "heard"; heardView.text = "Hearing: \"$text\"…" }
    }

    override fun onListening(active: Boolean) {
        runOnUiThread { listeningActive = active; refreshButtons() }
    }

    override fun onProblem(message: String) {
        runOnUiThread { setStatus(message); refreshButtons() }
    }

    private fun handleText(alternatives: List<String>) {
        val defaults = settings.defaults()
        val command = alternatives.asSequence().mapNotNull { CommandParser.parse(it, defaults) }.firstOrNull()
        if (command == null) {
            setStatus("Sorry, I didn't understand. Try: \"record video after 5 seconds till 30 seconds\".")
            return
        }
        execute(command)
    }

    private fun execute(command: VoiceCommand) {
        if (state != State.IDLE) {
            if (command.action == CameraAction.Stop) stopCurrentAction()
            else setStatus("Busy. Tap the red button to stop first.")
            return
        }
        if (command.review) { openTakes(); return }
        command.newDefaults?.let { change ->
            settings.apply(change)
            setStatus("Saved. Without a time, “record video” now waits ${settings.delaySeconds} s and records ${settings.durationSeconds} s.")
            heardView.text = defaultsHint()
            return
        }
        val cameraChanged = command.camera != null && command.camera != currentChoice()
        if (command.camera != null && !selectCamera(command.camera)) return
        command.zoom?.let { zoom ->
            // a freshly switched camera needs a moment before it reports its zoom range
            if (cameraChanged) handler.postDelayed({ applyZoom(zoom) }, ZOOM_AFTER_SWITCH_MS) else applyZoom(zoom)
        }
        when (val action = command.action) {
            is CameraAction.Record -> {
                mode = Mode.VIDEO
                startCountdown(action.delaySeconds) { beginRecording(action.durationSeconds) }
            }
            is CameraAction.Photo -> {
                mode = Mode.PHOTO
                startCountdown(action.delaySeconds) { takePhoto() }
            }
            CameraAction.Stop -> setStatus("Nothing is running.")
            null -> {}      // camera switch / zoom only; keep listening
        }
    }

    private fun onMicButton() {
        if (!hasPermissions()) { permissionLauncher.launch(REQUIRED_PERMISSIONS); return }
        autoListen = !autoListen
        if (autoListen) {
            if (state == State.IDLE) voice.start()
            setStatus("Voice control is on. Say a command.")
        } else {
            voice.stop()
            setStatus("Voice control is off. Tap the microphone icon to turn it on.")
        }
        refreshButtons()
    }

    // ------------------------------------------------------------------ shutter and mode

    private fun setMode(newMode: Mode) {
        if (state != State.IDLE) return
        mode = newMode
        refreshButtons()
    }

    /** The big round button: start (using the defaults from settings), or stop / cancel when something is running. */
    private fun onShutter() {
        when (state) {
            State.COUNTDOWN, State.RECORDING -> stopCurrentAction()
            State.PHOTO -> {}
            State.IDLE -> {
                if (!hasPermissions() || cameraProvider == null) { permissionLauncher.launch(REQUIRED_PERMISSIONS); return }
                val action = if (mode == Mode.VIDEO) {
                    CameraAction.Record(settings.delaySeconds, settings.durationSeconds)
                } else {
                    CameraAction.Photo(settings.delaySeconds)
                }
                execute(VoiceCommand(null, action))
            }
        }
    }

    // ------------------------------------------------------------------ countdown / actions

    private fun startCountdown(delaySeconds: Int, then: () -> Unit) {
        voice.stop()                       // free the microphone for the recording
        state = State.COUNTDOWN
        refreshButtons()
        var remaining = delaySeconds
        val tick = object : Runnable {
            override fun run() {
                if (state != State.COUNTDOWN) return
                if (remaining <= 0) {
                    countdownView.visibility = View.GONE
                    setStatus("Starting…")
                    // a short pause lets the microphone be released before the recorder takes it
                    handler.postDelayed({ if (state == State.COUNTDOWN) then() }, START_PAUSE_MS)
                    return
                }
                countdownView.text = remaining.toString()
                countdownView.visibility = View.VISIBLE
                setStatus("Starting in $remaining…")
                if (remaining <= 3) beep(120)
                remaining--
                handler.postDelayed(this, 1000)
            }
        }
        tick.run()
    }

    @SuppressLint("MissingPermission")
    private fun beginRecording(durationSeconds: Int) {
        val capture = videoCapture ?: return finishAction("The camera is not ready.")
        val name = "VoiceCamera_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/VoiceCamera")
        }
        val output = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values)
            .setDurationLimitMillis(durationSeconds * 1000L)       // the recorder also enforces the limit
            .build()

        recordingSeconds = durationSeconds
        state = State.RECORDING            // from here on, Stop ends the recording instead of cancelling a countdown
        refreshButtons()
        beep(250)
        recording = try {
            capture.output.prepareRecording(this, output)
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(this)) { event -> onRecordEvent(event) }
        } catch (e: Exception) {
            return finishAction("Could not start recording: ${e.message}")
        }
    }

    private fun onRecordEvent(event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Start -> {
                state = State.RECORDING
                recordingStartedAt = SystemClock.elapsedRealtime()
                recIndicator.visibility = View.VISIBLE
                refreshButtons()
                // stop exactly when the requested length is over
                handler.postDelayed({ recording?.stop() }, recordingSeconds * 1000L)
                handler.post(recordingTicker)
            }
            is VideoRecordEvent.Finalize -> {
                handler.removeCallbacks(recordingTicker)
                recording = null
                val limitReached = event.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED
                if (event.hasError() && !limitReached) {
                    finishAction("Recording failed (error ${event.error}).")
                } else {
                    // stay here and keep listening: take as many as you like, then review them all from the thumbnail
                    finishAction("Saved a ${formatTime(recordingSeconds.coerceAtMost(elapsedSeconds()))} video. ${reviewHint()}")
                }
            }
            else -> {}
        }
    }

    private val recordingTicker = object : Runnable {
        override fun run() {
            if (state != State.RECORDING) return
            recIndicator.text = "● ${formatTime(elapsedSeconds())} / ${formatTime(recordingSeconds)}"
            setStatus("Recording… stops automatically at ${formatTime(recordingSeconds)}.")
            handler.postDelayed(this, 250)
        }
    }

    private fun elapsedSeconds() = ((SystemClock.elapsedRealtime() - recordingStartedAt) / 1000L).toInt()

    private fun takePhoto() {
        val capture = imageCapture ?: return finishAction("Photos are not available on this camera setup.")
        state = State.PHOTO
        refreshButtons()
        val name = "VoiceCamera_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/VoiceCamera")
        }
        val output = ImageCapture.OutputFileOptions
            .Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values).build()
        capture.takePicture(output, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                finishAction("Saved a photo. ${reviewHint()}")
            }
            override fun onError(exception: ImageCaptureException) {
                finishAction("Photo failed: ${exception.message}")
            }
        })
    }

    /** Shutter / "stop": cancel a countdown, or end a recording early. */
    private fun stopCurrentAction() {
        when (state) {
            State.COUNTDOWN -> finishAction("Cancelled.")
            State.RECORDING -> { setStatus("Stopping…"); recording?.stop() }
            State.PHOTO, State.IDLE -> {}
        }
    }

    private fun finishAction(message: String) {
        handler.removeCallbacksAndMessages(null)
        countdownView.visibility = View.GONE
        recIndicator.visibility = View.GONE
        state = State.IDLE
        setStatus(message)
        beep(150)
        refreshButtons()
        if (autoListen && foreground && hasPermissions() && cameraProvider != null) {
            handler.postDelayed({ if (state == State.IDLE && autoListen && foreground) voice.start() }, RESUME_LISTEN_MS)
        }
    }

    // ------------------------------------------------------------------ review

    private fun openTakes() {
        if (state != State.IDLE) { setStatus("Finish or stop the current recording first."); return }
        startActivity(TakesActivity.intentFor(this))
    }

    /** Shown after every save: updates the thumbnail and count, and says what to do next. */
    private fun reviewHint(): String {
        updateThumbnail()
        val count = try { takeStore.count() } catch (_: Exception) { 0 }
        return "Say another command, or tap the picture at the bottom left to review ($count)."
    }

    private fun openSettings() {
        if (state != State.IDLE) { setStatus("Finish or stop the current recording first."); return }
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun defaultsHint(): String {
        val wait = settings.delaySeconds
        return "Defaults: ${if (wait == 0) "no wait" else "wait $wait s"} · record ${settings.durationSeconds} s"
    }

    /** The small picture at the bottom left is the newest take, with the number of takes on it. */
    private fun updateThumbnail() {
        io.execute {
            val takes = try { takeStore.loadTakes() } catch (_: Exception) { emptyList() }
            val bitmap = takes.firstOrNull()?.let {
                try { contentResolver.loadThumbnail(it.uri, Size(200, 200), null) } catch (_: Exception) { null }
            }
            runOnUiThread {
                thumbImage.setImageBitmap(bitmap)
                thumbBadge.visibility = if (takes.isEmpty()) View.GONE else View.VISIBLE
                thumbBadge.text = takes.size.toString()
            }
        }
    }

    // ------------------------------------------------------------------ small helpers

    private fun setStatus(text: String) { statusView.text = text }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    /** Redraw every control for the current state: shutter look, mode tabs, voice icon, dimmed buttons while busy. */
    private fun refreshButtons() {
        val idle = state == State.IDLE

        modeVideoView.setTextColor(if (mode == Mode.VIDEO) getColor(R.color.gold) else 0xB3FFFFFF.toInt())
        modePhotoView.setTextColor(if (mode == Mode.PHOTO) getColor(R.color.gold) else 0xB3FFFFFF.toInt())
        modeVideoView.isEnabled = idle
        modePhotoView.isEnabled = idle

        // red dot = ready to record, white = ready for a photo, red square = tap to stop
        when {
            state == State.COUNTDOWN || state == State.RECORDING -> setShutterInner(R.drawable.shutter_stop, 17)
            mode == Mode.VIDEO -> setShutterInner(R.drawable.shutter_video, 32)
            else -> setShutterInner(R.drawable.shutter_photo, 32)
        }
        shutter.isEnabled = state != State.PHOTO
        shutter.alpha = if (state == State.PHOTO) 0.5f else 1f

        flipButton.alpha = if (idle) 1f else 0.4f
        settingsButton.alpha = if (idle) 1f else 0.4f

        // voice icon: gold while the microphone is open, white when voice is on but paused, grey with a slash when off
        val voiceOn = hasPermissions() && autoListen
        micButton.setImageResource(if (voiceOn) R.drawable.ic_mic else R.drawable.ic_mic_off)
        val micColor = when {
            voiceOn && listeningActive && idle -> R.color.gold
            voiceOn -> R.color.white
            else -> R.color.dim
        }
        micButton.imageTintList = ColorStateList.valueOf(getColor(micColor))
        micButton.alpha = if (voiceOn && !idle) 0.5f else 1f
    }

    private fun setShutterInner(drawable: Int, sizeDp: Int) {
        shutterInner.setBackgroundResource(drawable)
        val params = shutterInner.layoutParams
        val size = dp(sizeDp)
        if (params.width != size) {
            params.width = size
            params.height = size
            shutterInner.layoutParams = params
        }
    }

    private fun beep(ms: Int) {
        try { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, ms) } catch (_: Exception) {}
    }

    private fun formatTime(seconds: Int) = String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)

    /** Lets the person open the app's settings when permissions were refused for good. */
    @Suppress("unused")
    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    companion object {
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        private val ZOOM_PRESETS = listOf(0.5f, 1f, 2f, 3f, 5f, 10f)
        private const val ZOOM_STEP = 1.5f                  // "zoom in" multiplies by this, "zoom out" divides
        private const val ZOOM_AFTER_SWITCH_MS = 400L
        private const val START_PAUSE_MS = 400L
        private const val RESUME_LISTEN_MS = 700L
    }
}
