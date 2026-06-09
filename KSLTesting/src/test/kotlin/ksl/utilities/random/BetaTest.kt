/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2022  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.utilities.random

import ksl.utilities.random.rvariable.KSLRandom
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class BetaTest {

    /**
     * First 10 values of JSLRandom.rBeta(2.4, 5.5, 1) captured from
     * JSLCore R1.0.12. Locks the bit-exact stream KSLRandom.rBeta produces
     * against the legacy parity reference.
     */
    private val expected = doubleArrayOf(
        0.12896713131367227,
        0.21334391742665174,
        0.2095659411164776,
        0.45417894707475426,
        0.17311771767489864,
        0.30057404483526967,
        0.27864877828207596,
        0.2282225939131053,
        0.13354477127910927,
        0.4087271078813605,
    )

    @Test
    fun rBetaMatchesFrozenStream() {
        // Frozen values were captured from a freshly-created stream 1.
        // DefaultRNStreamProvider's stream 1 is a JVM-wide singleton, so any
        // earlier test in the suite that touches it would shift the position.
        KSLRandom.rnStream(1).resetStartStream()
        for (i in expected.indices) {
            val a = KSLRandom.rBeta(2.4, 5.5, 1)
            assertEquals(expected[i], a, "iteration ${i + 1}")
        }
    }
}
