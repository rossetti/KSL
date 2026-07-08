package ksl.examples.general.simopt.problems

import ksl.simopt.benchmark.BenchmarkExperiment
import ksl.simopt.benchmark.BenchmarkSolverFactoryIfc
import ksl.simopt.benchmark.SolverCase
import ksl.simopt.evaluator.EvaluationRequest
import ksl.simopt.evaluator.ModelInputs
import ksl.simopt.evaluator.ResponseFunctionOracle
import ksl.simopt.solvers.algorithms.StochasticHillClimber
import ksl.utilities.random.rng.RNStreamProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Tests for the synthetic problem suite: construction and reference validity across the
 * full ladder (dimensions x noise levels), local optimality of the recorded optima,
 * CRN-ability of every response function (paired draws under common random numbers),
 * closed-form/reference agreement for the newsvendor problems (computed independently,
 * not hardcoded twice), the constrained newsvendor's feasibility boundary, and an
 * end-to-end sanity run recovering the sphere optimum at LOW noise.
 */
@Timeout(180)
class SyntheticProblemsTest {

    private fun ladderFamilies(dimension: Int, noiseLevel: NoiseLevel): List<SyntheticFunctionProblem> {
        return listOf(
            NoisySphere(dimension, noiseLevel),
            NoisyRosenbrock(dimension, noiseLevel),
            NoisyRastrigin(dimension, noiseLevel),
            ConstrainedNoisyQuadratic(dimension, noiseLevel)
        )
    }

    @Test
    @DisplayName("Every family x dimension x noise level constructs with a feasible lattice optimum")
    fun ladderConstructsWithValidReferences() {
        for (dimension in listOf(2, 5)) {
            for (noiseLevel in NoiseLevel.entries) {
                for (problem in ladderFamilies(dimension, noiseLevel)) {
                    val case = problem.problemCase()
                    val pd = case.problemDefinitionFactory()
                    val reference = case.referenceSolution
                    assertNotNull(reference)
                    // the optimum is on the integer lattice and input-feasible
                    for ((_, value) in reference!!.inputs) {
                        assertEquals(round(value), value)
                    }
                    val referencePoint = pd.toInputMap(reference.inputs.toMutableMap())
                    assertTrue(referencePoint.isInputFeasible()) {
                        "${problem.problemName}: reference point is not input feasible"
                    }
                    assertEquals(problem.trueObjective(problem.optimum), reference.objectiveValue)
                    assertEquals(dimension.toString(), case.tags["dimension"])
                    assertEquals(noiseLevel.name, case.tags["noiseLevel"])
                    // a fresh starting point can be drawn (the harness's D7 policy works)
                    assertNotNull(pd.startingPoint(RNStreamProvider().rnStream(1)))
                }
            }
        }
    }

