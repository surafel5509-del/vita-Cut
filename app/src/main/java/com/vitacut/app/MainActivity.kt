package com.vitacut.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vitacut.app.navigation.VitaNavHost
import com.vitacut.core.datastore.SettingsStore
import com.vitacut.core.datastore.ThemeMode
import com.vitacut.core.designsystem.theme.VitaTheme
import com.vitacut.core.designsystem.theme.VitaThemeMode
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import javax.inject.Inject

/**
 * The single activity. Owns the nav host and maps user preferences (theme, locale) onto the
 * composition. Locale changes are mirrored into a tiny SharedPreferences so
 * [attachBaseContext] can apply them synchronously before the first frame, then the activity
 * is recreated.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsStore: SettingsStore

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.wrapWithSavedLocale())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val settings by settingsStore.settings.collectAsStateWithLifecycle(
                initialValue = null,
            )
            val current = settings
            VitaTheme(mode = current?.themeMode.toVitaThemeMode()) {
                VitaNavHost()
            }

            // Recreate when the language preference changes (settings sheet persists it).
            val tag = current?.languageTag.orEmpty()
            LaunchedEffect(tag) {
                val prefs = localePrefs(this@MainActivity)
                val saved = prefs.getString(KEY_LOCALE, "").orEmpty()
                if (saved != tag) {
                    prefs.edit().putString(KEY_LOCALE, tag).apply()
                    if (tag.isNotEmpty()) {
                        recreate()
                    }
                }
            }
        }
    }

    private fun ThemeMode?.toVitaThemeMode(): VitaThemeMode = when (this) {
        ThemeMode.DARK -> VitaThemeMode.DARK
        ThemeMode.LIGHT -> VitaThemeMode.LIGHT
        ThemeMode.SYSTEM -> VitaThemeMode.SYSTEM
        null -> VitaThemeMode.DARK // dark-first default while preferences load
    }
}

private const val PREFS_NAME = "vita_locale"
private const val KEY_LOCALE = "language_tag"

internal fun localePrefs(context: Context): SharedPreferences =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

/**
 * Wraps [this] in a locale-configured context when the user picked a language. An empty tag
 * means "follow the system", which needs no wrapper (per-app locales flow through Android's
 * own locale APIs).
 */
private fun Context.wrapWithSavedLocale(): Context {
    val tag = localePrefs(this).getString(KEY_LOCALE, "").orEmpty()
    if (tag.isEmpty()) return this
    val locale = Locale.forLanguageTag(tag)
    val config = resources.configuration.let { android.content.res.Configuration(it) }
    config.setLocale(locale)
    return ContextWrapper(createConfigurationContext(config))
}
