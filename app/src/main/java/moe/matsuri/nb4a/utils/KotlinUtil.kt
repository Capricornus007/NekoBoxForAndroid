package moe.matsuri.nb4a.utils

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
import androidx.appcompat.content.res.AppCompatResources
import java.util.Locale

// SagerNet Class

const val KB = 1024L
const val MB = KB * 1024
const val GB = MB * 1024

// Context utils

@SuppressLint("DiscouragedApi")
fun Context.getDrawableByName(name: String?): Drawable? {
    val resourceId: Int = resources.getIdentifier(name, "drawable", packageName)
    return AppCompatResources.getDrawable(this, resourceId)
}

// Traffic display

fun Long.toBytesString(): String {
    val size = this.toDouble()
    return when {
        this >= GB -> String.format(Locale.ROOT, "%.2f GiB", size / GB)
        this >= MB -> String.format(Locale.ROOT, "%.2f MiB", size / MB)
        this >= KB -> String.format(Locale.ROOT, "%.2f KiB", size / KB)
        else -> "$this Bytes"
    }
}

// List

fun String.listByLineOrComma(): List<String> {
    return this.split(",", "\n").map { it.trim() }.filter { it.isNotEmpty() }
}
