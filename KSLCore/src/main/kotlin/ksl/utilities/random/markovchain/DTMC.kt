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

package ksl.utilities.random.markovchain

import ksl.utilities.KSLArrays
import ksl.utilities.copyOf
import ksl.utilities.math.KSLMath
import org.hipparchus.linear.MatrixUtils
import org.hipparchus.linear.QRDecomposition
import org.hipparchus.linear.RealMatrix
import kotlin.math.abs

/**
 *  The structure and the exact properties of a finite discrete-time Markov chain.
 *
 *  This class answers questions *about* a transition matrix. It holds no random number stream
 *  and generates nothing, because none of what it computes needs randomness: a stationary
 *  distribution or an expected absorption time is a property of the matrix, not of a sample
 *  path. To simulate a chain, use `DMarkovChain`, which holds one of these and exposes it as
 *  its `dtmc` property.
 *
 *  States are numbered 1 through [numStates], matching `DMarkovChain`. Row i of the transition
 *  matrix is the distribution of the next state given the chain is in state i, so every row
 *  must be a valid probability mass function.
 *
 *  Results that require a structural precondition say so and check it. A stationary
 *  distribution is unique only for an irreducible chain; the absorbing-chain results require an
 *  absorbing chain in the usual sense, that every recurrent state is absorbing. Asking for one
 *  of these on a chain that does not qualify raises an exception naming the reason rather than
 *  returning a number that means nothing.
 *
 *  @param transitionMatrix a square matrix whose rows are probability mass functions
 */
class DTMC(transitionMatrix: Array<DoubleArray>) {

    init {
        require(KSLArrays.isSquare(transitionMatrix)) { "The probability transition matrix must be square" }
        require(transitionMatrix.isNotEmpty()) { "The probability transition matrix must have at least one state" }
        for ((r, row) in transitionMatrix.withIndex()) {
            var sum = 0.0
            for ((c, p) in row.withIndex()) {
                require((0.0 <= p) && (p <= 1.0)) { "Row $r, column $c held $p, which is not a valid probability" }
                sum = sum + p
            }
            require(KSLMath.equal(sum, 1.0)) { "Row $r of the transition matrix summed to $sum rather than 1.0" }
        }
    }

    private val p: Array<DoubleArray> = transitionMatrix.copyOf()

    /** The number of states in the chain. */
    val numStates: Int = p.size

    /** The states, numbered 1 through [numStates]. */
    val states: IntArray
        get() = IntArray(numStates) { it + 1 }

    /** The transition matrix. A copy: mutating it does not affect this chain. */
    val transitionMatrix: Array<DoubleArray>
        get() = p.copyOf()

    private fun requireState(state: Int, name: String = "state") {
        require(state in 1..numStates) { "The $name, $state, is not in 1..$numStates" }
    }

    // -----------------------------------------------------------------------------------
    //  Structure
    // -----------------------------------------------------------------------------------

    /** Whether a single transition from state i to state j has positive probability. */
    private val edge: Array<BooleanArray> =
        Array(numStates) { i -> BooleanArray(numStates) { j -> p[i][j] > 0.0 } }

    /**
     *  Entry (i, j) is true when state j+1 can be reached from state i+1 in zero or more
     *  transitions. Every state reaches itself, so the diagonal is true.
     */
    val reachabilityMatrix: Array<BooleanArray> by lazy {
        val r = Array(numStates) { i -> BooleanArray(numStates) { j -> edge[i][j] || i == j } }
        for (k in 0 until numStates) {
            for (i in 0 until numStates) {
                if (r[i][k]) {
                    for (j in 0 until numStates) {
                        if (r[k][j]) r[i][j] = true
                    }
                }
            }
        }
        r
    }

    /**
     *  Whether [to] can be reached from [from] in zero or more transitions. A state is always
     *  reachable from itself; see [canReturnTo] for reachability in one or more.
     */
    fun isReachable(from: Int, to: Int): Boolean {
        requireState(from, "from state"); requireState(to, "to state")
        return reachabilityMatrix[from - 1][to - 1]
    }

