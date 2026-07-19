package com.boldexplorer.shared.output

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun event(kind: OutputKind) =
    OutputEvent(
        kind = kind,
        category = OutputCategory.SYSTEM,
        origin = OutputOrigin.AUTOMATIC,
        speech = kind.name,
    )

class OutputManagerTest {
    @Test
    fun emit_preservesEmissionOrder() =
        runBlocking {
            val manager = OutputManager()
            val received = mutableListOf<OutputKind>()
            val routerScope = CoroutineScope(Job())

            manager.emit(event(OutputKind.WAYPOINT_MARKED))
            manager.emit(event(OutputKind.TRAIL_CREATED))
            manager.emit(event(OutputKind.COPY_COORDINATES))

            manager.startConsuming(routerScope) { received += it.kind }
            delay(50)
            routerScope.cancel()

            assertEquals(
                listOf(OutputKind.WAYPOINT_MARKED, OutputKind.TRAIL_CREATED, OutputKind.COPY_COORDINATES),
                received,
            )
        }

    @Test
    fun startConsuming_isIdempotent() =
        runBlocking {
            val manager = OutputManager()
            val firstReceived = mutableListOf<OutputKind>()
            val secondReceived = mutableListOf<OutputKind>()
            val routerScope = CoroutineScope(Job())

            val startedFirst = manager.startConsuming(routerScope) { firstReceived += it.kind }
            val startedSecond = manager.startConsuming(routerScope) { secondReceived += it.kind }

            manager.emit(event(OutputKind.WAYPOINT_MARKED))
            delay(50)
            routerScope.cancel()

            assertTrue(startedFirst)
            assertFalse(startedSecond)
            assertEquals(listOf(OutputKind.WAYPOINT_MARKED), firstReceived)
            assertTrue(secondReceived.isEmpty())
        }

    @Test
    fun startConsuming_isolatesPerEventFailures() =
        runBlocking {
            val manager = OutputManager()
            val received = mutableListOf<OutputKind>()
            val routerScope = CoroutineScope(Job())

            manager.emit(event(OutputKind.WAYPOINT_MARKED))
            manager.emit(event(OutputKind.TRAIL_CREATED))
            manager.emit(event(OutputKind.COPY_COORDINATES))

            manager.startConsuming(routerScope) {
                if (it.kind == OutputKind.TRAIL_CREATED) error("simulated sink failure")
                received += it.kind
            }
            delay(50)
            routerScope.cancel()

            // The middle event's handler threw, but the loop must still process the one after it.
            assertEquals(listOf(OutputKind.WAYPOINT_MARKED, OutputKind.COPY_COORDINATES), received)
        }
}
