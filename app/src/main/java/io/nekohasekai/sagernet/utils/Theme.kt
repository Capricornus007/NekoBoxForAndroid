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

    private fun defaultTheme() = PINK_SSR

    fun apply(context: Context) {
        context.setTheme(getTheme())
    }

    fun applyDialog(context: Context) {
        context.setTheme(getDialogTheme())
    }

    fun getTheme(): Int {
        return getTheme(DataStore.appTheme)
    }

    fun getDialogTheme(): Int {
        return getDialogTheme(DataStore.appTheme)
    }

    fun getTheme(theme: Int): Int {
        val isOriginal = DataStore.themeStyle == 1
        return when (theme) {
            RED -> if (isOriginal) R.style.Theme_SagerNet_Original_Red else R.style.Theme_SagerNet_Red
            PINK -> if (isOriginal) R.style.Theme_SagerNet_Original else R.style.Theme_SagerNet
            PINK_SSR -> if (isOriginal) R.style.Theme_SagerNet_Original_Pink_SSR else R.style.Theme_SagerNet_Pink_SSR
            PURPLE -> if (isOriginal) R.style.Theme_SagerNet_Original_Purple else R.style.Theme_SagerNet_Purple
            DEEP_PURPLE -> if (isOriginal) R.style.Theme_SagerNet_Original_DeepPurple else R.style.Theme_SagerNet_DeepPurple
            INDIGO -> if (isOriginal) R.style.Theme_SagerNet_Original_Indigo else R.style.Theme_SagerNet_Indigo
            BLUE -> if (isOriginal) R.style.Theme_SagerNet_Original_Blue else R.style.Theme_SagerNet_Blue
            LIGHT_BLUE -> if (isOriginal) R.style.Theme_SagerNet_Original_LightBlue else R.style.Theme_SagerNet_LightBlue
            CYAN -> if (isOriginal) R.style.Theme_SagerNet_Original_Cyan else R.style.Theme_SagerNet_Cyan
            TEAL -> if (isOriginal) R.style.Theme_SagerNet_Original_Teal else R.style.Theme_SagerNet_Teal
            GREEN -> if (isOriginal) R.style.Theme_SagerNet_Original_Green else R.style.Theme_SagerNet_Green
            LIGHT_GREEN -> if (isOriginal) R.style.Theme_SagerNet_Original_LightGreen else R.style.Theme_SagerNet_LightGreen
            LIME -> if (isOriginal) R.style.Theme_SagerNet_Original_Lime else R.style.Theme_SagerNet_Lime
            YELLOW -> if (isOriginal) R.style.Theme_SagerNet_Original_Yellow else R.style.Theme_SagerNet_Yellow
            AMBER -> if (isOriginal) R.style.Theme_SagerNet_Original_Amber else R.style.Theme_SagerNet_Amber
            ORANGE -> if (isOriginal) R.style.Theme_SagerNet_Original_Orange else R.style.Theme_SagerNet_Orange
            DEEP_ORANGE -> if (isOriginal) R.style.Theme_SagerNet_Original_DeepOrange else R.style.Theme_SagerNet_DeepOrange
            BROWN -> if (isOriginal) R.style.Theme_SagerNet_Original_Brown else R.style.Theme_SagerNet_Brown
            GREY -> if (isOriginal) R.style.Theme_SagerNet_Original_Grey else R.style.Theme_SagerNet_Grey
            BLUE_GREY -> if (isOriginal) R.style.Theme_SagerNet_Original_BlueGrey else R.style.Theme_SagerNet_BlueGrey
            BLACK -> if (isOriginal) R.style.Theme_SagerNet_Original_Black else R.style.Theme_SagerNet_Black
            VERDANT_MINT -> if (isOriginal) R.style.Theme_SagerNet_Original_VerdantMint else R.style.Theme_SagerNet_VerdantMint
            else -> getTheme(defaultTheme())
        }
    }

    fun getDialogTheme(theme: Int): Int {
        val isOriginal = DataStore.themeStyle == 1
        return when (theme) {
            RED -> if (isOriginal) R.style.Theme_SagerNet_Dialog_Original_Red else R.style.Theme_SagerNet_Dialog_Red
            PINK -> if (isOriginal) R.style.Theme_SagerNet_Dialog_Original else R.style.Theme_SagerNet_Dialog
            PINK_SSR -> if (isOriginal) R.style.Theme_SagerNet_Dialog_Original_Pink_SSR else R.style.Theme_SagerNet_Dialog_Pink_SSR
            PURPLE -> if (isOriginal) R.style.Theme_SagerNet_Dialog_Original_Purple else R.style.Theme_SagerNet_Dialog_Purple
            DEEP_PURPLE -> if (isOriginal) R.style.Theme_SagerNet_Dialog_Original_DeepPurple else R.style.Theme_SagerNet_Dialog_DeepPurple
            INDIGO -> if (isOriginal) R.style.Theme_SagerNet_Dialog_Original_Indigo else R.style.Theme_SagerNet_Dialog_Indigo
            BLUE -> if (isOriginal) R.style.Theme_SagerNet_Dialog_Original_Blue else R.style.Theme_SagerNet_Dialog_Blue
            LIGHT_BLUE -> if (isOriginal) R.style.Theme_SagerNet_Dialog_Original_LightBlue else R.style.Theme_SagerNet_Dialog_LightBlue
            CYAN -> if (isOriginal) R.style.Theme_SagerNet_Dialog_Original_Cyan else R.style.Theme_SagerNet_Dialog_Cyan
            TEAL -> if (isOriginal) R.style.Theme_SagerNet_Dialog_Original_Teal else R.style.Theme_SagerNet_Dialog_Teal
            GREEN -> if (isOriginal) R.style.Theme_SagerNet_Dialog_Original_Green else R.style.Theme_SagerNet_Dialog_Green
            LIGHT_GREEN -> if (isOriginal) R.style.Theme_SagerNet_Dialog_Original_LightGreen else R.style.Theme_SagerNet_Dialog_LightGreen
            LIME -> if (isOriginal) R.style.Theme_SagerNet_Dialog_Original_Lime else R.style.Theme_SagerNet_Dialog_Lime
            YELLOW -> if (isOriginal) R.style.Theme_SagerNet_Dialog_Original_Yellow else R.style.Theme_SagerNet_Dialog_Yellow
            AMBER -> if (isOriginal) R.style.Theme_SagerNet_Dialog_Original_Amber else R.style.Theme_SagerNet_Dialog_Amber
            ORANGE -> if (isOriginal) R.style.Theme_SagerNet_Dialog_Original_Orange else R.style.Theme_SagerNet_Dialog_Orange
            DEEP_ORANGE -> if (isOriginal) R.style.Theme_SagerNet_Dialog_Original_DeepOrange else R.style.Theme_SagerNet_Dialog_DeepOrange
            BROWN -> if (isOriginal) R.style.Theme_SagerNet_Dialog_Original_Brown else R.style.Theme_SagerNet_Dialog_Brown
            GREY -> if (isOriginal) R.style.Theme_SagerNet_Dialog_Original_Grey else R.style.Theme_SagerNet_Dialog_Grey
            BLUE_GREY -> if (isOriginal) R.style.Theme_SagerNet_Dialog_Original_BlueGrey else R.style.Theme_SagerNet_Dialog_BlueGrey
            BLACK -> if (isOriginal) R.style.Theme_SagerNet_Dialog_Original_Black else R.style.Theme_SagerNet_Dialog_Black
            VERDANT_MINT -> if (isOriginal) R.style.Theme_SagerNet_Dialog_Original_VerdantMint else R.style.Theme_SagerNet_Dialog_VerdantMint
            else -> getDialogTheme(defaultTheme())
        }
    }

    var currentNightMode = -1
    fun getNightMode(): Int {
        if (currentNightMode == -1) {
            currentNightMode = DataStore.nightTheme
        }
        return getNightMode(currentNightMode)
    }

    fun getNightMode(mode: Int): Int {
        return when (mode) {
            0 -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            1 -> AppCompatDelegate.MODE_NIGHT_YES
            2 -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY
        }
    }

    fun usingNightMode(): Boolean {
        return when (DataStore.nightTheme) {
            1 -> true
            2 -> false
            else -> (app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
    }

    fun applyNightTheme() {
        AppCompatDelegate.setDefaultNightMode(getNightMode())
    }

}