    /** Whether the chain can leave [state] and come back to it in one or more transitions. */
    fun canReturnTo(state: Int): Boolean {
        requireState(state)
        val i = state - 1
        return (0 until numStates).any { j -> edge[i][j] && reachabilityMatrix[j][i] }
    }

    /**
     *  The communicating classes, each a set of state numbers that all reach one another.
     *  Returned in order of their smallest member.
     */
    val communicatingClasses: List<Set<Int>> by lazy {
        val assigned = BooleanArray(numStates)
        val classes = mutableListOf<Set<Int>>()
        for (i in 0 until numStates) {
            if (assigned[i]) continue
            val members = (0 until numStates).filter { j ->
                reachabilityMatrix[i][j] && reachabilityMatrix[j][i]
            }
            members.forEach { assigned[it] = true }
            classes.add(members.map { it + 1 }.toSet())
        }
        classes
    }

    /** Whether every state can reach every other state. */
    val isIrreducible: Boolean by lazy { communicatingClasses.size == 1 }

    /**
     *  The recurrent states: those in a communicating class the chain cannot leave. In a finite
     *  chain these are exactly the states the chain returns to infinitely often.
     */
    val recurrentStates: List<Int> by lazy {
        communicatingClasses.filter { members ->
            members.all { s -> (0 until numStates).none { j -> edge[s - 1][j] && (j + 1) !in members } }
        }.flatten().sorted()
    }

    /** The transient states: every state that is not recurrent. */
    val transientStates: List<Int> by lazy { states.filter { it !in recurrentStates } }

    /** Whether the chain, once in [state], never leaves. */
    fun isAbsorbing(state: Int): Boolean {
        requireState(state)
        return KSLMath.equal(p[state - 1][state - 1], 1.0)
    }

    /** Whether the chain returns to [state] infinitely often. */
    fun isRecurrent(state: Int): Boolean {
        requireState(state)
        return state in recurrentStates
    }

    /** Whether the chain visits [state] only finitely often. */
    fun isTransient(state: Int): Boolean = !isRecurrent(state)

    /** The states the chain never leaves, in increasing order. */
    val absorbingStates: List<Int> by lazy { states.filter { isAbsorbing(it) } }

    /** Whether the chain has at least one absorbing state. */
    val hasAbsorbingStates: Boolean by lazy { absorbingStates.isNotEmpty() }

    /**
     *  The period of [state]: the greatest common divisor of the numbers of transitions in which
     *  the chain can return to it. A period of 1 means aperiodic.
     *
     *  Requires that the chain can return to the state at all; a state it can never revisit has
     *  no period.
     */
    fun period(state: Int): Int {
        requireState(state)
        require(canReturnTo(state)) {
            "State $state has no period: the chain cannot return to it once it leaves"
        }
        val i = state - 1
        val member = BooleanArray(numStates) { j -> reachabilityMatrix[i][j] && reachabilityMatrix[j][i] }
        // Breadth-first levels within the communicating class; the period is the gcd of the
        // discrepancies each edge introduces against those levels.
        val level = IntArray(numStates) { -1 }
        level[i] = 0
        val queue = ArrayDeque<Int>()
        queue.add(i)
        var g = 0
        while (queue.isNotEmpty()) {
            val u = queue.removeFirst()
            for (v in 0 until numStates) {
                if (!member[v] || !edge[u][v]) continue
                if (level[v] == -1) {
                    level[v] = level[u] + 1
                    queue.add(v)
                } else {
                    g = gcd(g, abs(level[u] + 1 - level[v]))
                }
            }
        }
        return g
    }

    /** Whether every recurrent state has period 1. */
    val isAperiodic: Boolean by lazy { recurrentStates.all { period(it) == 1 } }

