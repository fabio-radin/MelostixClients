package com.hardrex.melostixclient.tablet.net

import android.content.Context

/** Password condivisa opzionale per l'handshake col master (protocollo master-slave 1.1.0,
 *  MelostixProtocol) - vuota/assente = nessuna password configurata su questo client, si
 *  connette solo a un master che non la richiede (comportamento identico al protocollo 1.0.0).
 *  Salvata in chiaro in SharedPreferences, stesso principio di
 *  AppSettingsStore.getServerPassword lato app-master: non e' pensata come difesa crittografica
 *  robusta, solo un gate "PIN" per una rete gia' considerata fidata - vedi protocol.md in
 *  MelostixProtocol. */
object ClientSettings {
    private const val PREFS_NAME = "melostix_client_settings"
    private const val KEY_PASSWORD = "master_password"

    fun getPassword(context: Context): String? {
        val value = prefs(context).getString(KEY_PASSWORD, null)
        return if (value.isNullOrEmpty()) null else value
    }

    fun setPassword(context: Context, password: String) {
        prefs(context).edit().putString(KEY_PASSWORD, password).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
