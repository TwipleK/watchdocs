package com.watchdocs.wear

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** App settings, observable from Compose and saved across restarts. */
object Settings {
    private var prefs: SharedPreferences? = null

    /** In the PDF/image viewer: true = turning the crown zooms, false = it scrolls. */
    var crownZooms by mutableStateOf(false)
        private set

    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs = p
        crownZooms = p.getBoolean("crown_zooms", false)
    }

    fun updateCrownZooms(value: Boolean) {
        crownZooms = value
        prefs?.edit()?.putBoolean("crown_zooms", value)?.apply()
    }
}
