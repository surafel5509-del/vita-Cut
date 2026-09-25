package com.vitacut.core.designsystem.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vitacut.core.designsystem.theme.VitaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * UI tests for the shared component kit: every feature screen is built from these, so their
 * contract (click routing, enabled state, selected state, text rendering inside [VitaTheme])
 * is verified once, here.
 */
class VitaComponentsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun buttonRendersTextAndRoutesClicks() {
        var clicks = 0
        composeRule.setContent {
            VitaTheme {
                VitaButton(text = "Export", onClick = { clicks++ })
            }
        }
        composeRule.onNodeWithText("Export").assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithText("Export").performClick()
        composeRule.onNodeWithText("Export").performClick()
        assertEquals(2, clicks)
    }

    @Test
    fun disabledButtonSwallowsClicks() {
        var clicks = 0
        composeRule.setContent {
            VitaTheme {
                VitaButton(text = "Save", onClick = { clicks++ }, enabled = false)
            }
        }
        composeRule.onNodeWithText("Save").assertIsNotEnabled()
        composeRule.onNodeWithText("Save").performClick()
        assertEquals(0, clicks)
    }

    @Test
    fun chipRowReportsSelectedIdOnClick() {
        val clicked = mutableListOf<String>()
        composeRule.setContent {
            VitaTheme {
                VitaChipRow(
                    chips = listOf(
                        VitaChipItem("cinematic", "Cinematic", selected = true),
                        VitaChipItem("retro", "Retro", selected = false),
                    ),
                    onChipClick = { clicked += it },
                )
            }
        }
        composeRule.onNodeWithText("Cinematic").assertIsDisplayed()
        composeRule.onNodeWithText("Retro").performClick()
        assertEquals(listOf("retro"), clicked)
    }

    @Test
    fun labeledSliderShowsLabelAndValueText() {
        composeRule.setContent {
            VitaTheme {
                VitaLabeledSlider(
                    label = "Brightness",
                    value = 0.25f,
                    onValueChange = {},
                    valueText = "+25",
                )
            }
        }
        composeRule.onNodeWithText("Brightness").assertIsDisplayed()
        composeRule.onNodeWithText("+25").assertIsDisplayed()
    }

    @Test
    fun emptyStateRendersTitleMessageAndAction() {
        var actionClicks = 0
        composeRule.setContent {
            VitaTheme {
                VitaEmptyState(
                    icon = Icons.Outlined.Movie,
                    iconContentDescription = null,
                    title = "No projects yet",
                    message = "Create your first edit to get started.",
                    actionLabel = "New project",
                    onAction = { actionClicks++ },
                )
            }
        }
        composeRule.onNodeWithText("No projects yet").assertIsDisplayed()
        composeRule.onNodeWithText("Create your first edit to get started.").assertIsDisplayed()
        composeRule.onNodeWithText("New project").performClick()
        assertEquals(1, actionClicks)
    }

    @Test
    fun errorStateOffersRetry() {
        var retries = 0
        composeRule.setContent {
            VitaTheme {
                VitaErrorState(
                    title = "Export failed",
                    message = "The encoder could not start. Try a lower resolution.",
                    retryLabel = "Retry",
                    onRetry = { retries++ },
                )
            }
        }
        composeRule.onNodeWithText("Export failed").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()
        assertEquals(1, retries)
    }
}
