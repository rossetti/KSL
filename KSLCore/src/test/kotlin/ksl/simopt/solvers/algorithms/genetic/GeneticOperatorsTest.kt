package ksl.simopt.solvers.algorithms.genetic

import ksl.simopt.evaluator.Solution
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 *  Unit tests for the genetic operators. Each operator is exercised through a (non-run) solver
 *  instance so that it can draw from the solver's single random number stream, mirroring how the
 *  operators are used during a search. A fixed stream number makes every test reproducible.
 */
class GeneticOperatorsTest {

    private val pd = GeneticTestSupport.boxProblem(dim = 3)
    private val objective = GeneticTestSupport.sphere(doubleArrayOf(0.0, 0.0, 0.0))

    private fun solver() = GeneticTestSupport.makeSolver(pd, objective, streamNum = 1)

    private fun population(): List<Solution> = listOf(
        GeneticTestSupport.solutionAt(pd, doubleArrayOf(1.0, 1.0, 1.0), fitness = 1.0),  // best
        GeneticTestSupport.solutionAt(pd, doubleArrayOf(2.0, 2.0, 2.0), fitness = 5.0),
        GeneticTestSupport.solutionAt(pd, doubleArrayOf(3.0, 3.0, 3.0), fitness = 9.0),
        GeneticTestSupport.solutionAt(pd, doubleArrayOf(4.0, 4.0, 4.0), fitness = 20.0) // worst
    )

    // ---- Tournament selection ----

    @Test
    fun tournamentSelectionReturnsRequestedCountFromPopulation() {
        val s = solver()
        val pop = population()
        val selected = TournamentSelection(tournamentSize = 2).select(pop, 6, s)
        assertEquals(6, selected.size, "selection must return exactly the requested number of parents")
        assertTrue(selected.all { it in pop }, "every selected parent must come from the population")
    }

    @Test
    fun tournamentSelectionIsReproducibleForAFixedStream() {
        val a = TournamentSelection(tournamentSize = 3).select(population(), 8, solver())
        val b = TournamentSelection(tournamentSize = 3).select(population(), 8, solver())
        assertEquals(a.map { it.penalizedObjFncValue }, b.map { it.penalizedObjFncValue },
            "the same stream number must reproduce the same selection sequence")
    }

    @Test
    fun tournamentSelectionFavorsBetterSolutions() {
        val s = solver()
        val pop = population()
        val best = pop.first()
        val worst = pop.last()
        val selected = TournamentSelection(tournamentSize = 3).select(pop, 400, s)
        val bestCount = selected.count { it === best }
        val worstCount = selected.count { it === worst }
        assertTrue(bestCount > worstCount, "the best solution should be selected more often than the worst")
    }

    // ---- Blend crossover ----

    @Test
    fun blendCrossoverProducesTwoOffspringOfCorrectDimension() {
        val children = BlendCrossover().crossover(
            doubleArrayOf(1.0, 2.0, 3.0), doubleArrayOf(4.0, 5.0, 6.0), solver()
        )
        assertEquals(2, children.size, "blend crossover should produce two offspring")
        children.forEach { assertEquals(3, it.size, "each offspring must match the parent dimension") }
    }

    @Test
    fun blendCrossoverWithIdenticalParentsCopiesThem() {
        val parent = doubleArrayOf(2.0, -3.0, 7.0)
        val children = BlendCrossover().crossover(parent.copyOf(), parent.copyOf(), solver())
        children.forEach { child ->
            for (i in parent.indices) {
                assertEquals(parent[i], child[i], 1e-12, "identical parents must yield identical offspring genes")
            }
        }
    }

    // ---- Gaussian mutation ----

    @Test
    fun gaussianMutationReturnsANewArrayAndDoesNotModifyInput() {
        val point = doubleArrayOf(1.0, 2.0, 3.0)
        val copy = point.copyOf()
        val mutated = GaussianMutation(pd, perGeneRate = 1.0).mutate(point, solver())
        assertNotSame(point, mutated, "mutation must return a new array")
        assertEquals(copy.toList(), point.toList(), "mutation must not modify the supplied array")
    }

    @Test
    fun gaussianMutationWithZeroRateLeavesPointUnchanged() {
        val point = doubleArrayOf(1.0, 2.0, 3.0)
        val mutated = GaussianMutation(pd, perGeneRate = 0.0).mutate(point, solver())
        for (i in point.indices) {
            assertEquals(point[i], mutated[i], 1e-12, "a zero per-gene rate must leave every coordinate unchanged")
        }
    }

    @Test
    fun gaussianMutationWithFullRatePerturbsPoint() {
        val point = doubleArrayOf(1.0, 2.0, 3.0)
        val mutated = GaussianMutation(pd, perGeneRate = 1.0).mutate(point, solver())
        var changed = false
        for (i in point.indices) {
            if (kotlin.math.abs(point[i] - mutated[i]) > 1e-12) changed = true
        }
        assertTrue(changed, "a full per-gene rate should perturb at least one coordinate")
    }

    // ---- Uniform crossover ----

