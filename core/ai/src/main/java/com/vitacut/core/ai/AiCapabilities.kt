package com.vitacut.core.ai

import android.content.Context
import com.google.android.gms.common.GoogleApiAvailability
import com.vitacut.core.common.logging.VitaLog
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Which AI feature class a given engine belongs to (drives UI availability hints). */
enum class AiFeature { OBJECT_DETECTION, SUBJECT_SEGMENTATION, TRACKING, AUDIO_ANALYSIS }

/**
 * Availability probing for the on-device vision engines.
 *
 * - Object detection ships with a *bundled* model → always available, even fully offline and
 *   without Google Play services.
 * - Subject segmentation is an *unbundled* ML Kit model → requires Google Play services and may
 *   need a one-time model download; when unavailable the UI offers the manual masking fallback
 *   instead of a dead button (spec: graceful offline degradation).
 * - Audio analysis (beats, silences, auto-cut) is pure local math → always available.
 */
@Singleton
class AiCapabilities @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    val playServicesAvailable: Boolean by lazy {
        runCatching {
            GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(context) == com.google.android.gms.common.ConnectionResult.SUCCESS
        }.getOrElse {
            VitaLog.i("AiCapabilities", "Play services probe failed: ${it.message}")
            false
        }
    }

    fun isAvailable(feature: AiFeature): Boolean = when (feature) {
        AiFeature.OBJECT_DETECTION -> true
        AiFeature.TRACKING -> true // template matching is local
        AiFeature.AUDIO_ANALYSIS -> true
        AiFeature.SUBJECT_SEGMENTATION -> playServicesAvailable
    }

    /** String-resource key explaining why a feature is unavailable (null when available). */
    fun unavailableReasonKey(feature: AiFeature): String? =
        if (isAvailable(feature)) null else "ai_requires_play_services"
}
