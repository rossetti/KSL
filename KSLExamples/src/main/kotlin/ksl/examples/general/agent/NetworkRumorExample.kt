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
import ksl.modeling.agent.NetworkProjection
import ksl.modeling.agent.positive
import ksl.modeling.agent.probability
import ksl.modeling.entity.KSLProcess
import ksl.modeling.variable.Response
import ksl.modeling.variable.TWResponse
import ksl.simulation.Model
import ksl.simulation.ModelElement

/**
 *  A worked example of [NetworkProjection]: rumor spread on a
 *  random social-contact graph (Erdős–Rényi G(n, p)). The example
 *  exercises the network projection's relationship API while staying
 *  small and easy to read:
 *
 *   - At setup time, [population] agents are created and added to a
 *     `Context<Person>`. A `NetworkProjection<Person>` lays edges
 *     between each unordered pair with independent probability
 *     [edgeProbability].
 *   - One randomly-chosen agent starts with the rumor.
 *   - Each step (`stepDuration` simulated time units), every agent
 *     who knows the rumor independently tries to tell each
 *     friend (neighbor on the projection); each tell succeeds with
 *     probability [tellProbability].
 *   - Once everyone in the start agent's connected component knows
 *     the rumor, the system has reached its absorbing state.
 *
 *  Notes:
 *   - This is a classic "independent cascade" diffusion model.
 *   - With a single connected component (typical for `n=50, p≈0.1`),
 *     the rumor reaches everyone eventually.
 *   - The example shows the network projection's [NetworkProjection.connect]
 *     setup, [NetworkProjection.neighborsOf] inside the spread step,
 *     and [NetworkProjection.reachableFrom] for sanity-checking the
 *     connected component at startup.
 */
class NetworkRumorExample(parent: ModelElement, name: String? = null) :
    AgentModel(parent, name) {

    // ── Configuration (initialized from Defaults) ──────────────────────────

    var population: Int by positive(Defaults.population)
    var edgeProbability: Double by probability(Defaults.edgeProbability)
    var tellProbability: Double by probability(Defaults.tellProbability)
    var stepDuration: Double by positive(Defaults.stepDuration)

    /** Mutable global defaults for [NetworkRumorExample]. */
    companion object Defaults {
        /** Total number of agents. Must be positive. */
        var population: Int by positive(50)
        /** Per-pair probability of an undirected friendship edge in G(n, p). Must be in [0, 1]. */
        var edgeProbability: Double by probability(0.10)
        /** Per-step probability of an informed friend passing the rumor to a neighbor. Must be in [0, 1]. */
        var tellProbability: Double by probability(0.10)
        /** Time between spread steps. Must be positive. */
        var stepDuration: Double by positive(1.0)
    }

    // ── Population infrastructure ──────────────────────────────────────────

    val people: Context<Person> = Context("people")
    val friendships: NetworkProjection<Person> = NetworkProjection(people, directed = false)

    /** Number of agents currently informed (time-weighted). */
    val numInformed: TWResponse = TWResponse(this, "NumInformed")

    /** Simulated time at which the last new informed agent learned the rumor. */
    val timeToFullSpread: Response = Response(this, "TimeToFullSpread")

    /** Size of the start agent's connected component. */
    val componentSize: Response = Response(this, "ComponentSize")

    // ── Agent ──────────────────────────────────────────────────────────────

    inner class Person(aName: String) : Agent(aName) {

        var informed: Boolean = false
            private set

        fun setInformed() {
            if (informed) return
            informed = true
            numInformed.increment()
        }

        val script: KSLProcess = process(isDefaultProcess = true) {
            while (true) {
                delay(stepDuration)
                if (!informed) continue
                // Try to tell each friend.
                for (friend in friendships.neighborsOf(this@Person)) {
                    if (!friend.informed && defaultRNStream.randU01() < tellProbability) {
                        friend.setInformed()
                        // Record the latest informed-time.
                        lastInformedTime = currentTime
                    }
                }
            }
        }
    }

    /** Updated each time a new agent learns the rumor; written to the
     *  response at the end of the replication. */
    private var lastInformedTime: Double = 0.0

    val populationList: MutableList<Person> = mutableListOf()

    override fun initialize() {
        super.initialize()
        // Construct fresh agents for this replication.
        populationList.clear()
        repeat(population) { i -> populationList.add(Person("person-$i")) }
        for (p in populationList) people.add(p)

        // Wire up the random friendship graph.
        val stream = defaultRNStream
        for (i in populationList.indices) {
            for (j in i + 1 until populationList.size) {
                if (stream.randU01() < edgeProbability) {
                    friendships.connect(populationList[i], populationList[j])
                }
            }
        }

        // Seed: pick a random agent and inform them.
        val seedIndex = stream.randInt(0, population - 1)
        val seed = populationList[seedIndex]
        seed.setInformed()
        lastInformedTime = time

        // Record the seed's connected component size for the smoke test.
        componentSize.value = friendships.reachableFrom(seed).size.toDouble()

        // Start everyone's spread loop.
        for (p in populationList) activate(p.script)
    }

    override fun replicationEnded() {
        super.replicationEnded()
        timeToFullSpread.value = lastInformedTime
    }
}

fun main() {
    val model = Model("NetworkRumorExample")
    val sys = NetworkRumorExample(model, "rumor")
    model.lengthOfReplication = 200.0
    model.numberOfReplications = 1
    model.simulate()
    model.print()
}
