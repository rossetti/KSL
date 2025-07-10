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
/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package ksl.observers

import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.io.dbutil.WithinRepViewData
import ksl.utilities.statistic.MultipleComparisonAnalyzer
import ksl.utilities.toPrimitives
import org.jetbrains.kotlinx.dataframe.impl.asList

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
 * @param autoAttach true indicates that the collector will automatically be attached
 * as an observer on the model when created. If the collector is not automatically attached
 * then the startObserving() function needs to be called before running the model.
 * @author rossetti
 */
@Suppress("unused")
class ExperimentDataCollector @JvmOverloads constructor(
    model: Model,
    autoAttach: Boolean = true
) {
    /**
     * First key, is for the experiment. The value holds the collected replication data
     */
    private val myExpData: MutableMap<String, ReplicationDataCollector> = mutableMapOf()
    private val myModel: Model = model
    private val modelObserver: ModelObserver = ModelObserver()

    val experimentNames: List<String>
        get() = myExpData.keys.asList()

    init {
        if (autoAttach){
            myModel.attachModelElementObserver(modelObserver)
        }
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
            val rdc = ReplicationDataCollector(myModel, addAll = true, autoAttach = false)
            myExpData[name] = rdc
//            rdc.stopObserving() // stop it so manual observing can be done
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
     *  Returns a map of maps. The outer map has the experiment name as its key.
     *  The inner map has the response names as the key and the replication results
     *  as a double array. The array contains the observed replication statistic for the response variable
     *  or the final count for a counter for each replication.
     *
     *  The array may contain Double.NaN values because an observation may be
     *  missing for a particular replication if no observations of the response are observed during the replication.
     *
     *  @return the map of maps
     */
    fun replicationDataArraysByExperimentAndResponse(): Map<String, Map<String, DoubleArray>> {
        val map = mutableMapOf<String, Map<String, DoubleArray>>()
        for((expName, rdc) in myExpData){
            map[expName] = rdc.allReplicationDataAsMap
        }
        return map
    }

    /**
     *  Provides a within replication view data representation of the
     *  collected data.
     */
    fun withRepViewData(): List<WithinRepViewData> {
        val list = mutableListOf<WithinRepViewData>()
        for((_, rdc) in myExpData){
            list.addAll(rdc.withRepViewData())
        }
        return list
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

    /**
     *  Creates a multiple comparison analyzer for the response for each of the named
     *  experiments. If the experiment name does not exist or the response name does
     *  not exist, then no data is extracted.
     *
     * @param expNames     The set of experiment names for with the responses need extraction, must not be null
     * @param responseName the name of the response variable, time weighted variable or counter
     * @return a configured MultipleComparisonAnalyzer
     */
    fun multipleComparisonAnalyzerFor(expNames: List<String>, responseName: String): MultipleComparisonAnalyzer {
        require(expNames.isNotEmpty()) { "The list of experiment names was empty" }
        val map = mutableMapOf<String, DoubleArray>()
        for(eName in expNames){
            if (myExpData.containsKey(eName)){
                // experiment is stored, get it and get the data for the response
                val dc =myExpData[eName]!!
                val data = dc.replicationData(responseName)
                if (data.isNotEmpty()){
                    map["${eName}_$responseName"] = data
                }
            }
        }
        return MultipleComparisonAnalyzer(map, responseName)
    }

}