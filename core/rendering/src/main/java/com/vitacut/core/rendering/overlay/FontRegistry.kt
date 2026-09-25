package com.vitacut.core.rendering.overlay

import android.graphics.Typeface
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Maps the model's font family keys to [Typeface]s.
 *
 * v1 ships the platform families (default sans, serif, monospace) which render identically
 * everywhere; user-installed font files can be registered at runtime via [register] (the text
 * panel offers font imports through SAF). Keys are stable strings stored in projects, so a
 * missing key always falls back to [DEFAULT_KEY] instead of failing.
 */
@Singleton
class FontRegistry @Inject constructor() {

    private val custom = mutableMapOf<String, Typeface>()

    fun register(key: String, typeface: Typeface) {
        custom[key] = typeface
    }

    fun unregister(key: String) {
        custom.remove(key)
    }

    fun resolve(key: String, bold: Boolean, italic: Boolean): Typeface {
        val base = custom[key] ?: when (key) {
            KEY_SERIF -> Typeface.SERIF
            KEY_MONOSPACE -> Typeface.MONOSPACE
            else -> Typeface.DEFAULT
        }
        val style = (if (bold) Typeface.BOLD else 0) or (if (italic) Typeface.ITALIC else 0)
        return Typeface.create(base, style)
    }

    /** Keys offered by the font picker. */
    fun availableKeys(): List<String> =
        listOf(DEFAULT_KEY, KEY_SERIF, KEY_MONOSPACE) + custom.keys.sorted()

    companion object {
        const val DEFAULT_KEY = "default"
        const val KEY_SERIF = "serif"
        const val KEY_MONOSPACE = "monospace"
    }
}
