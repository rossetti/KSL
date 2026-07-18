package ksl.simopt.solvers.algorithms.isc

import kotlin.math.abs

/**
 *  Strategy for deciding whether a candidate linear constraint is redundant given a set of other
 *  linear constraints. A constraint `a · x <= b` is *redundant* with respect to a set `S` of
 *  half-spaces when every point satisfying `S` already satisfies it — i.e. removing it does not
 *  change the feasible region. ISC uses this to prune the most-promising-area polytope so the RMD
 *  sampler walks only over the constraints that actually bind.
 */
fun interface RedundantConstraintChecker {

    /**
     *  Returns true if [target] is redundant given [others], i.e. the half-space defined by [target]
     *  is implied by the intersection of the [others].
     */
    fun isRedundant(target: HalfSpace, others: List<HalfSpace>): Boolean
}

/**
 *  An exact, dependency-free redundancy checker for a *continuous* polytope based on Fourier–Motzkin
 *  elimination. A half-space `a · x <= b` is redundant with respect to others when the system
 *  `others ∧ (a · x > b)` is infeasible. Strict violation is approximated by the closed system
 *  `others ∧ (-a · x <= -b - tolerance)`; if that system has no real solution, the target adds
 *  nothing and is redundant.
 *
 *  Fourier–Motzkin elimination is exact for the continuous relaxation but can grow the number of
 *  constraints combinatorially as variables are eliminated. To keep the check bounded, [maxRows]
 *  caps the intermediate constraint count: if the cap is exceeded the checker fails *open* — it
 *  reports the target as **not** redundant (conservative: keep the constraint). This never removes a
 *  constraint that might bind; at worst the RMD sampler carries a few extra inactive half-spaces.
 *
 *  @param tolerance the slack used both to detect a strict violation and to declare an eliminated
 *  row infeasible; defaults to [HalfSpace.defaultTolerance]
 *  @param maxRows the maximum number of intermediate rows tolerated during elimination before the
 *  check fails open; defaults to [DEFAULT_MAX_ROWS]
 */
class BruteForceRedundancyChecker(
    val tolerance: Double = HalfSpace.defaultTolerance,
    val maxRows: Int = DEFAULT_MAX_ROWS
) : RedundantConstraintChecker {

    init {
        require(tolerance >= 0.0) { "tolerance must be non-negative" }
        require(maxRows >= 1) { "maxRows must be at least 1" }
    }

    override fun isRedundant(target: HalfSpace, others: List<HalfSpace>): Boolean {
        // Build the system: others, plus the strict negation of the target (-a . x <= -b - tol).
        val system = ArrayList<Row>(others.size + 1)
        for (h in others) system.add(Row(h.a.copyOf(), h.b))
        val negA = DoubleArray(target.a.size) { -target.a[it] }
        system.add(Row(negA, -target.b - tolerance))
        return !isFeasible(system)
    }

    /** A single inequality `coeff · x <= rhs`. */
    private class Row(val coeff: DoubleArray, val rhs: Double)

    /**
     *  Fourier–Motzkin feasibility test: eliminates each variable in turn, then checks the residual
     *  constant rows. Returns true if the system has a real solution (feasible), false if a
     *  contradiction `0 <= negative` is derived. Fails open (returns true) if the row count exceeds
     *  [maxRows] during elimination.
     */
    private fun isFeasible(initial: List<Row>): Boolean {
        if (initial.isEmpty()) return true
        val numVars = initial[0].coeff.size
        var rows = initial
        for (j in 0 until numVars) {
            val positive = ArrayList<Row>()
            val negative = ArrayList<Row>()
            val zero = ArrayList<Row>()
            for (r in rows) {
                val c = r.coeff[j]
                when {
                    c > tolerance -> positive.add(r)
                    c < -tolerance -> negative.add(r)
                    else -> zero.add(r)
                }
            }
            if (positive.isEmpty() || negative.isEmpty()) {
                // x_j is unbounded in at least one direction: it imposes no joint restriction, so
                // simply drop the rows that mention it and keep the rest.
                rows = zero
                if (checkConstants(rows).not()) return false
                continue
            }
            // Fail open BEFORE allocating if the projected row product would blow past the cap.
            // The eager capacity below must never exceed maxRows: the pairwise product grows
            // combinatorially as variables are eliminated (e.g. dimension >= 3 with many
            // half-spaces reaches millions of rows), and a pre-sized ArrayList of that many
            // elements exhausts the heap before the per-row guard inside the loop could fire.
            // Long arithmetic also avoids Int overflow of positive.size * negative.size.
            val projectedRows = zero.size.toLong() + positive.size.toLong() * negative.size.toLong()
            if (projectedRows > maxRows) return true // fail open: treat as feasible (conservative)
            val combined = ArrayList<Row>(projectedRows.toInt())
            combined.addAll(zero)
            for (p in positive) {
                val ap = p.coeff[j]
                for (n in negative) {
                    val an = -n.coeff[j] // > 0
                    val newCoeff = DoubleArray(numVars)
                    for (k in 0 until numVars) {
                        if (k == j) continue
                        newCoeff[k] = p.coeff[k] / ap + n.coeff[k] / an
                    }
                    val newRhs = p.rhs / ap + n.rhs / an
                    combined.add(Row(newCoeff, newRhs))
                    if (combined.size > maxRows) return true // fail open: treat as feasible
                }
            }
            rows = combined
            if (checkConstants(rows).not()) return false
        }
        return checkConstants(rows)
    }

    /**
     *  Returns false if any row has become a pure constant inequality `0 <= rhs` with `rhs` strictly
     *  negative beyond tolerance (a contradiction); true otherwise.
     */
    private fun checkConstants(rows: List<Row>): Boolean {
        for (r in rows) {
            var allZero = true
            for (c in r.coeff) {
                if (abs(c) > tolerance) {
                    allZero = false
                    break
                }
            }
            if (allZero && r.rhs < -tolerance) return false
        }
        return true
    }

    companion object {
        /** Default cap on intermediate Fourier–Motzkin rows before the check fails open. */
        const val DEFAULT_MAX_ROWS: Int = 50_000
    }
}
