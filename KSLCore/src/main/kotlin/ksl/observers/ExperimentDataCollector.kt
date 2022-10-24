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
/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package ksl.observers

import ksl.simulation.Model
import ksl.simulation.ModelElement

/**
 * The purpose of this class is to store  replication data across a set of experiments
 *
 * This class should be attached to the simulation Model prior to running the
 * simulation in order to observe the data. For each replication, for each
 * response variable the replication data is collected using a ReplicationDataCollector
 *
 * If a simulation with an experiment name is run that has not been previously observed,
 * then the data from that experiment is stored.  If an experiment with a name
 * that has already been executed is run, then any previous data is replaced with
 * the newly observed results.
 *
 * @param model the model to observe
 * @author rossetti
 */
class ExperimentDataCollector(model: Model) {
    /**
     * First key, is for the experiment. The value holds the collected replication data
     */
    private val myExpData: MutableMap<String, ReplicationDataCollector> = mutableMapOf()
    private val myModel: Model = model
    private val modelObserver: ModelObserver = ModelObserver()

    init {
        myModel.attachModelElementObserver(modelObserver)
    }

    /**
     * Start observing the model
     */
    fun startObserving() {
        if (!myModel.isModelElementObserverAttached(modelObserver)) {
            myModel.attachModelElementObserver(modelObserver)
        }
    }

    /**
     * Stop observing the model
     */
    fun stopObserving() {
        if (myModel.isModelElementObserverAttached(modelObserver)) {
            myModel.detachModelElementObserver(modelObserver)
        }
    }

    private inner class ModelObserver : ModelElementObserver() {
        private lateinit var myRDC: ReplicationDataCollector

        override fun beforeExperiment(modelElement: ModelElement) {
            val name: String = modelElement.model.experimentName
            val rdc = ReplicationDataCollector(myModel, true)
            myExpData[name] = rdc
            rdc.stopObserving() // stop it so manual observing can be done
            rdc.beforeExperiment()
            myRDC = rdc
        }

        override fun afterReplication(modelElement: ModelElement) {
            myRDC.afterReplication()
        }
    }

    fun experimentData(expName: String): ReplicationDataCollector? {
        return myExpData[expName]
    }

    /**
     * Clears all saved data
     */
    fun clearAllResponseData() {
        myExpData.clear()
    }

    override fun toString(): String {
        val sb = StringBuilder()
        for(entry in myExpData){
            sb.append("Experiment: ")
            sb.append(entry.key)
            sb.appendLine()
            sb.append("Data: ")
            sb.appendLine()
            sb.append(entry.value)
            sb.appendLine()
        }
        return sb.toString()
    }


}