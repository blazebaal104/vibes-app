package dev.brahmkshatriya.echo.vibes.theme

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.utils.ContextUtils.getSettings

/**
 * "Temi Vibes" row at the top of the Look settings: a horizontal strip of theme cards
 * (gradient preview + accent dot + name), a "+" card that opens the theme creator, and a switch
 * for the theme background. Tap applies (the settings listener recreates the activity);
 * long-press a custom theme to edit or delete it.
 */
class ThemePickerPreference(context: Context) : Preference(context) {

    init {
        key = "vibes_theme_picker"
        isSelectable = false
        isIconSpaceReserved = false
        layoutResource = R.layout.preference_vibes_themes
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.itemView.isClickable = false
        val row = holder.findViewById(R.id.themeRow) as LinearLayout
        val bgSwitch = holder.findViewById(R.id.themeBgSwitch) as MaterialSwitch
        render(row)
        bgSwitch.setOnCheckedChangeListener(null)
        bgSwitch.isChecked = VibesThemes.backgroundEnabled(context)
        bgSwitch.setOnCheckedChangeListener { _, checked ->
            context.getSettings().edit().putBoolean(VibesThemes.THEME_BG_KEY, checked).apply()
        }
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    private fun render(row: LinearLayout) {
        row.removeAllViews()
        val currentId = context.getSettings().getString(VibesThemes.THEME_ID_KEY, VibesThemes.DEFAULT_ID)
        VibesThemes.all(context).forEach { theme ->
            row.addView(card(theme, theme.id == currentId))
        }
        row.addView(addCard())
    }

    private fun card(theme: VibesTheme, selected: Boolean): View {
        val card = MaterialCardView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(104), dp(148)).apply { marginEnd = dp(10) }
            radius = dp(16).toFloat()
            cardElevation = 0f
            strokeWidth = if (selected) dp(3) else 0
            strokeColor = theme.accent
            isClickable = true
            isFocusable = true
        }
        val box = FrameLayout(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(theme.bgTop, theme.bgBottom)
            )
        }
        val textColor = if (ColorUtils.calculateLuminance(theme.bgBottom) > 0.5) Color.BLACK else Color.WHITE
        // fake "mini UI": accent pill + two text lines, so the preset reads like a screen
        box.addView(View(context).apply {
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(theme.accent) }
        }, FrameLayout.LayoutParams(dp(56), dp(20)).apply {
            gravity = Gravity.TOP or Gravity.START; topMargin = dp(14); marginStart = dp(12)
        })
        listOf(30, 44).forEachIndexed { i, w ->
            box.addView(View(context).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(3).toFloat()
                    setColor(ColorUtils.setAlphaComponent(textColor, if (i == 0) 150 else 90))
                }
            }, FrameLayout.LayoutParams(dp(w + 20), dp(6)).apply {
                gravity = Gravity.TOP or Gravity.START; topMargin = dp(46 + i * 12); marginStart = dp(12)
            })
        }
        box.addView(TextView(context).apply {
            text = theme.name
            setTextColor(textColor)
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.START; marginStart = dp(12); bottomMargin = dp(12)
        })
        if (selected) box.addView(TextView(context).apply {
            text = "✓"
            gravity = Gravity.CENTER
            setTextColor(if (ColorUtils.calculateLuminance(theme.accent) > 0.5) Color.BLACK else Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(theme.accent) }
        }, FrameLayout.LayoutParams(dp(22), dp(22)).apply {
            gravity = Gravity.TOP or Gravity.END; topMargin = dp(10); marginEnd = dp(10)
        })
        card.addView(box)
        card.setOnClickListener {
            it.animate().scaleX(0.94f).scaleY(0.94f).setDuration(90).withEndAction {
                it.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                VibesThemes.apply(context.getSettings(), theme)
            }.start()
        }
        if (!theme.builtin) card.setOnLongClickListener {
            MaterialAlertDialogBuilder(context)
                .setTitle(theme.name)
                .setItems(arrayOf("Modifica", "Elimina")) { _, which ->
                    if (which == 0) openEditor(theme) else {
                        VibesThemes.deleteCustom(context, theme.id)
                        notifyChanged()
                    }
                }.show()
            true
        }
        return card
    }

    private fun addCard(): View = MaterialCardView(context).apply {
        layoutParams = LinearLayout.LayoutParams(dp(104), dp(148))
        radius = dp(16).toFloat()
        cardElevation = 0f
        strokeWidth = dp(2)
        strokeColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant)
        setCardBackgroundColor(Color.TRANSPARENT)
        addView(TextView(context).apply {
            text = "+\nCrea tema"
            gravity = Gravity.CENTER
            textSize = 15f
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setOnClickListener { openEditor(null) }
    }

    // ------------------------------------------------------------------ theme creator

    private val palette = listOf(
        "#FF2E97", "#FF4D4D", "#FF7A59", "#FFB020", "#FFE14D", "#B5E61D", "#1ED760", "#00C2A8",
        "#35D0F5", "#3B82F6", "#6366F1", "#B84DFF", "#E879F9", "#C2410C", "#8D6E63", "#FFFFFF",
    ).map { Color.parseColor(it) }

    private val bgPalette = listOf(
        "#000000", "#0A0014", "#12051C", "#1A0B1E", "#04121F", "#0B1A10", "#1C1C1C", "#2B1B0E",
        "#4A1670", "#6B1F4F", "#0B4062", "#0F3D24", "#3A0CA3", "#7A1C1C", "#F6DDB8", "#FFF8EE",
    ).map { Color.parseColor(it) }

    private fun openEditor(editing: VibesTheme?) {
        val base = editing ?: VibesThemes.current(context) ?: VibesThemes.presets.first()
        var accent = base.accent
        var top = base.bgTop
        var bottom = base.bgBottom
        var dark = base.dark
        var amoled = base.amoled

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val preview = View(context)
        val accentDot = View(context)
        fun refresh() {
            preview.background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom)
            ).apply { cornerRadius = dp(16).toFloat() }
            accentDot.background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(accent) }
        }
        root.addView(FrameLayout(context).apply {
            addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(110)))
            addView(accentDot, FrameLayout.LayoutParams(dp(90), dp(26)).apply {
                gravity = Gravity.CENTER
            })
        })

        val name = EditText(context).apply {
            hint = "Nome del tema"
            setText(editing?.name ?: "")
            setSingleLine()
        }
        root.addView(name)

        fun label(t: String) = TextView(context).apply {
            text = t; textSize = 13f; setPadding(0, dp(14), 0, dp(6))
            setTextColor(MaterialColors.getColor(root, com.google.android.material.R.attr.colorOnSurfaceVariant))
        }

        fun swatchRow(colors: List<Int>, selected: () -> Int, onPick: (Int) -> Unit): View {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            fun paint() {
                for (i in 0 until row.childCount) {
                    val v = row.getChildAt(i); val col = colors[i]
                    v.background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL; setColor(col)
                        val ring = if (col == selected()) MaterialColors.getColor(root, androidx.appcompat.R.attr.colorPrimary)
                        else ColorUtils.setAlphaComponent(Color.GRAY, 90)
                        setStroke(if (col == selected()) dp(3) else dp(1), ring)
                    }
                }
            }
            colors.forEach { col ->
                row.addView(View(context).apply {
                    setOnClickListener { onPick(col); paint(); refresh() }
                }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(8) })
            }
            paint()
            return HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(row)
            }
        }

        fun hexField(get: () -> Int, set: (Int) -> Unit) = EditText(context).apply {
            hint = "#RRGGBB"
            setText(String.format("#%06X", 0xFFFFFF and get()))
            setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    runCatching { Color.parseColor(s.toString().trim()) }.getOrNull()?.let { set(it); refresh() }
                }
            })
        }

        root.addView(label("Colore principale"))
        root.addView(swatchRow(palette, { accent }) { accent = it })
        root.addView(hexField({ accent }) { accent = it })
        root.addView(label("Sfondo – sopra"))
        root.addView(swatchRow(bgPalette, { top }) { top = it })
        root.addView(hexField({ top }) { top = it })
        root.addView(label("Sfondo – sotto"))
        root.addView(swatchRow(bgPalette, { bottom }) { bottom = it })
        root.addView(hexField({ bottom }) { bottom = it })

        root.addView(label("Base"))
        val modes = MaterialButtonToggleGroup(context).apply {
            isSingleSelection = true; isSelectionRequired = true
        }
        val ids = listOf("Chiaro", "Scuro", "Nero").map { t ->
            com.google.android.material.button.MaterialButton(
                context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply { text = t; id = View.generateViewId() }.also { modes.addView(it) }.id
        }
        modes.check(if (!dark) ids[0] else if (amoled) ids[2] else ids[1])
        modes.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            dark = id != ids[0]; amoled = id == ids[2]
        }
        root.addView(modes)
        refresh()

        MaterialAlertDialogBuilder(context)
            .setTitle(if (editing == null) "Crea tema" else "Modifica tema")
            .setView(android.widget.ScrollView(context).apply { addView(root) })
            .setPositiveButton("Salva e usa") { _, _ ->
                val theme = VibesTheme(
                    id = editing?.id ?: "custom_${System.currentTimeMillis()}",
                    name = name.text.toString().trim().ifEmpty { "Il mio tema" },
                    accent = accent, dark = dark, amoled = amoled, bgTop = top, bgBottom = bottom,
                )
                VibesThemes.saveCustom(context, theme)
                VibesThemes.apply(context.getSettings(), theme)
                notifyChanged()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    @Suppress("unused")
    private fun tint(v: View, c: Int) { v.backgroundTintList = ColorStateList.valueOf(c) }
}
