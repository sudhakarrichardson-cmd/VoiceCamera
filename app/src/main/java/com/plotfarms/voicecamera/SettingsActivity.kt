package com.plotfarms.voicecamera

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * The defaults used when a spoken sentence has no time in it: the wait before a video or photo starts,
 * and how long a video records. Saved as soon as they change.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var store: SettingsStore
    private lateinit var waitInput: EditText
    private lateinit var lengthInput: EditText
    private lateinit var summary: TextView
    private lateinit var waitPresets: LinearLayout
    private lateinit var lengthPresets: LinearLayout
    private var programmaticChange = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0).padForSystemBars()
        store = SettingsStore(this)

        waitInput = findViewById(R.id.waitInput)
        lengthInput = findViewById(R.id.lengthInput)
        summary = findViewById(R.id.summary)
        waitPresets = findViewById(R.id.waitPresets)
        lengthPresets = findViewById(R.id.lengthPresets)

        buildPresets(waitPresets, WAIT_PRESETS) { setWait(it) }
        buildPresets(lengthPresets, LENGTH_PRESETS) { setLength(it) }

        findViewById<Button>(R.id.waitMinus).setOnClickListener { setWait(store.delaySeconds - 1) }
        findViewById<Button>(R.id.waitPlus).setOnClickListener { setWait(store.delaySeconds + 1) }
        findViewById<Button>(R.id.lengthMinus).setOnClickListener { setLength(store.durationSeconds - 5) }
        findViewById<Button>(R.id.lengthPlus).setOnClickListener { setLength(store.durationSeconds + 5) }

        waitInput.addTextChangedListener(watcher { value -> store.delaySeconds = value })
        lengthInput.addTextChangedListener(watcher { value -> store.durationSeconds = value })

        findViewById<Button>(R.id.btnReset).setOnClickListener {
            store.reset()
            showValues()
        }
        findViewById<Button>(R.id.btnDone).setOnClickListener { finish() }

        showValues()
    }

    override fun onPause() {
        super.onPause()
        tidyInputs()
    }

    // ------------------------------------------------------------------ changing values

    private fun setWait(seconds: Int) {
        store.delaySeconds = seconds
        showValues()
    }

    private fun setLength(seconds: Int) {
        store.durationSeconds = seconds
        showValues()
    }

    /** Typing a number saves it straight away; empty or half-typed input is left alone until the person is done. */
    private fun watcher(save: (Int) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            if (programmaticChange) return
            val value = s?.toString()?.toIntOrNull() ?: return
            save(value)
            highlightPresets()
            updateSummary()
        }
    }

    /** Put the saved (and corrected, e.g. 9999 -> 600) values back into the boxes. */
    private fun showValues() {
        programmaticChange = true
        waitInput.setText(store.delaySeconds.toString())
        lengthInput.setText(store.durationSeconds.toString())
        waitInput.setSelection(waitInput.text.length)
        lengthInput.setSelection(lengthInput.text.length)
        programmaticChange = false
        highlightPresets()
        updateSummary()
    }

    private fun tidyInputs() {
        showValues()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(waitInput.windowToken, 0)
    }

    // ------------------------------------------------------------------ preset buttons

    private fun buildPresets(container: LinearLayout, values: IntArray, onPick: (Int) -> Unit) {
        container.removeAllViews()
        for (value in values) {
            val button = Button(this).apply {
                text = if (value >= 60 && value % 60 == 0) "${value / 60} min" else "$value s"
                tag = value
                textSize = 13f
                isAllCaps = false
                minWidth = 0
                minimumWidth = 0
                setPadding(0, 0, 0, 0)
                setOnClickListener { onPick(value) }
            }
            container.addView(button, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(6) })
        }
    }

    private fun highlightPresets() {
        paint(waitPresets, store.delaySeconds)
        paint(lengthPresets, store.durationSeconds)
    }

    private fun paint(container: LinearLayout, selected: Int) {
        for (i in 0 until container.childCount) {
            val button = container.getChildAt(i) as Button
            val on = button.tag == selected
            button.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(if (on) R.color.gold else R.color.surface2))
            button.setTextColor(getColor(if (on) R.color.black else R.color.white))
        }
    }

    private fun updateSummary() {
        val wait = store.delaySeconds
        val length = store.durationSeconds
        val waitText = if (wait == 0) "starts right away" else "waits $wait s, then starts"
        summary.text = "“Record video” $waitText and records for $length s.\n" +
            "“Take a photo” " + (if (wait == 0) "is taken right away." else "waits $wait s, then shoots.")
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private val WAIT_PRESETS = intArrayOf(0, 3, 5, 10, 15)
        private val LENGTH_PRESETS = intArrayOf(10, 15, 30, 60, 120)
    }
}
