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

package ksl.app.swing.single.example

import ksl.app.swing.single.framework.kslSingleApp
import ksl.simulation.ExperimentRunParametersIfc
import ksl.examples.book.appendixD.GIGcQueue
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc

/**
 * Minimal runnable example of `kslSingleApp(...)` framing an M/M/1
 * queue.  Run from IntelliJ via right-click `main` → Run.
 *
 * Lives in `KSLAppSwingSingle` (not `KSLExamples`) to preserve the
 * module dependency direction: `KSLAppSwingSingle` already
 * implementation-depends on `KSLExamples` for reference models;
 * adding the reverse direction would create a cycle.  Future
 * Phase-6D examples that bundle their own models can live under
 * `ksl.examples.general.appsupport` once Phase-6D ships a
 * non-cyclic dependency story.
 */
fun main() = kslSingleApp(appName = "M/M/1 Queue") {
    modelBuilder(MM1Builder())
}

/**
 * Named [ModelBuilderIfc] that constructs the example M/M/1
 * model — a single-server queue using
 * [ksl.examples.book.appendixD.GIGcQueue].  Named (not an inline
 * lambda) per workflow-single.md §2 OQ 2 so the same compiled
 * class is consumable from the Scenario app via
 * `ModelReference.ByJar(jarPath, "ksl.app.swing.single.example.MM1Builder")`.
 */
class MM1Builder : ModelBuilderIfc {
    override fun build(
        modelConfiguration: Map<String, String>?,
        experimentRunParameters: ExperimentRunParametersIfc?
    ): Model {
        val model = Model("MM1")
        GIGcQueue(model, numServers = 1, name = "MM1Queue")
        model.numberOfReplications = 30
        model.lengthOfReplication = 500.0
        model.lengthOfReplicationWarmUp = 50.0
        if (experimentRunParameters != null) {
            model.changeRunParameters(experimentRunParameters)
        }
        return model
    }
}
