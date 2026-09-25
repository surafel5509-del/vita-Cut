package com.vitacut.core.model

import kotlinx.serialization.Serializable

/** Template gallery categories. */
@Serializable
enum class TemplateCategory {
    TIKTOK,
    REELS,
    YOUTUBE_SHORTS,
    YOUTUBE,
    TRAVEL,
    BIRTHDAY,
    WEDDING,
    SPORTS,
    CINEMATIC,
    VLOG,
    BUSINESS,
}

/** A media or text placeholder inside a template. */
@Serializable
data class TemplateSlot(
    val order: Int,
    val kind: SlotKind,
    val durationUs: Long,
    /** Suggested transition into the *next* slot (null = cut). */
    val transitionOut: TransitionKind? = null,
    val transitionDurationUs: Long = 500_000L,
    val suggestedFilterId: String? = null,
    /** For TEXT slots: default copy, localized by key. */
    val textKey: String? = null,
    val textStyle: TextStyle? = null,
) {
    @Serializable
    enum class SlotKind { MEDIA, TEXT }
}

/**
 * A bundled project template. Templates ship as JSON in app assets; applying one produces a real
 * [Project] whose slots await user media (the "replace media, keep structure" flow).
 */
@Serializable
data class Template(
    val id: TemplateId,
    val nameKey: String,
    val category: TemplateCategory,
    val canvas: CanvasSettings = CanvasSettings.DEFAULT,
    val frameRate: Float = 30f,
    val slots: List<TemplateSlot> = emptyList(),
    /** Bundled music asset path (android_asset URI) or null. */
    val musicAssetUri: String? = null,
    /** Drawable resource name used for the gallery thumbnail. */
    val thumbnailKey: String? = null,
    val descriptionKey: String? = null,
) {
    val totalDurationUs: Long get() = slots.sumOf { it.durationUs }
}
