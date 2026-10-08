package com.example.voicecamera

/** Which camera the user asked for. */
enum class CameraChoice { FRONT, BACK, TOGGLE }

/** What the user wants the camera to do. */
sealed class CameraAction {
    /** Wait [delaySeconds], then record for [durationSeconds] and stop by itself. */
    data class Record(val delaySeconds: Int, val durationSeconds: Int) : CameraAction()

    /** Wait [delaySeconds], then take one photo. */
    data class Photo(val delaySeconds: Int) : CameraAction()

    /** Cancel a countdown or stop a recording that is running. */
    object Stop : CameraAction()
}

/** A change of zoom: a step in or out, an exact magnification, the maximum, or back to normal. */
sealed class ZoomChange {
    object In : ZoomChange()
    object Out : ZoomChange()
    object Max : ZoomChange()
    object Reset : ZoomChange()
    data class To(val ratio: Float) : ZoomChange()
}

/**
 * One spoken sentence can switch the camera, change the zoom, start an action, or several of these:
 * "use the front camera, zoom in and record after 5 seconds for 30 seconds".
 */
data class VoiceCommand(
    val camera: CameraChoice?,
    val action: CameraAction?,
    val zoom: ZoomChange? = null,
    /** "play my last video", "show my takes": open the review screen. */
    val review: Boolean = false,
    /** "set default wait to 5 seconds": change the saved defaults. */
    val newDefaults: DefaultsChange? = null
)

/** What to use when a sentence gives no number: wait before starting, and recording length. Set in the app's settings. */
data class CommandDefaults(val delaySeconds: Int = 0, val durationSeconds: Int = CommandParser.DEFAULT_DURATION_SECONDS)

/** A spoken change to the defaults; a null part is left as it is. */
data class DefaultsChange(val delaySeconds: Int?, val durationSeconds: Int?)

/**
 * Turns the text from speech recognition into a [VoiceCommand]. Pure Kotlin (no Android classes) so it can be unit tested.
 *
 * Timing words:
 *   "after / in / wait / delay N seconds"          -> how long to wait before recording starts
 *   "for / till / until / to / of N seconds"       -> how long the recording lasts
 *   "stop after N seconds", "end after N seconds"  -> also how long the recording lasts
 * Numbers can be spoken ("five", "thirty") or digits, in seconds or minutes.
 */
object CommandParser {
    const val DEFAULT_DURATION_SECONDS = 30
    const val MAX_DELAY_SECONDS = 300
    const val MAX_DURATION_SECONDS = 600

