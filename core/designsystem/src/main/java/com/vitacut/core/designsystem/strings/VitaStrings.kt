package com.vitacut.core.designsystem.strings

import android.content.Context

/**
 * Resolves dynamic message keys (error codes, fallback notices, AI capability reasons) that
 * travel inside data classes as stable identifiers. The concrete localized text lives in
 * whichever module declared the key — resource merging puts everything under the app package,
 * so a name lookup finds it at runtime.
 *
 * Unknown keys degrade to the key itself (visible in dev, never a crash).
 */
object VitaStrings {

    fun localized(context: Context, key: String): String {
        if (key.isBlank()) return key
        val id = context.resources.getIdentifier(key, "string", context.packageName)
        return if (id != 0) runCatching { context.getString(id) }.getOrDefault(key) else key
    }

    fun localized(context: Context, key: String, vararg args: Any): String {
        val template = localized(context, key)
        return runCatching { String.format(template, *args) }.getOrDefault(template)
    }
}
