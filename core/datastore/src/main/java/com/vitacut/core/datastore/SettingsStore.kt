package com.vitacut.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vitacut.core.common.logging.VitaLog
import dagger.hilt.android.qualifiers.ApplicationContext
import com.vitacut.core.model.ExportSettings
import com.vitacut.core.model.VitaJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "vitacut_settings")

/** App theme choice. */
enum class ThemeMode { DARK, LIGHT, SYSTEM }

/** Supported UI languages; `SYSTEM` follows the Android per-app locale API. */
object AppLanguages {
    val SUPPORTED_TAGS = listOf("en", "am", "ar", "fr", "es", "pt", "zh", "hi")
}

/** Strongly-typed snapshot of every user preference. */
data class VitaSettings(
    val themeMode: ThemeMode = ThemeMode.DARK,
    /** Empty string = follow system locale. */
    val languageTag: String = "",
    val defaultExportSettings: ExportSettings = ExportSettings.DEFAULT,
    val hardwareAcceleration: Boolean = true,
    val autoSaveEnabled: Boolean = true,
    val autoSaveIntervalSeconds: Int = 20,
    /** Preview quality tier; empty = use the device-recommended mode. */
    val performanceModeOverride: String = "",
    val reducedMotion: Boolean = false,
    val notificationsEnabled: Boolean = true,
    val hapticsEnabled: Boolean = true,
    /** SAF tree URI where exports are published (null = MediaStore default). */
    val exportLocationUri: String? = null,
    val proxyMediaEnabled: Boolean = true,
    val premiumUnlocked: Boolean = false,
    val onboardingComplete: Boolean = false,
    val lastOpenedProjectId: String? = null,
)

/**
 * Typed access to the preferences DataStore.
 *
 * All reads are Flows (offline-first, reactive settings UI); writes are suspend [edit] calls.
 * Corrupt preference files degrade to [emptyPreferences] rather than crashing the app.
 */
@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private object Keys {
        val THEME = stringPreferencesKey("theme_mode")
        val LANGUAGE = stringPreferencesKey("language_tag")
        val EXPORT_DEFAULTS = stringPreferencesKey("export_defaults_json")
        val HW_ACCEL = booleanPreferencesKey("hardware_acceleration")
        val AUTOSAVE = booleanPreferencesKey("autosave_enabled")
        val AUTOSAVE_INTERVAL = intPreferencesKey("autosave_interval_seconds")
        val PERF_MODE = stringPreferencesKey("performance_mode_override")
        val REDUCED_MOTION = booleanPreferencesKey("reduced_motion")
        val NOTIFICATIONS = booleanPreferencesKey("notifications_enabled")
        val HAPTICS = booleanPreferencesKey("haptics_enabled")
        val EXPORT_LOCATION = stringPreferencesKey("export_location_uri")
        val PROXY_MEDIA = booleanPreferencesKey("proxy_media_enabled")
        val PREMIUM = booleanPreferencesKey("premium_unlocked")
        val ONBOARDING = booleanPreferencesKey("onboarding_complete")
        val LAST_PROJECT = stringPreferencesKey("last_opened_project_id")
    }

    private val dataStore get() = context.settingsDataStore

    val settings: Flow<VitaSettings> = dataStore.data
        .catch { cause ->
            // DataStore throws IOException on a corrupt/missing file; fall back to defaults.
            if (cause is IOException) {
                VitaLog.w("SettingsStore", "Preferences unreadable, using defaults", cause)
                emit(emptyPreferences())
            } else {
                throw cause
            }
        }
        .map { prefs ->
            VitaSettings(
                themeMode = runCatching { ThemeMode.valueOf(prefs[Keys.THEME] ?: "DARK") }
                    .getOrDefault(ThemeMode.DARK),
                languageTag = prefs[Keys.LANGUAGE] ?: "",
                defaultExportSettings = prefs[Keys.EXPORT_DEFAULTS]
                    ?.let { json -> runCatching { VitaJson.decodeFromString(ExportSettings.serializer(), json) }.getOrNull() }
                    ?: ExportSettings.DEFAULT,
                hardwareAcceleration = prefs[Keys.HW_ACCEL] ?: true,
                autoSaveEnabled = prefs[Keys.AUTOSAVE] ?: true,
                autoSaveIntervalSeconds = prefs[Keys.AUTOSAVE_INTERVAL] ?: 20,
                performanceModeOverride = prefs[Keys.PERF_MODE] ?: "",
                reducedMotion = prefs[Keys.REDUCED_MOTION] ?: false,
                notificationsEnabled = prefs[Keys.NOTIFICATIONS] ?: true,
                hapticsEnabled = prefs[Keys.HAPTICS] ?: true,
                exportLocationUri = prefs[Keys.EXPORT_LOCATION],
                proxyMediaEnabled = prefs[Keys.PROXY_MEDIA] ?: true,
                premiumUnlocked = prefs[Keys.PREMIUM] ?: false,
                onboardingComplete = prefs[Keys.ONBOARDING] ?: false,
                lastOpenedProjectId = prefs[Keys.LAST_PROJECT],
            )
        }

    suspend fun current(): VitaSettings = settings.first()

    suspend fun setThemeMode(mode: ThemeMode) = dataStore.edit { it[Keys.THEME] = mode.name }

    suspend fun setLanguage(tag: String) = dataStore.edit { it[Keys.LANGUAGE] = tag }

    suspend fun setDefaultExportSettings(settings: ExportSettings) = dataStore.edit {
        it[Keys.EXPORT_DEFAULTS] = VitaJson.encodeToString(ExportSettings.serializer(), settings)
    }

    suspend fun setHardwareAcceleration(enabled: Boolean) = dataStore.edit { it[Keys.HW_ACCEL] = enabled }

    suspend fun setAutoSave(enabled: Boolean, intervalSeconds: Int? = null) = dataStore.edit {
        it[Keys.AUTOSAVE] = enabled
        intervalSeconds?.let { seconds -> it[Keys.AUTOSAVE_INTERVAL] = seconds }
    }

    suspend fun setPerformanceModeOverride(modeName: String) =
        dataStore.edit { it[Keys.PERF_MODE] = modeName }

    suspend fun setReducedMotion(enabled: Boolean) = dataStore.edit { it[Keys.REDUCED_MOTION] = enabled }

    suspend fun setNotifications(enabled: Boolean) = dataStore.edit { it[Keys.NOTIFICATIONS] = enabled }

    suspend fun setHaptics(enabled: Boolean) = dataStore.edit { it[Keys.HAPTICS] = enabled }

    suspend fun setExportLocation(uri: String?) = dataStore.edit { prefs ->
        if (uri == null) prefs.remove(Keys.EXPORT_LOCATION) else prefs[Keys.EXPORT_LOCATION] = uri
    }

    suspend fun setProxyMedia(enabled: Boolean) = dataStore.edit { it[Keys.PROXY_MEDIA] = enabled }

    suspend fun setPremiumUnlocked(unlocked: Boolean) = dataStore.edit { it[Keys.PREMIUM] = unlocked }

    suspend fun setOnboardingComplete(complete: Boolean) = dataStore.edit { it[Keys.ONBOARDING] = complete }

    suspend fun setLastOpenedProject(projectId: String?) = dataStore.edit { prefs ->
        if (projectId == null) prefs.remove(Keys.LAST_PROJECT) else prefs[Keys.LAST_PROJECT] = projectId
    }
}
