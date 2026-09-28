package me.androidloader.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Composition tests for the screen.
 *
 * These exist because of a bug that unit tests could not see and a device found
 * immediately: the log was a `LazyColumn` nested inside a `Column` with
 * `verticalScroll`, which Compose rejects at layout time. The log starts empty, so
 * the app launched cleanly and then crashed on the first button press, because
 * that was the first moment anything was added to it.
 *
 * The lesson generalises, so the assertions here are deliberately about
 * composition completing at all rather than about appearance.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SideloadScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the log composes inside a scrolling column`() {
        // Reproduces the exact structure that used to throw. An empty log must be
        // safe too, since that is the state the app launches in.
        compose.setContent {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                LogView(listOf("asked Termux to start usbmuxd", "usbmuxd is up"))
            }
        }
        compose.onNodeWithText("usbmuxd is up").assertExists()
    }

    @Test
    fun `an empty log composes`() {
        compose.setContent {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                LogView(emptyList())
            }
        }
        // Reaching here without a layout exception is the assertion.
        assertTrue(true)
    }

    @Test
    fun `a long log still composes`() {
        val lines = (1..200).map { "line $it" }
        compose.setContent {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                LogView(lines)
            }
        }
        compose.onNodeWithText("line 1").assertExists()
    }
}
