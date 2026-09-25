package com.vitacut.core.model

import kotlinx.serialization.Serializable

/** Filter categories shown in the Filters sheet. */
@Serializable
enum class FilterCategory {
    CINEMATIC,
    PORTRAIT,
    TRAVEL,
    FOOD,
    VINTAGE,
    BLACK_WHITE,
    WARM,
    COOL,
    RETRO,
    SOCIAL,
    MOODY,
    STREET,
    FANTASY,
    FILM,
}

/**
 * A filter is a *named grading delta* plus an optional LUT. Keeping filters expressed in the same
 * [Grading] structure as manual adjustments means one renderer path produces both, previews are
 * exact, and "copy/paste adjustments" and keyframed filter intensity come for free.
 */
@Serializable
data class FilterDefinition(
    val id: String,
    /** String resource name, resolved at the UI layer (models stay localization-free). */
    val nameKey: String,
    val category: FilterCategory,
    val grading: Grading,
)

/** Filter state attached to a clip. Intensity 0..1, keyframable via FILTER_INTENSITY. */
@Serializable
data class FilterState(
    val filterId: String? = null,
    val intensity: Float = 1f,
) {
    val isActive: Boolean get() = filterId != null && intensity > 0f

    companion object {
        val NONE = FilterState()
    }
}
