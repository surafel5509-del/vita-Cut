package com.vitacut.app

import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vitacut.core.designsystem.strings.VitaStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * Localization instrumentation test — the spec requires every UI string to live in resources
 * across all supported locales. This verifies, on a real device configuration path, that:
 * - key sample strings from the design-system and export catalogs resolve in every locale,
 * - translations actually differ from English (guards against copy-paste "translations"),
 * - [VitaStrings] degrades unknown keys to the key itself instead of crashing.
 */
@RunWith(AndroidJUnit4::class)
class LocalizationInstrumentedTest {

    private val locales = listOf("en", "am", "ar", "fr", "es", "pt", "zh", "hi")

    private val sampleKeys = listOf(
        "app_name",
        "app_tagline",
        "action_cancel",
        "action_save",
        "action_delete",
        "export_channel_name",
        "export_notification_title",
        "error_export_failed",
        "error_insufficient_storage",
    )

    private fun contextFor(tag: String) =
        ApplicationProvider.getApplicationContext<android.app.Application>()
            .createConfigurationContext(
                Configuration().apply {
                    setLocale(Locale.forLanguageTag(tag))
                },
            )

    @Test
    fun allSampleKeysResolveInEveryLocale() {
        for (tag in locales) {
            val context = contextFor(tag)
            for (key in sampleKeys) {
                val resolved = VitaStrings.localized(context, key)
                assertTrue("[$tag] '$key' did not resolve (got '$resolved')", resolved != key)
                assertTrue("[$tag] '$key' resolved empty", resolved.isNotBlank())
            }
        }
    }

    @Test
    fun translationsDifferFromEnglish() {
        val english = contextFor("en")
        // app_name stays the brand everywhere; compare the translatable subset.
        val keys = sampleKeys - "app_name" - "app_tagline"
        for (tag in locales - "en") {
            val localized = contextFor(tag)
            val differing = keys.count { key ->
                VitaStrings.localized(localized, key) != VitaStrings.localized(english, key)
            }
            assertTrue(
                "[$tag] only $differing/${keys.size} strings differ from English",
                differing >= keys.size - 1,
            )
        }
    }

    @Test
    fun unknownKeysDegradeToTheKeyItself() {
        val context = contextFor("en")
        assertEquals("no_such_key_xyz", VitaStrings.localized(context, "no_such_key_xyz"))
        assertEquals("", VitaStrings.localized(context, ""))
    }

    @Test
    fun formattedKeysAcceptArguments() {
        val context = contextFor("en")
        val formatted = VitaStrings.localized(context, "export_progress_notification", 42)
        assertTrue("formatted='$formatted'", formatted.contains("42"))
        assertNotEquals("export_progress_notification", formatted)
    }
}