    private val ONES = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
        "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19
    )
    private val TENS = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90
    )

    private val DELAY_LABELS = setOf("after", "in", "within", "delay", "wait", "waiting")
    private val DURATION_LABELS = setOf("for", "till", "until", "to", "of", "lasting")
    private val STOP_LABELS = setOf("stop", "end", "finish")

    private val RECORD_WORDS = setOf("record", "recording", "video", "videos", "film", "filming")
    private val PHOTO_WORDS = setOf("photo", "photos", "picture", "pictures", "pic", "snap", "capture", "photograph")
    private val STOP_WORDS = setOf("stop", "cancel", "abort")
    private val STOP_FIRST_WORDS = setOf("stop", "cancel", "abort", "end")
    private val START_WORDS = setOf("start", "begin")
    private val FRONT_WORDS = setOf("front", "selfie")
    private val BACK_WORDS = setOf("back", "rear", "main", "primary")
    private val SWITCH_WORDS = setOf("switch", "flip", "toggle", "swap", "change", "other")
    private val CAMERA_WORDS = setOf("camera", "cam")

    private enum class Label { DELAY, DURATION, NONE }
    private data class Quantity(val seconds: Int, val label: Label)

    /**
     * @param defaults used for any timing the sentence leaves out ("record video" = wait + record by the defaults)
     * @return the command, or null if nothing in [raw] was understood.
     */
    fun parse(raw: String, defaults: CommandDefaults = CommandDefaults()): VoiceCommand? {
        // zoom first, and cut it out of the sentence so "zoom to 3" is not mistaken for a time
        val (zoom, withoutZoom) = extractZoom(normalize(raw))
        val text = withoutZoom.replace(Regex("\\s+"), " ").trim()
        if (text.isBlank()) return if (zoom != null) VoiceCommand(null, null, zoom) else null

        // "set default wait to 5 seconds and record time to 30 seconds"
        extractDefaultsChange(text)?.let { return VoiceCommand(null, null, zoom, newDefaults = it) }

        val words = text.split(' ')
        val quantities = findQuantities(text)
        val camera = findCamera(words)

        val hasStart = words.any { it in START_WORDS }
        val hasStop = words.any { it in STOP_WORDS }
        val noWait = NO_WAIT.containsMatchIn(text)         // "record now", "no wait"
        // "play my last video" / "show my takes" -- but never when the sentence is really about recording
        val review = words.none { it in RECORD_INTENT_WORDS } && REVIEW.containsMatchIn(text)
        val action: CameraAction? = when {
            review -> null
            // "stop", "stop recording", "cancel" - but not "start recording and stop after 30 seconds"
            hasStop && (words.first() in STOP_FIRST_WORDS || (!hasStart && quantities.isEmpty())) -> CameraAction.Stop
            words.any { it in RECORD_WORDS } -> buildRecord(quantities, defaults, noWait)
            words.any { it in PHOTO_WORDS } -> buildPhoto(quantities, defaults, noWait)
            else -> null
        }
        if (camera == null && action == null && zoom == null && !review) return null
        return VoiceCommand(camera, action, zoom, review)
    }

    private val RECORD_INTENT_WORDS = setOf("record", "recording", "film", "filming", "start", "begin")
    private val REVIEW = Regex(
        "\\b(?:play|replay|review|watch)\\b|\\b(?:show|open|view|see|list)\\b.*\\b(?:videos?|takes?|recordings?|gallery|clips?)\\b"
    )

    // ---------------------------------------------------------------- zoom

    private val ZOOM_ABSOLUTE = Regex(
        "\\bzoom(?:\\s+(?:in|to|at|by))*\\s+(\\d+(?:\\.\\d+)?)(?:\\s*(?:x|times|time))?\\b(?!\\s*(?:seconds?|secs?|minutes?|mins?))"
    )
    private val ZOOM_ABSOLUTE_X = Regex("\\b(\\d+(?:\\.\\d+)?)\\s*(?:x|times|time)\\s+zoom\\b")
    private val ZOOM_WORD = Regex("\\b(?:zoom|zoomed|zooming|magnify)\\b(?:\\s+(?:in|out|back|to|max|maximum|reset|normal|default|full))?")
    private val ZOOM_RESET = setOf("reset", "normal", "default", "original")
    private val ZOOM_MAX = setOf("max", "maximum", "full", "most")
    private val ZOOM_OUT = setOf("out", "wider", "wide", "back")

    /** @return the zoom the sentence asks for (or null) and the sentence with the zoom words removed. */
    private fun extractZoom(text: String): Pair<ZoomChange?, String> {
        for (pattern in listOf(ZOOM_ABSOLUTE, ZOOM_ABSOLUTE_X)) {
            val m = pattern.find(text) ?: continue
            val ratio = m.groupValues[1].toFloatOrNull() ?: continue
            if (ratio > 0f) return ZoomChange.To(ratio.coerceIn(0.1f, 20f)) to text.removeRange(m.range)
        }
        if (!ZOOM_WORD.containsMatchIn(text)) return null to text
        val words = text.split(' ').toSet()
        val change = when {
            words.any { it in ZOOM_RESET } -> ZoomChange.Reset
            words.any { it in ZOOM_MAX } -> ZoomChange.Max
            words.any { it in ZOOM_OUT } -> ZoomChange.Out
            else -> ZoomChange.In                                // "zoom in", "zoom", "magnify"
        }
        return change to ZOOM_WORD.replace(text, " ")
    }

    // ---------------------------------------------------------------- camera

    private fun findCamera(words: List<String>): CameraChoice? {
        val front = words.indexOfLast { it in FRONT_WORDS }
        val back = words.indexOfLast { it in BACK_WORDS }
        if (front >= 0 || back >= 0) {
            // "switch from back to front camera": the camera named last is the one wanted
            return if (front > back) CameraChoice.FRONT else CameraChoice.BACK
        }
        val mentionsCamera = words.any { it in CAMERA_WORDS }
        if (mentionsCamera && words.any { it in SWITCH_WORDS }) return CameraChoice.TOGGLE
        return null
    }

    // ---------------------------------------------------------------- timing

    private val NO_WAIT = Regex("\\b(?:now|immediately|right away|straight away|no delay|without delay|no wait|without waiting)\\b")
    private val DEFAULT_WORD = Regex("\\bdefaults?\\b")
    private val SET_DELAY = Regex(
        "\\b(?:wait|waiting|delay|countdown)\\b(?:\\s+(?:time|timer|length|to|of|for|at|is|be|as))*\\s+(\\d+)(?:\\s+(seconds?|secs?|minutes?|mins?))?"
    )
    private val SET_LENGTH = Regex(
        "\\b(?:record|recording|length|duration|clip)\\b(?:\\s+(?:time|timer|length|duration|to|of|for|at|is|be|as))*\\s+(\\d+)(?:\\s+(seconds?|secs?|minutes?|mins?))?"
    )

    /** Only for sentences that say "default": "set the default wait to 5 seconds", "default record time 45 seconds". */
    private fun extractDefaultsChange(text: String): DefaultsChange? {
        if (!DEFAULT_WORD.containsMatchIn(text)) return null
        fun seconds(match: MatchResult): Int {
            val n = match.groupValues[1].toIntOrNull() ?: return 0
            return if (match.groupValues[2].startsWith("m")) n * 60 else n
        }
        var delay = SET_DELAY.find(text)?.let { seconds(it).coerceIn(0, MAX_DELAY_SECONDS) }
        val length = SET_LENGTH.find(text)?.let { seconds(it).coerceIn(1, MAX_DURATION_SECONDS) }
        if (delay == null && NO_WAIT.containsMatchIn(text)) delay = 0       // "no wait by default"
        return if (delay == null && length == null) null else DefaultsChange(delay, length)
    }

    private fun buildRecord(quantities: List<Quantity>, defaults: CommandDefaults, noWait: Boolean): CameraAction.Record {
        var delay = quantities.firstOrNull { it.label == Label.DELAY }?.seconds
        var duration = quantities.firstOrNull { it.label == Label.DURATION }?.seconds
        val unlabeled = quantities.filter { it.label == Label.NONE }.map { it.seconds }
        when {
            delay == null && duration == null -> when (unlabeled.size) {
                0 -> {}
                1 -> duration = unlabeled[0]                       // "record a 30 second video"
                else -> { delay = unlabeled[0]; duration = unlabeled[1] }
            }
            delay == null && unlabeled.isNotEmpty() -> delay = unlabeled[0]
            duration == null && unlabeled.isNotEmpty() -> duration = unlabeled[0]
        }
        // anything the sentence left out comes from the saved defaults; "now" / "no wait" skips the default wait
        val wait = delay ?: if (noWait) 0 else defaults.delaySeconds
        return CameraAction.Record(
            delaySeconds = wait.coerceIn(0, MAX_DELAY_SECONDS),
            durationSeconds = (duration ?: defaults.durationSeconds).coerceIn(1, MAX_DURATION_SECONDS)
        )
    }

    private fun buildPhoto(quantities: List<Quantity>, defaults: CommandDefaults, noWait: Boolean): CameraAction.Photo {
        val spoken = (quantities.firstOrNull { it.label == Label.DELAY } ?: quantities.firstOrNull())?.seconds
        val wait = spoken ?: if (noWait) 0 else defaults.delaySeconds
        return CameraAction.Photo(wait.coerceIn(0, MAX_DELAY_SECONDS))
    }

    private val QUANTITY = Regex("(\\d+)(?:\\.\\d+)?(?:\\s+(seconds?|secs?|minutes?|mins?|s|m)\\b)?")

    private fun findQuantities(text: String): List<Quantity> {
        val result = mutableListOf<Quantity>()
        var previousEnd = 0
        for (m in QUANTITY.findAll(text)) {
            val unit = m.groupValues[2]
            val number = m.groupValues[1].toIntOrNull() ?: continue
            val seconds = if (unit.startsWith("m")) number * 60 else number

            val before = text.substring(previousEnd, m.range.first).trim().split(' ').filter { it.isNotEmpty() }
            val nextWord = text.substring(m.range.last + 1).trim().split(' ').firstOrNull().orEmpty()
            val label = labelFor(before.takeLast(3), nextWord)
            previousEnd = m.range.last + 1

            // a bare number with no unit and no timing word ("channel 4") is not a time
            if (unit.isEmpty() && label == Label.NONE) continue
            result += Quantity(seconds, label)
        }
        return result
    }

    private fun labelFor(wordsBefore: List<String>, nextWord: String): Label {
        // "stop after 30 seconds": the stop word right before decides it, even though "after" follows
        if (wordsBefore.takeLast(2).any { it in STOP_LABELS }) return Label.DURATION
        for (w in wordsBefore.asReversed()) {
            if (w in DELAY_LABELS) return Label.DELAY
            if (w in DURATION_LABELS) return Label.DURATION
        }
        if (nextWord == "long") return Label.DURATION             // "30 seconds long"
        return Label.NONE
    }

    // ---------------------------------------------------------------- text clean-up

    private fun normalize(raw: String): String {
        var t = raw.lowercase()
        t = t.replace(Regex("(?<!\\d)\\.|\\.(?!\\d)"), " ")        // full stops, but keep the point in 2.5
        t = t.replace(Regex("[,;:!?\"'()\\-]"), " ")
        t = t.replace(Regex("(\\d)([a-z])"), "$1 $2")              // "5sec" -> "5 sec"
        t = t.replace(Regex("\\bhalf a minute\\b"), "30 seconds")
        t = t.replace(Regex("\\ba minute\\b"), "1 minute")
        t = t.replace(Regex("\\ba second\\b"), "1 second")
        t = numberWordsToDigits(t)
        return t.replace(Regex("\\s+"), " ").trim()
    }

    /** "five" -> "5", "thirty five" -> "35", "one hundred twenty" -> "120" */
    private fun numberWordsToDigits(text: String): String {
        val out = mutableListOf<String>()
        var active = false
        var total = 0
        fun flush() {
            if (active) out += total.toString()
            active = false
            total = 0
        }
        for (token in text.split(' ')) {
            val ones = ONES[token]
            val tens = TENS[token]
            when {
                ones != null -> { total += ones; active = true }
                tens != null -> { total += tens; active = true }
                token == "hundred" && active -> total = (if (total == 0) 1 else total) * 100
                else -> { flush(); out += token }
            }
        }
        flush()
        return out.joinToString(" ")
    }
}
