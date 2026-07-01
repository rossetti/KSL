package ksl.animation

import ksl.modeling.entity.BlockingQueue
import ksl.modeling.entity.HoldQueue
import ksl.modeling.entity.Resource
import ksl.modeling.entity.ResourcePoolWithQ
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.entity.Signal
import ksl.modeling.spatial.DistancesModel
import ksl.modeling.variable.Counter
import ksl.modeling.variable.Response
import ksl.simulation.ModelElement
import ksl.simulation.Model
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Verifies the Stage-4 DSL conveniences (8B.1/8B.3/8B.4) build the expected layout elements. */
class AnimationDslTest {

    @Test
    fun `resourceWithQ creates a resource and an auto-named queue whose head leads to the server`() {
        // Default growthDegrees = 180 (line extends left); head is queueGap to the left of the resource.
        val layout = Model("dsl1").animation {
            resourceWithQ("Worker", 100.0, 50.0, queueGap = 60.0)
        }
        assertEquals(listOf("Worker"), layout.resources.map { it.resourceName })
        assertEquals(listOf("Worker:Q"), layout.queues.map { it.queueName }, "queue auto-named '<resource>:Q'")
        assertEquals(40.0, layout.queues[0].position.x, 1e-9, "head placed queueGap to the left of the resource")
        assertEquals(50.0, layout.queues[0].position.y, 1e-9)
        assertEquals(180.0, layout.queues[0].growthDegrees, 1e-9, "line grows left, away from the server")
    }

    @Test
    fun `queue growthDegrees defaults to 0 (grows right)`() {
        val layout = Model("dsl1b").animation { queue("Q", 0.0, 0.0) { spacing = 10.0 } }
        assertEquals(0.0, layout.queues[0].growthDegrees, 1e-9)
        assertEquals(10.0, layout.queues[0].spacing, 1e-9)
    }

    @Test
    fun `location DSL adds placed and unplaced spatial locations`() {
        val layout = Model("dslLoc").animation {
            location("Depot", 10.0, 20.0, label = "Depot")
            location("drop-0")
        }
        assertEquals(listOf("Depot", "drop-0"), layout.locations.map { it.locationName })
        assertEquals(LayoutPoint(10.0, 20.0), layout.locations[0].position)
        assertEquals(null, layout.locations[1].position, "an unplaced location has a null position")
    }

    @Test
    fun `pathThrough reuses station positions`() {
        val layout = Model("dsl2").animation {
            station("A", 0.0, 0.0)
            station("B", 10.0, 5.0)
            pathThrough("route", "A", "B")
        }
        assertEquals(1, layout.paths.size)
        assertEquals(2, layout.paths[0].points.size)
        assertEquals(10.0, layout.paths[0].points[1].x, 1e-9)
        assertEquals(5.0, layout.paths[0].points[1].y, 1e-9)
    }

    @Test
    fun `pathBetween builds a functional path between anchors`() {
        val layout = Model("dslPath").animation {
            pathBetween("route", AnchorRef.location("A"), AnchorRef.location("B"), 5.0 to 5.0, 6.0 to 7.0, bidirectional = false)
        }
        val p = layout.paths.single { it.name == "route" }
        assertEquals(AnchorRef.location("A"), p.from)
        assertEquals(AnchorRef.location("B"), p.to)
        assertEquals(false, p.bidirectional)
        assertEquals(listOf(LayoutPoint(5.0, 5.0), LayoutPoint(6.0, 7.0)), p.points, "waypoints become the intermediate points")
    }

    @Test
    fun `object-reference overloads derive trace names from the model elements (8K1)`() {
        val m = Model("dslObj")
        val worker = ResourceWithQ(m, "ShirtMakers_R")
        val resp = Response(m, "System Time")
        val cnt = Counter(m, "Num Made")

        val layout = m.animation {
            resource(worker, 10.0, 10.0)
            resourceWithQ(worker, 100.0, 50.0, queueGap = 60.0)
            queue(worker.waitingQ, 200.0, 0.0)
            bar(resp, 0.0, 0.0); plot(resp, 0.0, 0.0); value(resp, 0.0, 0.0)
            summary(resp, 0.0, 0.0); histogram(resp, 0.0, 0.0)
            bar(cnt, 0.0, 0.0); value(cnt, 0.0, 0.0); frequency(cnt, 0.0, 0.0)
        }

        assertEquals(listOf("ShirtMakers_R", "ShirtMakers_R"), layout.resources.map { it.resourceName })
        // both the resourceWithQ-composed queue and the queue(waitingQ) call resolve to "<resource>:Q"
        assertEquals(listOf("ShirtMakers_R:Q", "ShirtMakers_R:Q"), layout.queues.map { it.queueName })
        assertTrue(layout.bars.all { it.responseName in setOf("System Time", "Num Made") })
        assertEquals(listOf("System Time", "Num Made"), layout.values.map { it.responseName })
        assertEquals(listOf("System Time"), layout.plots.map { it.responseName })
        assertEquals(listOf("System Time"), layout.summaries.map { it.responseName })
        assertEquals(listOf("System Time", "Num Made"), layout.histograms.map { it.responseName })
    }

