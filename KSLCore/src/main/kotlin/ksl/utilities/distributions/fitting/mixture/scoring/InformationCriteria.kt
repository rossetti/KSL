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
package ksl.utilities.distributions.fitting.mixture.scoring

import ksl.utilities.Interval
import ksl.utilities.distributions.fitting.mixture.MixtureCandidate
import ksl.utilities.distributions.fitting.mixture.MixtureLogLikelihood
import ksl.utilities.io.KSL
import ksl.utilities.moda.Metric
import ksl.utilities.moda.MetricIfc
import ksl.utilities.statistic.Statistic
import kotlin.math.ln

/**
 *  Shared machinery for criteria built from the observed-data log-likelihood.
 *
 *  The screening this performs is the reason these are separate classes rather than direct calls
 *  to the library's helpers. Those helpers require a finite log-likelihood and throw otherwise,
 *  while a mixture yields a non-finite one whenever a component assigns zero density to an
 *  observation. That is an expected outcome of a candidate whose components do not cover the
 *  data, not a programming error, so it is reported as an incomparable value with a bounded
 *  magnitude, following the pattern the library's own scoring models use for the same situation.
 *
 *  @param name the criterion name
 *  @param worstValue the magnitude assigned when the criterion cannot be computed
 */
abstract class LogLikelihoodCriterion(
    override val name: String,
    protected val worstValue: Double = defaultWorstValue
) : MixtureCriterionIfc {

    final override val smallerIsBetter: Boolean = true

    override fun metric(): MetricIfc {
        val m = Metric(name, Interval(-worstValue, worstValue))
        m.direction = MetricIfc.Direction.SmallerIsBetter
        m.description = "Mixture $name, smaller is better"
        return m
    }

    final override fun evaluate(candidate: MixtureCandidate, data: DoubleArray): MixtureCriterionValue {
        require(data.isNotEmpty()) { "The data must not be empty" }
        val ll = candidate.logLikelihood(data)
        if (!ll.isUsable) {
            val message = "The log-likelihood was ${ll.value}; ${ll.numZeroDensity} of " +
                    "${data.size} observations lie where the mixture density is zero"
            KSL.logger.warn { "$name: bounded value $worstValue assigned. $message" }
            return MixtureCriterionValue(worstValue, false, ll.numZeroDensity, message)
        }
        val value = compute(candidate, data, ll.value)
        if (!value.isFinite()) {
            val message = "The criterion evaluated to $value from a finite log-likelihood"
            KSL.logger.warn { "$name: bounded value $worstValue assigned. $message" }
            return MixtureCriterionValue(worstValue, false, ll.numZeroDensity, message)
        }
        return MixtureCriterionValue(value, true, ll.numZeroDensity)
    }

    /**
     *  Computes the criterion from a log-likelihood already known to be finite.
     *
     *  @param candidate the assembled mixture
     *  @param data the observations
     *  @param logLikelihood the finite observed-data log-likelihood
     */
    protected abstract fun compute(
        candidate: MixtureCandidate,
        data: DoubleArray,
        logLikelihood: Double
    ): Double

    companion object {

        /**
         *  The magnitude assigned when a criterion cannot be computed. Chosen to match the
         *  bound the library's own continuous scoring models use, so that a mixture criterion
         *  and a single-distribution criterion behave alike at their extremes.
         */
        var defaultWorstValue: Double = 1.0e7
            set(value) {
                require(value > 0.0) { "The default worst value must be > 0.0" }
                field = value
            }
    }
}

/**
 *  The Bayesian information criterion of a fitted mixture.
 *
 *  The number of free parameters is taken from the candidate, which charges one fewer parameter
 *  than there are components for the mixing weights, plus every estimated component parameter
 *  including any shift.
 */
class MixtureBICCriterion(
    worstValue: Double = defaultWorstValue
) : LogLikelihoodCriterion("BIC", worstValue) {

    override fun compute(
        candidate: MixtureCandidate,
        data: DoubleArray,
        logLikelihood: Double
    ): Double {
        return Statistic.bayesianInfoCriterion(data.size, candidate.numFreeParameters, logLikelihood)
    }

    /**
     *  A fresh instance, for use where a criterion must not be shared.
     */
    fun newInstance(): MixtureBICCriterion = MixtureBICCriterion(worstValue)
}

