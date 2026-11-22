/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2026  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.examples.general.processview.waits.guiderules

import ksl.examples.general.processview.waits.CallCenter
import ksl.examples.general.processview.waits.ErlangA
import ksl.simulation.Model
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  A figure the waits tutorial (`ksl-waits-tutorial.md`, case 1, "What to learn") states but the example does not
 *  print: the agents of the impatient-callers centre are busy about 85% of the time on average. The callers answered,
 *  2.4 a minute times one less the probability of hanging up, each take 4 minutes of one of 10 agents, so the exact
 *  fraction busy is 2.4 (1 - P) 4 / 10, which with the exact P of 0.1174 is 0.847. The example's own model is run
 *  as its `main` runs it, and the exact fraction is required to lie inside the 99% interval of the agents' time-average
 *  utilization, and to be 85% when rounded to a whole percent.
 */
class WaitsTutorialFiguresTest {

    @Test
    @DisplayName("impatient callers: the agents are busy about 85% of the time on average")
    fun agentsBusy85Percent() {
        val exact = ErlangA(lambda = 2.4, mu = 1.0 / 4.0, theta = 1.0 / 3.0, c = 10)
        val busy = 2.4 * (1.0 - exact.probAbandon) * 4.0 / 10.0
        assertEquals(85, (busy * 100.0).roundToInt(), "the exact fraction busy, $busy, as a whole percent")

        val m = Model("ImpatientCallersBusy${System.nanoTime()}", autoCSVReports = false)
        CallCenter(m, name = "CallCenter")
        m.numberOfReplications = 20
        m.lengthOfReplication = 20_000.0
        m.lengthOfReplicationWarmUp = 2_000.0
        m.simulate()
        val util = m.responses.first { it.name == "Agents:InstantaneousUtil" }.acrossReplicationStatistic
        val ci = util.confidenceInterval(0.99)
        assertTrue(busy in ci, "the exact fraction busy $busy is outside the 99% interval [${ci.lowerLimit}, ${ci.upperLimit}]")
        assertEquals(85, (util.average * 100.0).roundToInt(), "the estimated fraction busy, ${util.average}, as a whole percent")
    }
}
