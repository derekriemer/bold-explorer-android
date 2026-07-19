package com.boldexplorer.shared.output

import com.boldexplorer.shared.settings.AppSettings
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun event(origin: OutputOrigin) =
    OutputEvent(
        kind = OutputKind.WAYPOINT_REACHED,
        category = OutputCategory.NAVIGATION,
        origin = origin,
        speech = "Reached waypoint",
    )

class OutputPolicyTest {
    private val automaticEvent = event(OutputOrigin.AUTOMATIC)

    @Test
    fun evaluate_allowsSpeechWhenSpokenGuidanceEnabled() {
        val policy = DefaultOutputPolicy()
        val decision = policy.evaluate(automaticEvent, AppSettings(spokenGuidanceEnabled = true))
        assertTrue(decision.speechAllowed)
        assertTrue(decision.liveRegionAllowed)
    }

    @Test
    fun evaluate_disallowsSpeechWhenSpokenGuidanceDisabled() {
        val policy = DefaultOutputPolicy()
        val decision = policy.evaluate(automaticEvent, AppSettings(spokenGuidanceEnabled = false))
        assertFalse(decision.speechAllowed)
        // Live region is a separate concern from the spokenGuidanceEnabled TTS toggle — it stays
        // on even when TTS is off (matches pre-#14 behavior).
        assertTrue(decision.liveRegionAllowed)
    }

    @Test
    fun evaluate_absoluteSilenceSuppressesAutomaticEvents() {
        val policy = DefaultOutputPolicy()
        val decision = policy.evaluate(automaticEvent, AppSettings(absoluteSilenceEnabled = true))
        assertFalse(decision.speechAllowed)
        assertFalse(decision.liveRegionAllowed)
    }

    @Test
    fun evaluate_absoluteSilenceSuppressesInteractionConfirmationEvents() {
        val policy = DefaultOutputPolicy()
        val decision =
            policy.evaluate(event(OutputOrigin.INTERACTION_CONFIRMATION), AppSettings(absoluteSilenceEnabled = true))
        assertFalse(decision.speechAllowed)
        assertFalse(decision.liveRegionAllowed)
    }

    @Test
    fun evaluate_absoluteSilenceExemptsUserRequestedEvents() {
        val policy = DefaultOutputPolicy()
        val decision =
            policy.evaluate(
                event(OutputOrigin.USER_REQUESTED),
                AppSettings(absoluteSilenceEnabled = true, spokenGuidanceEnabled = true),
            )
        assertTrue(decision.speechAllowed)
        assertTrue(decision.liveRegionAllowed)
    }
}
