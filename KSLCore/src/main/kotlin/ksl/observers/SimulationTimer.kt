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

package ksl.observers

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.DoubleArraySaver
import ksl.utilities.statistic.Statistic
import kotlin.time.Duration

class SimulationTimer(private val model: Model) {
    private val simObserver = SimObserver()
    init {
        startObserving()
    }
    private val timeData: DoubleArraySaver = DoubleArraySaver()
    var experimentStartTime: Instant = Instant.DISTANT_PAST
        private set
    private var repStartTime: Instant = Instant.DISTANT_PAST
    var experimentEndTime: Instant = Instant.DISTANT_FUTURE
        private set

    val totalElapsedTime: Duration
        get() = experimentEndTime - experimentStartTime

    fun startObserving(){
        if (!model.isModelElementObserverAttached(simObserver)){
            model.attachModelElementObserver(simObserver)
        }
    }

    fun stopObserving(){
        if (model.isModelElementObserverAttached(simObserver)){
            model.detachModelElementObserver(simObserver)
        }
    }

    /**
     *  The replication times in milliseconds
     */
    fun replicationTimes(): DoubleArray{
        return timeData.savedData()
    }

    fun replicationTimeStatistics(): Statistic {
        return Statistic("Replication Time (milliseconds)", replicationTimes())
    }

    private inner class SimObserver(): ModelElementObserver(){
        override fun beforeExperiment(modelElement: ModelElement) {
            timeData.clearData()
            experimentStartTime = Clock.System.now()
        }

        override fun beforeReplication(modelElement: ModelElement) {
            repStartTime = Clock.System.now()
        }

        override fun afterReplication(modelElement: ModelElement) {
            val now = Clock.System.now()
            val rt = now - repStartTime
            timeData.save(rt.inWholeMilliseconds.toDouble())
        }

        override fun afterExperiment(modelElement: ModelElement) {
            experimentEndTime = Clock.System.now()
        }
    }
}