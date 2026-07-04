package ksl.examples.general.simopt.problems

import ksl.simopt.benchmark.FunctionMemberEvaluatorFactory
import ksl.simopt.benchmark.ProblemCase
import ksl.simopt.benchmark.ReferenceSolution
import ksl.simopt.benchmark.ReferenceType
import ksl.simopt.evaluator.ResponseFunctionIfc
import ksl.simopt.problem.ProblemDefinition
import ksl.utilities.random.rng.RNStreamIfc

/**
 *  The base of the synthetic benchmark ladder: a deterministic test function observed
 *  through additive Gaussian noise at a named [NoiseLevel]. Each subclass supplies the
 *  family's true function, box bounds, and known optimum; this base turns that into a
 *  benchmark-ready [ProblemCase] with:
 *
 *  - input variables on the integer lattice (granularity 1) so integer-ordered solvers
 *    such as R-SPLINE participate,
 *  - the optimum placed on an integer lattice point, recorded as a
 *    KNOWN-OPTIMUM reference solution so gaps are exact,
 *  - all randomness drawn from the stream the oracle supplies (never a global stream),
 *    preserving common random numbers and whole-experiment reproducibility, and
 *  - analysis tags: family, dimension, noiseLevel, constrained.
 *
 *  The default replication observes only the objective. Subclasses with response
 *  constraints override the extra response names, the replication, and the problem
 *  configuration hook.
 *
 *  @param dimension the number of decision variables; must be at least 1
 *  @param noiseLevel the additive Gaussian noise level
 */
abstract class SyntheticFunctionProblem(
    val dimension: Int,
    val noiseLevel: NoiseLevel
) {

    init {
        require(dimension >= 1) { "The dimension must be >= 1" }
    }

    /** The family name used in problem names and analysis tags (e.g. "noisySphere"). */
    abstract val familyName: String

    /** The lower bound of every input variable's range. */
    abstract val lowerBound: Double

    /** The upper bound of every input variable's range. */
    abstract val upperBound: Double

    /** The known optimum; every coordinate must be an integer lattice point within bounds. */
    abstract val optimum: DoubleArray

    /** The deterministic (noise-free) objective at the supplied point. */
    abstract fun trueObjective(point: DoubleArray): Double

    /** The name of the objective response. */
    val objectiveResponseName: String = "objFn"

    /** Response names beyond the objective (for constrained subclasses). */
    open val extraResponseNames: List<String> = emptyList()

    /** The problem's name: family, dimension, and noise level. */
    val problemName: String
        get() = "${familyName}_d${dimension}_${noiseLevel.name}"

    /** The input variable names, x1..xd. */
    val inputNames: List<String> = (1..dimension).map { "x$it" }

    /**
     *  One noisy replication: the true objective plus one Gaussian draw at the noise
     *  level. Subclasses with extra responses override and must draw all randomness
     *  from the supplied stream.
     */
    open fun replication(point: DoubleArray, stream: RNStreamIfc): Map<String, Double> {
        return mapOf(
            objectiveResponseName to
                    trueObjective(point) + stream.rNormal(0.0, noiseLevel.sigma * noiseLevel.sigma)
        )
    }

    /** Hook for subclasses to add constraints to a freshly built problem definition. */
    protected open fun configureProblem(problemDefinition: ProblemDefinition) {
    }

    /** The response function over this synthetic, suitable for a `ResponseFunctionOracle`. */
    fun responseFunction(): ResponseFunctionIfc {
        return ResponseFunctionIfc { inputs, stream ->
            val point = DoubleArray(dimension) { i -> inputs.getValue(inputNames[i]) }
            replication(point, stream)
        }
    }

    /** A fresh problem definition: integer-lattice inputs over the family's box, plus
     *  any subclass constraints. */
    fun problemDefinition(): ProblemDefinition {
        val pd = ProblemDefinition(
            problemName = problemName,
            modelIdentifier = problemName,
            objFnResponseName = objectiveResponseName,
            inputNames = inputNames,
            responseNames = extraResponseNames
        )
        for (inputName in inputNames) {
            pd.inputVariable(inputName, lowerBound, upperBound, granularity = 1.0)
        }
        configureProblem(pd)
        return pd
    }

    /** The known-optimum reference this family's runs are gapped against. */
    fun referenceSolution(): ReferenceSolution {
        return ReferenceSolution(
            inputs = inputNames.zip(optimum.toList()).toMap(),
            objectiveValue = trueObjective(optimum),
            type = ReferenceType.KNOWN_OPTIMUM
        )
    }

    /** The benchmark-ready problem case for this synthetic. */
    fun problemCase(): ProblemCase {
        return ProblemCase(
            name = problemName,
            problemDefinitionFactory = { problemDefinition() },
            evaluatorFactoryProvider = { pd -> FunctionMemberEvaluatorFactory(pd, responseFunction()) },
            referenceSolution = referenceSolution(),
            tags = mapOf(
                "family" to familyName,
                "dimension" to dimension.toString(),
                "noiseLevel" to noiseLevel.name,
                "constrained" to (extraResponseNames.isNotEmpty()).toString()
            )
        )
    }

    companion object {

        /**
         *  The standard off-center integer shift for optima: alternating 3 and -2 by
         *  coordinate, so the optimum is neither at the origin nor at the box center
         *  (both of which some heuristics find for free).
         */
        fun standardShift(dimension: Int): DoubleArray {
            return DoubleArray(dimension) { i -> if (i % 2 == 0) 3.0 else -2.0 }
        }
    }
}