    private fun gcd(a: Int, b: Int): Int {
        var x = a; var y = b
        while (y != 0) { val t = y; y = x % y; x = t }
        return x
    }

    // -----------------------------------------------------------------------------------
    //  Exact results
    // -----------------------------------------------------------------------------------

    private fun matrix(): RealMatrix = MatrixUtils.createRealMatrix(p.copyOf())

    /**
     *  The stationary distribution: the unique probability vector with pi = pi P. Entry k is the
     *  long-run proportion of transitions spent in state k+1.
     *
     *  Requires an irreducible chain, which is what makes the vector unique. For a periodic
     *  chain the vector is still stationary and still the long-run proportion of visits, but it
     *  is not the limit of the n-step transition matrix, which does not converge.
     */
    val steadyStateDistribution: DoubleArray by lazy {
        check(isIrreducible) {
            "A stationary distribution is unique only for an irreducible chain; this one has " +
                "${communicatingClasses.size} communicating classes: $communicatingClasses"
        }
        if (numStates == 1) return@lazy doubleArrayOf(1.0)
        // Solve the overdetermined system formed by (P^T - I) pi = 0 together with sum(pi) = 1.
        val a = Array(numStates + 1) { DoubleArray(numStates) }
        for (i in 0 until numStates) {
            for (j in 0 until numStates) {
                a[i][j] = p[j][i] - if (i == j) 1.0 else 0.0
            }
        }
        for (j in 0 until numStates) a[numStates][j] = 1.0
        val b = DoubleArray(numStates + 1)
        b[numStates] = 1.0
        val solution = QRDecomposition(MatrixUtils.createRealMatrix(a))
            .solver.solve(MatrixUtils.createRealVector(b)).toArray()
        // Round-off can leave a component a shade below zero; clamp and renormalise so the
        // result is a probability vector rather than nearly one.
        for (k in solution.indices) if (solution[k] < 0.0) solution[k] = 0.0
        val total = solution.sum()
        DoubleArray(numStates) { solution[it] / total }
    }

    /**
     *  The n-step transition matrix: entry (i, j) is the probability of being in state j+1 after
     *  [n] transitions, having started in state i+1. Zero steps gives the identity.
     */
    fun nStepTransitionMatrix(n: Int): Array<DoubleArray> {
        require(n >= 0) { "The number of steps must be >= 0" }
        var result = MatrixUtils.createRealIdentityMatrix(numStates)
        var base = matrix()
        var k = n
        while (k > 0) {
            if (k % 2 == 1) result = result.multiply(base)
            base = base.multiply(base)
            k /= 2
        }
        return result.data
    }

    /**
     *  True when every recurrent state is absorbing, which is what the absorbing-chain results
     *  below assume. A chain with a recurrent class of two or more states is not an absorbing
     *  chain, however many absorbing states it also has.
     */
    val isAbsorbingChain: Boolean by lazy {
        hasAbsorbingStates && recurrentStates == absorbingStates
    }

    private fun requireAbsorbingChain() {
        check(isAbsorbingChain) {
            if (!hasAbsorbingStates) {
                "This chain has no absorbing states"
            } else {
                "This is not an absorbing chain: the recurrent states are $recurrentStates but " +
                    "only $absorbingStates are absorbing, so the chain can settle into a class " +
                    "it never leaves without ever being absorbed"
            }
        }
    }

    /**
     *  The fundamental matrix N = (I - Q) inverted, where Q holds the transition probabilities
     *  among the transient states. Entry (i, j) is the expected number of visits to the j-th
     *  transient state, starting from the i-th, before absorption. Rows and columns follow the
     *  order of [transientStates].
     *
     *  Requires an absorbing chain, in the sense of [isAbsorbingChain].
     */
    fun fundamentalMatrix(): Array<DoubleArray> {
        requireAbsorbingChain()
        val t = transientStates
        check(t.isNotEmpty()) { "Every state of this chain is absorbing, so there is nothing to invert" }
        val q = Array(t.size) { i -> DoubleArray(t.size) { j -> p[t[i] - 1][t[j] - 1] } }
        val iMinusQ = MatrixUtils.createRealIdentityMatrix(t.size)
            .subtract(MatrixUtils.createRealMatrix(q))
        return MatrixUtils.inverse(iMinusQ).data
    }

