package io.nekohasekai.sagernet.utils

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.app

object Theme {

    const val RED = 1
    const val PINK_SSR = 2
    const val PINK = 3
    const val PURPLE = 4
    const val DEEP_PURPLE = 5
    const val INDIGO = 6
    const val BLUE = 7
    const val LIGHT_BLUE = 8
    const val CYAN = 9
    const val TEAL = 10
    const val GREEN = 11
    const val LIGHT_GREEN = 12
    const val LIME = 13
    const val YELLOW = 14
    const val AMBER = 15
    const val ORANGE = 16
    const val DEEP_ORANGE = 17
    const val BROWN = 18
    const val GREY = 19
    const val BLUE_GREY = 20
    const val BLACK = 21
    const val VERDANT_MINT = 22
    const val DRACULA = 23
    const val DYNAMIC = 24
    const val DARK_HIGH_CONTRAST = 25
    const val DRACULA_M3 = 26
    const val NORD = 27
    const val MONOKAI = 28
    const val AYU = 29
    const val CATPPUCCIN = 30

    /**
     * Themes that only make sense in dark mode: selecting one forces night mode
     * on so its dark canvas (values-night) takes effect, and the prior night
     * setting is restored on exit (see SettingsPreferenceFragment). Dracula was
     * the first such theme; the modern M3 themes are dark-only too.
     */
    val DARK_ONLY_THEMES = setOf(
        DRACULA,
        DARK_HIGH_CONTRAST,
        DRACULA_M3,
        NORD,
        MONOKAI,
        AYU,
        CATPPUCCIN,
    )

    private fun defaultTheme() = DARK_HIGH_CONTRAST

    /**
     * Metadata for a theme shown in the modern named picker.
     *
     * @param id          one of the Theme int constants above (persisted to appTheme)
     * @param nameRes     display name string resource
     * @param previewColor fill color for the preview swatch shown next to the name
     * @param ringColor   optional circumference-ring color; when non-zero the swatch
     *                    is drawn as [previewColor] fill + a thin [ringColor] frame.
     *                    Used by Dark High Contrast (black fill + white ring) so its
     *                    OLED-black identity doesn't read as "a green theme".
     */
    data class ThemeInfo(
        val id: Int,
        val nameRes: Int,
        val previewColor: Int,
        val ringColor: Int = 0,
    )

    /**
     * Modern, full-fledged M3 themes presented by name in the picker dialog.
     * The legacy single-accent palettes stay behind the "Legacy colors…" grid.
     * Order here is the display order in the dialog.
     */
    val MODERN_THEMES: List<ThemeInfo> = listOf(
        ThemeInfo(
            DARK_HIGH_CONTRAST,
            R.string.theme_dark_high_contrast,
            R.color.dhc_background,
            R.color.white,
        ),
        ThemeInfo(DRACULA_M3, R.string.theme_dracula_m3, R.color.draculam3_primary),
        ThemeInfo(NORD, R.string.theme_nord, R.color.nord_primary),
        ThemeInfo(MONOKAI, R.string.theme_monokai, R.color.monokai_primary),
        ThemeInfo(AYU, R.string.theme_ayu, R.color.ayu_primary),
        ThemeInfo(CATPPUCCIN, R.string.theme_catppuccin, R.color.catppuccin_primary),
        ThemeInfo(DYNAMIC, R.string.theme_dynamic, R.color.color_dynamic_swatch),
    )

    /**
     * Themes a context: the structural base (app or dialog window), the shared
     * overlay with every role and semantic default, then the palette. Layering at
     * runtime keeps each palette written once for both window kinds. The base is
     * re-applied explicitly because setTheme is a no-op when the base is unchanged,
     * and a palette switch must not keep values a previous palette set.
     */
    fun apply(context: Context, theme: Int = DataStore.appTheme, dialog: Boolean = false) {
        val base = if (dialog) R.style.Theme_SagerNet_Dialog else R.style.Theme_SagerNet
        context.setTheme(base)
        context.theme.apply {
            applyStyle(base, true)
            applyStyle(R.style.ThemeOverlay_SagerNet_Common, true)
            applyStyle(palette(theme), true)
            // The dark schemes paint the app canvas via windowBackground; a dialog keeps its frame.
            if (dialog) applyStyle(R.style.ThemeOverlay_SagerNet_DialogWindow, true)
        }
    }

