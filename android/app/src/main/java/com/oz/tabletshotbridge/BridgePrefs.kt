package com.oz.tabletshotbridge

import android.content.Context

data class SavedBridge(
    val url: String,
    val token: String,
    val deviceName: String,
)

class BridgePrefs(context: Context) {
    private val prefs = context.getSharedPreferences("bridge", Context.MODE_PRIVATE)

    fun save(url: String, token: String, deviceName: String) {
        prefs.edit()
            .putString("url", url)
            .putString("token", token)
            .putString("deviceName", deviceName)
            .apply()
    }

    fun load(): SavedBridge? {
        val url = prefs.getString("url", null) ?: return null
        val token = prefs.getString("token", null) ?: return null
        val deviceName = prefs.getString("deviceName", "小米平板") ?: "小米平板"
        return SavedBridge(url, token, deviceName)
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
