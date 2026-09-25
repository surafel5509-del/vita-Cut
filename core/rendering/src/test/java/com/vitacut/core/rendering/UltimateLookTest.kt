package com.vitacut.core.rendering

import com.vitacut.core.model.EffectKind
import com.vitacut.core.model.FilterCategory
import com.vitacut.core.model.Grading
import com.vitacut.core.model.TransitionKind
import com.vitacut.core.rendering.effects.FxEffect
import com.vitacut.core.rendering.effects.TransitionEffect
import com.vitacut.core.rendering.filters.FilterLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UltimateLookTest {

    @Test
    fun `filter catalog is unique and spans ultimate categories`() {
        assertTrue(FilterLibrary.ALL.size >= 40)
        assertEquals(FilterLibrary.ALL.size, FilterLibrary.ALL.map { it.id }.toSet().size)
        FilterCategory.entries.forEach { category ->
            assertTrue("$category has no filters", FilterLibrary.byCategory(category).isNotEmpty())
        }
        assertNotNull(FilterLibrary.byId("teal-orange"))
        assertNotNull(FilterLibrary.byId("neon-tokyo"))
        assertNotNull(FilterLibrary.byId("kodak-portra"))
        assertNotNull(FilterLibrary.byId("bleach-bypass"))
    }

    @Test
    fun `filter blend scales intensity`() {
        val blended = FilterLibrary.blend(Grading.DEFAULT, "ember", 0.5f)
        assertTrue(blended.adjustments.temperature > 0f)
        assertTrue(blended.adjustments.temperature < FilterLibrary.byId("ember")!!.grading.adjustments.temperature)
        assertEquals(Grading.DEFAULT, FilterLibrary.blend(Grading.DEFAULT, "missing", 1f))
    }

    @Test
    fun `every effect kind maps to a shader asset`() {
        EffectKind.entries.forEach { kind ->
            val path = FxEffect.shaderFor(kind)
            assertTrue("$kind → $path", path.startsWith("shaders/") && path.endsWith(".glsl"))
        }
    }

    @Test
    fun `every transition kind has a stable shader index`() {
        val indices = TransitionKind.entries.map { TransitionEffect.kindIndex(it) }
        assertEquals(TransitionKind.entries.size, indices.toSet().size)
        assertEquals(0, TransitionEffect.kindIndex(TransitionKind.FADE))
        assertEquals(25, TransitionEffect.kindIndex(TransitionKind.IRIS))
        assertEquals(31, TransitionEffect.kindIndex(TransitionKind.FADE_COLOR))
    }
}
