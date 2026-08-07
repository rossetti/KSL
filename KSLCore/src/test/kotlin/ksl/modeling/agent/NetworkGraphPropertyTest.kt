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

package ksl.modeling.agent

import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.KSLRandom
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  Differential tests for `NetworkProjection`'s graph algorithms (Phase A1.3 / A1.4).
 *
 *  Unlike the grid and voxel suites — where `shortestPath` with a ZERO heuristic is
 *  itself Dijkstra and can serve as the oracle — an abstract graph has no such
 *  built-in reference. So both references here are written independently in the
 *  test, over the edge list the test itself generated:
 *
 *   - **Weighted shortest path** is checked against exhaustive enumeration of
 *     simple paths. With non-negative weights an optimal walk always reduces to a
 *     simple path (dropping a cycle cannot increase cost), so the minimum over
 *     simple paths is the true optimum.
 *   - **Strongly connected components** are checked against mutual reachability
 *     computed by the test's own BFS. Deliberately *not* via
 *     `NetworkProjection.reachableFrom` — using the library to check the library
 *     would let a shared defect agree with itself.
 *
 *  Graphs are kept small (5–7 nodes) so exhaustive enumeration stays cheap, and are
 *  generated from KSL's own `RNStreamProvider` so each case is reproducible from
 *  its stream number.
 */
class NetworkGraphPropertyTest {

    private class NetModel(parent: ModelElement, directed: Boolean) :
        AgentModel(parent, "netPropModel") {
        val context: Context<Agent> = Context("nodes")
        val net: NetworkProjection<Agent> = NetworkProjection(context, directed = directed)
        inner class Node(aName: String) : Agent(aName)
    }

    /**
     *  A generated graph plus the test's own record of its edges, so the reference
     *  implementations never consult the object under test.
     */
    private class Generated(
        val model: NetModel,
        val nodes: List<AgentModel.Agent>,
        /** Outgoing adjacency as the test built it: from -> (to -> weight). */
        val adjacency: Map<AgentModel.Agent, Map<AgentModel.Agent, Double>>,
    )

    private fun generate(
        streamNum: Int,
        nodeCount: Int,
        directed: Boolean,
        edgeProbability: Double,
        weighted: Boolean,
    ): Generated {
        val rng = KSLRandom.rnStream(streamNum)
        val model = Model("NetProp-$streamNum-$directed")
        val tm = NetModel(model, directed)
        val nodes = (0 until nodeCount).map { tm.Node("n$it") }
        nodes.forEach { tm.context.add(it) }

        val adj = HashMap<AgentModel.Agent, MutableMap<AgentModel.Agent, Double>>()
        fun record(a: AgentModel.Agent, b: AgentModel.Agent, w: Double) {
            adj.getOrPut(a) { HashMap() }[b] = w
        }

        for (i in nodes.indices) {
            for (j in nodes.indices) {
                if (i == j) continue
                // For undirected graphs only consider each pair once; connect()
                // installs both directions itself.
                if (!directed && j < i) continue
                if (rng.randU01() >= edgeProbability) continue
                val w = if (weighted) 0.5 + rng.randU01() * 4.0 else 1.0
                tm.net.connect(nodes[i], nodes[j], w)
                record(nodes[i], nodes[j], w)
                if (!directed) record(nodes[j], nodes[i], w)
            }
        }
        return Generated(tm, nodes, adj)
    }

    // ── Reference implementations (independent of the object under test) ─────

    /**
     *  Minimum total weight over all simple paths from [from] to [to], or null if
     *  none exists. Exhaustive with branch-and-bound pruning.
     */
    private fun bruteForceMinWeight(
        adjacency: Map<AgentModel.Agent, Map<AgentModel.Agent, Double>>,
        from: AgentModel.Agent,
        to: AgentModel.Agent,
    ): Double? {
        if (from === to) return 0.0
        var best: Double? = null
        val onPath = HashSet<AgentModel.Agent>()

        fun dfs(current: AgentModel.Agent, accumulated: Double) {
            if (current === to) {
                if (best == null || accumulated < best!!) best = accumulated
                return
            }
            onPath.add(current)
            for ((next, w) in adjacency[current] ?: emptyMap()) {
                if (next in onPath) continue
                val bound = best
                if (bound != null && accumulated + w >= bound) continue
                dfs(next, accumulated + w)
            }
            onPath.remove(current)
        }

        dfs(from, 0.0)
        return best
    }

