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

package ksl.service.capability.dbanalysis

import ksl.examples.book.appendixD.GIGcQueue
import ksl.simulation.Model
import ksl.utilities.io.dbutil.KSLDatabase
import ksl.utilities.io.dbutil.KSLDatabaseObserver
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Proves capability C (database analysis): a results database produced by a
 * real run is opened *read-only* from a file path through the service, and its
 * experiments and per-replication observations are queried via the existing
 * comparison seam. This is the query-shaped capability — open, then query — that
 * deliberately sits beside the job spine.
 */
class DatabaseAnalysisServiceTest {

    @Test
    fun `opens a populated database from a path and queries it`() {
        val tmp = Files.createTempDirectory("kslsvc-db")
        val reps = 5

        // Produce a real results database: run a small M/M/1 with a DB observer.
        val model = Model("DbAnalysis", autoCSVReports = false)
        GIGcQueue(model, numServers = 1, name = "Q")
        model.numberOfReplications = reps
        model.lengthOfReplication = 200.0
        model.lengthOfReplicationWarmUp = 20.0
        val created: KSLDatabase = KSLDatabase.createKSLDatabase("analysis.db", tmp)
        KSLDatabaseObserver(model, created)
        model.simulate()

        // Open it through the service from the file path (the production entry point).
        DatabaseAnalysisService().use { service ->
            val handle = service.open(tmp.resolve("analysis.db"))

            val experiments = service.listExperiments(handle)
            assertTrue(experiments.isNotEmpty(), "expected at least one recorded experiment")
            val experiment = experiments.first()
            assertEquals(reps, experiment.numReplications)
            assertTrue(experiment.responses.isNotEmpty(), "the M/M/1 records responses")

            // Some response yields per-replication observations: one value per replication.
            val observations = experiment.responses
                .firstNotNullOfOrNull { service.observations(handle, experiment.name, it.name) }
            assertNotNull(observations, "expected per-replication observations for some response")
            assertEquals(reps, observations.size)

            service.close(handle)
        }
    }
}
