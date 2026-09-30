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

package ksl.app.animation.replay

import ksl.animation.AnimationEvent
import ksl.animation.AnimationTraceHeader
import ksl.app.animation.io.AnimationSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The vehicle replay, run in the browser build on a real trace.
 *
 * The excerpt is the start of `traces/SimpleAgvShop.atf` (the web player's vehicle demo), cut to the vehicle
 * events and a few resource and queue changes, up to the first load set down. It is embedded because a
 * common test cannot read a file in a browser; `VehicleReplayTest` in KSLExamples regenerates the full trace.
 */
class VehicleReplaySharedTest {

    private val excerpt = """
{"event":"GuidedPathDefined","simTime":0.0,"networkName":"SimpleAgvNetwork","intersections":[{"name":"I1","x":0.0,"y":72.0,"z":0.0,"aliases":["EntryStation"]},{"name":"I2","x":48.0,"y":72.0,"z":0.0,"aliases":[]},{"name":"I3","x":48.0,"y":0.0,"z":0.0,"aliases":[]},{"name":"I4","x":0.0,"y":0.0,"z":0.0,"aliases":[]},{"name":"I5","x":0.0,"y":-36.0,"z":0.0,"aliases":["ExitStation"]},{"name":"I6","x":54.0,"y":72.0,"z":0.0,"aliases":[]},{"name":"I7","x":54.0,"y":0.0,"z":0.0,"aliases":[]}],"links":[{"name":"Link1","from":"I1","to":"I2","numZones":4,"bidirectional":false,"spur":false,"length":48.0},{"name":"Link2","from":"I2","to":"I3","numZones":6,"bidirectional":false,"spur":false,"length":72.0},{"name":"Link3","from":"I3","to":"I4","numZones":4,"bidirectional":false,"spur":false,"length":48.0},{"name":"Link4","from":"I4","to":"I1","numZones":6,"bidirectional":false,"spur":false,"length":72.0},{"name":"Spur","from":"I4","to":"I5","numZones":3,"bidirectional":false,"spur":true,"length":36.0},{"name":"Link5","from":"I2","to":"I6","numZones":1,"bidirectional":false,"spur":true,"length":6.0},{"name":"Link6","from":"I3","to":"I7","numZones":1,"bidirectional":false,"spur":true,"length":6.0}],"transporters":[{"name":"Cart1","lengthInZones":1,"loadCapacity":1,"physicalLength":NaN,"homeBase":"I6","vehicleName":null},{"name":"Cart2","lengthInZones":1,"loadCapacity":1,"physicalLength":NaN,"homeBase":"I7","vehicleName":null}],"spaceName":"AgvSystem"}
{"event":"GuidedTransporterMoved","simTime":0.0,"transporterName":"Cart1","networkName":"SimpleAgvNetwork","zoneName":"I6","linkName":null,"zoneIndex":0,"spaceName":"AgvSystem"}
{"event":"GuidedTransporterStateChanged","simTime":0.0,"transporterName":"Cart1","networkName":"SimpleAgvNetwork","state":"IDLE","spaceName":"AgvSystem","halted":false,"awaitedZoneName":null,"awaitedLinkName":null,"blockReason":null}
{"event":"GuidedTransporterMoved","simTime":0.0,"transporterName":"Cart2","networkName":"SimpleAgvNetwork","zoneName":"I7","linkName":null,"zoneIndex":0,"spaceName":"AgvSystem"}
{"event":"GuidedTransporterStateChanged","simTime":0.0,"transporterName":"Cart2","networkName":"SimpleAgvNetwork","state":"IDLE","spaceName":"AgvSystem","halted":false,"awaitedZoneName":null,"awaitedLinkName":null,"blockReason":null}
{"event":"QueueLengthChanged","simTime":2.716649265082664,"queueName":"Carts:Q","length":1}
{"event":"QueueLengthChanged","simTime":2.716649265082664,"queueName":"Carts:Q","length":0}
{"event":"ResourceStateChanged","simTime":2.716649265082664,"resourceName":"Cart2","state":"Cart2_Busy","busyUnits":1,"capacity":1}
{"event":"GuidedTransporterStateChanged","simTime":2.716649265082664,"transporterName":"Cart2","networkName":"SimpleAgvNetwork","state":"MOVING_EMPTY","spaceName":"AgvSystem","halted":false,"awaitedZoneName":null,"awaitedLinkName":null,"blockReason":null}
{"event":"QueueLengthChanged","simTime":2.716649265082664,"queueName":"AgvSystem:AwaitingPickupHoldQ","length":1}
{"event":"GuidedTransporterMoved","simTime":3.316649265082664,"transporterName":"Cart2","networkName":"SimpleAgvNetwork","zoneName":"Link6.Zone1","linkName":"Link6","zoneIndex":1,"spaceName":"AgvSystem"}
{"event":"GuidedTransporterMoved","simTime":3.316649265082664,"transporterName":"Cart2","networkName":"SimpleAgvNetwork","zoneName":"I3","linkName":null,"zoneIndex":0,"spaceName":"AgvSystem"}
{"event":"GuidedTransporterMoved","simTime":4.516649265082664,"transporterName":"Cart2","networkName":"SimpleAgvNetwork","zoneName":"Link3.Zone1","linkName":"Link3","zoneIndex":1,"spaceName":"AgvSystem"}
{"event":"GuidedTransporterMoved","simTime":5.716649265082665,"transporterName":"Cart2","networkName":"SimpleAgvNetwork","zoneName":"Link3.Zone2","linkName":"Link3","zoneIndex":2,"spaceName":"AgvSystem"}
{"event":"GuidedTransporterMoved","simTime":6.916649265082665,"transporterName":"Cart2","networkName":"SimpleAgvNetwork","zoneName":"Link3.Zone3","linkName":"Link3","zoneIndex":3,"spaceName":"AgvSystem"}
{"event":"GuidedTransporterMoved","simTime":8.116649265082664,"transporterName":"Cart2","networkName":"SimpleAgvNetwork","zoneName":"Link3.Zone4","linkName":"Link3","zoneIndex":4,"spaceName":"AgvSystem"}
{"event":"GuidedTransporterMoved","simTime":8.116649265082664,"transporterName":"Cart2","networkName":"SimpleAgvNetwork","zoneName":"I4","linkName":null,"zoneIndex":0,"spaceName":"AgvSystem"}
{"event":"GuidedTransporterMoved","simTime":9.316649265082663,"transporterName":"Cart2","networkName":"SimpleAgvNetwork","zoneName":"Link4.Zone1","linkName":"Link4","zoneIndex":1,"spaceName":"AgvSystem"}
{"event":"QueueLengthChanged","simTime":10.386638800843075,"queueName":"Carts:Q","length":1}
{"event":"QueueLengthChanged","simTime":10.386638800843075,"queueName":"Carts:Q","length":0}
{"event":"ResourceStateChanged","simTime":10.386638800843075,"resourceName":"Cart1","state":"Cart1_Busy","busyUnits":1,"capacity":1}
{"event":"GuidedTransporterStateChanged","simTime":10.386638800843075,"transporterName":"Cart1","networkName":"SimpleAgvNetwork","state":"MOVING_EMPTY","spaceName":"AgvSystem","halted":false,"awaitedZoneName":null,"awaitedLinkName":null,"blockReason":null}
{"event":"QueueLengthChanged","simTime":10.386638800843075,"queueName":"AgvSystem:AwaitingPickupHoldQ","length":2}
{"event":"GuidedTransporterMoved","simTime":10.516649265082663,"transporterName":"Cart2","networkName":"SimpleAgvNetwork","zoneName":"Link4.Zone2","linkName":"Link4","zoneIndex":2,"spaceName":"AgvSystem"}
{"event":"GuidedTransporterMoved","simTime":10.986638800843075,"transporterName":"Cart1","networkName":"SimpleAgvNetwork","zoneName":"Link5.Zone1","linkName":"Link5","zoneIndex":1,"spaceName":"AgvSystem"}
{"event":"GuidedTransporterMoved","simTime":10.986638800843075,"transporterName":"Cart1","networkName":"SimpleAgvNetwork","zoneName":"I2","linkName":null,"zoneIndex":0,"spaceName":"AgvSystem"}
{"event":"GuidedTransporterMoved","simTime":11.716649265082662,"transporterName":"Cart2","networkName":"SimpleAgvNetwork","zoneName":"Link4.Zone3","linkName":"Link4","zoneIndex":3,"spaceName":"AgvSystem"}
{"event":"GuidedTransporterMoved","simTime":12.186638800843074,"transporterName":"Cart1","networkName":"SimpleAgvNetwork","zoneName":"Link2.Zone1","linkName":"Link2","zoneIndex":1,"spaceName":"AgvSystem"}
{"event":"GuidedTransporterMoved","simTime":12.916649265082661,"transporterName":"Cart2","networkName":"SimpleAgvNetwork","zoneName":"Link4.Zone4","linkName":"Link4","zoneIndex":4,"spaceName":"AgvSystem"}
{"event":"GuidedTransporterMoved","simTime":13.386638800843073,"transporterName":"Cart1","networkName":"SimpleAgvNetwork","zoneName":"Link2.Zone2","linkName":"Link2","zoneIndex":2,"spaceName":"AgvSystem"}
{"event":"GuidedTransporterMoved","simTime":14.11664926508266,"transporterName":"Cart2","networkName":"SimpleAgvNetwork","zoneName":"Link4.Zone5","linkName":"Link4","zoneIndex":5,"spaceName":"AgvSystem"}
{"event":"GuidedTransporterMoved","simTime":14.586638800843073,"transporterName":"Cart1","networkName":"SimpleAgvNetwork","zoneName":"Link2.Zone3","linkName":"Link2","zoneIndex":3,"spaceName":"AgvSystem"}
{"event":"GuidedTransporterMoved","simTime":15.31664926508266,"transporterName":"Cart2","networkName":"SimpleAgvNetwork","zoneName":"Link4.Zone6","linkName":"Link4","zoneIndex":6,"spaceName":"AgvSystem"}
{"event":"GuidedTransporterMoved","simTime":15.31664926508266,"transporterName":"Cart2","networkName":"SimpleAgvNetwork","zoneName":"I1","linkName":null,"zoneIndex":0,"spaceName":"AgvSystem"}
{"event":"GuidedTransporterStateChanged","simTime":15.31664926508266,"transporterName":"Cart2","networkName":"SimpleAgvNetwork","state":"IDLE","spaceName":"AgvSystem","halted":false,"awaitedZoneName":null,"awaitedLinkName":null,"blockReason":null}
{"event":"QueueLengthChanged","simTime":15.31664926508266,"queueName":"AgvSystem:AwaitingPickupHoldQ","length":1}
{"event":"GuidedTransporterStateChanged","simTime":15.31664926508266,"transporterName":"Cart2","networkName":"SimpleAgvNetwork","state":"LOADING","spaceName":"AgvSystem","halted":false,"awaitedZoneName":null,"awaitedLinkName":null,"blockReason":null}
{"event":"GuidedTransporterMoved","simTime":15.786638800843072,"transporterName":"Cart1","networkName":"SimpleAgvNetwork","zoneName":"Link2.Zone4","linkName":"Link2","zoneIndex":4,"spaceName":"AgvSystem"}
{"event":"VehicleLoadBoarded","simTime":15.81664926508266,"entityId":1,"vehicleName":"Cart2","bodyName":"Cart2","networkName":"SimpleAgvNetwork","locationName":"I1"}
{"event":"GuidedTransporterStateChanged","simTime":15.81664926508266,"transporterName":"Cart2","networkName":"SimpleAgvNetwork","state":"MOVING_LOADED","spaceName":"AgvSystem","halted":false,"awaitedZoneName":null,"awaitedLinkName":null,"blockReason":null}
{"event":"QueueLengthChanged","simTime":15.81664926508266,"queueName":"AgvSystem:RidingHoldQ","length":1}
{"event":"GuidedTransporterMoved","simTime":16.98663880084307,"transporterName":"Cart1","networkName":"SimpleAgvNetwork","zoneName":"Link2.Zone5","linkName":"Link2","zoneIndex":5,"spaceName":"AgvSystem"}
{"event":"GuidedTransporterMoved","simTime":17.01664926508266,"transporterName":"Cart2","networkName":"SimpleAgvNetwork","zoneName":"Link1.Zone1","linkName":"Link1","zoneIndex":1,"spaceName":"AgvSystem"}
{"event":"QueueLengthChanged","simTime":17.78433258314238,"queueName":"Carts:Q","length":1}
{"event":"GuidedTransporterMoved","simTime":18.18663880084307,"transporterName":"Cart1","networkName":"SimpleAgvNetwork","zoneName":"Link2.Zone6","linkName":"Link2","zoneIndex":6,"spaceName":"AgvSystem"}
{"event":"GuidedTransporterMoved","simTime":18.18663880084307,"transporterName":"Cart1","networkName":"SimpleAgvNetwork","zoneName":"I3","linkName":null,"zoneIndex":0,"spaceName":"AgvSystem"}
{"event":"GuidedTransporterMoved","simTime":18.21664926508266,"transporterName":"Cart2","networkName":"SimpleAgvNetwork","zoneName":"Link1.Zone2","linkName":"Link1","zoneIndex":2,"spaceName":"AgvSystem"}
{"event":"GuidedTransporterMoved","simTime":19.38663880084307,"transporterName":"Cart1","networkName":"SimpleAgvNetwork","zoneName":"Link3.Zone1","linkName":"Link3","zoneIndex":1,"spaceName":"AgvSystem"}
{"event":"GuidedTransporterMoved","simTime":19.416649265082658,"transporterName":"Cart2","networkName":"SimpleAgvNetwork","zoneName":"Link1.Zone3","linkName":"Link1","zoneIndex":3,"spaceName":"AgvSystem"}
{"event":"GuidedTransporterMoved","simTime":20.58663880084307,"transporterName":"Cart1","networkName":"SimpleAgvNetwork","zoneName":"Link3.Zone2","linkName":"Link3","zoneIndex":2,"spaceName":"AgvSystem"}
{"event":"GuidedTransporterMoved","simTime":20.616649265082657,"transporterName":"Cart2","networkName":"SimpleAgvNetwork","zoneName":"Link1.Zone4","linkName":"Link1","zoneIndex":4,"spaceName":"AgvSystem"}
{"event":"GuidedTransporterMoved","simTime":20.616649265082657,"transporterName":"Cart2","networkName":"SimpleAgvNetwork","zoneName":"I2","linkName":null,"zoneIndex":0,"spaceName":"AgvSystem"}
{"event":"GuidedTransporterMoved","simTime":21.78663880084307,"transporterName":"Cart1","networkName":"SimpleAgvNetwork","zoneName":"Link3.Zone3","linkName":"Link3","zoneIndex":3,"spaceName":"AgvSystem"}
{"event":"GuidedTransporterMoved","simTime":21.816649265082656,"transporterName":"Cart2","networkName":"SimpleAgvNetwork","zoneName":"Link2.Zone1","linkName":"Link2","zoneIndex":1,"spaceName":"AgvSystem"}
{"event":"GuidedTransporterMoved","simTime":22.986638800843068,"transporterName":"Cart1","networkName":"SimpleAgvNetwork","zoneName":"Link3.Zone4","linkName":"Link3","zoneIndex":4,"spaceName":"AgvSystem"}
{"event":"GuidedTransporterMoved","simTime":22.986638800843068,"transporterName":"Cart1","networkName":"SimpleAgvNetwork","zoneName":"I4","linkName":null,"zoneIndex":0,"spaceName":"AgvSystem"}
{"event":"GuidedTransporterMoved","simTime":23.016649265082656,"transporterName":"Cart2","networkName":"SimpleAgvNetwork","zoneName":"Link2.Zone2","linkName":"Link2","zoneIndex":2,"spaceName":"AgvSystem"}
{"event":"GuidedTransporterMoved","simTime":24.186638800843067,"transporterName":"Cart1","networkName":"SimpleAgvNetwork","zoneName":"Link4.Zone1","linkName":"Link4","zoneIndex":1,"spaceName":"AgvSystem"}
{"event":"GuidedTransporterMoved","simTime":24.216649265082655,"transporterName":"Cart2","networkName":"SimpleAgvNetwork","zoneName":"Link2.Zone3","linkName":"Link2","zoneIndex":3,"spaceName":"AgvSystem"}
{"event":"GuidedTransporterMoved","simTime":25.386638800843066,"transporterName":"Cart1","networkName":"SimpleAgvNetwork","zoneName":"Link4.Zone2","linkName":"Link4","zoneIndex":2,"spaceName":"AgvSystem"}
{"event":"GuidedTransporterMoved","simTime":25.416649265082654,"transporterName":"Cart2","networkName":"SimpleAgvNetwork","zoneName":"Link2.Zone4","linkName":"Link2","zoneIndex":4,"spaceName":"AgvSystem"}
{"event":"GuidedTransporterMoved","simTime":26.586638800843065,"transporterName":"Cart1","networkName":"SimpleAgvNetwork","zoneName":"Link4.Zone3","linkName":"Link4","zoneIndex":3,"spaceName":"AgvSystem"}
{"event":"GuidedTransporterMoved","simTime":26.616649265082653,"transporterName":"Cart2","networkName":"SimpleAgvNetwork","zoneName":"Link2.Zone5","linkName":"Link2","zoneIndex":5,"spaceName":"AgvSystem"}
{"event":"GuidedTransporterMoved","simTime":27.786638800843065,"transporterName":"Cart1","networkName":"SimpleAgvNetwork","zoneName":"Link4.Zone4","linkName":"Link4","zoneIndex":4,"spaceName":"AgvSystem"}
{"event":"GuidedTransporterMoved","simTime":27.816649265082653,"transporterName":"Cart2","networkName":"SimpleAgvNetwork","zoneName":"Link2.Zone6","linkName":"Link2","zoneIndex":6,"spaceName":"AgvSystem"}
{"event":"GuidedTransporterMoved","simTime":27.816649265082653,"transporterName":"Cart2","networkName":"SimpleAgvNetwork","zoneName":"I3","linkName":null,"zoneIndex":0,"spaceName":"AgvSystem"}
{"event":"GuidedTransporterMoved","simTime":28.986638800843064,"transporterName":"Cart1","networkName":"SimpleAgvNetwork","zoneName":"Link4.Zone5","linkName":"Link4","zoneIndex":5,"spaceName":"AgvSystem"}
{"event":"GuidedTransporterMoved","simTime":29.016649265082652,"transporterName":"Cart2","networkName":"SimpleAgvNetwork","zoneName":"Link3.Zone1","linkName":"Link3","zoneIndex":1,"spaceName":"AgvSystem"}
{"event":"GuidedTransporterMoved","simTime":30.186638800843063,"transporterName":"Cart1","networkName":"SimpleAgvNetwork","zoneName":"Link4.Zone6","linkName":"Link4","zoneIndex":6,"spaceName":"AgvSystem"}
{"event":"GuidedTransporterMoved","simTime":30.186638800843063,"transporterName":"Cart1","networkName":"SimpleAgvNetwork","zoneName":"I1","linkName":null,"zoneIndex":0,"spaceName":"AgvSystem"}
{"event":"GuidedTransporterStateChanged","simTime":30.186638800843063,"transporterName":"Cart1","networkName":"SimpleAgvNetwork","state":"IDLE","spaceName":"AgvSystem","halted":false,"awaitedZoneName":null,"awaitedLinkName":null,"blockReason":null}
{"event":"QueueLengthChanged","simTime":30.186638800843063,"queueName":"AgvSystem:AwaitingPickupHoldQ","length":0}
{"event":"GuidedTransporterStateChanged","simTime":30.186638800843063,"transporterName":"Cart1","networkName":"SimpleAgvNetwork","state":"LOADING","spaceName":"AgvSystem","halted":false,"awaitedZoneName":null,"awaitedLinkName":null,"blockReason":null}
{"event":"GuidedTransporterMoved","simTime":30.21664926508265,"transporterName":"Cart2","networkName":"SimpleAgvNetwork","zoneName":"Link3.Zone2","linkName":"Link3","zoneIndex":2,"spaceName":"AgvSystem"}
{"event":"VehicleLoadBoarded","simTime":30.686638800843063,"entityId":3,"vehicleName":"Cart1","bodyName":"Cart1","networkName":"SimpleAgvNetwork","locationName":"I1"}
{"event":"GuidedTransporterStateChanged","simTime":30.686638800843063,"transporterName":"Cart1","networkName":"SimpleAgvNetwork","state":"MOVING_LOADED","spaceName":"AgvSystem","halted":false,"awaitedZoneName":null,"awaitedLinkName":null,"blockReason":null}
{"event":"QueueLengthChanged","simTime":30.686638800843063,"queueName":"AgvSystem:RidingHoldQ","length":2}
{"event":"GuidedTransporterMoved","simTime":31.41664926508265,"transporterName":"Cart2","networkName":"SimpleAgvNetwork","zoneName":"Link3.Zone3","linkName":"Link3","zoneIndex":3,"spaceName":"AgvSystem"}
{"event":"GuidedTransporterMoved","simTime":31.886638800843063,"transporterName":"Cart1","networkName":"SimpleAgvNetwork","zoneName":"Link1.Zone1","linkName":"Link1","zoneIndex":1,"spaceName":"AgvSystem"}
{"event":"GuidedTransporterMoved","simTime":32.61664926508265,"transporterName":"Cart2","networkName":"SimpleAgvNetwork","zoneName":"Link3.Zone4","linkName":"Link3","zoneIndex":4,"spaceName":"AgvSystem"}
{"event":"GuidedTransporterMoved","simTime":32.61664926508265,"transporterName":"Cart2","networkName":"SimpleAgvNetwork","zoneName":"I4","linkName":null,"zoneIndex":0,"spaceName":"AgvSystem"}
{"event":"GuidedTransporterMoved","simTime":33.08663880084306,"transporterName":"Cart1","networkName":"SimpleAgvNetwork","zoneName":"Link1.Zone2","linkName":"Link1","zoneIndex":2,"spaceName":"AgvSystem"}
{"event":"GuidedTransporterMoved","simTime":33.81664926508265,"transporterName":"Cart2","networkName":"SimpleAgvNetwork","zoneName":"Spur.Zone1","linkName":"Spur","zoneIndex":1,"spaceName":"AgvSystem"}
{"event":"GuidedTransporterMoved","simTime":34.286638800843065,"transporterName":"Cart1","networkName":"SimpleAgvNetwork","zoneName":"Link1.Zone3","linkName":"Link1","zoneIndex":3,"spaceName":"AgvSystem"}
{"event":"GuidedTransporterMoved","simTime":35.016649265082656,"transporterName":"Cart2","networkName":"SimpleAgvNetwork","zoneName":"Spur.Zone2","linkName":"Spur","zoneIndex":2,"spaceName":"AgvSystem"}
{"event":"GuidedTransporterMoved","simTime":35.48663880084307,"transporterName":"Cart1","networkName":"SimpleAgvNetwork","zoneName":"Link1.Zone4","linkName":"Link1","zoneIndex":4,"spaceName":"AgvSystem"}
{"event":"GuidedTransporterMoved","simTime":35.48663880084307,"transporterName":"Cart1","networkName":"SimpleAgvNetwork","zoneName":"I2","linkName":null,"zoneIndex":0,"spaceName":"AgvSystem"}
{"event":"GuidedTransporterMoved","simTime":36.21664926508266,"transporterName":"Cart2","networkName":"SimpleAgvNetwork","zoneName":"Spur.Zone3","linkName":"Spur","zoneIndex":3,"spaceName":"AgvSystem"}
{"event":"GuidedTransporterMoved","simTime":36.21664926508266,"transporterName":"Cart2","networkName":"SimpleAgvNetwork","zoneName":"I5","linkName":null,"zoneIndex":0,"spaceName":"AgvSystem"}
{"event":"GuidedTransporterStateChanged","simTime":36.21664926508266,"transporterName":"Cart2","networkName":"SimpleAgvNetwork","state":"IDLE","spaceName":"AgvSystem","halted":false,"awaitedZoneName":null,"awaitedLinkName":null,"blockReason":null}
{"event":"QueueLengthChanged","simTime":36.21664926508266,"queueName":"AgvSystem:RidingHoldQ","length":1}
{"event":"GuidedTransporterStateChanged","simTime":36.21664926508266,"transporterName":"Cart2","networkName":"SimpleAgvNetwork","state":"UNLOADING","spaceName":"AgvSystem","halted":false,"awaitedZoneName":null,"awaitedLinkName":null,"blockReason":null}
{"event":"GuidedTransporterMoved","simTime":36.68663880084307,"transporterName":"Cart1","networkName":"SimpleAgvNetwork","zoneName":"Link2.Zone1","linkName":"Link2","zoneIndex":1,"spaceName":"AgvSystem"}
{"event":"VehicleLoadAlighted","simTime":36.71664926508266,"entityId":1,"vehicleName":"Cart2","bodyName":"Cart2","networkName":"SimpleAgvNetwork","locationName":"I5"}
    """.trimIndent()

    private val events: List<AnimationEvent> = excerpt.lines().filter { it.isNotBlank() }.map { AnimationEvent.decodeFromLine(it) }

    private fun model() = ReplayModel.build(AnimationSource(null, AnimationTraceHeader(), events))

    @Test
    fun theGuidePathAndItsCartsReplay() {
        val v = model().vehicles
        val path = assertNotNull(v.guidePaths["AgvSystem"], "the guide path is keyed by its space's name")
        assertEquals(7, path.intersectionPoints.size)
        assertEquals(setOf("Cart1", "Cart2"), v.transporterNames)
        val end = events.last().simTime
        for (cart in v.transporterNames) {
            assertNotNull(v.transporterPositionAt(cart, end), "$cart is placed")
        }
    }

    @Test
    fun aLoadIsCarriedAndSetDown() {
        val m = model()
        val boarded = events.filterIsInstance<AnimationEvent.VehicleLoadBoarded>().first()
        val alighted = events.filterIsInstance<AnimationEvent.VehicleLoadAlighted>().first { it.entityId == boarded.entityId }
        val body = boarded.bodyName ?: boarded.vehicleName
        val midway = (boarded.simTime + alighted.simTime) / 2.0
        assertEquals(body, m.vehicles.vehicleCarryingAt(boarded.entityId, midway))
        assertEquals(m.vehicles.transporterPositionAt(body, midway), m.carriedEntityPositionAt(boarded.entityId, midway))
        assertEquals(null, m.vehicles.vehicleCarryingAt(boarded.entityId, alighted.simTime))
    }

    @Test
    fun autoLayoutFramesThePathAndLeavesTheCartsToIt() {
        val m = model()
        val layout = m.autoLayout(events)
        assertEquals(listOf("AgvSystem"), layout.guidedPaths.map { it.spaceName })
        assertTrue(layout.resources.none { it.resourceName in m.vehicles.transporterNames })
        assertTrue(layout.queues.none { m.vehicles.isVehicleHoldQueue(it.queueName) })
    }
}
