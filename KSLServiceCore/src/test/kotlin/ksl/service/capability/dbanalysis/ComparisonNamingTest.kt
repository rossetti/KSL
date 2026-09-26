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
package ksl.service.capability.dbanalysis

import ksl.app.comparison.ComparisonDataSourceIfc
import ksl.app.comparison.ExperimentRow
import ksl.app.comparison.ResponseCategory
import ksl.app.comparison.ResponseRow
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 *  A comparison request that names something the database does not have must say *what* it could not
 *  find. Both of these used to be silent in the way that matters: the input was discarded, the request
 *  then failed for a downstream reason, and the message pointed at the consequence rather than the
 *  cause.
 *
 *  A typo is the likeliest cause of either, which is why naming the available alternatives is the
 *  whole value — the caller can see their mistake instead of inferring it.
 */
class ComparisonNamingTest {

    /** Two experiments recording one response, enough to make a comparison request well-formed. */
    private val source = object : ComparisonDataSourceIfc {
        override val sourceLabel: String = "test"

        override fun availableExperiments(): List<ExperimentRow> = listOf(
            ExperimentRow("TwoPharmacists", "Pharmacy", 30, listOf(ResponseRow("System Time", ResponseCategory.OBSERVATION))),
            ExperimentRow("ThreePharmacists", "Pharmacy", 30, listOf(ResponseRow("System Time", ResponseCategory.OBSERVATION))),
        )

        override fun observations(experimentName: String, responseName: String): DoubleArray? =
            if (responseName == "System Time") DoubleArray(30) { it.toDouble() } else null
    }

    @Test
    @DisplayName("an experiment name that matches nothing is named, not dropped")
    fun unmatchedExperimentIsNamed() {
        val outcome = DatabaseAnalysisService().comparisonJson(
            source = source,
            responseName = "System Time",
            experimentNames = listOf("TwoPharmacists", "FourPharmacists"),
        )
        val invalid = outcome as? DbQueryResult.Invalid
        assertTrue(invalid != null, "a request naming a missing experiment must not be analyzable: $outcome")

        // Before: the unmatched name was toggled and ignored, leaving "needs at least 2 experiments ...
        // Currently: 1" -- true, and pointing at the wrong thing entirely.
        assertTrue("FourPharmacists" in invalid.reason, "the reason must name the typo: ${invalid.reason}")
        assertTrue(
            "TwoPharmacists" in invalid.reason && "ThreePharmacists" in invalid.reason,
            "and list what is available: ${invalid.reason}"
        )
    }

    @Test
    @DisplayName("an unknown response lists the responses that do exist")
    fun unknownResponseListsTheRealOnes() {
        val outcome = DatabaseAnalysisService().comparisonJson(
            source = source,
            responseName = "Sytem Time", // the report's actual typo
        )
        val invalid = outcome as? DbQueryResult.Invalid
        assertTrue(invalid != null, "an unknown response must not be analyzable: $outcome")
        assertTrue("Sytem Time" in invalid.reason, "the reason must quote what was asked for")
        assertTrue(
            "System Time" in invalid.reason,
            "and list what is recorded, which settles a typo immediately: ${invalid.reason}"
        )
    }
}