    @Test
    @DisplayName("Recorded optima are locally optimal on the lattice for the unconstrained families")
    fun optimaAreLocallyOptimal() {
        for (dimension in listOf(2, 5)) {
            val unconstrained = listOf(
                NoisySphere(dimension, NoiseLevel.LOW),
                NoisyRosenbrock(dimension, NoiseLevel.LOW),
                NoisyRastrigin(dimension, NoiseLevel.LOW)
            )
            for (problem in unconstrained) {
                val optimumValue = problem.trueObjective(problem.optimum)
                for (i in 0 until dimension) {
                    for (delta in listOf(-1.0, 1.0)) {
                        val neighbor = problem.optimum.copyOf()
                        neighbor[i] += delta
                        assertTrue(problem.trueObjective(neighbor) > optimumValue) {
                            "${problem.problemName}: lattice neighbor $i/$delta not worse than optimum"
                        }
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("Additive-noise families are CRN-able: paired points share exactly the same noise")
    fun additiveNoiseFamiliesAreCrnAble() {
        for (problem in ladderFamilies(2, NoiseLevel.MED)) {
            val responseNames = (listOf(problem.objectiveResponseName) + problem.extraResponseNames).toSet()
            val oracle = ResponseFunctionOracle(
                problem.problemName, responseNames, problem.responseFunctionBuilder()
            )
            val point1 = ModelInputs(problem.problemName, 8, mapOf("x1" to 1.0, "x2" to 2.0))
            val point2 = ModelInputs(problem.problemName, 8, mapOf("x1" to -3.0, "x2" to 4.0))
            val request = EvaluationRequest(
                problem.problemName, listOf(point1, point2), crnOption = true, cachingAllowed = false
            )
            val results = oracle.simulate(request)
            val avg1 = results.getValue(point1).getOrThrow().getValue(problem.objectiveResponseName).average
            val avg2 = results.getValue(point2).getOrThrow().getValue(problem.objectiveResponseName).average
            val true1 = problem.trueObjective(doubleArrayOf(1.0, 2.0))
            val true2 = problem.trueObjective(doubleArrayOf(-3.0, 4.0))
            // identical noise sequences under CRN: the noise averages match exactly
            assertEquals(avg1 - true1, avg2 - true2, 1e-9) {
                "${problem.problemName}: CRN points did not share noise draws"
            }
        }
    }

    @Test
    @DisplayName("A replication with a micro sample averages that many raw draws, shrinking noise exactly")
    fun microSampleAveragesRawDraws() {
        // the same problem observed at micro sample sizes 1 and 4: with identical
        // positioning, the size-4 observation is the average of four consecutive raw
        // draws, so its noise equals the average of the size-1 noise draws would be —
        // verified here by exact construction against the problem's own noise model
        val problem = NoisySphere(2, NoiseLevel.MED)
        val inputs = mapOf("x1" to 1.0, "x2" to 2.0)
        val trueValue = problem.trueObjective(doubleArrayOf(1.0, 2.0))
        val microSampleSize = 4
        val oracle = ResponseFunctionOracle(
            problem.problemName, setOf(problem.objectiveResponseName),
            problem.responseFunctionBuilder(),
            microRepSampleSize = microSampleSize
        )
        val request = ModelInputs(problem.problemName, 3, inputs)
        val estimate = oracle.simulate(EvaluationRequest(problem.problemName, listOf(request)))
            .getValue(request).getOrThrow().getValue(problem.objectiveResponseName)
        assertEquals(3.0, estimate.count)
        // reference: same builder against a fresh provider, positioned the same way,
        // averaging microSampleSize raw draws per sub-stream
        val referenceProvider = ksl.utilities.random.rng.RNStreamProvider()
        val referenceFunction = problem.responseFunctionBuilder().build(referenceProvider)
        referenceProvider.resetAllStreamsToStart()
        val observations = DoubleArray(3)
        for (r in 0 until 3) {
            if (r > 0) referenceProvider.advanceAllStreamsToNextSubStream()
            var sum = 0.0
            repeat(microSampleSize) {
                sum += referenceFunction.replication(inputs).getValue(problem.objectiveResponseName)
            }
            observations[r] = sum / microSampleSize
        }
        assertEquals(observations.average(), estimate.average, 1e-9)
        assertTrue(kotlin.math.abs(estimate.average - trueValue) < 4.0 * NoiseLevel.MED.sigma)
    }

    @Test
    @DisplayName("The newsvendor's CRN demand draws match a hand-driven reference stream")
    fun newsvendorCrnSharesDemandDraws() {
        val newsvendor = Newsvendor()
        val oracle = ResponseFunctionOracle(
            newsvendor.problemName,
            setOf(newsvendor.objectiveResponseName),
            newsvendor.responseFunctionBuilder()
        )
        val numReplications = 5
        val q1 = 40.0
        val q2 = 90.0
        val point1 = ModelInputs(newsvendor.problemName, numReplications, mapOf(newsvendor.inputName to q1))
        val point2 = ModelInputs(newsvendor.problemName, numReplications, mapOf(newsvendor.inputName to q2))
        val request = EvaluationRequest(
            newsvendor.problemName, listOf(point1, point2), crnOption = true, cachingAllowed = false
        )
        val results = oracle.simulate(request)
        // hand-drive the same demands: replication r draws the first exponential of sub-stream r
        val stream = RNStreamProvider().rnStream(1)
        stream.resetStartStream()
        val demands = DoubleArray(numReplications)
        for (r in 0 until numReplications) {
            if (r > 0) stream.advanceToNextSubStream()
            demands[r] = stream.rExponential(newsvendor.demandMean)
        }
        fun profit(q: Double, demand: Double): Double {
            return newsvendor.unitPrice * min(demand, q) +
                    newsvendor.salvageValue * max(q - demand, 0.0) - newsvendor.unitCost * q
        }
        val expected1 = demands.map { profit(q1, it) }.average()
        val expected2 = demands.map { profit(q2, it) }.average()
        val avg1 = results.getValue(point1).getOrThrow().getValue(newsvendor.objectiveResponseName).average
        val avg2 = results.getValue(point2).getOrThrow().getValue(newsvendor.objectiveResponseName).average
        assertEquals(expected1, avg1, 1e-9)
        assertEquals(expected2, avg2, 1e-9)
    }

    @Test
    @DisplayName("The newsvendor reference matches a numeric integral and a lattice brute force")
    fun newsvendorReferenceMatchesIndependentComputation() {
        val newsvendor = Newsvendor()
        // independent check of the closed form: E(min(D,q)) as a numeric integral of the
        // exponential survival function
        fun numericExpectedProfit(q: Double): Double {
            val steps = 20_000
            var integral = 0.0
            val dx = q / steps
            for (k in 0 until steps) {
                val x1 = k * dx
                val x2 = (k + 1) * dx
                integral += 0.5 * (exp(-x1 / newsvendor.demandMean) + exp(-x2 / newsvendor.demandMean)) * dx
            }
            return (newsvendor.unitPrice - newsvendor.salvageValue) * integral -
                    (newsvendor.unitCost - newsvendor.salvageValue) * q
        }
        assertEquals(numericExpectedProfit(80.0), newsvendor.expectedProfit(80.0), 1e-3)
        // brute force the lattice: the recorded optimum is the argmax
        val bruteForceBest = (0..newsvendor.maxOrderQuantity.toInt())
            .maxBy { newsvendor.expectedProfit(it.toDouble()) }
        assertEquals(bruteForceBest.toDouble(), newsvendor.optimalOrderQuantity)
        assertEquals(
            newsvendor.expectedProfit(newsvendor.optimalOrderQuantity),
            newsvendor.referenceSolution().objectiveValue
        )
    }

    @Test
    @DisplayName("The multi-item greedy allocation matches brute force and honors the budget boundary")
    fun multiItemNewsvendorGreedyIsOptimalAndFeasible() {
        // a small instance that brute force can enumerate exactly
        val small = MultiItemNewsvendor(
            demandMeans = doubleArrayOf(20.0, 40.0),
            unitPrices = doubleArrayOf(6.0, 4.0),
            unitBudget = 30,
            maxOrderQuantity = 30.0
        )
        var bestProfit = Double.NEGATIVE_INFINITY
        var bestAllocation = doubleArrayOf(0.0, 0.0)
        for (q1 in 0..30) {
            for (q2 in 0..(30 - q1)) {
                val profit = small.expectedProfit(doubleArrayOf(q1.toDouble(), q2.toDouble()))
                if (profit > bestProfit) {
                    bestProfit = profit
                    bestAllocation = doubleArrayOf(q1.toDouble(), q2.toDouble())
                }
            }
        }
        assertTrue(bestAllocation.contentEquals(small.optimalOrderQuantities)) {
            "greedy ${small.optimalOrderQuantities.toList()} != brute force ${bestAllocation.toList()}"
        }
        // the defaults make the budget bind: the reference sits exactly on the boundary
        val defaults = MultiItemNewsvendor()
        assertEquals(defaults.unitBudget.toDouble(), defaults.optimalOrderQuantities.sum())
        val pd = defaults.problemDefinition()
        val referencePoint = pd.toInputMap(defaults.referenceSolution().inputs.toMutableMap())
        assertTrue(referencePoint.isInputFeasible())
        assertTrue(pd.linearConstraintViolations(defaults.referenceSolution().inputs).all { it == 0.0 })
        // the unconstrained per-item optima violate the budget (the constraint matters)
        val unconstrained = defaults.inputNames.mapIndexed { i, name ->
            name to defaults.demandMeans[i] * kotlin.math.ln(defaults.unitPrices[i] / defaults.unitCost)
        }.toMap()
        assertTrue(pd.linearConstraintViolations(unconstrained).any { it > 0.0 })
    }

    @Test
    @DisplayName("Sanity: SHC at LOW noise recovers the sphere optimum region within budget")
    fun shcRecoversSphereOptimumAtLowNoise() {
        val sphere = NoisySphere(2, NoiseLevel.LOW)
        val summary = BenchmarkExperiment(
            name = "sanity",
            problems = listOf(sphere.problemCase()),
            solverCases = listOf(
                SolverCase("shc", BenchmarkSolverFactoryIfc { pd, evaluator, _, name ->
                    StochasticHillClimber(
                        pd, evaluator,
                        maximumIterations = 1,
                        replicationsPerEvaluation = 10,
                        name = name
                    )
                })
            ),
            macroReplications = 1,
            replicationBudgetPerRun = 3000,
            numWorkers = 1
        ).run()
        val problemResult = summary.problemResults.single()
        val winner = problemResult.winner
        assertNotNull(winner)
        val winnerPoint = DoubleArray(2) { i -> winner!!.inputMap.getValue(sphere.inputNames[i]) }
        val trueValueAtWinner = sphere.trueObjective(winnerPoint)
        assertTrue(trueValueAtWinner <= 9.0) {
            "SHC did not reach the optimum region: true objective $trueValueAtWinner at " +
                    winnerPoint.toList()
        }
        val gap = problemResult.runs.single().gap
        assertNotNull(gap)
        assertEquals(ksl.simopt.benchmark.GapType.KNOWN_OPTIMUM, problemResult.runs.single().gapType)
    }
}
