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
package ksl.modeling.entity

import ksl.modeling.spatial.DistancesModel
import ksl.modeling.spatial.MovableResource
import ksl.modeling.spatial.MovableResourcePoolWithQ
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  A pool restores its default rules at the start of each replication without warning, since nothing changed them
 *  mid-replication; a model that changes a rule during a replication is still warned.
 */
class PoolRuleWarningTest {

    private fun captureWarnings(block: () -> Unit): List<String> {
        val logger = org.slf4j.LoggerFactory.getLogger(Model::class.java) as ch.qos.logback.classic.Logger
        val appender = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
        return appender.list.filter { it.level == ch.qos.logback.classic.Level.WARN }.map { it.formattedMessage }
    }

    /** A pool and a movable pool, each seized once a replication; [changeRule] changes the pool's rule mid-run. */
    private class Shop(parent: ModelElement, val changeRule: Boolean) : ProcessModel(parent, "Shop") {
        private val dm = DistancesModel().apply { addDistance("A", "B", 1.0, symmetric = true) }

        init {
            spatialModel = dm
        }

        val pool = ResourcePoolWithQ(this, listOf(Resource(this, "R1"), Resource(this, "R2")), name = "Pool")
        val fleet = MovableResourcePoolWithQ(
            this, listOf(MovableResource(this, dm.location("A")!!, ConstantRV(1.0), "M1")), ConstantRV(1.0), name = "Fleet"
        )

        private inner class Job : Entity() {
            val p: KSLProcess = process {
                val a = seize(pool)
                delay(1.0)
                release(a)
                val m = seize(fleet, requestLocation = dm.location("A")!!)
                release(m)
            }
        }

        override fun initialize() {
            activate(Job().p)
            if (changeRule) schedule({ _: KSLEvent<Nothing> -> pool.defaultResourceSelectionRule = ResourceSelectionRule() }, 2.0)
        }
    }

    private fun run(changeRule: Boolean): List<String> = captureWarnings {
        val model = Model("PoolRules${System.nanoTime()}", autoCSVReports = false)
        Shop(model, changeRule)
        model.numberOfReplications = 3
        model.lengthOfReplication = 10.0
        model.simulate()
    }.filter { it.contains("resource selection rule") || it.contains("resource allocation rule") }

    @Test
    @DisplayName("pools restore their default rules each replication without warning")
    fun noWarningForTheReset() = assertEquals(emptyList(), run(changeRule = false))

    @Test
    @DisplayName("a rule changed during a replication is still warned about")
    fun warningForAChangeDuringARun() = assertTrue(run(changeRule = true).isNotEmpty())
}
