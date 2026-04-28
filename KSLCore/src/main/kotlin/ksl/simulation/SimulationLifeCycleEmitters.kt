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

package ksl.simulation

import ksl.utilities.io.dbutil.SimulationSnapshot
import ksl.utilities.observers.Emitter

/**
 * Holds one typed [Emitter] for each simulation lifecycle boundary.
 *
 * Instantiated lazily via [Model.lifeCycleEmitters] so there is zero overhead
 * when no subscribers are attached.  Attach subscribers before the simulation
 * starts; dynamic mid-run subscription is not supported.
 */
class SimulationLifeCycleEmitters {

    /** Fired once before the first replication of an experiment. */
    val experimentStarted: Emitter<SimulationSnapshot.ExperimentStarted> = Emitter()

    /** Fired once after each replication completes successfully. */
    val replicationCompleted: Emitter<SimulationSnapshot.ReplicationCompleted> = Emitter()

    /** Fired once after all replications of an experiment complete successfully. */
    val experimentCompleted: Emitter<SimulationSnapshot.ExperimentCompleted> = Emitter()

    /** Fired if the experiment terminates due to an unhandled exception. */
    val experimentFailed: Emitter<SimulationSnapshot.ExperimentFailed> = Emitter()
}
