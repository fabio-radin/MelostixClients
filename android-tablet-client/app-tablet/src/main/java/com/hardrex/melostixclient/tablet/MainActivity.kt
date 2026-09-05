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
 * Client per un tablet Android 4.4.4 generico (display 800x480, landscape): si connette
 * all'app-master sulla rete locale (scoperta automatica via broadcast UDP, vedi net/) e mostra
 * fullscreen le 3 righe di testo (precedente/corrente/successiva) attorno alla posizione di
 * riproduzione. Niente SDK proprietario, niente pannello di controllo backlight (un tablet
 * normale espone gia' la propria regolazione luminosita' a livello di sistema).
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

        showStatusMessage("In attesa del master...")
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

    // Dimensioni non ancora verificate su device reale: punto di partenza ragionevole per un
    // display 800x480 visto a distanza normale, da rifinire quando il tablet di riferimento
    // sara' disponibile per un test reale.
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

    /** Un solo messaggio testuale al centro (prev/next vuote): usato per gli stati che non
     *  sono "testo sincronizzato in riproduzione" (in attesa di connessione, caricamento,
     *  nessun testo trovato, ...). */
    private fun showStatusMessage(message: String) {
        previousText.text = ""
        currentText.text = message
        nextText.text = ""
    }

    /** Pannello impostazioni (solo password del master per ora, protocollo 1.1.0 -
     *  MelostixProtocol): nascosto di default, si apre/chiude con il tocco sull'icona a tre
     *  puntini di buildSettingsToggle. Nessuna voce di luminosita': un tablet normale espone
     *  gia' la propria regolazione luminosita' a livello di sistema. */
    private fun buildSettingsPanel(): View {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Password master (opzionale)"
            setText(ClientSettings.getPassword(this@MainActivity).orEmpty())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val saveButton = Button(this).apply {
            text = "Salva"
            setOnClickListener {
                ClientSettings.setPassword(this@MainActivity, input.text.toString())
                Toast.makeText(this@MainActivity, "Password salvata", Toast.LENGTH_SHORT).show()
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
            // Sfondo grigio scuro (non nero): si vede chiaramente come pannello di controllo,
            // distinto dal resto dell'overlay che invece deve restare nero/invisibile.
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

    /** Icona "tre puntini verticali" minuscola in un angolo, per non disturbare la lettura del
     *  testo: unico modo per aprire/chiudere [buildSettingsPanel]. */
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

            "loading" -> showStatusMessage("Ricerca testo per \"${update.title}\"...")
            "plain" -> showStatusMessage("${update.title} - ${update.artist}\n(testo non sincronizzato)")
            "not_found" -> showStatusMessage("Testo non trovato per \"${update.title}\"")
            "error" -> showStatusMessage("Errore ricerca testo")
            else -> showStatusMessage(
                if (update.title != null) "${update.title} - ${update.artist}" else "Nessun brano in riproduzione",
            )
        }
    }

    override fun onConnectionStateChanged(connected: Boolean) {
        if (!connected) showStatusMessage("Connessione al master persa, ricerco di nuovo...")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // "Sticky immersive" (nasconde barra di stato/navigazione, torna visibile con uno
            // swipe e si richiude da sola): minSdk 19 lo garantisce da subito.
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
