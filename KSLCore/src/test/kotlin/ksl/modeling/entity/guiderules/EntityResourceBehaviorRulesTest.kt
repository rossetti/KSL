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

package ksl.modeling.entity.guiderules

import ksl.modeling.entity.ResourceWithQ
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 *  A rule of the entity guide's section on giving a resource its own behavior: every replication, the resource
 *  starts its behavior at time 0, after every element of the model has initialized.
 */
class EntityResourceBehaviorRulesTest {

    /**
     *  R declares a behavior that records when it starts. An element made after R, so initialized after it, records
     *  its initialization. In each of two replications the element's initialization comes first, and the behavior
     *  starts at 0.
     */
    @Test
    @DisplayName("the behavior starts at time 0 after every element has initialized, in every replication")
    fun behaviorStartsAfterEveryElementInitialized() {
        val events = mutableListOf<String>()
        val m = RuleModel("BehaviorStart")
        val r = ResourceWithQ(m, "R")
        r.behavior { events.add("behavior@${m.time}") }
        object : ModelElement(m, "InitializedAfterR") {
            override fun initialize() {
                events.add("element initialized")
            }
        }
        m.simulate(10.0, reps = 2)
        assertEquals(
            listOf("element initialized", "behavior@0.0", "element initialized", "behavior@0.0"),
            events
        )
    }
}
