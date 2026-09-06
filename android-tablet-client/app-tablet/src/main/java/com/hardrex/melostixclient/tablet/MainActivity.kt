package com.hardrex.melostixclient.tablet

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.hardrex.melostixclient.tablet.net.ClientSettings
import com.hardrex.melostixclient.tablet.net.MelostixClient
import com.hardrex.melostixclient.tablet.net.MelostixClientListener
import com.hardrex.melostixclient.tablet.net.LyricsUpdate

/**
 * Client for a generic Android 4.4.4 tablet (800x480 display, landscape): connects to the
 * app-master over the local network (automatic discovery via UDP broadcast, see net/) and
 * shows fullscreen the 3 lines of text (previous/current/next) around the playback position.
 * No proprietary SDK, no backlight control panel (a normal tablet already exposes its own
 * brightness control at the system level).
 */
class MainActivity : Activity(), MelostixClientListener {

    private companion object {
        private const val DIM_COLOR = 0xFF9E9E9E.toInt()
    }

    private val melostixClient = MelostixClient(this, passwordProvider = { ClientSettings.getPassword(this) })

    private lateinit var previousText: TextView
    private lateinit var currentText: TextView
    private lateinit var nextText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
        )
        window.setFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
        )

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(buildLyricsView())
            val settingsPanel = buildSettingsPanel()
            addView(settingsPanel)
            addView(buildSettingsToggle(settingsPanel))
        }

        setContentView(root)

        showStatusMessage("Waiting for master...")
    }

    override fun onStart() {
        super.onStart()
        melostixClient.start()
    }

    override fun onStop() {
        super.onStop()
        melostixClient.stop()
    }

    private fun buildLyricsView(): View {
        previousText = lyricLine(dimmed = true)
        currentText = lyricLine(dimmed = false)
        nextText = lyricLine(dimmed = true)

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 0, 48, 0)
            addView(previousText)
            addView(currentText)
            addView(nextText)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
    }

    // Sizes not yet verified on a real device: a reasonable starting point for an 800x480
    // display viewed from a normal distance, to be refined once the reference tablet is
    // available for a real test.
    private fun lyricLine(dimmed: Boolean): TextView = TextView(this).apply {
        setTextColor(if (dimmed) DIM_COLOR else Color.WHITE)
        textSize = if (dimmed) 20f else 28f
        gravity = Gravity.CENTER
        setPadding(0, 12, 0, 12)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
    }

    /** A single text message in the center (prev/next empty): used for states other than
     *  "lyrics synced during playback" (waiting for connection, loading, no lyrics found,
     *  ...). */
    private fun showStatusMessage(message: String) {
        previousText.text = ""
        currentText.text = message
        nextText.text = ""
    }

    /** Settings panel (only the master password for now, protocol 1.1.0 - MelostixProtocol):
     *  hidden by default, opens/closes with a tap on buildSettingsToggle's three-dot icon. No
     *  brightness entry: a normal tablet already exposes its own brightness control at the
     *  system level. */
    private fun buildSettingsPanel(): View {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Master password (optional)"
            setText(ClientSettings.getPassword(this@MainActivity).orEmpty())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val saveButton = Button(this).apply {
            text = "Save"
            setOnClickListener {
                ClientSettings.setPassword(this@MainActivity, input.text.toString())
                Toast.makeText(this@MainActivity, "Password saved", Toast.LENGTH_SHORT).show()
            }
        }
        val passwordRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(input)
            addView(saveButton)
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Dark gray background (not black): reads clearly as a control panel, distinct
            // from the rest of the overlay, which must instead stay black/invisible.
            setBackgroundColor(Color.DKGRAY)
            setPadding(16, 16, 16, 16)
            visibility = View.GONE
            addView(passwordRow)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            )
        }
    }

    /** Tiny "three vertical dots" icon in a corner, so as not to disturb reading the lyrics:
     *  the only way to open/close [buildSettingsPanel]. */
    private fun buildSettingsToggle(panel: View): View = TextView(this).apply {
        text = "⋮"
        setTextColor(DIM_COLOR)
        textSize = 20f
        setPadding(24, 24, 24, 24)
        setOnClickListener {
            panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.RIGHT,
        )
    }

    override fun onUpdate(update: LyricsUpdate) {
        when (update.status) {
            "synced" -> {
                previousText.text = update.previous.orEmpty()
                currentText.text = update.current.orEmpty()
                nextText.text = update.next.orEmpty()
            }

            "loading" -> showStatusMessage("Searching lyrics for \"${update.title}\"...")
            "plain" -> showStatusMessage("${update.title} - ${update.artist}\n(lyrics not synced)")
            "not_found" -> showStatusMessage("Lyrics not found for \"${update.title}\"")
            "error" -> showStatusMessage("Lyrics search error")
            else -> showStatusMessage(
                if (update.title != null) "${update.title} - ${update.artist}" else "No track playing",
            )
        }
    }

    override fun onConnectionStateChanged(connected: Boolean) {
        if (!connected) showStatusMessage("Lost connection to master, searching again...")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // "Sticky immersive" (hides the status/navigation bar, comes back with a swipe
            // and closes again on its own): minSdk 19 guarantees it from the start.
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        }
    }
}