/**
 *  The Akaike information criterion of a fitted mixture, `2 * p - 2 * logLikelihood`.
 *
 *  Delegated to `Statistic.akaikeInfoCriterion`, as BIC is to `Statistic.bayesianInfoCriterion`.
 *  The library's guard that the log-likelihood be finite cannot fire from here: `evaluate` above
 *  rejects an unusable log-likelihood and assigns the bounded worst value before `compute` is
 *  reached.
 */
class MixtureAICCriterion(
    worstValue: Double = defaultWorstValue
) : LogLikelihoodCriterion("AIC", worstValue) {

    override fun compute(
        candidate: MixtureCandidate,
        data: DoubleArray,
        logLikelihood: Double
    ): Double {
        return Statistic.akaikeInfoCriterion(candidate.numFreeParameters, logLikelihood)
    }

    /**
     *  A fresh instance, for use where a criterion must not be shared.
     */
    fun newInstance(): MixtureAICCriterion = MixtureAICCriterion(worstValue)
}

/**
 *  The Hannan-Quinn information criterion of a fitted mixture,
 *  `-2 * logLikelihood + 2 * p * ln(ln(n))`.
 *
 *  **Measured as the best single criterion for this method's component count.** Scored on the
 *  identical fits of the count experiment, it recovers the true count on 41.1% of attempts against
 *  BIC's 36.6% and AIC's 29.5%, and its mean error is -0.36 components against BIC's -1.21 and
 *  AIC's +1.34. That it sits between them is the point rather than a coincidence: AIC over-selects
 *  and BIC under-selects for reasons that are theorems, so the useful penalty is between `2` and
 *  `ln n`.
 *
 *  It is the principled member of that interval rather than an interpolation. Hannan and Quinn
 *  (1979) derived `2 ln ln n` from the law of the iterated logarithm as the **smallest** penalty
 *  that remains consistent, so it keeps as much of AIC's power as consistency allows.
 *
 *  Reported alongside the others rather than made the default. Being best here by four points on
 *  one designed experiment is not enough to enthrone a criterion, and the wider finding of that
 *  experiment is that no penalty rescues the component count — an oracle-tuned coefficient reaches
 *  only about 47%.
 *
 *  Requires at least sixteen observations. Below `e` to the power `e`, which is about 15.15,
 *  `ln ln n` falls under one and the penalty is lighter than AIC's `2p` — which inverts the
 *  ordering this criterion exists to occupy, since it is meant to be the smallest penalty that
 *  is still consistent rather than a more lenient one than AIC. The penalty is positive from three
 *  observations and undefined at one, but neither of those is the binding constraint.
 */
class MixtureHannanQuinnCriterion(
    worstValue: Double = defaultWorstValue
) : LogLikelihoodCriterion("HQC", worstValue) {

    override fun compute(
        candidate: MixtureCandidate,
        data: DoubleArray,
        logLikelihood: Double
    ): Double {
        require(data.size >= 16) {
            "The Hannan-Quinn criterion needs at least 16 observations for its penalty to exceed " +
                    "AIC's; below that it is the more lenient of the two, which is not what it is " +
                    "for. This sample has ${data.size}"
        }
        return -2.0 * logLikelihood +
                2.0 * candidate.numFreeParameters * ln(ln(data.size.toDouble()))
    }

    /**
     *  A fresh instance, for use where a criterion must not be shared.
     */
    fun newInstance(): MixtureHannanQuinnCriterion = MixtureHannanQuinnCriterion(worstValue)
}

/**
 *  The integrated completed likelihood, in its approximation by the Bayesian information
 *  criterion penalized by the classification entropy.
 *
 *  This is the criterion matched to a classification-based fitting method: the entropy term
 *  penalizes a mixture whose components overlap, so it selects for a partition that classifies
 *  the data cleanly rather than merely fitting its density. The responsibilities it needs are
 *  computed from the same candidate.
 *
 *  The name reflects what is computed. This is not the exact integrated completed likelihood but
 *  its information-criterion approximation, and the distinction matters when comparing against
 *  published results.
 */
class MixtureICLBICCriterion(
    worstValue: Double = defaultWorstValue
) : LogLikelihoodCriterion("ICL-BIC", worstValue) {

    override fun compute(
        candidate: MixtureCandidate,
        data: DoubleArray,
        logLikelihood: Double
    ): Double {
        val bic = Statistic.bayesianInfoCriterion(
            data.size, candidate.numFreeParameters, logLikelihood
        )
        val responsibilities = MixtureLogLikelihood.responsibilities(
            data, candidate.weights, candidate.distributions
        )
        val entropy = MixtureLogLikelihood.classificationEntropy(responsibilities)
        return bic + 2.0 * entropy
    }

    /**
     *  A fresh instance, for use where a criterion must not be shared.
     */
    fun newInstance(): MixtureICLBICCriterion = MixtureICLBICCriterion(worstValue)
}

