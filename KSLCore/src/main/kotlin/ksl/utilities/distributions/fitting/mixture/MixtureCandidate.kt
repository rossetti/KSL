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
package ksl.utilities.distributions.fitting.mixture

import ksl.utilities.distributions.ContinuousDistributionIfc
import ksl.utilities.distributions.MixtureDistribution

/**
 *  One assembled mixture: a partition, one fitted component per group, and the mixing weights
 *  implied by the group sizes.
 *
 *  @param partition the partition the components were fitted to
 *  @param components the fitted components, one per group, in group order
 */
class MixtureCandidate(
    val partition: DataPartition,
    components: List<ComponentCandidate>
) {

    private val myComponents: List<ComponentCandidate> = components.toList()

    init {
        require(components.isNotEmpty()) { "There must be at least one component" }
        require(components.size == partition.numGroups) {
            "The number of components (${components.size}) must equal the number of groups " +
                    "(${partition.numGroups})"
        }
    }

    /**
     *  The fitted components, in group order.
     */
    val components: List<ComponentCandidate>
        get() = myComponents

    /**
     *  The number of components.
     */
    val numComponents: Int
        get() = myComponents.size

    /**
     *  The mixing weights, taken as the empirical group proportions. These sum to one.
     */
    val weights: DoubleArray
        get() = partition.proportions

    /**
     *  The fitted component distributions, in group order.
     */
    val distributions: List<ContinuousDistributionIfc>
        get() = myComponents.map { it.distribution }

    /**
     *  The number of free parameters of the fitted mixture, as an information criterion should
     *  charge them: one fewer than the number of components for the mixing weights, since they
     *  are constrained to sum to one, plus every estimated parameter of every component
     *  including any estimated shift.
     *
     *  The library's mixture distribution reports its parameters as the mixing cumulative
     *  distribution followed by the component parameters, which counts as many weights as there
     *  are components. Reading the parameter count from that array therefore overstates the
     *  dimension by one, so the criteria in this package are supplied this value explicitly
     *  rather than reading it from the assembled distribution.
     */
    val numFreeParameters: Int
        get() = (numComponents - 1) + myComponents.sumOf { it.numParameters }

    /**
     *  The assembled candidate as a continuous distribution, suitable for any scoring model,
     *  plot, or goodness-of-fit test that accepts one.
     *
     *  A single-component candidate is returned as that component rather than as a mixture of
     *  one. The library's mixture distribution requires at least two components — reasonably, in
     *  that a mixture of one is not a mixture — so wrapping one would fail, and returning the
     *  component is both the honest answer and the useful one. This matters in practice because
     *  one component is how a single distribution is expressed here, and comparing a mixture
     *  against a single distribution is the first thing a user wants to do.
     *
     *  The return type is therefore the interface rather than the mixture class. Callers needing
     *  the mixture's own members, such as its mixing CDF, should check the type; a candidate with
     *  two or more components always yields one.
     */
    fun toMixtureDistribution(): ContinuousDistributionIfc {
        if (numComponents == 1) {
            return distributions.first()
        }
        val cdf = DoubleArray(numComponents)
        var cumulative = 0.0
        val w = weights
        for (j in 0 until numComponents) {
            cumulative += w[j]
            cdf[j] = cumulative
        }
        // guard against a final value fractionally below one from accumulated rounding
        cdf[numComponents - 1] = 1.0
        return MixtureDistribution(distributions, cdf)
    }

    /**
     *  The observed-data log-likelihood of this candidate over the supplied observations,
     *  computed by log-sum-exp, together with the diagnostics that say whether it is comparable
     *  with another candidate's.
     *
     *  @param data the observations
     */
    fun logLikelihood(data: DoubleArray): MixtureLogLikelihoodResult {
        return MixtureLogLikelihood.evaluate(data, weights, distributions)
    }

    /**
     *  A readable description naming each component and its weight.
     */
    override fun toString(): String {
        val w = weights
        return buildString {
            append("MixtureCandidate(k=$numComponents, p=$numFreeParameters)")
            for (j in 0 until numComponents) {
                append("\n  w=${"%.4f".format(w[j])}  n=${partition.sizeOf(j)}  ${myComponents[j].name}")
            }
        }
    }

    companion object {

        /**
         *  Assembles one candidate per combination of component choices, as a lazy sequence.
         *
         *  The number of combinations is the product of the per-group candidate counts, which
         *  grows exponentially in the number of groups. The sequence is lazy so that a caller
         *  that only needs the first few, or that is bounded by a beam width, never materializes
         *  the rest.
         *
         *  Returns an empty sequence when any group has no candidates: a mixture needs a
         *  component for every group, so one unfittable group invalidates the whole partition
         *  rather than one component of it.
         *
         *  @param partition the partition the groups belong to
         *  @param groupFits the fit results, one per group, in group order
         */
        fun allCombinations(
            partition: DataPartition,
            groupFits: List<GroupFitResult>
        ): Sequence<MixtureCandidate> {
            require(groupFits.size == partition.numGroups) {
                "The number of group fits (${groupFits.size}) must equal the number of groups " +
                        "(${partition.numGroups})"
            }
            if (groupFits.any { !it.hasCandidates }) return emptySequence()
            return cartesian(groupFits.map { it.candidates }).map { MixtureCandidate(partition, it) }
        }

        private fun <T> cartesian(lists: List<List<T>>): Sequence<List<T>> {
            if (lists.isEmpty()) return sequenceOf(emptyList())
            return sequence {
                val indices = IntArray(lists.size)
                while (true) {
                    yield(lists.indices.map { lists[it][indices[it]] })
                    var position = lists.size - 1
                    while (position >= 0) {
                        indices[position]++
                        if (indices[position] < lists[position].size) break
                        indices[position] = 0
                        position--
                    }
                    if (position < 0) break
                }
            }
        }
    }
}
