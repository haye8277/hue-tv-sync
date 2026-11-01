package com.kneejar.tvhue

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("tvhue", Context.MODE_PRIVATE)

    var bridgeIp: String?
        get() = sp.getString("bridgeIp", null)
        set(v) = sp.edit().putString("bridgeIp", v).apply()

    var username: String?
        get() = sp.getString("username", null)
        set(v) = sp.edit().putString("username", v).apply()

    var clientKey: String?
        get() = sp.getString("clientKey", null)
        set(v) = sp.edit().putString("clientKey", v).apply()

    var areaId: String?
        get() = sp.getString("areaId", null)
        set(v) = sp.edit().putString("areaId", v).apply()

    var areaName: String?
        get() = sp.getString("areaName", null)
        set(v) = sp.edit().putString("areaName", v).apply()

    /** 0 = low, 1 = medium, 2 = high. */
    var brightness: Int
        get() = sp.getInt("brightness", 2).coerceIn(0, 2)
        set(v) = sp.edit().putInt("brightness", v.coerceIn(0, 2)).apply()

    val brightnessFactor: Float get() = BRIGHTNESS_FACTORS[brightness]
    val brightnessName: String get() = BRIGHTNESS_NAMES[brightness]

    val isPaired: Boolean get() = bridgeIp != null && username != null && clientKey != null

    fun clearSetup() {
        sp.edit()
            .remove("bridgeIp").remove("username").remove("clientKey")
            .remove("areaId").remove("areaName")
            .apply()
    }

    companion object {
        private val BRIGHTNESS_FACTORS = floatArrayOf(0.35f, 0.65f, 1f)
        private val BRIGHTNESS_NAMES = arrayOf("Low", "Medium", "High")
    }
}