/**
 *  The Bayesian information criterion with an additional penalty for having selected the
 *  component families from a catalog.
 *
 *  Searching a catalog of families for every component and then reporting a criterion computed
 *  on the winner is optimistic, in the direction that inflates the number of components. The
 *  penalty added here is inspired by the extended information criterion for large model spaces,
 *  inserting the size of the family search space into a general model-space term.
 *
 *  This formula is an analogue and not a result: the extended criterion is not established for
 *  mixtures of heterogeneous families. The tuning constant must be calibrated against held-out
 *  likelihood rather than assumed.
 *
 *  @param catalogSize the number of candidate families per component
 *  @param xi the tuning constant, within the unit interval; zero recovers the ordinary criterion
 */
class MixtureEBICCriterion(
    val catalogSize: Int,
    val xi: Double = defaultXi,
    worstValue: Double = defaultWorstValue
) : LogLikelihoodCriterion("EBIC", worstValue) {

    init {
        require(catalogSize >= 1) { "The catalog size must be >= 1" }
        require(xi in 0.0..1.0) { "The tuning constant must be within [0, 1]. It was $xi" }
    }

    override fun compute(
        candidate: MixtureCandidate,
        data: DoubleArray,
        logLikelihood: Double
    ): Double {
        val bic = Statistic.bayesianInfoCriterion(
            data.size, candidate.numFreeParameters, logLikelihood
        )
        return bic + 2.0 * xi * candidate.numComponents * ln(catalogSize.toDouble())
    }

    /**
     *  A fresh instance, for use where a criterion must not be shared.
     */
    fun newInstance(): MixtureEBICCriterion = MixtureEBICCriterion(catalogSize, xi, worstValue)

    companion object {

        /**
         *  The default tuning constant for the family-search penalty.
         */
        var defaultXi: Double = 0.5
            set(value) {
                require(value in 0.0..1.0) { "The default tuning constant must be within [0, 1]" }
                field = value
            }
    }
}

/**
 *  The mean log-likelihood of a fitted mixture on observations it was not fitted to.
 *
 *  This is the only measure immune to the optimism introduced by selecting the partition, the
 *  families, and the number of components from the same data, which makes it the arbiter when
 *  the penalized criteria disagree.
 *
 *  Larger values indicate a better fit, unlike every other criterion here. Ranking machinery
 *  must consult the direction rather than assume it.
 *
 *  @param holdOutData the observations to evaluate on, disjoint from those used to fit
 */
class HeldOutLogLikelihoodCriterion(
    holdOutData: DoubleArray,
    private val worstValue: Double = LogLikelihoodCriterion.defaultWorstValue
) : MixtureCriterionIfc {

    private val myHoldOut: DoubleArray = holdOutData.copyOf()

    init {
        require(holdOutData.isNotEmpty()) { "The held-out data must not be empty" }
    }

    override val name: String = "HeldOutLogLikelihood"

    override val smallerIsBetter: Boolean = false

    override fun metric(): MetricIfc {
        val m = Metric(name, Interval(-worstValue, worstValue))
        m.direction = MetricIfc.Direction.BiggerIsBetter
        m.description = "Mean log-likelihood on held-out data, bigger is better"
        return m
    }

    override fun evaluate(candidate: MixtureCandidate, data: DoubleArray): MixtureCriterionValue {
        val ll = MixtureLogLikelihood.evaluate(myHoldOut, candidate.weights, candidate.distributions)
        if (!ll.isUsable) {
            val message = "${ll.numZeroDensity} of ${myHoldOut.size} held-out observations lie " +
                    "where the mixture density is zero"
            KSL.logger.warn { "$name: bounded value ${-worstValue} assigned. $message" }
            return MixtureCriterionValue(-worstValue, false, ll.numZeroDensity, message)
        }
        return MixtureCriterionValue(ll.value / myHoldOut.size, true, ll.numZeroDensity)
    }

    override fun toString(): String = "HeldOutLogLikelihoodCriterion(n=${myHoldOut.size})"
}
