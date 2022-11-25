/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2022  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.utilities.io.dbutil

import ksl.observers.ModelElementObserver
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.io.KSL

/** The observer is automatically attached to the model upon creation. Use stopObserving() or stopObserving() as needed.
 * @param model the model to observe
 * @param db the properly configured databased to hold KSL related results
 * @param clearDataBeforeExperimentOption indicates whether data should be cleared before each experiment. The
 * default is false. Data will not be clear if multiple simulations of the same model are executed within
 * the same execution frame. An error is issued if the experiment name has not changed.
 */
class KSLDatabaseObserver(
    private val model: Model,
    val db: KSLDatabase = KSLDatabase("${model.name}.db".replace(" ","_"), model.outputDirectory.dbDir),
    var clearDataBeforeExperimentOption: Boolean = false
) {

    private val myObserver = SimulationDatabaseObserver()

    init {
        model.attachModelElementObserver(myObserver)
    }

    /**
     * Tells the observer to stop collecting simulation result data
     */
    fun stopObserving() {
        model.detachModelElementObserver(myObserver)
    }

    /**
     * Tells a stopped observer to start collecting simulation result data
     */
    fun startObserving() {
        if (!model.isModelElementObserverAttached(myObserver)) {
            model.attachModelElementObserver(myObserver)
        }
    }

    private inner class SimulationDatabaseObserver : ModelElementObserver() {
        override fun beforeExperiment(modelElement: ModelElement) {
            super.beforeExperiment(modelElement)
            //handle clearing of database here
            if (clearDataBeforeExperimentOption) {
                db.clearSimulationData(model)
            } else {
                // no clear option specified, need to check if simulation record exists
                val simName: String = model.simulationName
                val expName: String = model.experimentName
                if (db.doesSimulationRunRecordExist(simName, expName)) {
                    KSL.logger.error(
                        "A simulation run record exists for simulation: {}, and experiment: {} in database {}",
                        simName, expName, db.label
                    )
                    KSL.logger.error("You attempted to run a simulation for a run that has ")
                    KSL.logger.error(" the same name and experiment without allowing its data to be cleared.")
                    KSL.logger.error("You should consider using the clearDataBeforeExperimentOption property on the observer.")
                    KSL.logger.error("Or, you might change the name of the experiment before calling model.simulate().")
                    KSL.logger.error(
                        "This error is to prevent you from accidentally losing data associated with simulation: {}, and experiment: {} in database {}",
                        simName, expName, db.label
                    )
                    throw DataAccessException("A simulation run record already exists with the name $simName and experiment name $expName. Check the ksl.log for details.")
                }
            }
            db.beforeExperiment(model)
        }

        override fun afterReplication(modelElement: ModelElement) {
            super.afterReplication(modelElement)
            db.afterReplication(model)
        }

        override fun afterExperiment(modelElement: ModelElement) {
            super.afterExperiment(modelElement)
            db.afterExperiment(model)
        }
    }

}