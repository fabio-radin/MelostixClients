package com.hardrex.melostixclient.tablet.net

import android.content.Context

/** Optional shared password for the handshake with the master (master-slave protocol 1.1.0,
 *  MelostixProtocol) - empty/absent = no password configured on this client, it only connects
 *  to a master that doesn't require one (identical behavior to protocol 1.0.0). Stored in
 *  plain text in SharedPreferences, same principle as AppSettingsStore.getServerPassword on the
 *  app-master side: not meant as a robust cryptographic defense, just a "PIN" gate for a
 *  network already considered trusted - see protocol.md in MelostixProtocol. */
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
