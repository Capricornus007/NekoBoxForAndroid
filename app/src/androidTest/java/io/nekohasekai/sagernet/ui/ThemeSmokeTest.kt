package io.nekohasekai.sagernet.ui

import android.Manifest
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.color.DynamicColors
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.utils.Theme
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ThemeSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val allThemes = Theme.RED..Theme.CATPPUCCIN

    private fun themed(theme: Int, night: Boolean, dialog: Boolean = false): Context {
        val config = Configuration(app.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        val base = if (dialog) R.style.Theme_SagerNet_Dialog else R.style.Theme_SagerNet
        val ctx = ContextThemeWrapper(app.createConfigurationContext(config), base).also {
            Theme.apply(it, theme, dialog)
        }
        // Dynamic takes its color roles from the wallpaper, layered on top like ThemedActivity does.
        return if (theme == Theme.DYNAMIC) DynamicColors.wrapContextIfAvailable(ctx) else ctx
    }

    /**
     * A palette that leaves an attr unresolved throws "Failed to resolve attribute" at
     * inflation and makes getColorAttr fall back to transparent. Every palette must
     * resolve what the layouts use, for both window kinds.
     */
    @Test
    fun everyPaletteResolvesLayoutAttrs() {
        val layouts = intArrayOf(
            R.layout.layout_appbar,
            R.layout.layout_backup,
            R.layout.layout_import,
            R.layout.item_keyboard_key,
        )
        for (id in allThemes) {
            for (dialog in booleanArrayOf(false, true)) {
                val ctx = themed(id, night = false, dialog = dialog)
                val inflater = LayoutInflater.from(ctx)
                for (layout in layouts) inflater.inflate(layout, null)
                for (attr in intArrayOf(R.attr.fabConnectedColor, R.attr.appBarBackgroundColor, R.attr.colorPrimaryText)) {
                    assertNotEquals("theme $id dialog=$dialog: attr unresolved", Color.TRANSPARENT, ctx.getColorAttr(attr))
                }
            }
        }
    }

    /**
     * Text-like accents (dialog buttons, chip text, protocol label) must read on the
     * surface at 4.5:1 and the FAB icon on the FAB at 3:1, in every mode a palette can
     * be shown in. Dark-only palettes force night mode, so day is not checked for them.
     */
    @Test
    fun everyPaletteMeetsContrastFloors() {
        val failures = mutableListOf<String>()
        for (id in allThemes) {
            val modes = if (id in Theme.DARK_ONLY_THEMES) listOf(true) else listOf(false, true)
            for (night in modes) {
                val ctx = themed(id, night)
                val surface = ctx.getColorAttr(com.google.android.material.R.attr.colorSurface)
                fun check(label: String, fg: Int, bg: Int, floor: Double) {
                    val ratio = ColorUtils.calculateContrast(fg, bg)
                    if (ratio < floor) failures += "theme $id night=$night $label %.2f:1 (needs %.1f)".format(ratio, floor)
                }
                check("primaryOrTextPrimary/surface", ctx.getColorAttr(R.attr.primaryOrTextPrimary), surface, 4.5)
                check("protocolColor/surface", ctx.getColorAttr(R.attr.protocolColor), surface, 4.5)
                check("fabStoppedColor/fab", ctx.getColorAttr(R.attr.fabStoppedColor), ctx.getColorAttr(R.attr.fabColorBackground), 3.0)
                // Checked filter chips: on-container text over the (possibly translucent) container.
                val container = ColorUtils.compositeColors(
                    ctx.getColorAttr(com.google.android.material.R.attr.colorSecondaryContainer),
                    surface,
                )
                check("onSecondaryContainer/container", ctx.getColorAttr(com.google.android.material.R.attr.colorOnSecondaryContainer), container, 4.5)
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    /**
     * Launches the main screen under every theme in both modes and stores screenshots;
     * the apps screen (chips, search field, list switches) for a representative subset.
     */
    @Test
    fun mainActivityLaunchesUnderEveryTheme() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(
                app.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        val out = File(app.getExternalFilesDir(null), "theme-shots").apply { mkdirs() }
        val appsScreenThemes = setOf(Theme.PINK, Theme.YELLOW, Theme.DEEP_PURPLE, Theme.BLACK, Theme.NORD)
        fun select(theme: Int, night: Int) {
            DataStore.appTheme = theme
            DataStore.nightTheme = night
            Theme.currentNightMode = -1
            instrumentation.runOnMainSync {
                AppCompatDelegate.setDefaultNightMode(Theme.getNightMode())
            }
        }
        fun shoot(activity: Class<out ThemedActivity>, name: String) {
            ActivityScenario.launch(activity).use {
                instrumentation.waitForIdleSync()
                Thread.sleep(1200) // bottom bar slide-in / list load settle
                val shot = instrumentation.uiAutomation.takeScreenshot()
                File(out, name).outputStream().use { stream ->
                    shot.compress(Bitmap.CompressFormat.PNG, 100, stream)
                }
            }
        }
        val savedTheme = DataStore.appTheme
        val savedNight = DataStore.nightTheme
        try {
            for (id in allThemes) {
                for ((modeName, night) in mapOf("light" to 2, "dark" to 1)) {
                    select(id, night)
                    shoot(MainActivity::class.java, "%02d-%s.png".format(id, modeName))
                    if (id in appsScreenThemes) shoot(AppManagerActivity::class.java, "%02d-%s-apps.png".format(id, modeName))
                }
            }
        } finally {
            select(savedTheme, savedNight)
        }
    }
}