    @Test
    fun `object overload produces the same layout as the name overload (8K1)`() {
        val m = Model("dslEq")
        val worker = ResourceWithQ(m, "W")
        val resp = Response(m, "R")
        val cnt = Counter(m, "C")

        val byName = m.animation {
            resource("W", 1.0, 2.0); queue("W:Q", 5.0, 6.0)
            bar("R", 3.0, 4.0); value("R", 7.0, 8.0); plot("R", 9.0, 10.0)
            summary("R", 11.0, 12.0); histogram("R", 13.0, 14.0)
            value("C", 15.0, 16.0); frequency("C", 17.0, 18.0)
        }
        val byObj = m.animation {
            resource(worker, 1.0, 2.0); queue(worker.waitingQ, 5.0, 6.0)
            bar(resp, 3.0, 4.0); value(resp, 7.0, 8.0); plot(resp, 9.0, 10.0)
            summary(resp, 11.0, 12.0); histogram(resp, 13.0, 14.0)
            value(cnt, 15.0, 16.0); frequency(cnt, 17.0, 18.0)
        }

        // The object overloads are exact sugar: identical layouts (the whole data class compares equal).
        assertEquals(byName, byObj)
    }

    @Test
    fun `process-construct helpers bind to the names these constructs emit (8K3)`() {
        val m = Model("dsl8k3")
        val bq = BlockingQueue<ModelElement.QObject>(m, name = "CompletedShirtQ")
        val sig = Signal(m, "GoSignal")
        val hq = HoldQueue(m, "WaitQ")
        val u1 = Resource(m, "U1")
        val u2 = Resource(m, "U2")
        val pool = ResourcePoolWithQ(m, listOf(u1, u2), name = "Pool")

        val layout = m.animation {
            blockingQueue(bq, 10.0, 10.0, showSender = true, showRequest = true)
            signal(sig, 20.0, 20.0)
            holdQueue(hq, 30.0, 30.0)
            resourcePoolWithQ(pool, 100.0, 100.0)
        }

        val qNames = layout.queues.map { it.queueName }.toSet()
        assertTrue("CompletedShirtQ:ChannelQ" in qNames, "blocking queue channel")
        assertTrue("CompletedShirtQ:SenderQ" in qNames, "blocking queue sender (opt-in)")
        assertTrue("CompletedShirtQ:RequestQ" in qNames, "blocking queue request (opt-in)")
        assertTrue("GoSignal:HoldQ" in qNames, "signal hold queue")
        assertTrue("WaitQ" in qNames, "hold queue by name")
        assertTrue("Pool:Q" in qNames, "pool waiting queue")
        assertEquals(listOf("U1", "U2"), layout.resources.map { it.resourceName }, "each pool member drawn")
        assertTrue(layout.values.any { it.responseName == "Pool:NumBusy" }, "pool busy-count readout")
    }

    @Test
    fun `storage element binds to a suspension or type name (8K4)`() {
        val layout = Model("dslStorage").animation {
            storage("inspect", 100.0, 100.0) { capacity = 20; spacing = 16.0 }
            holdingArea("Student", 300.0, 100.0) { style = ksl.animation.StorageStyle.PACKED_REGION }
        }
        assertEquals(listOf("inspect", "Student"), layout.storages.map { it.suspensionName })
        assertEquals(ksl.animation.StorageStyle.PROGRESS_BELT, layout.storages[0].style, "default style is progress belt")
        assertEquals(20, layout.storages[0].capacity)
        assertEquals(ksl.animation.StorageStyle.PACKED_REGION, layout.storages[1].style)
    }

    @Test
    fun `obstaclesFrom authors grid geometry extracted from a graph (P5a)`() {
        val graph = ksl.modeling.agent.GridGraph(10, 10).also {
            it.block(ksl.modeling.agent.Cell(3, 3)); it.block(ksl.modeling.agent.Cell(3, 4))
        }
        val layout = Model("dslObstacles").animation {
            gridSpace("floor", cols = 10, rows = 10, cellSize = 1.0)
            obstaclesFrom("floor", graph)
        }
        val spec = layout.spaceGeometry.single()
        assertEquals("floor", spec.spaceName)
        assertEquals(listOf(ksl.modeling.agent.Cell(3, 3), ksl.modeling.agent.Cell(3, 4)), spec.blockedCells)
    }

    @Test
    fun `placeLocations MDS-places a distance model's locations`() {
        val dm = DistancesModel()
        val a = dm.Location("A"); val b = dm.Location("B"); val c = dm.Location("C")
        dm.addDistance(a, b, 10.0, symmetric = true)
        dm.addDistance(b, c, 10.0, symmetric = true)
        dm.addDistance(a, c, 10.0, symmetric = true)
        val layout = Model("dslMds").animation { placeLocations(dm) }
        assertEquals(listOf("A", "B", "C"), layout.locations.map { it.locationName }.sorted())
        assertTrue(
            layout.locations.all { it.position?.x?.isFinite() == true && it.position?.y?.isFinite() == true },
            "every location gets a finite MDS position"
        )
        assertTrue(layout.stations.isEmpty(), "placeLocations emits locations, not stations")
    }
}
