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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FittingCatalogTest {

    @Test
    fun `catalog registers eighteen estimators`() {
        assertEquals(18, FittingCatalog.estimators.size)
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
}
