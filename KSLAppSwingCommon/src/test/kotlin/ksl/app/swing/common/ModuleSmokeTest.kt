package ksl.app.swing.common

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Bootstrap smoke test for `KSLAppSwingCommon`.
 *
 * Exists so the module's `test` source set is non-empty from the
 * bootstrap commit forward — preventing a silent
 * "no-tests-discovered" state in CI as widget commits begin landing.
 * Replace or remove once the first real widget test arrives.
 */
class ModuleSmokeTest {
    @Test
    fun `module builds and tests run`() {
        assertEquals(4, 2 + 2)
    }
}
