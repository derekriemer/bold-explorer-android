package com.boldexplorer.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On/off switch for hearing off-trail / wrong-way alerts that the grace window would otherwise
 * mute, following [ShadowMatchMonitor]'s pattern.
 *
 * Grace stopped being a hard mute in S6 (ADR 0001): `TrailGuidanceCoordinator` now evaluates every
 * fix inside the grace window and records what it would have decided, but still withholds the
 * alert unless `shadowAlertsAudible` is set. That default keeps the shadow strictly a measurement —
 * a walk's log tells you what dropping grace would have done, and nothing new is spoken.
 *
 * Defaults to **false**, unlike [ShadowMatchMonitor.enabled] (which defaults on). The asymmetry is
 * deliberate: that switch governs logging, where the failure mode of leaving it off is a silent gap
 * in the record. This one governs what is *said out loud* during a real walk, so the safer default
 * is off — a count in a log costs nothing to review later, but an unexpected extra alert costs
 * something in the moment, for a user walking alone.
 */
@Singleton
class ShadowAlertsMonitor
    @Inject
    constructor() {
        private val _audible = MutableStateFlow(false)

        /** Whether grace-suppressed off-trail/backtrack alerts are spoken anyway. */
        val audible: StateFlow<Boolean> = _audible.asStateFlow()

        fun setAudible(audible: Boolean) {
            _audible.value = audible
        }
    }
