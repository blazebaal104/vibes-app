package dev.brahmkshatriya.echo.vibes.theme

import android.content.Context
import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import androidx.core.content.edit
import dev.brahmkshatriya.echo.MainActivity.Companion.AMOLED_KEY
import dev.brahmkshatriya.echo.MainActivity.Companion.COLOR_KEY
import dev.brahmkshatriya.echo.MainActivity.Companion.CUSTOM_THEME_KEY
import dev.brahmkshatriya.echo.MainActivity.Companion.THEME_KEY
import dev.brahmkshatriya.echo.utils.ContextUtils.getSettings
import org.json.JSONArray
import org.json.JSONObject

/**
 * Vibes colour themes.
 *
 * A theme is an accent (the Material 3 palette is generated from it by the existing dynamic-colour
 * code in MainActivity.applyUiChanges), a light/dark/AMOLED base, and a two-colour background
 * gradient painted behind the main screens (UiViewModel.applyGradient).
 *
 * Applying a theme only writes the settings keys the app already understands
 * (THEME_KEY / CUSTOM_THEME_KEY / COLOR_KEY / AMOLED_KEY) plus the Vibes keys below, so every
 * existing screen picks it up without changes.
 */
data class VibesTheme(
    val id: String,
    val name: String,
    val accent: Int,
    val dark: Boolean,
    val amoled: Boolean,
    val bgTop: Int,
    val bgBottom: Int,
    val builtin: Boolean = false,
) {
    fun gradient(): Drawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(bgTop, bgBottom)
    ).apply {
        // A real size, so the blur step (which rasterises the drawable) keeps the gradient.
        setSize(64, 160)
    }

    fun toJson() = JSONObject()
        .put("id", id).put("name", name).put("accent", accent)
        .put("dark", dark).put("amoled", amoled)
        .put("bgTop", bgTop).put("bgBottom", bgBottom)

    companion object {
        fun fromJson(o: JSONObject) = VibesTheme(
            o.getString("id"), o.getString("name"), o.getInt("accent"),
            o.getBoolean("dark"), o.optBoolean("amoled"),
            o.getInt("bgTop"), o.getInt("bgBottom"), false
        )
    }
}

object VibesThemes {
    const val THEME_ID_KEY = "vibes_theme_id"
    const val CUSTOM_THEMES_KEY = "vibes_custom_themes"
    const val THEME_BG_KEY = "vibes_theme_background"
    const val DEFAULT_ID = "vibes"

    private fun c(hex: String) = android.graphics.Color.parseColor(hex)

    val presets = listOf(
        VibesTheme("vibes", "Vibes", c("#B84DFF"), true, false, c("#4A1670"), c("#12051C"), true),
        VibesTheme("mezzanotte", "Mezzanotte", c("#1ED760"), true, true, c("#0F3D24"), c("#000000"), true),
        VibesTheme("tramonto", "Tramonto", c("#FF7A59"), true, false, c("#6B1F4F"), c("#1A0B1E"), true),
        VibesTheme("oceano", "Oceano", c("#35D0F5"), true, false, c("#0B4062"), c("#04121F"), true),
        VibesTheme("neon", "Neon", c("#FF2E97"), true, true, c("#3A0CA3"), c("#0A0014"), true),
        VibesTheme("sabbia", "Sabbia", c("#C2410C"), false, false, c("#F6DDB8"), c("#FFF8EE"), true),
    )

    fun custom(context: Context): List<VibesTheme> = runCatching {
        val arr = JSONArray(context.getSettings().getString(CUSTOM_THEMES_KEY, "[]"))
        (0 until arr.length()).map { VibesTheme.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun all(context: Context) = presets + custom(context)

    fun current(context: Context): VibesTheme? {
        val id = context.getSettings().getString(THEME_ID_KEY, null) ?: return null
        return all(context).firstOrNull { it.id == id }
    }

    /** True when the user wants the theme background behind the screens (default on). */
    fun backgroundEnabled(context: Context) = context.getSettings().getBoolean(THEME_BG_KEY, true)

    /**
     * True while [apply] writes its keys: the Look settings listener recreates the activity on each
     * colour key, so it skips them during an apply and recreates once, on [THEME_ID_KEY].
     * (commit = true makes the listener calls synchronous on the main thread, inside this window.)
     */
    @Volatile
    var applying = false
        private set

    fun apply(prefs: SharedPreferences, theme: VibesTheme) {
        applying = true
        try {
            prefs.edit(commit = true) {
                putString(THEME_KEY, if (theme.dark) "dark" else "light")
                putBoolean(CUSTOM_THEME_KEY, true)
                putInt(COLOR_KEY, theme.accent)
                putBoolean(AMOLED_KEY, theme.amoled && theme.dark)
            }
            // written last and on its own, so it's the one change that triggers the recreate
            prefs.edit(commit = true) { remove(THEME_ID_KEY) }
        } finally {
            applying = false
        }
        prefs.edit(commit = true) { putString(THEME_ID_KEY, theme.id) }
    }

    fun saveCustom(context: Context, theme: VibesTheme) {
        val list = custom(context).filter { it.id != theme.id } + theme
        context.getSettings().edit {
            putString(CUSTOM_THEMES_KEY, JSONArray(list.map { it.toJson() }).toString())
        }
    }

    fun deleteCustom(context: Context, id: String) {
        val prefs = context.getSettings()
        val list = custom(context).filter { it.id != id }
        val wasActive = prefs.getString(THEME_ID_KEY, null) == id
        prefs.edit(commit = true) {
            putString(CUSTOM_THEMES_KEY, JSONArray(list.map { it.toJson() }).toString())
        }
        if (wasActive) apply(prefs, presets.first())
    }

    /**
     * First launch: apply the default Vibes theme once, so a fresh install already looks like Vibes
     * instead of Gladix's defaults. Never overrides a choice the user made.
     */
    fun ensureDefault(context: Context) {
        val prefs = context.getSettings()
        if (prefs.contains(THEME_ID_KEY)) return
        apply(prefs, presets.first())
    }
}
