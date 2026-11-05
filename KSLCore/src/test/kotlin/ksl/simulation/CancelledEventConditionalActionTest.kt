/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
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
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ksl.simulation

import ksl.modeling.agent.AgentModel
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** A condition becomes true at t=1, and the next live event is at t=5. */
private class DirectCondition(parent: ModelElement, private val cancelSameTimeEvent: Boolean) : ModelElement(parent) {
    private var flag = false
    var firedAt = Double.NaN

    private val conditional = object : ConditionalAction() {
        override fun testCondition() = flag && firedAt.isNaN()
        override fun action() {
            firedAt = time
        }
    }

    override fun registerConditionalActions() {
        executive.register(conditional)
    }

    override fun initialize() {
        flag = false
        firedAt = Double.NaN
        schedule(EventActionIfc<Nothing> {
            flag = true
            if (cancelSameTimeEvent) {
                schedule(EventActionIfc<Nothing> { }, 0.0).cancel = true
            }
        }, 1.0)
        schedule(EventActionIfc<Nothing> { }, 5.0)
    }
}

/** The same timing, through a statechart's onCondition. */
private class StatechartCondition(parent: ModelElement, private val cancelSameTimeEvent: Boolean) :
    AgentModel(parent, "StatechartCondition") {
    private var flag = false
    var firedAt = Double.NaN

    inner class Sentinel : Agent("sentinel") {
        init {
            statechart {
                initial("waiting")
                state("waiting") {
                    onCondition({ flag }) {
                        firedAt = time
                        transitionTo("fired")
                    }
                }
                state("fired") {}
            }
        }
    }

    private val sentinel = Sentinel()

    override fun initialize() {
        super.initialize()
        flag = false
        firedAt = Double.NaN
        schedule(EventActionIfc<Nothing> {
            flag = true
            if (cancelSameTimeEvent) {
                schedule(EventActionIfc<Nothing> { }, 0.0).cancel = true
            }
        }, 1.0)
        schedule(EventActionIfc<Nothing> { }, 5.0)
    }
}

@DisplayName("Conditional actions after a cancelled event")
class CancelledEventConditionalActionTest {

    private fun model(): Model {
        val model = Model("ConditionalAction${System.nanoTime()}", autoCSVReports = false)
        model.numberOfReplications = 1
        model.lengthOfReplication = 10.0
        return model
    }

    @Test
    @DisplayName("a condition fires at its own time without a cancelled event")
    fun firesWithoutACancelledEvent() {
        val model = model()
        val element = DirectCondition(model, false)
        model.simulate()
        assertEquals(1.0, element.firedAt)
    }

    @Test
    @DisplayName("a cancelled event due at the same time doesn't delay the condition")
    fun cancelledEventDoesNotDelayTheCondition() {
        val model = model()
        val element = DirectCondition(model, true)
        model.simulate()
        assertEquals(1.0, element.firedAt)
    }

    @Test
    @DisplayName("a cancelled event doesn't delay a statechart's onCondition")
    fun cancelledEventDoesNotDelayOnCondition() {
        val model = model()
        val element = StatechartCondition(model, true)
        model.simulate()
        assertEquals(1.0, element.firedAt)
    }
}