    @Test
    fun uniformCrossoverNoSwapInheritsParentsDirectly() {
        val p1 = doubleArrayOf(1.0, 2.0, 3.0)
        val p2 = doubleArrayOf(4.0, 5.0, 6.0)
        val children = UniformCrossover(swapProbability = 0.0).crossover(p1, p2, solver())
        assertEquals(p1.toList(), children[0].toList(), "with no swap, child 1 must equal parent 1")
        assertEquals(p2.toList(), children[1].toList(), "with no swap, child 2 must equal parent 2")
    }

    @Test
    fun uniformCrossoverFullSwapExchangesParents() {
        val p1 = doubleArrayOf(1.0, 2.0, 3.0)
        val p2 = doubleArrayOf(4.0, 5.0, 6.0)
        val children = UniformCrossover(swapProbability = 1.0).crossover(p1, p2, solver())
        assertEquals(p2.toList(), children[0].toList(), "with full swap, child 1 must equal parent 2")
        assertEquals(p1.toList(), children[1].toList(), "with full swap, child 2 must equal parent 1")
    }

    // ---- Single-point crossover ----

    @Test
    fun singlePointCrossoverRecombinesGenesPositionwise() {
        val p1 = doubleArrayOf(1.0, 2.0, 3.0, 4.0)
        val p2 = doubleArrayOf(5.0, 6.0, 7.0, 8.0)
        val children = SinglePointCrossover().crossover(p1, p2, solver())
        assertEquals(2, children.size)
        for (i in p1.indices) {
            val pair = setOf(p1[i], p2[i])
            assertTrue(children[0][i] in pair && children[1][i] in pair,
                "each offspring gene must come from one of the parents at that position")
            assertTrue(children[0][i] != children[1][i] || p1[i] == p2[i],
                "the two offspring must be complementary at each position")
        }
    }

    @Test
    fun singlePointCrossoverWithSingleDimensionCopiesParents() {
        val onePd = GeneticTestSupport.boxProblem(dim = 1)
        val s = GeneticTestSupport.makeSolver(onePd, GeneticTestSupport.sphere(doubleArrayOf(0.0)), streamNum = 1)
        val children = SinglePointCrossover().crossover(doubleArrayOf(2.0), doubleArrayOf(9.0), s)
        assertEquals(2.0, children[0][0], 1e-12)
        assertEquals(9.0, children[1][0], 1e-12)
    }

    // ---- Uniform-reset mutation ----

    @Test
    fun uniformResetMutationKeepsValuesWithinBounds() {
        val point = doubleArrayOf(0.0, 0.0, 0.0)
        val mutated = UniformResetMutation(pd, perGeneRate = 1.0).mutate(point, solver())
        for (v in mutated) {
            assertTrue(v in -10.0..10.0, "reset values must lie within the input range")
        }
        assertNotSame(point, mutated, "mutation must return a new array")
    }

    @Test
    fun uniformResetMutationWithZeroRateLeavesPointUnchanged() {
        val point = doubleArrayOf(1.0, -2.0, 3.0)
        val mutated = UniformResetMutation(pd, perGeneRate = 0.0).mutate(point, solver())
        assertEquals(point.toList(), mutated.toList(), "a zero per-gene rate must leave the point unchanged")
    }

    // ---- Rank selection ----

    @Test
    fun rankSelectionReturnsRequestedCountFromPopulation() {
        val s = solver()
        val pop = population()
        val selected = RankSelection(selectionPressure = 1.5).select(pop, 10, s)
        assertEquals(10, selected.size)
        assertTrue(selected.all { it in pop })
    }

    @Test
    fun rankSelectionWithMaxPressureNeverSelectsTheWorst() {
        val s = solver()
        val pop = population()
        val worst = pop.last()
        val selected = RankSelection(selectionPressure = 2.0).select(pop, 300, s)
        assertFalse(selected.any { it === worst },
            "with maximum selection pressure the worst-ranked solution must have zero probability")
    }

    // ---- Roulette wheel selection ----

    @Test
    fun rouletteWheelSelectionFavorsBetterSolutions() {
        val s = solver()
        val pop = population()
        val best = pop.first()
        val worst = pop.last()
        val selected = RouletteWheelSelection().select(pop, 400, s)
        assertEquals(400, selected.size)
        assertTrue(selected.count { it === best } > selected.count { it === worst },
            "windowed roulette selection should favor the better solution")
    }

    @Test
    fun rouletteWheelSelectionWithEqualFitnessStillReturnsCount() {
        val s = solver()
        val equalPop = listOf(
            GeneticTestSupport.solutionAt(pd, doubleArrayOf(1.0, 1.0, 1.0), fitness = 3.0),
            GeneticTestSupport.solutionAt(pd, doubleArrayOf(2.0, 2.0, 2.0), fitness = 3.0),
            GeneticTestSupport.solutionAt(pd, doubleArrayOf(3.0, 3.0, 3.0), fitness = 3.0)
        )
        val selected = RouletteWheelSelection().select(equalPop, 5, s)
        assertEquals(5, selected.size, "equal fitness must fall back to uniform selection and still return the count")
    }
}