    /** Forward reachability by the test's own BFS over its own adjacency. */
    private fun reachableSet(
        adjacency: Map<AgentModel.Agent, Map<AgentModel.Agent, Double>>,
        start: AgentModel.Agent,
    ): Set<AgentModel.Agent> {
        val seen = LinkedHashSet<AgentModel.Agent>()
        seen.add(start)
        val queue = ArrayDeque<AgentModel.Agent>()
        queue.addLast(start)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            for (next in (adjacency[cur] ?: emptyMap()).keys) {
                if (seen.add(next)) queue.addLast(next)
            }
        }
        return seen
    }

    /**
     *  Mutual-reachability equivalence classes over the nodes that have at least one
     *  incident edge. `NetworkProjection.stronglyConnectedComponents` derives its
     *  node set from the adjacency maps, so edge-less nodes appear in no component;
     *  the reference matches that convention, and the semantics are pinned
     *  separately by [isolatedNodesBelongToNoComponent].
     */
    private fun referenceSccs(g: Generated): Set<Set<AgentModel.Agent>> {
        val incident = g.nodes.filter { n ->
            (g.adjacency[n]?.isNotEmpty() == true) ||
                g.adjacency.values.any { n in it.keys }
        }
        val reach = incident.associateWith { reachableSet(g.adjacency, it) }
        return incident.map { a ->
            incident.filter { b -> b in reach.getValue(a) && a in reach.getValue(b) }.toSet()
        }.toSet()
    }

    // ── A1.3 — weighted shortest path ────────────────────────────────────────

    @Test
    @DisplayName("A1.3: weightedShortestPath cost equals exhaustive simple-path minimum")
    fun weightedShortestPathMatchesBruteForce() {
        var compared = 0
        for (directed in listOf(false, true)) {
            for (i in 0 until 10) {
                val g = generate(
                    streamNum = 1 + i,
                    nodeCount = 5 + (i % 3),
                    directed = directed,
                    edgeProbability = 0.35,
                    weighted = true,
                )
                for (from in g.nodes) {
                    for (to in g.nodes) {
                        val expected = bruteForceMinWeight(g.adjacency, from, to)
                        val actual = g.model.net.weightedShortestPath(from, to)
                        compared++
                        if (expected == null) {
                            assertNull(
                                actual,
                                "expected no path ${from.name}->${to.name} " +
                                    "(directed=$directed, stream=${1 + i})",
                            )
                        } else {
                            assertNotNull(
                                actual,
                                "expected a path ${from.name}->${to.name} " +
                                    "(directed=$directed, stream=${1 + i})",
                            )
                            assertEquals(
                                expected, actual!!.totalWeight, 1e-9,
                                "cost mismatch ${from.name}->${to.name} " +
                                    "(directed=$directed, stream=${1 + i})",
                            )
                        }
                    }
                }
            }
        }
        assertTrue(compared > 500, "expected a meaningful number of comparisons; was $compared")
    }

    /**
     *  A returned path must actually be walkable: consecutive nodes joined by real
     *  edges, and the reported total equal to the sum of those edge weights.
     */
    @Test
    @DisplayName("A1.3: returned path is walkable and its weights sum to totalWeight")
    fun weightedShortestPathIsInternallyConsistent() {
        for (directed in listOf(false, true)) {
            for (i in 0 until 8) {
                val g = generate(1 + i, 6, directed, 0.4, weighted = true)
                for (from in g.nodes) {
                    for (to in g.nodes) {
                        val path = g.model.net.weightedShortestPath(from, to) ?: continue
                        assertEquals(from, path.nodes.first())
                        assertEquals(to, path.nodes.last())
                        var sum = 0.0
                        for (k in 0 until path.nodes.size - 1) {
                            val a = path.nodes[k]
                            val b = path.nodes[k + 1]
                            val w = g.model.net.weightOf(a, b)
                            assertNotNull(w, "path traverses a non-edge ${a.name}->${b.name}")
                            sum += w!!
                        }
                        assertEquals(sum, path.totalWeight, 1e-9, "totalWeight disagrees with edges")
                    }
                }
            }
        }
    }

    // ── A1.4 — strongly connected components ─────────────────────────────────

    @Test
    @DisplayName("A1.4: stronglyConnectedComponents equals mutual-reachability classes")
    fun sccMatchesMutualReachability() {
        var multiNodeComponents = 0
        var singletonComponents = 0
        for (directed in listOf(true, false)) {
            for (i in 0 until 12) {
                val g = generate(1 + i, 6 + (i % 2), directed, 0.3, weighted = false)
                val actual = g.model.net.stronglyConnectedComponents().map { it.toSet() }.toSet()
                val expected = referenceSccs(g)
                assertEquals(
                    expected, actual,
                    "SCC mismatch (directed=$directed, stream=${1 + i})",
                )
                for (c in actual) if (c.size > 1) multiNodeComponents++ else singletonComponents++
            }
        }
        // Anti-vacuity: a sweep that only ever produced singletons would pass while
        // exercising none of Tarjan's cycle handling.
        assertTrue(
            multiNodeComponents > 0,
            "sweep produced no multi-node SCC, so cycle detection went untested",
        )
        assertTrue(
            singletonComponents > 0,
            "sweep produced no singleton SCC, so the trivial case went untested",
        )
    }

    @Test
    @DisplayName("A1.4: components are pairwise disjoint and cover every incident node")
    fun sccPartitionsTheIncidentNodes() {
        for (directed in listOf(true, false)) {
            for (i in 0 until 10) {
                val g = generate(1 + i, 7, directed, 0.3, weighted = false)
                val components = g.model.net.stronglyConnectedComponents()
                val union = mutableSetOf<AgentModel.Agent>()
                var total = 0
                for (c in components) {
                    union.addAll(c)
                    total += c.size
                }
                assertEquals(total, union.size, "components overlap (stream=${1 + i})")

                val incident = g.nodes.filter {
                    g.model.net.degreeOf(it) > 0 || g.model.net.inDegreeOf(it) > 0
                }.toSet()
                assertEquals(incident, union, "components do not cover the incident nodes")
            }
        }
    }

    @Test
    @DisplayName("A1.4: sccContaining and largestSCC agree with the component list")
    fun sccAccessorsAgreeWithComponentList() {
        for (directed in listOf(true, false)) {
            for (i in 0 until 8) {
                val g = generate(1 + i, 6, directed, 0.35, weighted = false)
                val components = g.model.net.stronglyConnectedComponents()
                for (n in g.nodes) {
                    val owning = components.firstOrNull { n in it }
                    assertEquals(owning, g.model.net.sccContaining(n), "sccContaining(${n.name})")
                }
                if (components.isNotEmpty()) {
                    assertEquals(
                        components.maxOf { it.size },
                        g.model.net.largestSCC().size,
                        "largestSCC size",
                    )
                }
            }
        }
    }

    /**
     *  Pins the documented convention: `stronglyConnectedComponents` builds its node
     *  set from the adjacency maps, so a node with no incident edge belongs to no
     *  component and `sccContaining` returns null for it — rather than each isolated
     *  node forming a singleton component.
     */
    @Test
    @DisplayName("A1.4: an edge-less node belongs to no component")
    fun isolatedNodesBelongToNoComponent() {
        val model = Model("NetIsolated")
        val tm = NetModel(model, directed = true)
        val a = tm.Node("a")
        val b = tm.Node("b")
        val lonely = tm.Node("lonely")
        listOf(a, b, lonely).forEach { tm.context.add(it) }
        tm.net.connect(a, b)
        tm.net.connect(b, a)

        val components = tm.net.stronglyConnectedComponents()
        assertEquals(setOf(setOf(a, b)), components.map { it.toSet() }.toSet())
        assertNull(tm.net.sccContaining(lonely), "an edge-less node has no component")
        assertTrue(components.none { lonely in it })
    }
}
