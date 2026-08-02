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

package ksl.simulation

import ksl.modeling.entity.ProcessModel
import org.junit.jupiter.api.DisplayName
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 *  Ids handed out while a model runs stay unique when several models run at once.
 *
 *  KSL runs models concurrently as a matter of course -- parallel simulation providers, concurrent
 *  solver runners, parallel designed experiments and concurrent scenario runners all do -- and the
 *  counters behind these ids are shared by the whole process. A counter read and written without
 *  synchronization hands the same id to objects in different models.
 *
 *  For QObject the window was wider than a lost increment. The counter was incremented in an init
 *  block and read again by a separate property initializer, so two models could each increment once
 *  and then both read the same value, with the counter left perfectly correct. That is an ordinary
 *  interleaving rather than a rare one, which is why this test reproduces the old defect quickly.
 *
 *  What a duplicate costs is worth stating, because it is not mainly the exception. Two QObjects in
 *  one queue with equal priority, equal entry time and equal ids make `compareTo` throw, but two
 *  QObjects in different models never share a queue, so the usual result is quieter: repeated ids
 *  and repeated default names of the form ID_n in whatever reads them.
 */
class QObjectIdUniquenessTest {

    private val threads = 8
    private val perThread = 2_000

    /**
     *  Builds ids from several threads at once and returns them all.
     *
     *  Each task makes its own model, so this is the shape the library actually runs in: separate
     *  models on separate threads, sharing nothing except the counter under test.
     */
    private fun <T> idsFromThreads(make: (ProcessModel) -> T, idOf: (T) -> Long): List<Long> {
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val tasks = (1..threads).map { t ->
                Callable {
                    val model = Model("concurrent-$t")
                    val processModel = object : ProcessModel(model, "pm-$t") {}
                    (1..perThread).map { idOf(make(processModel)) }
                }
            }
            return pool.invokeAll(tasks).flatMap { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    @DisplayName("QObjects built by several models at once never share an id")
    fun qObjectIdsAreUniqueAcrossConcurrentModels() {
        val ids = idsFromThreads({ pm -> pm.QObject() }, { it.id })
        assertEquals(threads * perThread, ids.size, "the harness did not build what it claimed to")
        assertEquals(
            ids.size, ids.toSet().size,
            "two QObjects were given the same id: ${ids.size - ids.toSet().size} duplicates"
        )
    }

}
