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

package ksl.modeling.agent

import ksl.utilities.random.rng.RNStreamIfc

/**
 *  A composable view over a group of agents.
 *
 *  Following the convention adopted by Mesa 3.0, a `Population` is not a
 *  scheduler — it does not decide *when* its members act. It is a typed
 *  collection with the operations a modeler typically needs when expressing
 *  agent activation: shuffle, filter by type, filter by predicate, group.
 *  Iteration order is the caller's choice:
 *
 *  ```
 *  // random-order activation
 *  population.shuffled(stream).forEach { it.step() }
 *
 *  // staged activation
 *  population.ofType<Predator>().forEach { it.hunt() }
 *  population.ofType<Prey>().forEach { it.flee() }
 *
 *  // two-phase simultaneous activation
 *  population.forEach { it.preStep() }
 *  population.forEach { it.commit() }
 *  ```
 *
 *  A `Population` is a live view: changes to the underlying agent collection
 *  (for example, new agents created by an [AgentModel.AgentGenerator]) are
 *  reflected on the next iteration.
 */
class Population<A : AgentModel.Agent>(
    private val source: () -> Iterable<A>,
) : Iterable<A> {

    /**
     *  Construct a population that always reflects the current contents of
     *  [agentModel].`agents`, filtered to instances of [A].
     */
    constructor(agentModel: AgentModel, type: Class<A>) : this({
        @Suppress("UNCHECKED_CAST")
        agentModel.agents.filter { type.isInstance(it) } as List<A>
    })

    /**
     *  Construct a population backed by a snapshot of [members]. The
     *  population view reflects later mutations to [members] if it is a
     *  live collection.
     */
    constructor(members: List<A>) : this({ members })

    override fun iterator(): Iterator<A> = source().iterator()

    /**
     *  Number of agents currently in this population.
     */
    val size: Int
        get() = source().count()

    /**
     *  True when no agents are in this population.
     */
    val isEmpty: Boolean
        get() = !source().iterator().hasNext()

    /**
     *  Return a new list containing the agents in a random order, drawn
     *  from [stream]. Using an explicit stream preserves reproducibility
     *  across replications.
     */
    fun shuffled(stream: RNStreamIfc): List<A> {
        val list = source().toMutableList()
        // Fisher-Yates using the supplied stream for reproducibility.
        for (i in list.indices.reversed()) {
            val j = stream.randInt(0, i)
            if (i != j) {
                val tmp = list[i]
                list[i] = list[j]
                list[j] = tmp
            }
        }
        return list
    }

    /**
     *  Return a new list of agents whose type is exactly [T] or a subtype.
     */
    inline fun <reified T : A> ofType(): List<T> =
        this.filterIsInstance<T>()

    /**
     *  Return a new list of agents matching [predicate].
     */
    fun where(predicate: (A) -> Boolean): List<A> =
        source().filter(predicate)

    /**
     *  Group the population by the value of [keySelector].
     */
    fun <K> groupBy(keySelector: (A) -> K): Map<K, List<A>> =
        source().groupBy(keySelector)

    /**
     *  Convenience: return an [AgentModel.AgentMailbox]-bound list of
     *  (agent, mailbox) pairs for use in broadcast scenarios. The
     *  mailbox returned is the agent's default mailbox.
     */
    fun mailboxes(): List<Pair<A, AgentModel.AgentMailbox<AgentMessage>>> =
        source().map { it to it.mailbox }
}