    fun applyDialog(context: Context) = apply(context, dialog = true)

    fun palette(theme: Int): Int = when (theme) {
        RED -> R.style.ThemeOverlay_SagerNet_Palette_Red
        PINK -> R.style.ThemeOverlay_SagerNet_Palette_Pink
        PINK_SSR -> R.style.ThemeOverlay_SagerNet_Palette_Pink_SSR
        PURPLE -> R.style.ThemeOverlay_SagerNet_Palette_Purple
        DEEP_PURPLE -> R.style.ThemeOverlay_SagerNet_Palette_DeepPurple
        INDIGO -> R.style.ThemeOverlay_SagerNet_Palette_Indigo
        BLUE -> R.style.ThemeOverlay_SagerNet_Palette_Blue
        LIGHT_BLUE -> R.style.ThemeOverlay_SagerNet_Palette_LightBlue
        CYAN -> R.style.ThemeOverlay_SagerNet_Palette_Cyan
        TEAL -> R.style.ThemeOverlay_SagerNet_Palette_Teal
        GREEN -> R.style.ThemeOverlay_SagerNet_Palette_Green
        LIGHT_GREEN -> R.style.ThemeOverlay_SagerNet_Palette_LightGreen
        LIME -> R.style.ThemeOverlay_SagerNet_Palette_Lime
        YELLOW -> R.style.ThemeOverlay_SagerNet_Palette_Yellow
        AMBER -> R.style.ThemeOverlay_SagerNet_Palette_Amber
        ORANGE -> R.style.ThemeOverlay_SagerNet_Palette_Orange
        DEEP_ORANGE -> R.style.ThemeOverlay_SagerNet_Palette_DeepOrange
        BROWN -> R.style.ThemeOverlay_SagerNet_Palette_Brown
        GREY -> R.style.ThemeOverlay_SagerNet_Palette_Grey
        BLUE_GREY -> R.style.ThemeOverlay_SagerNet_Palette_BlueGrey
        BLACK -> R.style.ThemeOverlay_SagerNet_Palette_Black
        VERDANT_MINT -> R.style.ThemeOverlay_SagerNet_Palette_VerdantMint
        // The legacy Dracula id keeps working; it applies the same official palette.
        DRACULA, DRACULA_M3 -> R.style.ThemeOverlay_SagerNet_Palette_DraculaM3
        DARK_HIGH_CONTRAST -> R.style.ThemeOverlay_SagerNet_Palette_DarkHighContrast
        NORD -> R.style.ThemeOverlay_SagerNet_Palette_Nord
        MONOKAI -> R.style.ThemeOverlay_SagerNet_Palette_Monokai
        AYU -> R.style.ThemeOverlay_SagerNet_Palette_Ayu
        CATPPUCCIN -> R.style.ThemeOverlay_SagerNet_Palette_Catppuccin
        DYNAMIC -> R.style.ThemeOverlay_SagerNet_Palette_Dynamic
        else -> palette(defaultTheme())
    }

    var currentNightMode = -1
    fun getNightMode(): Int {
        if (currentNightMode == -1) {
            currentNightMode = DataStore.nightTheme
        }
        return getNightMode(currentNightMode)
    }

    fun getNightMode(mode: Int): Int = when (mode) {
        0 -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        1 -> AppCompatDelegate.MODE_NIGHT_YES
        2 -> AppCompatDelegate.MODE_NIGHT_NO
        else -> AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY
    }

    fun usingNightMode(): Boolean = when (DataStore.nightTheme) {
        1 -> true
        2 -> false
        else -> (app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    fun applyNightTheme() {
        AppCompatDelegate.setDefaultNightMode(getNightMode())
    }
}