    /**
     *  The expected number of transitions before absorption, one entry per state in state order.
     *  An absorbing state is already absorbed, so its entry is zero.
     *
     *  Requires an absorbing chain, in the sense of [isAbsorbingChain].
     */
    fun expectedAbsorptionTimes(): DoubleArray {
        val n = fundamentalMatrix()
        val t = transientStates
        val result = DoubleArray(numStates)
        for (i in t.indices) result[t[i] - 1] = n[i].sum()
        return result
    }

    /**
     *  Entry (i, j) is the probability that the chain is eventually absorbed in state j+1 having
     *  started in state i+1. Columns for states that are not absorbing are zero, and an
     *  absorbing state is absorbed in itself with probability one.
     *
     *  Requires an absorbing chain, in the sense of [isAbsorbingChain].
     */
    fun absorptionProbabilities(): Array<DoubleArray> {
        val n = fundamentalMatrix()
        val t = transientStates
        val a = absorbingStates
        val r = Array(t.size) { i -> DoubleArray(a.size) { j -> p[t[i] - 1][a[j] - 1] } }
        val b = MatrixUtils.createRealMatrix(n).multiply(MatrixUtils.createRealMatrix(r)).data
        val result = Array(numStates) { DoubleArray(numStates) }
        for (s in a) result[s - 1][s - 1] = 1.0
        for (i in t.indices) {
            for (j in a.indices) result[t[i] - 1][a[j] - 1] = b[i][j]
        }
        return result
    }

    /**
     *  Entry (i, j) is the expected number of transitions to reach state j+1 for the first time,
     *  having started in state i+1. The diagonal holds the mean recurrence times, the expected
     *  number of transitions to return to a state, which is the reciprocal of that state's
     *  stationary probability.
     *
     *  Requires an irreducible chain, since otherwise some state is unreachable from some other
     *  and the expectation is infinite.
     */
    fun meanFirstPassageTimes(): Array<DoubleArray> {
        check(isIrreducible) {
            "Mean first passage times require an irreducible chain; this one has " +
                "${communicatingClasses.size} communicating classes: $communicatingClasses"
        }
        val pi = steadyStateDistribution
        val m = Array(numStates) { DoubleArray(numStates) }
        for (target in 0 until numStates) {
            m[target][target] = 1.0 / pi[target]
            if (numStates == 1) continue
            // Drop the target's row and column: from every other state the expected time
            // satisfies m = 1 + sum over the remaining states of P m.
            val others = (0 until numStates).filter { it != target }
            val q = Array(others.size) { i -> DoubleArray(others.size) { j -> p[others[i]][others[j]] } }
            val iMinusQ = MatrixUtils.createRealIdentityMatrix(others.size)
                .subtract(MatrixUtils.createRealMatrix(q))
            val ones = MatrixUtils.createRealVector(DoubleArray(others.size) { 1.0 })
            val solved = QRDecomposition(iMinusQ).solver.solve(ones).toArray()
            for (i in others.indices) m[others[i]][target] = solved[i]
        }
        return m
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine("DTMC with $numStates states")
        sb.appendLine("Transition matrix")
        for (row in p) sb.appendLine("  " + row.joinToString())
        sb.appendLine("Communicating classes: $communicatingClasses")
        sb.appendLine("Irreducible: $isIrreducible")
        sb.appendLine("Recurrent states: $recurrentStates")
        sb.appendLine("Transient states: $transientStates")
        sb.appendLine("Absorbing states: $absorbingStates")
        return sb.toString()
    }
}
