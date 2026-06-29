/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2024  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.examples.general.animationbundle

import ksl.animation.AnimationLayout
import ksl.animation.animation
import ksl.examples.general.agent.NetworkRumorExample
import ksl.simulation.Model

/**
 * Example 16 — rumor spread on a social network (G7). A `NetworkProjection` holds a `G(n, p)` friendship
 * graph; one seed agent starts informed and tells neighbors each step. The graph is non-spatial, so the
 * animation lays the people out on a **circle** (auto layout) and draws the friendship edges; each person
 * recolors from **uninformed** (blue) to **informed** (red) as the rumor reaches them.
 *
 * The network backdrop is derived from the trace (`NetworkDefined`), so the layout authors **no** space —
 * authoring one would replace the derived network. Read-outs sit in the empty middle of the ring.
 */
object Example16NetworkRumor {

    private const val POPULATION = 30

    // The circular auto-layout uses radius = population, centered at (radius, radius); so the ring spans
    // roughly [0, 2·POPULATION] on each axis. The center is clear of nodes — a natural spot for read-outs.
    private const val SPAN = 2.0 * POPULATION

    fun buildModel(): Model {
        val m = Model("NetworkRumorModel")
        NetworkRumorExample(m, "rumor").apply { population = POPULATION }
        m.numberOfReplications = 1
        m.lengthOfReplication = 120.0
        return m
    }

    fun buildLayout(model: Model): AnimationLayout = model.animation {
        title = "Rumor Spread on a Social Network (agent demo)"
        size(SPAN, SPAN)
        clock(POPULATION - 6.0, POPULATION - 6.0)

        objectClass("Person") { color = "#1f77b4"; size = 1.4 }
        // Recolor people by rumor state (substring-matched against the agent's reported state).
        agentStateColor("Uninformed", "#1f77b4")
        agentStateColor("Informed", "#d62728")

        // Live count in the empty center of the friendship ring.
        value("NumInformed", POPULATION - 8.0, POPULATION + 2.0, label = "Informed", decimals = 0)
    }

}
