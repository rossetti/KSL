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

import ksl.examples.general.agent.CorridorPedestrianExample
import ksl.examples.general.agent.GridEpidemicExample
import ksl.examples.general.agent.NetworkRumorExample
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 *  Tests for `NetworkProjection` — edges and adjacency, unweighted and weighted
 *  shortest paths, reachability, and strongly connected components.
 *
 *  **Phase D.** This was one section of `ContextTest`, a 1274-line umbrella covering
 *  `Context` and all three projections together. Coverage was never the problem;
 *  finding it was. A reader asking what is tested about a given type had no file to
 *  open, and a gap in a 60-test umbrella is invisible in a way a gap in a per-type
 *  file is not — the most likely reason the missing `runDynamics*` and
 *  `GridGeometrySpec` coverage went unnoticed until a deliberate sweep.
 *
 *  Differential coverage of the graph algorithms, checked against independent
 *  references written in the test, lives in `NetworkGraphPropertyTest`.
 */
class NetworkProjectionTest {

    private class NetTestModel(parent: ModelElement, directed: Boolean = false) :
        AgentModel(parent, "netmodel") {
        val context: Context<Agent> = Context("net-agents")
        val net: NetworkProjection<Agent> = NetworkProjection(context, directed = directed)
        inner class Person(aName: String) : Agent(aName)
    }

    @Test
    fun networkUndirectedConnectIsSymmetric() {
        val model = Model("NetUndirTest")
        val tm = NetTestModel(model)
        val a = tm.Person("a"); val b = tm.Person("b")
        tm.context.add(a); tm.context.add(b)
        tm.net.connect(a, b)

        assertTrue(tm.net.hasEdge(a, b))
        assertTrue(tm.net.hasEdge(b, a), "undirected connect should be symmetric")
        assertEquals(setOf(b), tm.net.neighborsOf(a))
        assertEquals(setOf(a), tm.net.neighborsOf(b))
        assertEquals(1, tm.net.edgeCount, "edgeCount should count an undirected edge once")
    }

    @Test
    fun networkDirectedConnectIsAsymmetric() {
        val model = Model("NetDirTest")
        val tm = NetTestModel(model, directed = true)
        val a = tm.Person("a"); val b = tm.Person("b")
        tm.context.add(a); tm.context.add(b)
        tm.net.connect(a, b)

        assertTrue(tm.net.hasEdge(a, b))
        assertTrue(!tm.net.hasEdge(b, a), "directed connect should NOT be symmetric")
        assertEquals(setOf(b), tm.net.neighborsOf(a))
        assertEquals(emptySet<AgentModel.Agent>(), tm.net.neighborsOf(b))
        assertEquals(setOf(a), tm.net.inNeighborsOf(b))
        assertEquals(1, tm.net.degreeOf(a))
        assertEquals(0, tm.net.degreeOf(b))
        assertEquals(1, tm.net.inDegreeOf(b))
    }

    @Test
    fun networkConnectIsIdempotentAndUpdatesWeight() {
        val model = Model("NetIdempTest")
        val tm = NetTestModel(model)
        val a = tm.Person("a"); val b = tm.Person("b")
        tm.context.add(a); tm.context.add(b)

        tm.net.connect(a, b, weight = 1.0)
        tm.net.connect(a, b, weight = 3.0)  // re-connect updates the weight
        assertEquals(1, tm.net.edgeCount, "re-connecting the same pair should not add a parallel edge")
        assertEquals(3.0, tm.net.weightOf(a, b))
        assertEquals(3.0, tm.net.weightOf(b, a), "weight should be symmetric on undirected")
    }

