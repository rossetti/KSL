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

package ksl.examples.general.agent

import ksl.modeling.agent.AgentModel
import ksl.modeling.agent.Cell
import ksl.modeling.agent.GridProjection
import ksl.modeling.agent.positive
import ksl.modeling.entity.KSLProcess
import ksl.modeling.variable.Counter
import ksl.modeling.variable.Response
import ksl.modeling.variable.TWResponse
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV

/**
 *  A worked example of `AgentGenerator`: an **open agent population** on a grid.
 *
 *  Where [GridEpidemicExample] creates a fixed population at the start of each
 *  replication, here agents *arrive over time* from a stochastic process, cross the
 *  grid, and depart — the agent-view reading of the arrival / service / departure
 *  pattern from the process view.
 *
 *  ## Why this example exists
 *
 *  `AgentGenerator` is the agent layer's mirror of
 *  `ksl.modeling.entity.ProcessModel.EntityGenerator`, and the most direct
 *  translation for a reader arriving from discrete-event modelling: "entities
 *  arrive from a Poisson process" becomes "agents arrive from a Poisson process".
 *  It is also the construct most easily reinvented by hand — a dedicated agent
 *  looping `delay(interArrival)` does the same job with more code and none of the
 *  generator controls.
 *
 *  ## The generator is a model field, and that is correct here
 *
 *  A `KSLProcess` is one-shot: an agent held as a model field and re-activated each
 *  replication fails on the second one. A *generator* held as a field is a different
 *  matter, and is the standard shape — it manufactures a **new** agent, with a new
 *  process, on every firing. So this example is also the antidote to that trap:
 *  keep the generator, not the agents.
 *
 *  ## Division of labour
 *
 *  The generator creates the agent, adds it to the `context` it was given, and
 *  activates its default process. It does not position the agent, because where a
 *  new agent belongs is a modelling decision — so [Shopper] placement happens in the
 *  factory lambda, which runs before activation.
 *
 *  ## It checks itself
 *
 *  Two properties make this model self-validating, which is unusual and worth
 *  exploiting when teaching:
 *
 *   - **Time in system is deterministic.** A shopper advances one column per step of
 *     [stepDuration], so crossing a [gridSize]-wide grid takes exactly
 *     `gridSize × stepDuration` regardless of the random walk in `y`.
 *   - **Little's law holds.** With arrival rate `λ = 1 / arrivalMean` and that fixed
 *     time in system `W`, the expected number in system is `L = λW`. At the defaults
 *     that is `0.5 × 20 = 10`; an observed average slightly below it is the
 *     empty-system warm-up, not an error.
 */
class AgentArrivalsExample(parent: ModelElement, name: String? = null) :
    AgentModel(parent, name) {

    // ── Tunable parameters ──────────────────────────────────────────────────

    var gridSize: Int by positive(Defaults.gridSize)
    var stepDuration: Double by positive(Defaults.stepDuration)
    var arrivalMean: Double by positive(Defaults.arrivalMean)

    /** Mutable global defaults for [AgentArrivalsExample]. */
    companion object Defaults {
        /** Side length of the square grid, in cells. Must be positive. */
        var gridSize: Int by positive(20)
        /** Time between movement steps. Must be positive. */
        var stepDuration: Double by positive(1.0)
        /** Mean inter-arrival time of shoppers. Must be positive. */
        var arrivalMean: Double by positive(2.0)
    }

    // ── Population infrastructure ───────────────────────────────────────────

    val shoppers: Context<Shopper> = Context("shoppers")
    val grid: GridProjection<Shopper> = GridProjection(
        context = shoppers, columns = gridSize, rows = gridSize, torus = false,
    )

    // ── Responses ───────────────────────────────────────────────────────────

    val numInSystem: TWResponse = TWResponse(this, "NumInSystem")
    val timeInSystem: Response = Response(this, "TimeInSystem")
    val numExited: Counter = Counter(this, "NumExited")

    private var created: Int = 0

    // ── Agent ───────────────────────────────────────────────────────────────

    /**
     *  A shopper: enters at the left edge, advances one column per step while
     *  wandering vertically, and departs at the right edge — leaving the
     *  [shoppers] context and disposing of itself on the way out.
     */
    inner class Shopper(aName: String) : Agent(aName) {

        val script: KSLProcess = process(isDefaultProcess = true) {
            val entryTime = currentTime
            numInSystem.increment()
            reportAnimationState("Shopping")

            while (true) {
                delay(stepDuration)
                val cell = grid.cellOf(this@Shopper) ?: break
                if (cell.col >= gridSize - 1) break
                val u = defaultRNStream.randU01()
                val row = when {
                    u < 0.25 -> (cell.row - 1).coerceAtLeast(0)
                    u < 0.50 -> (cell.row + 1).coerceAtMost(gridSize - 1)
                    else -> cell.row
                }
                grid.moveTo(this@Shopper, Cell(cell.col + 1, row))
            }

            timeInSystem.value = currentTime - entryTime
            numInSystem.decrement()
            numExited.increment()
            // Leaving the population and finishing are separate acts: remove ends
            // membership and stops the renderer drawing it, dispose ends behaviour.
            shoppers.remove(this@Shopper)
            dispose()
        }
    }

    // ── Arrivals ────────────────────────────────────────────────────────────

    /**
     *  The generator adds each new shopper to [shoppers]; the factory positions it.
     *  Held as a field deliberately — see the class KDoc.
     */
    private val arrivals = AgentGenerator(
        agentFactory = {
            Shopper("shopper-${++created}")
                .also { grid.placeAt(it, Cell(0, gridSize / 2)) }
        },
        timeUntilFirst = ExponentialRV(arrivalMean, streamNum = 1),
        timeBetween = ExponentialRV(arrivalMean, streamNum = 2),
        context = shoppers,
    )
}

fun main() {
    val model = Model("AgentArrivalsExample")
    val sys = AgentArrivalsExample(model, "arrivals")
    model.numberOfReplications = 5
    model.lengthOfReplication = 500.0
    model.simulate()
    model.print()

    val w = sys.timeInSystem.acrossReplicationStatistic.average
    val l = sys.numInSystem.acrossReplicationStatistic.average
    println()
    println("Little's law check: L = %.3f, lambda*W = %.3f".format(l, w / sys.arrivalMean))
}
