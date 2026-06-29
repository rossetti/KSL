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
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.examples.general.agent

import ksl.modeling.agent.AgentModel
import ksl.modeling.agent.Cell
import ksl.modeling.agent.GridMetric
import ksl.modeling.agent.GridProjection
import ksl.modeling.agent.nonNegative
import ksl.modeling.agent.positive
import ksl.modeling.agent.probability
import ksl.modeling.entity.KSLProcess
import ksl.modeling.variable.TWResponse
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rng.RNStreamIfc

/**
 *  A worked example of [GridProjection]: a simple agent-based SIR
 *  (Susceptible–Infected–Recovered) epidemic on a 2D torus. Agents
 *  perform random walks on Moore neighbors each time step. When a
 *  susceptible agent is in the same Moore neighborhood as an
 *  infected agent, it has a per-step probability of becoming
 *  infected. Infected agents recover after a deterministic duration.
 *
 *  Designed to be the smallest end-to-end exercise of the grid:
 *   - Fixed population of agents created at setup time.
 *   - Each agent is an `Agent` whose process body alternates
 *     `step()` and `delay(stepDuration)`.
 *   - `Context<Agent>` holds the population; `GridProjection<Agent>`
 *     gives them cell positions on a torus.
 *   - `TWResponse` instances track time-weighted population in each
 *     of the three SIR states.
 *
 *  Not a calibrated epidemiological model — the point is to exercise
 *  the grid + neighborhood-query API end-to-end.
 */
class GridEpidemicExample(parent: ModelElement, name: String? = null) :
    AgentModel(parent, name) {

    // ── World (initialized from Defaults) ──────────────────────────────────

    var gridSize: Int by positive(Defaults.gridSize)
    var stepDuration: Double by positive(Defaults.stepDuration)
    var population: Int by positive(Defaults.population)
    var initialInfected: Int by nonNegative(Defaults.initialInfected)
    var infectionProb: Double by probability(Defaults.infectionProb)
    var infectionDuration: Double by positive(Defaults.infectionDuration)

    /** Mutable global defaults for [GridEpidemicExample]. */
    companion object Defaults {
        /** Side length of the square grid, in cells. Must be positive. */
        var gridSize: Int by positive(20)
        /** Time between population update steps. Must be positive. */
        var stepDuration: Double by positive(1.0)
        /** Number of agents to spawn at initialization. Must be positive. */
        var population: Int by positive(50)
        /** Number of initially-infected agents at the start of each replication. Must be non-negative. */
        var initialInfected: Int by nonNegative(3)
        /** Per-step infection probability per contact. Must be in [0, 1]. */
        var infectionProb: Double by probability(0.1)
        /** Time units an agent stays infected before recovering. Must be positive. */
        var infectionDuration: Double by positive(14.0)
    }

    // ── Population infrastructure ───────────────────────────────────────────

    val people: Context<Person> = Context("people")
    val grid: GridProjection<Person> = GridProjection(
        context = people, columns = gridSize, rows = gridSize, torus = true,
    )

    // ── Responses ───────────────────────────────────────────────────────────

    val numSusceptible: TWResponse = TWResponse(this, "NumSusceptible")
    val numInfected: TWResponse = TWResponse(this, "NumInfected")
    val numRecovered: TWResponse = TWResponse(this, "NumRecovered")

    // ── Agent ───────────────────────────────────────────────────────────────

    enum class HealthState { SUSCEPTIBLE, INFECTED, RECOVERED }

    inner class Person(aName: String) : Agent(aName) {

        var state: HealthState = HealthState.SUSCEPTIBLE
            private set

        private var timeOfInfection: Double = Double.NaN

        fun infect() {
            require(state == HealthState.SUSCEPTIBLE) { "only susceptible can be infected" }
            state = HealthState.INFECTED
            timeOfInfection = currentTime
            numSusceptible.decrement(); numInfected.increment()
        }

        private fun recover() {
            state = HealthState.RECOVERED
            numInfected.decrement(); numRecovered.increment()
        }

        val script: KSLProcess = process(isDefaultProcess = true) {
            while (true) {
                delay(stepDuration)

                // Recovery check (deterministic duration).
                if (state == HealthState.INFECTED &&
                    currentTime - timeOfInfection >= infectionDuration
                ) {
                    recover()
                }

                // Infection check (susceptibles only).
                if (state == HealthState.SUSCEPTIBLE) {
                    val cell = grid.cellOf(this@Person) ?: continue
                    val nearby = grid.agentsWithin(
                        cell, radius = 1, metric = GridMetric.CHEBYSHEV, includeCenter = true,
                    )
                    val anyInfected = nearby.any {
                        it !== this@Person && it.state == HealthState.INFECTED
                    }
                    if (anyInfected && defaultRNStream.randU01() < infectionProb) {
                        infect()
                    }
                }

                // Random walk on Moore neighbors.
                val cell = grid.cellOf(this@Person) ?: continue
                val nbrs = grid.mooreNeighborhood(cell)
                val choice = nbrs[defaultRNStream.randInt(0, nbrs.size - 1)]
                grid.moveTo(this@Person, choice)
            }
        }
    }

    // ── Population creation ─────────────────────────────────────────────────

    /**
     *  Population for the current replication. Filled in [initialize]
     *  using the user-configurable [population] field, so tests can
     *  override [population] before calling `simulate()`. Person
     *  construction at runtime is safe because the Phase 2.5
     *  refactor made AgentMailbox a POJO.
     */
    val population_list: MutableList<Person> = mutableListOf()

    override fun initialize() {
        super.initialize()
        // (Re)create the population for this replication, using the
        // current value of `population`. Done here rather than in an
        // init block so tests can override `population` before
        // calling `simulate()`.
        population_list.clear()
        repeat(population) { i -> population_list.add(Person("person-$i")) }

        val stream: RNStreamIfc = defaultRNStream
        // First mark everyone as susceptible so the TWResponse
        // counters start at the right baseline; then infect() the
        // initial set, which moves them from susceptible to infected.
        for ((i, p) in population_list.withIndex()) {
            people.add(p)
            val c = stream.randInt(0, gridSize - 1)
            val r = stream.randInt(0, gridSize - 1)
            grid.placeAt(p, c, r)
            numSusceptible.increment()
            activate(p.script)
            if (i < initialInfected) p.infect()
        }
    }

    override fun afterReplication() {
        super.afterReplication()
        // Reset for next replication: clear grid state, reset counters
        // (TWResponses reset themselves automatically; just clear the
        // context membership and per-agent state).
        people.clear()
        for (p in population_list) {
            // No clean reset API for HealthState; just leave the
            // process to be re-activated. State is set by infect() /
            // initialize on next replication start.
        }
    }
}

fun main() {
    val model = Model("GridEpidemicExample")
    val sys = GridEpidemicExample(model, "epidemic")
    model.lengthOfReplication = 200.0
    model.numberOfReplications = 1
    model.simulate()
    model.print()
    val sus = sys.numSusceptible.acrossReplicationStatistic.average
    val inf = sys.numInfected.acrossReplicationStatistic.average
    val rec = sys.numRecovered.acrossReplicationStatistic.average
    println(
        "Time-weighted SIR: susceptible=${"%.1f".format(sus)}, " +
            "infected=${"%.1f".format(inf)}, recovered=${"%.1f".format(rec)}",
    )
}
