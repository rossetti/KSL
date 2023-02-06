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

package ksl.utilities.io.dbutil

import ksl.observers.ModelElementObserver
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.io.KSL

/** The observer is automatically attached to the model upon creation. Use startObserving() or stopObserving() as needed.
 * @param model the model to observe
 * @param db the properly configured databased to hold KSL related results
 * @param clearDataBeforeExperimentOption indicates whether data should be cleared before each experiment. The
 * default is false. Data will not be clear if multiple simulations of the same model are executed within
 * the same execution frame. An error is issued if the experiment name has not changed.
 */
class KSLDatabaseObserver(
    private val model: Model,
    val db: KSLDatabase = KSLDatabase("${model.simulationName}.db".replace(" ", "_"), model.outputDirectory.dbDir),
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
            val simName: String = model.simulationName
            val expName: String = model.experimentName
            //handle clearing of database here
            if (clearDataBeforeExperimentOption) {
                db.clearSimulationData(model)
                Model.logger.info{"KSLDatabaseObserver cleared data for experiment $expName of simulation $simName"}
            } else {
                Model.logger.info{"KSLDatabaseObserver no clear option set for experiment $expName of simulation $simName"}
                if (model.isChunked){
                    Model.logger.info{"Run ${model.runName} is a chunk of Experiment $expName of simulation $simName."}
                    Model.logger.info{"KSLDatabase ACROSS_REP_STAT results only reflect the results for each individual chunk, not the overall experiment"}
                }
            }
            // experiment record may exist if run is a chunk
            db.beforeExperiment(model)
            Model.logger.info{"Before Experiment: KSLDatabaseObserver set up the database for run ${model.runName} of experiment $expName of simulation $simName"}
        }

        override fun afterReplication(modelElement: ModelElement) {
            super.afterReplication(modelElement)
            db.afterReplication(model)
            val simName: String = model.simulationName
            val expName: String = model.experimentName
            Model.logger.info{"After replication ${model.currentReplicationId}: KSLDatabaseObserver inserted replication results for experiment $expName of simulation $simName"}
        }

        override fun afterExperiment(modelElement: ModelElement) {
            super.afterExperiment(modelElement)
            db.afterExperiment(model)
            val simName: String = model.simulationName
            val expName: String = model.experimentName
            Model.logger.info{"After Experiment: KSLDatabaseObserver inserted across replication results for experiment $expName of simulation $simName"}
        }
    }

    companion object{

        fun createSQLiteKSLDatabaseObserver(model: Model) : KSLDatabaseObserver {
            return KSLDatabaseObserver(model)
        }

        fun createDerbyKSLDatabaseObserver(model: Model) : KSLDatabaseObserver {
            val db = KSLDatabase.createEmbeddedDerbyKSLDatabase(model.simulationName.replace(" ", "_"), model.outputDirectory.dbDir)
            val kslDb = KSLDatabase(db, false)
            return KSLDatabaseObserver(model, kslDb, false)
        }
    }
}