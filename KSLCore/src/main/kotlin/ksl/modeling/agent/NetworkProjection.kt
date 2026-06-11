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

/**
 *  A directed edge in a [NetworkProjection], with an optional weight.
 *  For undirected networks each "logical" edge is stored as a single
 *  [Edge] instance with the canonical ordering chosen by the user
 *  (the projection emits each undirected edge exactly once from
 *  [NetworkProjection.edges]).
 */
data class Edge<A>(val from: A, val to: A, val weight: Double = NetworkProjection.Defaults.edgeWeight)

/**
 *  Result of a weighted shortest-path query on a [NetworkProjection].
 *  [nodes] lists the path including both endpoints (single-element
 *  list for a self-path); [totalWeight] is the sum of edge weights
 *  along the path.
 */
data class WeightedPath<A>(val nodes: List<A>, val totalWeight: Double)

/**
 *  A graph-based projection: agents are nodes, edges are typed
 *  relationships between pairs of agents. The third projection type
 *  in the agent layer's spatial abstraction (alongside
 *  [ContinuousProjection] and [GridProjection]).
 *
 *  Use this for relationships that aren't about position — friendship
 *  networks, contact graphs, communication links, organizational
 *  hierarchies. An agent can simultaneously be in a [ContinuousProjection]
 *  (its physical position) and a [NetworkProjection] (its social
 *  contacts) on the same [AgentModel.Context], because both abstractions
 *  layer over the context's membership rather than owning it.
 *
 *  Directionality:
 *   - [directed] = false (default): connecting `a` to `b` is the same
 *     as connecting `b` to `a`. [neighborsOf] returns both ends.
 *     [edges] returns each logical edge once with `(from, to)` in
 *     the order in which it was originally connected.
 *   - [directed] = true: an edge has a direction. [neighborsOf]
 *     returns *out*-neighbors only; [inNeighborsOf] returns
 *     in-neighbors. To connect in both directions, call [connect]
 *     twice.
 *
 *  Weights:
 *   - Each edge has a `weight` (default 1.0). The unweighted graph
 *     algorithms ([shortestPath], [reachableFrom]) ignore weights.
 *     User code can read weights via [weightOf] for its own purposes.
 *
 *  Lifecycle:
 *   - Nodes are implicit: any agent in the context can be referenced
 *     by [connect] / [disconnect] / [neighborsOf]; no separate "add
 *     node" step is needed.
 *   - When an agent leaves the context, [onAgentLeft] drops every
 *     edge incident to that agent.
 */
