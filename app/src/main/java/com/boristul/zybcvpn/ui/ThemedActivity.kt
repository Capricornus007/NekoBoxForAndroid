package com.boristul.zybcvpn.ui

import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.snackbar.Snackbar
import com.boristul.zybcvpn.R
import com.boristul.zybcvpn.utils.Theme

abstract class ThemedActivity : AppCompatActivity {
    constructor() : super()
    constructor(contentLayoutId: Int) : super(contentLayoutId)

    var themeResId = 0
    var uiMode = 0
    open val isDialog = false

    override fun onCreate(savedInstanceState: Bundle?) {
        if (!isDialog) {
            Theme.apply(this)
        } else {
            Theme.applyDialog(this)
        }
        Theme.applyNightTheme()

        super.onCreate(savedInstanceState)

        uiMode = resources.configuration.uiMode
    }

    fun applyStatusBarInsetToToolbar(toolbar: View) {
        val appBar = findViewById<View>(R.id.appbar) ?: toolbar
        val initialTopPadding = appBar.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(appBar) { v, insets ->
            v.updatePadding(
                top = initialTopPadding + insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            )
            insets
        }
        ViewCompat.requestApplyInsets(appBar)
    }

    override fun setTheme(resId: Int) {
        super.setTheme(resId)

        themeResId = resId
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        if (newConfig.uiMode != uiMode) {
            uiMode = newConfig.uiMode
            ActivityCompat.recreate(this)
        }
    }

    fun snackbar(@StringRes resId: Int): Snackbar = snackbar("").setText(resId)
    fun snackbar(text: CharSequence): Snackbar = snackbarInternal(text).apply {
        view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text).apply {
            maxLines = 10
        }
    }

    internal open fun snackbarInternal(text: CharSequence): Snackbar =
        Snackbar.make(window.decorView, text, Snackbar.LENGTH_LONG)

}
