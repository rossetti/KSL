/*
 * Copyright (c) 2018. Manuel D. Rossetti, rossetti@uark.edu
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
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