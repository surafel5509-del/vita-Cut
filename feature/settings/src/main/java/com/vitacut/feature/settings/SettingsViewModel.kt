package com.vitacut.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vitacut.core.common.device.DeviceCapabilities
import com.vitacut.core.common.device.PerformanceMode
import com.vitacut.core.datastore.AppLanguages
import com.vitacut.core.datastore.SettingsStore
import com.vitacut.core.datastore.ThemeMode
import com.vitacut.core.datastore.VitaSettings
import com.vitacut.core.export.StorageGuard
import com.vitacut.core.model.ExportSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val settings: VitaSettings = VitaSettings(),
    val recommendedMode: PerformanceMode = PerformanceMode.BALANCED,
    val supportedLanguages: List<String> = AppLanguages.SUPPORTED_TAGS,
    val clearedCacheBytes: Long? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
    private val capabilities: DeviceCapabilities,
    private val storageGuard: StorageGuard,
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = settingsStore.settings
        .map { settings ->
            SettingsUiState(
                settings = settings,
                recommendedMode = capabilities.recommendedPerformanceMode(),
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { settingsStore.setThemeMode(mode) }

    fun setLanguage(tag: String) = viewModelScope.launch { settingsStore.setLanguage(tag) }

    fun setPerformanceMode(mode: PerformanceMode?) = viewModelScope.launch {
        settingsStore.setPerformanceModeOverride(mode?.name ?: "")
    }

    fun setHardwareAcceleration(enabled: Boolean) = viewModelScope.launch {
        settingsStore.setHardwareAcceleration(enabled)
    }

    fun setProxyMedia(enabled: Boolean) = viewModelScope.launch { settingsStore.setProxyMedia(enabled) }

    fun setAutoSave(enabled: Boolean, intervalSeconds: Int?) = viewModelScope.launch {
        settingsStore.setAutoSave(enabled, intervalSeconds)
    }

    fun setReducedMotion(enabled: Boolean) = viewModelScope.launch {
        settingsStore.setReducedMotion(enabled)
    }

    fun setNotifications(enabled: Boolean) = viewModelScope.launch {
        settingsStore.setNotifications(enabled)
    }

    fun setHaptics(enabled: Boolean) = viewModelScope.launch { settingsStore.setHaptics(enabled) }

    fun setExportLocation(uri: String?) = viewModelScope.launch { settingsStore.setExportLocation(uri) }

    fun setDefaultExportSettings(settings: ExportSettings) = viewModelScope.launch {
        settingsStore.setDefaultExportSettings(settings)
    }

    private val _cleared = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)
    val clearedCache: StateFlow<Long?> = _cleared

    fun clearCache() = viewModelScope.launch {
        val before = storageGuard.freeCacheBytes()
        storageGuard.cleanupIncompleteExports()
        val after = storageGuard.freeCacheBytes()
        _cleared.value = (before - after).coerceAtLeast(0L)
    }

    fun consumeClearedCache() {
        _cleared.value = null
    }
}