class NetworkProjection<A : AgentLike> @JvmOverloads constructor(
    val context: AgentModel.Context<A>,
    val directed: Boolean = false,
    override val name: String = "network",
) : Projection<A> {

    /**
     *  Mutable global defaults for [NetworkProjection] edges.
     */
    companion object Defaults {
        /** Default edge weight when [connect] or [Edge] is called without one. Must be non-negative. */
        var edgeWeight: Double by nonNegative(1.0)
    }

    // For an undirected graph we store each edge in BOTH directions
    // for O(1) lookup; edgeCount and edges() de-dupe to report a
    // single logical edge per pair. The (a -> b) entries also carry
    // the canonical (from, to) ordering chosen by the first connect()
    // call so edges() returns deterministic output.
    private val outAdjacency: MutableMap<A, MutableMap<A, Double>> = mutableMapOf()
    private val inAdjacency: MutableMap<A, MutableMap<A, Double>> = mutableMapOf()

    // For undirected, tracks which (from, to) ordering to emit from
    // edges(). Keyed by the unordered pair represented as a Set.
    private val canonicalUndirectedOrder: MutableMap<Set<A>, Pair<A, A>> = mutableMapOf()

    init {
        context.addProjection(this)
    }

    /**
     *  Number of nodes that currently have at least one incident edge.
     *  Note: an agent in the context with no edges is not counted here.
     *  Use [AgentModel.Context.size] for total membership.
     */
    val nodeCount: Int
        get() = (outAdjacency.keys + inAdjacency.keys).size

    /** Number of distinct logical edges. */
    val edgeCount: Int
        get() = if (directed) {
            outAdjacency.values.sumOf { it.size }
        } else {
            outAdjacency.values.sumOf { it.size } / 2
        }

    /**
     *  Add an edge from [a] to [b] with the given `weight`. For
     *  undirected networks this is symmetric with `connect(b, a, weight)`.
     *  Idempotent on the *existence* of the edge — calling connect
     *  twice updates the weight to the most recent value rather than
     *  creating a parallel edge.
     */
    fun connect(a: A, b: A, weight: Double = Defaults.edgeWeight) {
        require(a !== b) { "self-edges are not supported" }
        require(weight >= 0.0) { "edge weight must be non-negative; was $weight" }
        outAdjacency.getOrPut(a) { mutableMapOf() }[b] = weight
        inAdjacency.getOrPut(b) { mutableMapOf() }[a] = weight
        if (!directed) {
            outAdjacency.getOrPut(b) { mutableMapOf() }[a] = weight
            inAdjacency.getOrPut(a) { mutableMapOf() }[b] = weight
            val key = setOf(a, b)
            canonicalUndirectedOrder.putIfAbsent(key, a to b)
        }
    }

    /**
     *  Remove the edge from [a] to [b] if one exists. For undirected
     *  networks this also removes `b -> a`. Returns true if any edge
     *  was actually removed.
     */
    fun disconnect(a: A, b: A): Boolean {
        var removed = outAdjacency[a]?.remove(b) != null
        inAdjacency[b]?.remove(a)
        if (!directed) {
            val r2 = outAdjacency[b]?.remove(a) != null
            inAdjacency[a]?.remove(b)
            canonicalUndirectedOrder.remove(setOf(a, b))
            removed = removed || r2
        }
        return removed
    }

    /** True if there is an edge from [a] to [b]. */
    fun hasEdge(a: A, b: A): Boolean = outAdjacency[a]?.containsKey(b) == true

    /**
     *  Weight of the edge from [a] to [b], or null if there is no
     *  such edge.
     */
    fun weightOf(a: A, b: A): Double? = outAdjacency[a]?.get(b)

    /**
     *  All neighbors of [agent]. For a directed network this is
     *  out-neighbors only; for undirected it's all incident agents.
     *  Returns an empty set for an agent with no edges.
     */
    fun neighborsOf(agent: A): Set<A> = outAdjacency[agent]?.keys?.toSet() ?: emptySet()

    /**
     *  In-neighbors of [agent]: agents with an edge pointing *to*
     *  [agent]. For undirected networks this is identical to
     *  [neighborsOf].
     */
    fun inNeighborsOf(agent: A): Set<A> = inAdjacency[agent]?.keys?.toSet() ?: emptySet()

    /**
     *  Out-degree of [agent] (number of outgoing edges). For
     *  undirected networks this is identical to the total degree.
     */
    fun degreeOf(agent: A): Int = outAdjacency[agent]?.size ?: 0

    /** In-degree of [agent]. */
    fun inDegreeOf(agent: A): Int = inAdjacency[agent]?.size ?: 0

    /**
     *  All edges in the network. For undirected networks each
     *  logical edge appears once, in the original `connect` argument
     *  ordering.
     */
    fun edges(): List<Edge<A>> {
        if (directed) {
            val out = mutableListOf<Edge<A>>()
            for ((from, adj) in outAdjacency) {
                for ((to, w) in adj) out.add(Edge(from, to, w))
            }
            return out
        }
        val out = mutableListOf<Edge<A>>()
        for ((_, canonical) in canonicalUndirectedOrder) {
            val (a, b) = canonical
            val w = outAdjacency[a]?.get(b)
            if (w != null) out.add(Edge(a, b, w))
        }
        return out
    }

    /**
     *  Unweighted shortest path from [from] to [to] (BFS). Returns
     *  the list of agents on the path, including both endpoints.
     *  Returns null if [to] is not reachable. Returns a single-element
     *  list when `from === to`.
     */
    fun shortestPath(from: A, to: A): List<A>? {
        if (from === to) return listOf(from)
        val parents = mutableMapOf<A, A>()
        val visited = mutableSetOf(from)
        val queue = ArrayDeque<A>()
        queue.addLast(from)
        var found = false
        bfs@ while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (next in neighborsOf(current)) {
                if (next in visited) continue
                visited.add(next)
                parents[next] = current
                if (next === to) {
                    found = true
                    break@bfs
                }
                queue.addLast(next)
            }
        }
        if (!found) return null
        // Reconstruct path by walking parents back to `from`.
        val path = ArrayDeque<A>()
        var node: A? = to
        while (node != null) {
            path.addFirst(node)
            node = parents[node]
        }
        return path.toList()
    }

    /**
     *  Hop count of the shortest path from [from] to [to], or `-1`
     *  if unreachable. Returns 0 when `from === to`.
     */
    fun shortestPathLength(from: A, to: A): Int {
        val p = shortestPath(from, to) ?: return -1
        return p.size - 1
    }

    /**
     *  All agents reachable from [start] via outgoing edges (BFS).
     *  Includes [start] itself. For undirected networks this is the
     *  connected component containing [start]; for directed networks
     *  it's the forward-reachable set (also called "out-component").
     *  To get a weakly-connected-component style answer for a
     *  directed graph, the caller can union with [reachableFrom]
     *  walking inEdges, or invert the graph.
     */
    fun reachableFrom(start: A): Set<A> {
        val visited = LinkedHashSet<A>()
        visited.add(start)
        val queue = ArrayDeque<A>()
        queue.addLast(start)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (next in neighborsOf(current)) {
                if (visited.add(next)) queue.addLast(next)
            }
        }
        return visited
    }

    // ── Weighted shortest path (Dijkstra / A*) ─────────────────────────────

    /**
     *  Weighted shortest path from [from] to [to], respecting edge
     *  weights. Returns the path (including both endpoints) plus
     *  total weighted cost. Returns null if [to] is not reachable.
     *  A self-path (`from === to`) returns `WeightedPath(listOf(from), 0.0)`.
     *
     *  **Edge weights must be non-negative.** No runtime check (would
     *  be O(E)); negative weights cause incorrect results.
     *
     *  Mode of operation, controlled by the [heuristic] parameter:
     *
     *  - **Dijkstra** (default): pass no heuristic, or pass
     *    `{ _, _ -> 0.0 }`. The algorithm explores nodes in order of
     *    `g(n)` — actual cost from `from` to `n`. Optimal, but
     *    uninformed: it may explore many irrelevant nodes when the
     *    target is in a specific direction.
     *
     *  - **A\***: pass a non-trivial heuristic `(currentNode,
     *    targetNode) -> Double` that estimates the cost from
     *    `currentNode` to `targetNode`. The algorithm explores nodes
     *    in order of `f(n) = g(n) + h(n, to)`. If the heuristic is
     *    *admissible* (never overestimates actual cost), A\* finds
     *    the optimal path while exploring far fewer nodes than
     *    Dijkstra in practice.
     *
     *  Admissibility of a heuristic is not enforced at runtime.
     *  Common admissible heuristics:
     *   - Geometric distance (straight-line) when nodes have
     *     positions and edges follow physical paths whose weights
     *     are at least the geometric distance. The natural pattern
     *     for combining this with a [ContinuousProjection]:
     *     ```kotlin
     *     net.weightedShortestPath(start, dest) { a, b ->
     *         space.distance(space.positionOf(a)!!, space.positionOf(b)!!)
     *     }
     *     ```
     *   - Zero — always admissible; equivalent to Dijkstra.
     *
     *  An inadmissible heuristic may produce a sub-optimal path; the
     *  algorithm will still terminate and return *some* path if
     *  reachable.
     *
     *  Complexity: O((V + E) log V) with the binary-heap priority
     *  queue used here. Early-exit when the target is finalized
     *  reduces practical cost significantly when the target is
     *  close to the source relative to the graph diameter.
     */
    fun weightedShortestPath(
        from: A,
        to: A,
        heuristic: (current: A, target: A) -> Double = { _, _ -> 0.0 },
    ): WeightedPath<A>? {
        if (from === to) return WeightedPath(listOf(from), 0.0)

        val gScore: MutableMap<A, Double> = HashMap()
        val parents: MutableMap<A, A> = HashMap()
        val finalized: MutableSet<A> = HashSet()
        gScore[from] = 0.0

        // Priority queue holds (node, f-value) pairs. We use lazy
        // deletion: when a node's gScore is improved we add a new
        // entry to the PQ rather than search-and-update; on pop we
        // skip nodes already finalized.
        val pq = java.util.PriorityQueue<Pair<A, Double>>(compareBy { it.second })
        pq.add(from to heuristic(from, to))

        while (pq.isNotEmpty()) {
            val current = pq.poll().first
            if (current in finalized) continue
            finalized.add(current)

            if (current === to) {
                // Reconstruct the path by walking parents back to `from`.
                val path = ArrayDeque<A>()
                var node: A? = current
                while (node != null) {
                    path.addFirst(node)
                    node = parents[node]
                }
                return WeightedPath(path.toList(), gScore[current]!!)
            }

            val currentG = gScore[current]!!
            val outNeighbors = outAdjacency[current] ?: continue
            for ((neighbor, weight) in outNeighbors) {
                if (neighbor in finalized) continue
                val tentativeG = currentG + weight
                if (tentativeG < (gScore[neighbor] ?: Double.POSITIVE_INFINITY)) {
                    parents[neighbor] = current
                    gScore[neighbor] = tentativeG
                    pq.add(neighbor to (tentativeG + heuristic(neighbor, to)))
                }
            }
        }
        return null
    }

    /**
     *  Convenience accessor: total weighted distance from [from] to
     *  [to], or `Double.POSITIVE_INFINITY` if unreachable. Returns
     *  0.0 for the self-path.
     */
    fun weightedShortestPathLength(
        from: A,
        to: A,
        heuristic: (current: A, target: A) -> Double = { _, _ -> 0.0 },
    ): Double = weightedShortestPath(from, to, heuristic)?.totalWeight ?: Double.POSITIVE_INFINITY

    // ── Strongly Connected Components (Tarjan's algorithm) ──────────────────

    /**
     *  Compute the strongly connected components of this network.
     *  A strongly connected component (SCC) is a maximal set of
     *  agents where every agent can reach every other agent via
     *  directed paths.
     *
     *  For an undirected network this returns the standard connected
     *  components (every undirected edge contributes a 2-cycle, so
     *  SCC and connected component coincide).
     *
     *  Only agents currently incident to at least one edge are
     *  considered nodes of the network and appear in the result.
     *  Agents in the context with no edges are not in any returned
     *  SCC; callers can identify them by comparing `context.members`
     *  with the union of all returned SCCs.
     *
     *  Implemented with the iterative form of Tarjan's algorithm:
     *  one DFS pass with explicit DFS index and lowlink tracking.
     *  Time complexity O(V + E); space O(V) for the bookkeeping
     *  maps plus the explicit-frame stack (depth bounded by the
     *  longest simple path, not by JVM call stack).
     *
     *  The order of SCCs in the returned list corresponds to a
     *  reverse-topological order of the condensation: an SCC that
     *  has only outgoing inter-SCC edges (a "source SCC" in the
     *  condensation) appears later, an SCC that has only incoming
     *  inter-SCC edges (a "sink SCC") appears earlier. This is a
     *  consequence of how Tarjan's algorithm emits SCCs in DFS
     *  post-order.
     */
    fun stronglyConnectedComponents(): List<Set<A>> {
        val nodes: Set<A> = (outAdjacency.keys + inAdjacency.keys).toSet()
        if (nodes.isEmpty()) return emptyList()

        val index: MutableMap<A, Int> = HashMap()
        val lowlink: MutableMap<A, Int> = HashMap()
        val onStack: MutableSet<A> = HashSet()
        val componentStack: ArrayDeque<A> = ArrayDeque()
        val sccs: MutableList<Set<A>> = mutableListOf()
        var counter = 0

        // Each DFS frame holds the node being expanded and an
        // iterator over its (still-to-visit) out-neighbors.
        data class Frame(val node: A, val iter: Iterator<A>)

        for (root in nodes) {
            if (root in index) continue

            // Initialize DFS from root.
            index[root] = counter
            lowlink[root] = counter
            counter++
            componentStack.addLast(root)
            onStack.add(root)
            val frames: ArrayDeque<Frame> = ArrayDeque()
            frames.addLast(Frame(root, neighborsOf(root).iterator()))

            while (frames.isNotEmpty()) {
                val top = frames.last()
                if (top.iter.hasNext()) {
                    val w = top.iter.next()
                    if (w !in index) {
                        // Tree edge: descend into w.
                        index[w] = counter
                        lowlink[w] = counter
                        counter++
                        componentStack.addLast(w)
                        onStack.add(w)
                        frames.addLast(Frame(w, neighborsOf(w).iterator()))
                    } else if (w in onStack) {
                        // Back-edge to a node in the current DFS subtree.
                        // Update v's lowlink to track the earliest-indexed
                        // node reachable from v's subtree.
                        lowlink[top.node] = minOf(lowlink[top.node]!!, index[w]!!)
                    }
                    // else: cross-edge to a finished SCC — ignore.
                } else {
                    // No more children. If top.node is its SCC's root,
                    // pop the component stack down to (and including) it.
                    val v = top.node
                    if (lowlink[v] == index[v]) {
                        val component = LinkedHashSet<A>()
                        while (true) {
                            val w = componentStack.removeLast()
                            onStack.remove(w)
                            component.add(w)
                            if (w === v) break
                        }
                        sccs.add(component)
                    }
                    frames.removeLast()
                    // Propagate v's lowlink up to its DFS parent.
                    if (frames.isNotEmpty()) {
                        val parent = frames.last().node
                        lowlink[parent] = minOf(lowlink[parent]!!, lowlink[v]!!)
                    }
                }
            }
        }
        return sccs
    }

    /**
     *  The strongly connected component containing [agent], or null
     *  if [agent] has no incident edges. Cheaper than calling
     *  [stronglyConnectedComponents] when only one SCC is needed,
     *  but still O(V + E) in the worst case.
     */
    fun sccContaining(agent: A): Set<A>? {
        if (agent !in outAdjacency && agent !in inAdjacency) return null
        return stronglyConnectedComponents().firstOrNull { agent in it }
    }

    /**
     *  The largest strongly connected component, by size. Ties are
     *  broken by the order SCCs are emitted by
     *  [stronglyConnectedComponents] (reverse topological order of
     *  the condensation). Returns an empty set if the network has
     *  no edges.
     */
    fun largestSCC(): Set<A> =
        stronglyConnectedComponents().maxByOrNull { it.size } ?: emptySet()

    // ── Adapter: NetworkProjection → DistancesModel ────────────────────────

    /**
     *  Snapshot this network as a spatial-layer
     *  [ksl.modeling.spatial.DistancesModel]. The returned model
     *  contains one location per node currently in the network
     *  (any agent with at least one incident edge) and one
     *  distance entry per *reachable* ordered pair, computed via
     *  shortest path (Dijkstra) from each node.
     *
     *  Use case: a model that already uses agent-layer
     *  `NetworkProjection` for graph dynamics (Contract-Net, rumor
     *  diffusion, etc.) and also needs `MovableResource` or
     *  `KSLProcess.transportWith` semantics over the same
     *  topology. The adapter gives the entity-layer movement
     *  code a `DistancesModel` whose distances match the network's
     *  shortest-path costs.
     *
     *  Semantics:
     *   - **All-pairs shortest path** is precomputed up front, not
     *     lazy. Edges represent "one hop"; the DistancesModel
     *     entry for `(a, c)` when only `a→b→c` exists holds
     *     `weight(a,b) + weight(b,c)`. This matches user
     *     intuition that the "distance" between two locations on
     *     a network is the routing distance, not infinity for
     *     non-adjacent nodes.
     *   - **Snapshot semantics.** The DistancesModel reflects the
     *     network at the moment of the call. Edges added or
     *     removed afterward are not reflected. Call again to
     *     rebuild.
     *   - **Reachability only.** Unreachable pairs are not added.
     *     `DistancesModel.distance(unreachableA, unreachableB)`
     *     throws.
     *   - **Self-pairs** use `DistancesModel.defaultSameLocationDistance`
     *     (default 0.0).
     *
     *  Lookup: every node gets a location named `nameOf(node)`
     *  (default `node.name`). After construction, retrieve the
     *  `LocationIfc` for an agent via
     *  `distancesModel.location(nameOf(agent))`. Node names must be
     *  distinct under [nameOf]; collisions throw.
     *
     *  Complexity: O(V × (V + E) log V) for the Dijkstra-per-source
     *  pass. For small networks (< 1000 nodes) this is a one-time
     *  hit at setup; for larger networks consider Floyd-Warshall
     *  (O(V³)) or partial precomputation.
     *
     *  @param nameOf maps each node to a unique location name in the
     *    DistancesModel. Defaults to `agent.name`; provide a custom
     *    mapper if multiple agents share names.
     *  @return a populated [ksl.modeling.spatial.DistancesModel]
     */
    fun asDistancesModel(nameOf: (A) -> String = { it.name }): ksl.modeling.spatial.DistancesModel {
        val nodes = (outAdjacency.keys + inAdjacency.keys).toSet().toList()

        // Validate unique names — DistancesModel keys by name internally.
        val nameCounts = nodes.groupingBy { nameOf(it) }.eachCount()
        val collisions = nameCounts.filter { it.value > 1 }.keys
        require(collisions.isEmpty()) {
            "node names must be unique under nameOf for the DistancesModel adapter; " +
                "collisions: $collisions"
        }

        val dm = ksl.modeling.spatial.DistancesModel()
        if (nodes.isEmpty()) return dm

        // For each source, single-source Dijkstra; add a directed
        // distance entry for every reachable target.
        for (source in nodes) {
            val distances = shortestDistancesFrom(source)
            for ((target, d) in distances) {
                if (source === target) continue  // self-pair handled by defaultSameLocationDistance
                dm.addDistance(nameOf(source), nameOf(target), d)
            }
        }
        return dm
    }

    /**
     *  Single-source Dijkstra returning the shortest-path distance
     *  from [source] to every reachable node. Unreachable nodes are
     *  absent from the result.
     */
    private fun shortestDistancesFrom(source: A): Map<A, Double> {
        val dist: MutableMap<A, Double> = HashMap()
        val finalized: MutableSet<A> = HashSet()
        val pq = java.util.PriorityQueue<Pair<A, Double>>(compareBy { it.second })
        dist[source] = 0.0
        pq.add(source to 0.0)
        while (pq.isNotEmpty()) {
            val current = pq.poll().first
            if (current in finalized) continue
            finalized.add(current)
            val currentDist = dist[current]!!
            val out = outAdjacency[current] ?: continue
            for ((neighbor, weight) in out) {
                if (neighbor in finalized) continue
                val tentative = currentDist + weight
                if (tentative < (dist[neighbor] ?: Double.POSITIVE_INFINITY)) {
                    dist[neighbor] = tentative
                    pq.add(neighbor to tentative)
                }
            }
        }
        return dist
    }

    override fun onAgentLeft(agent: A) {
        val outs = outAdjacency.remove(agent)
        if (outs != null) {
            for (other in outs.keys) inAdjacency[other]?.remove(agent)
        }
        val ins = inAdjacency.remove(agent)
        if (ins != null) {
            for (other in ins.keys) outAdjacency[other]?.remove(agent)
        }
        if (!directed) {
            // Clean up canonical-ordering entries that reference this agent.
            val toRemove = canonicalUndirectedOrder.keys.filter { agent in it }
            for (key in toRemove) canonicalUndirectedOrder.remove(key)
        }
    }
}
