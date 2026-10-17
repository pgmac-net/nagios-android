// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.theme

import net.pgmac.nagwatch.nagios.model.HostState
import net.pgmac.nagwatch.nagios.model.ServiceState
import net.pgmac.nagwatch.nagios.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Test

class StatePresentationTest {
    private val themes = listOf(LightStateColors, DarkStateColors)

    /** The state each severity is a severity of. */
    private val stateOf = mapOf(
        Severity.HOST_DOWN to HostState.DOWN,
        Severity.HOST_UNREACHABLE to HostState.UNREACHABLE,
        Severity.CRITICAL to ServiceState.CRITICAL,
        Severity.WARNING to ServiceState.WARNING,
        Severity.UNKNOWN to ServiceState.UNKNOWN,
    )

    @Test
    fun `a problem is drawn in the colour of its state, in both themes`() {
        assertEquals(Severity.entries.toSet(), stateOf.keys)
        themes.forEach { colors ->
            stateOf.forEach { (severity, state) ->
                val expected = when (state) {
                    is HostState -> colors.of(state)
                    is ServiceState -> colors.of(state)
                    else -> error("not a state: $state")
                }
                assertEquals("$severity", expected, colors.of(severity))
            }
        }
    }

    @Test
    fun `an unreachable host is unknown, not down`() {
        themes.forEach { colors ->
            assertEquals(colors.unknown, colors.of(Severity.HOST_UNREACHABLE))
            assertEquals(colors.unknown, colors.of(HostState.UNREACHABLE))
            assertEquals(colors.critical, colors.of(Severity.HOST_DOWN))
        }
    }

    @Test
    fun `a problem has the same word as its state`() {
        assertEquals(HostState.DOWN.shortWord(), Severity.HOST_DOWN.shortWord())
        assertEquals(ServiceState.CRITICAL.shortWord(), Severity.CRITICAL.shortWord())
        assertEquals(ServiceState.WARNING.shortWord(), Severity.WARNING.shortWord())
        assertEquals(ServiceState.UNKNOWN.shortWord(), Severity.UNKNOWN.shortWord())
    }
}
