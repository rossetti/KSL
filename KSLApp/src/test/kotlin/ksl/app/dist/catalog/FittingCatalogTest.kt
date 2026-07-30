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

package ksl.app.dist.catalog

import ksl.app.dist.config.DistributionKind
import ksl.utilities.distributions.MetalogBoundedness
import ksl.utilities.distributions.fitting.estimators.MetalogParameterEstimator
import ksl.utilities.random.rvariable.RVParametersTypeIfc
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FittingCatalogTest {

    @Test
    fun `catalog registers eighteen classical estimators plus twenty metalog estimators`() {
        assertEquals(38, FittingCatalog.estimators.size)
        assertEquals(20, FittingCatalog.estimators.count { it.id.startsWith("metalog") })
    }

    @Test
    fun `catalog registers fifteen scoring models`() {
        assertEquals(15, FittingCatalog.scoringModels.size)
    }

    @Test
    fun `every estimator id resolves and factory produces a non-null instance`() {
        for (descriptor in FittingCatalog.estimators) {
            assertEquals(descriptor, FittingCatalog.estimator(descriptor.id))
            assertNotNull(descriptor.factory(), "factory for '${descriptor.id}' returned null")
        }
    }

    @Test
    fun `every scoring model id resolves and factory produces a non-null instance`() {
        for (descriptor in FittingCatalog.scoringModels) {
            assertEquals(descriptor, FittingCatalog.scoringModel(descriptor.id))
            assertNotNull(descriptor.factory(), "factory for '${descriptor.id}' returned null")
        }
    }

    @Test
    fun `unknown estimator id throws`() {
        assertThrows<IllegalStateException> { FittingCatalog.estimator("nope-mle") }
    }

    @Test
    fun `unknown scoring model id throws`() {
        assertThrows<IllegalStateException> { FittingCatalog.scoringModel("nope") }
    }

    @Test
    fun `continuous default ids exactly match PDFModeler dot allEstimators families`() {
        val continuousDefaults = FittingCatalog.defaultEstimatorIds(DistributionKind.CONTINUOUS)
        // PDFModeler ships 9 default estimators (unrestricted + positive-restricted).
        // All must resolve and all must be CONTINUOUS.
        assertEquals(9, continuousDefaults.size)
        for (id in continuousDefaults) {
            val descriptor = FittingCatalog.estimator(id)
            assertEquals(DistributionKind.CONTINUOUS, descriptor.kind)
        }
    }

    @Test
    fun `discrete default ids cover all registered discrete estimators`() {
        val discreteDefaults = FittingCatalog.defaultEstimatorIds(DistributionKind.DISCRETE)
        val discreteRegistered = FittingCatalog.estimators
            .filter { it.kind == DistributionKind.DISCRETE }
            .map { it.id }
            .toSet()
        assertEquals(discreteRegistered, discreteDefaults)
    }

    @Test
    fun `default scoring model ids match PDFModeler dot defaultScoringModels by count`() {
        val defaults = FittingCatalog.defaultScoringModelIds()
        // PDFModeler ships BIC, Anderson-Darling, Cramer-von Mises, Q-Q correlation.
        assertEquals(4, defaults.size)
        assertTrue("bic" in defaults)
        assertTrue("anderson-darling" in defaults)
        assertTrue("cramer-von-mises" in defaults)
        assertTrue("qq-correlation" in defaults)
    }

    @Test
    fun `continuous and discrete estimator id sets are disjoint`() {
        val continuous = FittingCatalog.estimators
            .filter { it.kind == DistributionKind.CONTINUOUS }
            .map { it.id }
            .toSet()
        val discrete = FittingCatalog.estimators
            .filter { it.kind == DistributionKind.DISCRETE }
            .map { it.id }
            .toSet()
        assertTrue(continuous.intersect(discrete).isEmpty())
    }

    @Test
    fun `family descriptors cover every estimator's rvType`() {
        for (estimator in FittingCatalog.estimators) {
            val familyId = FittingCatalog.familyIdFor(estimator.rvType)
            assertNotNull(familyId, "no family registered for ${estimator.id}'s rvType")
            assertEquals(estimator.familyId, familyId)
            assertNotNull(FittingCatalog.familyOrNull(familyId))
        }
    }

    @Test
    fun `gamma family is reached by both MLE and MOM estimators`() {
        val gammaEstimators = FittingCatalog.estimators.filter { it.familyId == "gamma" }
        assertEquals(setOf("gamma-mle", "gamma-mom"), gammaEstimators.map { it.id }.toSet())
    }

    // ----- metalog -----------------------------------------------------------

    @Test
    fun `every metalog arity and boundedness pair is registered under a stable id`() {
        val expected = (2..6).flatMap { n ->
            listOf(
                "metalog${n}p-unbounded",
                "metalog${n}p-lower-bounded",
                "metalog${n}p-upper-bounded",
                "metalog${n}p-bounded",
            )
        }.toSet()
        val registered = FittingCatalog.estimators
            .filter { it.id.startsWith("metalog") }
            .map { it.id }
            .toSet()
        assertEquals(expected, registered)
    }

    @Test
    fun `the four boundedness variants of one arity share a single family`() {
        // Boundedness is carried by the fitted lowerBound and upperBound parameters, not
        // by the distribution class, so all four collapse to the arity's family.
        for (n in 2..6) {
            val familyIds = FittingCatalog.estimators
                .filter { it.id.startsWith("metalog${n}p-") }
                .map { it.familyId }
                .toSet()
            assertEquals(setOf("metalog${n}p"), familyIds, "arity $n did not collapse to one family")
        }
    }

    @Test
    fun `metalog families resolve by id and carry a readable display name`() {
        for (n in 2..6) {
            val family = FittingCatalog.familyOrNull("metalog${n}p")
            assertNotNull(family, "metalog${n}p family is not registered")
            assertEquals(DistributionKind.CONTINUOUS, family.kind)
            assertEquals("Metalog $n-term", family.displayName)
        }
    }

    @Test
    fun `metalog estimators are continuous, skip the range check, and instantiate freshly`() {
        val metalog = FittingCatalog.estimators.filter { it.id.startsWith("metalog") }
        for (descriptor in metalog) {
            assertEquals(DistributionKind.CONTINUOUS, descriptor.kind, descriptor.id)
            // A metalog represents a lower bound natively, so PDFModeler must not shift the data.
            assertFalse(descriptor.checksRange, "${descriptor.id} should not request a range check")
            val first = descriptor.factory()
            val second = descriptor.factory()
            assertTrue(first !== second, "${descriptor.id} factory returned a shared instance")
            assertEquals(descriptor.rvType, first.rvType)
        }
    }

    @Test
    fun `metalog estimators resolve to the arity's distribution type`() {
        for (n in 2..6) {
            val expected = MetalogParameterEstimator.typeFor(n)
            val types = FittingCatalog.estimators
                .filter { it.id.startsWith("metalog${n}p-") }
                .map { it.rvType }
                .toSet()
            assertEquals(setOf<RVParametersTypeIfc>(expected), types)
        }
    }

    @Test
    fun `metalog estimators are opted into rather than defaulted`() {
        // PDFModeler deliberately keeps the family out of allEstimators; the catalog must
        // mirror that, or every existing caller's recommended distribution could change.
        val defaults = FittingCatalog.defaultEstimatorIds(DistributionKind.CONTINUOUS)
        assertTrue(
            defaults.none { it.startsWith("metalog") },
            "metalog leaked into the continuous defaults: $defaults"
        )
    }

    @Test
    fun `metalog ids instantiate estimators matching their arity and boundedness`() {
        val cases = mapOf(
            "metalog2p-unbounded" to Pair(2, MetalogBoundedness.Unbounded),
            "metalog4p-lower-bounded" to Pair(4, MetalogBoundedness.LowerBounded),
            "metalog5p-upper-bounded" to Pair(5, MetalogBoundedness.UpperBounded),
            "metalog6p-bounded" to Pair(6, MetalogBoundedness.Bounded),
        )
        for ((id, expected) in cases) {
            val estimator = FittingCatalog.estimator(id).factory()
            assertTrue(estimator is MetalogParameterEstimator, "$id did not produce a metalog estimator")
            assertEquals(expected.first, estimator.numTerms, id)
            assertEquals(expected.second, estimator.boundedness, id)
        }
    }
}
