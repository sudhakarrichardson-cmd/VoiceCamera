package com.example.voicecamera

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Keeps Android's speech recogniser listening, one sentence at a time, and hands each result to [callbacks].
 *
 * - Restarts itself after each sentence (and after "no match" / timeouts) until [stop] is called.
 * - Backs off when something is wrong (no network, busy recogniser) instead of restarting in a tight loop.
 * - [stop] fully releases the microphone, which the video recorder needs.
 *
 * Must be used from the main thread, like SpeechRecognizer itself.
 */
class VoiceListener(private val context: Context, private val callbacks: Callbacks) {

    interface Callbacks {
        /** The recogniser's best guesses for one sentence, most likely first. */
        fun onResult(alternatives: List<String>)
        /** Live text while the person is still speaking. */
        fun onPartial(text: String)
        /** True while the microphone is open and waiting for speech. */
        fun onListening(active: Boolean)
        /** Something the person should know (no network, not available, ...). */
        fun onProblem(message: String)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var wanted = false
    private var failures = 0

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    val isRunning: Boolean get() = wanted

    fun start() {
        if (wanted) return
        if (!isAvailable()) {
            callbacks.onProblem("Speech recognition is not available on this phone. Install or enable the Google app's speech service.")
            return
        }
        wanted = true
        failures = 0
        scheduleListen(0)
    }

    /** Stop listening and give the microphone back. */
    fun stop() {
        wanted = false
        handler.removeCallbacksAndMessages(null)
        releaseRecognizer()
        callbacks.onListening(false)
    }

    private fun releaseRecognizer() {
        recognizer?.let {
            try { it.cancel() } catch (_: Exception) {}
            try { it.destroy() } catch (_: Exception) {}
        }
        recognizer = null
    }

    private fun scheduleListen(delayMs: Long) {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ if (wanted) listenOnce() }, delayMs)
    }

    private fun listenOnce() {
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")      // the commands are English
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        try {
            r.startListening(intent)
        } catch (e: Exception) {
            problem("Could not start listening: ${e.message}")
            retryWithBackoff()
        }
    }

    private fun problem(message: String) = callbacks.onProblem(message)

    private fun retryWithBackoff() {
        failures++
        if (failures > 6) {
            problem("Voice listening stopped after repeated errors. Tap the microphone button to try again.")
            stop()
            return
        }
        releaseRecognizer()
        scheduleListen(minOf(5000L, 500L shl failures))
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { callbacks.onListening(true) }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!text.isNullOrBlank()) callbacks.onPartial(text)
        }

        override fun onResults(results: Bundle?) {
            failures = 0
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            callbacks.onListening(false)
            if (list.isNotEmpty()) callbacks.onResult(list)   // the caller may call stop() here
            if (wanted) scheduleListen(250)
        }

        override fun onError(error: Int) {
            callbacks.onListening(false)
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> if (wanted) scheduleListen(300)        // silence is normal

                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    releaseRecognizer()
                    if (wanted) scheduleListen(800)
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    problem("Microphone permission is needed for voice commands.")
                    stop()
                }
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                    problem("No internet connection for speech recognition. Trying again…")
                    if (wanted) retryWithBackoff()
                }
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                    problem("English speech recognition is not available on this phone right now.")
                    stop()
                }
                else -> if (wanted) retryWithBackoff()
            }
        }
    }
}