    @Test
    fun networkDisconnectRemovesEdge() {
        val model = Model("NetDiscTest")
        val tm = NetTestModel(model)
        val a = tm.Person("a"); val b = tm.Person("b")
        tm.context.add(a); tm.context.add(b)
        tm.net.connect(a, b)
        assertTrue(tm.net.disconnect(a, b), "disconnect should report removal of an existing edge")
        assertTrue(!tm.net.hasEdge(a, b))
        assertTrue(!tm.net.hasEdge(b, a), "undirected disconnect should remove both directions")
        assertTrue(!tm.net.disconnect(a, b), "disconnect of a non-existent edge should return false")
    }

    @Test
    fun networkSelfEdgesAreRejected() {
        val model = Model("NetSelfTest")
        val tm = NetTestModel(model)
        val a = tm.Person("a")
        tm.context.add(a)
        try {
            tm.net.connect(a, a)
            error("connect(a, a) should throw")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun networkShortestPathOnLine() {
        val model = Model("NetPathTest")
        val tm = NetTestModel(model)
        val nodes = (0..4).map { tm.Person("n$it") }
        nodes.forEach { tm.context.add(it) }
        // Line: 0 - 1 - 2 - 3 - 4
        for (i in 0..3) tm.net.connect(nodes[i], nodes[i + 1])

        // Shortest path from 0 to 4 is the full line.
        val path = tm.net.shortestPath(nodes[0], nodes[4])
        assertEquals(nodes, path)
        assertEquals(4, tm.net.shortestPathLength(nodes[0], nodes[4]))

        // Self-path is a single element.
        assertEquals(listOf(nodes[0]), tm.net.shortestPath(nodes[0], nodes[0]))
        assertEquals(0, tm.net.shortestPathLength(nodes[0], nodes[0]))
    }

    @Test
    fun networkShortestPathOnTriangleShortcut() {
        val model = Model("NetTriTest")
        val tm = NetTestModel(model)
        val a = tm.Person("a"); val b = tm.Person("b"); val c = tm.Person("c")
        listOf(a, b, c).forEach { tm.context.add(it) }
        // Triangle: a-b, b-c, a-c. Shortest a-c is direct.
        tm.net.connect(a, b); tm.net.connect(b, c); tm.net.connect(a, c)
        assertEquals(1, tm.net.shortestPathLength(a, c))
        assertEquals(listOf(a, c), tm.net.shortestPath(a, c))
    }

    @Test
    fun networkShortestPathReturnsNullWhenDisconnected() {
        val model = Model("NetDiscPathTest")
        val tm = NetTestModel(model)
        val a = tm.Person("a"); val b = tm.Person("b"); val c = tm.Person("c")
        listOf(a, b, c).forEach { tm.context.add(it) }
        tm.net.connect(a, b)
        // c is isolated
        assertNull(tm.net.shortestPath(a, c))
        assertEquals(-1, tm.net.shortestPathLength(a, c))
    }

    @Test
    fun networkReachableFromBfsExploresComponent() {
        val model = Model("NetReachTest")
        val tm = NetTestModel(model)
        val nodes = (0..4).map { tm.Person("n$it") }
        nodes.forEach { tm.context.add(it) }
        // Two components: {0, 1, 2} (chain) and {3, 4} (pair)
        tm.net.connect(nodes[0], nodes[1]); tm.net.connect(nodes[1], nodes[2])
        tm.net.connect(nodes[3], nodes[4])

        val reach0 = tm.net.reachableFrom(nodes[0])
        assertEquals(setOf(nodes[0], nodes[1], nodes[2]), reach0)

        val reach3 = tm.net.reachableFrom(nodes[3])
        assertEquals(setOf(nodes[3], nodes[4]), reach3)

        // Isolated node (no edges): reachable from itself only.
        val isolated = tm.Person("isolated")
        tm.context.add(isolated)
        assertEquals(setOf(isolated), tm.net.reachableFrom(isolated))
    }

    @Test
    fun networkEdgesReturnsUndirectedEdgesOnce() {
        val model = Model("NetEdgesTest")
        val tm = NetTestModel(model)
        val a = tm.Person("a"); val b = tm.Person("b"); val c = tm.Person("c")
        listOf(a, b, c).forEach { tm.context.add(it) }
        tm.net.connect(a, b, weight = 2.5)
        tm.net.connect(b, c, weight = 1.5)

        val edges = tm.net.edges()
        assertEquals(2, edges.size)
        val pairsToWeight = edges.associate { setOf(it.from, it.to) to it.weight }
        assertEquals(2.5, pairsToWeight[setOf(a, b)])
        assertEquals(1.5, pairsToWeight[setOf(b, c)])
    }

    @Test
    fun weightedShortestPathSelfPathIsTrivial() {
        val model = Model("WSPSelfTest")
        val tm = NetTestModel(model)
        val a = tm.Person("a")
        tm.context.add(a)
        val path = tm.net.weightedShortestPath(a, a)
        assertNotNull(path)
        assertEquals(listOf(a), path!!.nodes)
        assertEquals(0.0, path.totalWeight)
    }

    @Test
    fun weightedShortestPathUnreachableReturnsNull() {
        val model = Model("WSPUnreachableTest")
        val tm = NetTestModel(model)
        val a = tm.Person("a"); val b = tm.Person("b"); val c = tm.Person("c")
        listOf(a, b, c).forEach { tm.context.add(it) }
        tm.net.connect(a, b)  // c is isolated
        assertNull(tm.net.weightedShortestPath(a, c))
        assertEquals(Double.POSITIVE_INFINITY, tm.net.weightedShortestPathLength(a, c))
    }

    /**
     *  Canonical Dijkstra check: a path with more hops but smaller
     *  total weight should be preferred over a fewer-hop heavier path.
     *  The BFS shortestPath would pick the wrong one here.
     */
    @Test
    fun weightedShortestPathPrefersCheaperPathOverFewerHops() {
        val model = Model("WSPCheaperTest")
        val tm = NetTestModel(model)
        val a = tm.Person("a"); val b = tm.Person("b"); val c = tm.Person("c"); val d = tm.Person("d")
        listOf(a, b, c, d).forEach { tm.context.add(it) }
        // Heavy direct edge: a → d with cost 10
        // Cheap 3-hop path:  a → b → c → d with costs 1+1+1 = 3
        tm.net.connect(a, d, weight = 10.0)
        tm.net.connect(a, b, weight = 1.0)
        tm.net.connect(b, c, weight = 1.0)
        tm.net.connect(c, d, weight = 1.0)
        val path = tm.net.weightedShortestPath(a, d)
        assertNotNull(path)
        assertEquals(listOf(a, b, c, d), path!!.nodes)
        assertEquals(3.0, path.totalWeight, 1e-9)

        // The BFS unweighted shortestPath, by contrast, picks the
        // direct edge because it has fewer hops.
        assertEquals(listOf(a, d), tm.net.shortestPath(a, d))
    }

    @Test
    fun weightedShortestPathOnDirectedGraphRespectsDirection() {
        val model = Model("WSPDirTest")
        val tm = NetTestModel(model, directed = true)
        val a = tm.Person("a"); val b = tm.Person("b"); val c = tm.Person("c")
        listOf(a, b, c).forEach { tm.context.add(it) }
        tm.net.connect(a, b, weight = 1.0)
        tm.net.connect(b, c, weight = 1.0)
        // No edge c → a; the reverse path doesn't exist.
        assertNotNull(tm.net.weightedShortestPath(a, c))
        assertNull(tm.net.weightedShortestPath(c, a))
    }

    @Test
    fun weightedShortestPathAllUnitWeightsAgreesWithBfs() {
        val model = Model("WSPUnitTest")
        val tm = NetTestModel(model)
        val nodes = (0..5).map { tm.Person("n$it") }
        nodes.forEach { tm.context.add(it) }
        // 0 - 1, 1 - 2, 2 - 3, 0 - 4, 4 - 3, 3 - 5
        tm.net.connect(nodes[0], nodes[1])
        tm.net.connect(nodes[1], nodes[2])
        tm.net.connect(nodes[2], nodes[3])
        tm.net.connect(nodes[0], nodes[4])
        tm.net.connect(nodes[4], nodes[3])
        tm.net.connect(nodes[3], nodes[5])

        // Weighted (all weights = 1.0) and unweighted should produce
        // the same hop count for every pair.
        for (i in nodes.indices) {
            for (j in nodes.indices) {
                if (i == j) continue
                val bfs = tm.net.shortestPathLength(nodes[i], nodes[j])
                val wsp = tm.net.weightedShortestPath(nodes[i], nodes[j])
                if (bfs == -1) {
                    assertNull(wsp)
                } else {
                    assertNotNull(wsp)
                    assertEquals(bfs.toDouble(), wsp!!.totalWeight, 1e-9)
                    assertEquals(bfs + 1, wsp.nodes.size)
                }
            }
        }
    }

    @Test
    fun weightedShortestPathAStarWithZeroHeuristicEqualsDijkstra() {
        val model = Model("WSPAStarZeroTest")
        val tm = NetTestModel(model)
        val a = tm.Person("a"); val b = tm.Person("b"); val c = tm.Person("c"); val d = tm.Person("d")
        listOf(a, b, c, d).forEach { tm.context.add(it) }
        tm.net.connect(a, b, weight = 2.0)
        tm.net.connect(b, d, weight = 2.0)
        tm.net.connect(a, c, weight = 1.0)
        tm.net.connect(c, d, weight = 1.5)

        val dijkstra = tm.net.weightedShortestPath(a, d)
        val aStarZero = tm.net.weightedShortestPath(a, d) { _, _ -> 0.0 }
        assertEquals(dijkstra, aStarZero)
    }

    /**
     *  A* with an admissible heuristic finds the same optimal path
     *  as Dijkstra. Verified by building a network whose nodes have
     *  positions in a [ContinuousProjection] and using Euclidean
     *  distance as the heuristic — the canonical GPS-routing
     *  composition.
     */
    @Test
    fun weightedShortestPathAStarWithAdmissibleHeuristicMatchesDijkstra() {
        val model = Model("WSPAStarTest")
        val tm = object : AgentModel(model, "astarmodel") {
            val context: Context<Agent> = Context("ctx")
            val net: NetworkProjection<Agent> = NetworkProjection(context)
            val space: ContinuousProjection<Agent> = ContinuousProjection(
                context, xRange = 0.0..100.0, yRange = 0.0..100.0,
            )
            inner class Person(aName: String) : Agent(aName)
        }
        // Build a small "road network" with positions:
        //
        //   a(0,0)  --[5]--  b(5,0)
        //     |               |
        //   [4]            [3]
        //     |               |
        //   c(0,4)  --[10]--  d(5,4)
        //
        // Two paths from a to d:
        //   a → b → d : cost 5 + 3 = 8
        //   a → c → d : cost 4 + 10 = 14
        // Shortest is a → b → d.
        val a = tm.Person("a"); val b = tm.Person("b"); val c = tm.Person("c"); val d = tm.Person("d")
        listOf(a, b, c, d).forEach { tm.context.add(it) }
        tm.space.placeAt(a, 0.0, 0.0)
        tm.space.placeAt(b, 5.0, 0.0)
        tm.space.placeAt(c, 0.0, 4.0)
        tm.space.placeAt(d, 5.0, 4.0)
        tm.net.connect(a, b, weight = 5.0)
        tm.net.connect(b, d, weight = 3.0)
        tm.net.connect(a, c, weight = 4.0)
        tm.net.connect(c, d, weight = 10.0)

        // Euclidean-distance heuristic (admissible: edge weights >=
        // straight-line distance for this construction).
        val heuristic = { n: AgentModel.Agent, target: AgentModel.Agent ->
            val pn = tm.space.positionOf(n)!!
            val pt = tm.space.positionOf(target)!!
            tm.space.distance(pn, pt)
        }

        val dijkstra = tm.net.weightedShortestPath(a, d)
        val aStar = tm.net.weightedShortestPath(a, d, heuristic)

        assertNotNull(dijkstra); assertNotNull(aStar)
        assertEquals(listOf(a, b, d), dijkstra!!.nodes)
        assertEquals(listOf(a, b, d), aStar!!.nodes)
        assertEquals(8.0, dijkstra.totalWeight, 1e-9)
        assertEquals(8.0, aStar.totalWeight, 1e-9)
    }

    /**
     *  Triangle inequality on weights: a single edge with high
     *  weight should still beat a multi-edge path if its weight is
     *  lower than the sum.
     */
    @Test
    fun weightedShortestPathPicksDirectEdgeWhenItIsCheapest() {
        val model = Model("WSPDirectTest")
        val tm = NetTestModel(model)
        val a = tm.Person("a"); val b = tm.Person("b"); val c = tm.Person("c")
        listOf(a, b, c).forEach { tm.context.add(it) }
        tm.net.connect(a, c, weight = 1.0)
        tm.net.connect(a, b, weight = 5.0)
        tm.net.connect(b, c, weight = 5.0)
        val path = tm.net.weightedShortestPath(a, c)
        assertEquals(listOf(a, c), path!!.nodes)
        assertEquals(1.0, path.totalWeight, 1e-9)
    }

    @Test
    fun weightedShortestPathSurvivesAgentRemoval() {
        val model = Model("WSPRemoveTest")
        val tm = NetTestModel(model)
        val a = tm.Person("a"); val b = tm.Person("b"); val c = tm.Person("c"); val d = tm.Person("d")
        listOf(a, b, c, d).forEach { tm.context.add(it) }
        tm.net.connect(a, b, weight = 1.0)
        tm.net.connect(b, c, weight = 1.0)
        tm.net.connect(c, d, weight = 1.0)
        // Direct heavier route
        tm.net.connect(a, d, weight = 5.0)

        assertEquals(3.0, tm.net.weightedShortestPathLength(a, d), 1e-9)

        // Remove b: the cheap chain is broken. Only the direct edge remains.
        tm.context.remove(b)
        assertEquals(5.0, tm.net.weightedShortestPathLength(a, d), 1e-9)
    }

    @Test
    fun networkSCCEmptyGraphHasNoSCCs() {
        val model = Model("SCCEmptyTest")
        val tm = NetTestModel(model, directed = true)
        assertTrue(tm.net.stronglyConnectedComponents().isEmpty())
        assertEquals(emptySet<AgentModel.Agent>(), tm.net.largestSCC())
    }

    @Test
    fun networkSCCSingletonForEdgelessAgentReturnsNull() {
        val model = Model("SCCNoEdgesTest")
        val tm = NetTestModel(model, directed = true)
        val a = tm.Person("a")
        tm.context.add(a)
        // Agent in the context but no edges. Not a node in the
        // network's view; sccContaining returns null.
        assertNull(tm.net.sccContaining(a))
        assertTrue(tm.net.stronglyConnectedComponents().isEmpty())
    }

    @Test
    fun networkSCCDirectedChainGivesAllSingletons() {
        val model = Model("SCCChainTest")
        val tm = NetTestModel(model, directed = true)
        val nodes = (0..3).map { tm.Person("n$it") }
        nodes.forEach { tm.context.add(it) }
        // 0 -> 1 -> 2 -> 3, no back-edges. Each node is its own SCC.
        for (i in 0..2) tm.net.connect(nodes[i], nodes[i + 1])
        val sccs = tm.net.stronglyConnectedComponents()
        assertEquals(4, sccs.size)
        for (scc in sccs) assertEquals(1, scc.size)
        // sccContaining for each gives a singleton set with that node.
        for (n in nodes) assertEquals(setOf(n), tm.net.sccContaining(n))
    }

    @Test
    fun networkSCCDirectedCycleGivesOneSCC() {
        val model = Model("SCCCycleTest")
        val tm = NetTestModel(model, directed = true)
        val nodes = (0..2).map { tm.Person("n$it") }
        nodes.forEach { tm.context.add(it) }
        // 3-cycle: 0 -> 1 -> 2 -> 0
        tm.net.connect(nodes[0], nodes[1])
        tm.net.connect(nodes[1], nodes[2])
        tm.net.connect(nodes[2], nodes[0])

        val sccs = tm.net.stronglyConnectedComponents()
        assertEquals(1, sccs.size)
        assertEquals(nodes.toSet(), sccs[0])
        // largestSCC returns the full cycle.
        assertEquals(nodes.toSet(), tm.net.largestSCC())
    }

    @Test
    fun networkSCCMixedGraphPartitionsCorrectly() {
        val model = Model("SCCMixedTest")
        val tm = NetTestModel(model, directed = true)
        // Build the example from the discussion:
        //   A → B → C
        //       ↑   ↓
        //       D ← E
        // SCCs: {A} and {B, C, D, E} (the latter is the cycle B->C->E->D->B).
        val a = tm.Person("A"); val b = tm.Person("B"); val c = tm.Person("C")
        val d = tm.Person("D"); val e = tm.Person("E")
        listOf(a, b, c, d, e).forEach { tm.context.add(it) }
        tm.net.connect(a, b)
        tm.net.connect(b, c)
        tm.net.connect(c, e)
        tm.net.connect(e, d)
        tm.net.connect(d, b)

        val sccs = tm.net.stronglyConnectedComponents()
        assertEquals(2, sccs.size, "should be exactly 2 SCCs: {A} and {B,C,D,E}")
        val singletons = sccs.filter { it.size == 1 }
        val bigger = sccs.filter { it.size > 1 }
        assertEquals(1, singletons.size)
        assertEquals(1, bigger.size)
        assertEquals(setOf(a), singletons.single())
        assertEquals(setOf(b, c, d, e), bigger.single())
        // largestSCC picks the size-4 component.
        assertEquals(setOf(b, c, d, e), tm.net.largestSCC())
        // sccContaining(A) is the singleton; sccContaining(B) is the cycle.
        assertEquals(setOf(a), tm.net.sccContaining(a))
        assertEquals(setOf(b, c, d, e), tm.net.sccContaining(b))
    }

    @Test
    fun networkSCCDirectedTwoCycleIsSCC() {
        val model = Model("SCCTwoCycleTest")
        val tm = NetTestModel(model, directed = true)
        val a = tm.Person("a"); val b = tm.Person("b")
        listOf(a, b).forEach { tm.context.add(it) }
        // a <-> b via two directed edges
        tm.net.connect(a, b)
        tm.net.connect(b, a)
        val sccs = tm.net.stronglyConnectedComponents()
        assertEquals(1, sccs.size)
        assertEquals(setOf(a, b), sccs.single())
    }

    @Test
    fun networkSCCUndirectedGraphEqualsConnectedComponents() {
        val model = Model("SCCUndirTest")
        val tm = NetTestModel(model, directed = false)
        // Two undirected components: {a, b, c} (triangle) and {d, e} (edge).
        val nodes = (0..4).map { tm.Person("u$it") }
        nodes.forEach { tm.context.add(it) }
        tm.net.connect(nodes[0], nodes[1])
        tm.net.connect(nodes[1], nodes[2])
        tm.net.connect(nodes[0], nodes[2])
        tm.net.connect(nodes[3], nodes[4])

        val sccs = tm.net.stronglyConnectedComponents()
        assertEquals(2, sccs.size, "undirected SCC partition should match connected components")
        val sccSets = sccs.map { it }.toSet()
        assertContains(sccSets, setOf(nodes[0], nodes[1], nodes[2]))
        assertContains(sccSets, setOf(nodes[3], nodes[4]))
    }

    @Test
    fun networkSCCMultipleDisjointDirectedCycles() {
        val model = Model("SCCDisjointTest")
        val tm = NetTestModel(model, directed = true)
        // Two independent 3-cycles plus an isolated edge.
        val a = tm.Person("a"); val b = tm.Person("b"); val c = tm.Person("c")
        val d = tm.Person("d"); val e = tm.Person("e"); val f = tm.Person("f")
        val g = tm.Person("g"); val h = tm.Person("h")
        listOf(a, b, c, d, e, f, g, h).forEach { tm.context.add(it) }
        // Cycle 1: a -> b -> c -> a
        tm.net.connect(a, b); tm.net.connect(b, c); tm.net.connect(c, a)
        // Cycle 2: d -> e -> f -> d
        tm.net.connect(d, e); tm.net.connect(e, f); tm.net.connect(f, d)
        // Edge: g -> h (no cycle)
        tm.net.connect(g, h)

        val sccs = tm.net.stronglyConnectedComponents()
        // 2 size-3 SCCs + 2 singletons = 4 SCCs total
        assertEquals(4, sccs.size)
        val bySize = sccs.groupBy { it.size }
        assertEquals(2, bySize[3]?.size, "expected 2 SCCs of size 3 (the cycles)")
        assertEquals(2, bySize[1]?.size, "expected 2 SCCs of size 1 (g, h)")
        val cycle1 = bySize[3]!!.first { a in it }
        val cycle2 = bySize[3]!!.first { d in it }
        assertEquals(setOf(a, b, c), cycle1)
        assertEquals(setOf(d, e, f), cycle2)
    }

    @Test
    fun networkSCCSurvivesAgentRemoval() {
        val model = Model("SCCRemoveTest")
        val tm = NetTestModel(model, directed = true)
        val nodes = (0..3).map { tm.Person("r$it") }
        nodes.forEach { tm.context.add(it) }
        // 4-cycle: 0 -> 1 -> 2 -> 3 -> 0
        for (i in 0..3) tm.net.connect(nodes[i], nodes[(i + 1) % 4])
        assertEquals(setOf(nodes[0], nodes[1], nodes[2], nodes[3]), tm.net.largestSCC())

        // Removing one node breaks the cycle: 0 -> 1, 2 -> 3, 1 -> (gone), 3 -> 0
        // Wait: removing node 2 means edges 1 -> 2 and 2 -> 3 are gone.
        // Remaining edges: 0 -> 1, 3 -> 0. Path: 3 -> 0 -> 1, no path back.
        tm.context.remove(nodes[2])
        val sccs = tm.net.stronglyConnectedComponents()
        // 3 nodes, 2 edges, no cycle → 3 singleton SCCs.
        assertEquals(3, sccs.size)
        for (scc in sccs) assertEquals(1, scc.size)
    }

    @Test
    fun networkDropsEdgesWhenAgentLeavesContext() {
        val model = Model("NetLeaveTest")
        val tm = NetTestModel(model)
        val a = tm.Person("a"); val b = tm.Person("b"); val c = tm.Person("c")
        listOf(a, b, c).forEach { tm.context.add(it) }
        tm.net.connect(a, b); tm.net.connect(b, c); tm.net.connect(a, c)
        assertEquals(3, tm.net.edgeCount)

        tm.context.remove(b)
        // b's edges (a-b, b-c) should be gone; a-c remains.
        assertEquals(1, tm.net.edgeCount)
        assertTrue(tm.net.hasEdge(a, c))
        assertTrue(!tm.net.hasEdge(a, b))
        assertTrue(!tm.net.hasEdge(b, c))
        assertEquals(emptySet<AgentModel.Agent>(), tm.net.neighborsOf(b))
    }
}
