package ksl.simulation

import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SimulationDispatcherTest {

    @Test
    fun `availableProcessors matches runtime`() {
        assertEquals(
            Runtime.getRuntime().availableProcessors(),
            SimulationDispatcher.availableProcessors
        )
    }

    @Test
    fun `default dispatcher is non-null`() {
        assertNotNull(SimulationDispatcher.default)
    }

    @Test
    fun `default can be replaced with a custom dispatcher`() {
        val original = SimulationDispatcher.default
        try {
            val custom = Dispatchers.IO.limitedParallelism(2)
            SimulationDispatcher.default = custom
            assertSame(custom, SimulationDispatcher.default)
        } finally {
            SimulationDispatcher.default = original
        }
    }
}
