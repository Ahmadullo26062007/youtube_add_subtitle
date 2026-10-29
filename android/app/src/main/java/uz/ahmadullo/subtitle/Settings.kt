package uz.ahmadullo.subtitle

import android.content.Context
import android.content.SharedPreferences

// Kengaytmadagi DEFAULT_SETTINGS'ga o'xshash, lekin pozitsiya portrait va
// landscape uchun alohida saqlanadi.
class Settings(context: Context) {

    val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Shrift — video balandligiga nisbatan % */
    var fontSize: Float
        get() = prefs.getFloat(KEY_FONT, 5.5f)
        set(v) = prefs.edit().putFloat(KEY_FONT, v).apply()

    /** Fon shaffofligi, 0 = fon yo'q */
    var bgOpacity: Int
        get() = prefs.getInt(KEY_BG, 60)
        set(v) = prefs.edit().putInt(KEY_BG, v).apply()

    /** Landscape: ekran pastidan, ekran balandligiga nisbatan % */
    var bottomLandscape: Float
        get() = prefs.getFloat(KEY_BOTTOM_LAND, 8f)
        set(v) = prefs.edit().putFloat(KEY_BOTTOM_LAND, v).apply()

    /** Portrait: video maydoni pastidan, video balandligiga nisbatan % */
    var bottomPortrait: Float
        get() = prefs.getFloat(KEY_BOTTOM_PORT, 6f)
        set(v) = prefs.edit().putFloat(KEY_BOTTOM_PORT, v).apply()

    /** Overlay vaqtincha yashirilganmi (notification tugmasi) */
    var hidden: Boolean
        get() = prefs.getBoolean(KEY_HIDDEN, false)
        set(v) = prefs.edit().putBoolean(KEY_HIDDEN, v).apply()

    companion object {
        const val PREFS = "settings"
        const val KEY_FONT = "font_size"
        const val KEY_BG = "bg_opacity"
        const val KEY_BOTTOM_LAND = "bottom_landscape"
        const val KEY_BOTTOM_PORT = "bottom_portrait"
        const val KEY_HIDDEN = "hidden"
    }
}
