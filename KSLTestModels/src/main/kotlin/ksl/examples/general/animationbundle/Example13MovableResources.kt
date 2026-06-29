/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2024  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.examples.general.animationbundle

import ksl.animation.AnimationLayout
import ksl.animation.LayoutShape
import ksl.animation.animation
import ksl.examples.book.chapter8.TestAndRepairShopWithMovableResources
import ksl.modeling.spatial.DistancesModel
import ksl.simulation.Model

/**
 * Example 13 — **movable resources as on-screen actors** (8K.5). Parts are carried between a diagnostic
 * station, three test stations and a repair station by a pool of 3 **transport workers**. Today the
 * workers would be invisible — only the entity riding along animates — and *empty* repositioning
 * (returning for the next part) shows nothing at all. With 8K.5 each worker emits its own
 * `SpatialElementMoved` stream and is drawn as a moving triangle, including empty moves.
 *
 * The model's spatial model is a coordinate-free `DistancesModel`, so the worker moves carry only
 * location **names** (NaN coordinates); the renderer resolves them against the placed `station(...)`
 * markers (8H.3), exactly like coordinate-free entity moves.
 */
object Example13MovableResources {

    // Captured so the layout can read the model's DistancesModel for MDS placement (8K.6b).
    private var distances: DistancesModel? = null

    fun buildModel(): Model {
        val m = Model("MovableResourcesModel")
        val shop = TestAndRepairShopWithMovableResources(m, "TestAndRepair")
        distances = shop.spatialModel as? DistancesModel
        m.numberOfReplications = 1
        m.lengthOfReplication = 480.0
        return m
    }

    fun buildLayout(model: Model): AnimationLayout = model.animation {
        title = "Test & Repair — movable workers + MDS-placed stations (8K.5 + 8K.6b)"
        size(780.0, 520.0)
        clock(24.0, 32.0)

        objectClass("Part") { color = "#1f77b4"; size = 12.0 }

        // 8K.6b: place the DistancesModel's 5 named locations automatically from its distance matrix
        // (classical MDS) — no hand-picked coordinates. Worker (and part) moves resolve against these.
        placeStations(distances!!)

        // Service resources (all ResourceWithQ) co-located with their stations — read the MDS-derived
        // station positions back via stationPosition (8B.3) so each resource sits on its marker, and draw
        // its waiting line "<name>:Q" growing downward (90°) below it.
        fun stationResource(stationName: String, resourceName: String, size: Double) {
            val (x, y) = stationPosition(stationName)
            resource(resourceName, x, y) { this.size = size }
            queue("$resourceName:Q", x, y + size) { growthDegrees = 90.0 } // waiting parts stack downward
        }
        stationResource("DiagnosticStation", "DiagnosticWorkers", 30.0)
        stationResource("TestStation1", "Test1", 26.0)
        stationResource("TestStation2", "Test2", 26.0)
        stationResource("TestStation3", "Test3", 26.0)
        stationResource("RepairStation", "RepairWorkers", 30.0)

        // Parts waiting for an available transport worker (the movable-resource pool's queue).
        stationPosition("DiagnosticStation").let { (x, y) -> queue("TransportWorkerPool:Q", x - 70.0, y) { growthDegrees = 270.0 } }

        // The 3 transport workers (pool "TransportWorkerPool" -> members :R1.. :R3), drawn as moving
        // triangles. They have no fixed position — the renderer animates them from their move stream.
        movableResource("TransportWorkerPool:R1") { shape = LayoutShape.TRIANGLE; color = "#d62728"; size = 16.0 }
        movableResource("TransportWorkerPool:R2") { shape = LayoutShape.TRIANGLE; color = "#2ca02c"; size = 16.0 }
        movableResource("TransportWorkerPool:R3") { shape = LayoutShape.TRIANGLE; color = "#9467bd"; size = 16.0 }

        bar("NumInSystem", 40.0, 470.0) { width = 300.0; height = 20.0; maxValue = 20.0; label = "Number in system" }
    }

}
