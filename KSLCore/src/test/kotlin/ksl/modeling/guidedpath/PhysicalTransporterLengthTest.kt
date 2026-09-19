package ksl.modeling.guidedpath

import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.rules.StartOfZoneControl
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 *  Sizing a transporter by its physical length rather than by a count of zones.
 *
 *  The reference implementation offers both -- sizing by whole zones and sizing by length -- and
 *  they are not two spellings of one thing. A vehicle
 *  sized in zones is a token that occupies whole cells of the guide path and travels from point to
 *  point. A vehicle sized by length is a **body**: parked at a dead end it has already covered its
 *  own length of the spur, so backing out is that much shorter than driving in. That asymmetry is
 *  the whole of what this adds, and it is what let the cross-check against the reference implementation close exactly (see
 *  [SimpleAgvCrossCheckTest]).
 *
 *  The most important property asserted here is the negative one: with no physical length given,
 *  nothing changes at all. Every model written before this, and the paradigm-equivalence gate and
 *  the throughput benchmark that rest on them, are untouched.
 */
class PhysicalTransporterLengthTest {

    private companion object {
        const val HORIZON = 200.0
    }

    /** A loop with a dead-end spur, so a cart has somewhere to back out of. */
    private class Shop(
        parent: ModelElement,
        cartLength: Double?,
        loopZone: Double = 12.0
    ) : ProcessModel(parent, "Shop") {

        val network = GuidedPathNetwork.builder("Loop")
            .intersection("A", x = 0.0, y = 0.0)
            .intersection("B", x = 100.0, y = 0.0)
            .intersection("C", x = 100.0, y = 100.0)
            .intersection("D", x = 0.0, y = 100.0)
            .intersection("E", x = -60.0, y = 0.0)
            .link("AB", "A", "B", length = 96.0, zoneLength = loopZone, beginDirection = 0.0)
            .link("BC", "B", "C", length = 96.0, zoneLength = loopZone, beginDirection = 90.0)
            .link("CD", "C", "D", length = 96.0, zoneLength = loopZone, beginDirection = 180.0)
            .link("DA", "D", "A", length = 96.0, zoneLength = loopZone, beginDirection = 270.0)
            .link("Spur", "A", "E", length = 60.0, zoneLength = 60.0,
                type = LinkType.SPUR, beginDirection = 180.0)
            .station("Dock", "E")
            .station("Depot", "A")
            .build()

        init {
            spatialModel = network
        }

        val system = GuidedPathTransportSystem(this, network, name = "Sys")

        val cart = GuidedTransporter(
            system, TransporterPlacement.At("Depot"), ConstantRV(10.0), 1,
            StartOfZoneControl(), "Cart", physicalLength = cartLength
        )

        override fun initialize() {
            // Down to the dock, and later back. Both journeys are finished long before the horizon,
            // so the transporter's total moving time over the replication is exactly their sum --
            // which is an exact measurement and needs no arrival callback to take it.
            schedule(::goToDock, 0.0)
            schedule(::comeBack, 50.0)
        }

        @Suppress("UNUSED_PARAMETER")
        private fun goToDock(event: ksl.simulation.KSLEvent<Nothing>) {
            cart.sendTo("Dock")
        }

        @Suppress("UNUSED_PARAMETER")
        private fun comeBack(event: ksl.simulation.KSLEvent<Nothing>) {
            cart.sendTo("Depot")
        }
    }

    /** Total time the cart spent moving, which is the round trip and nothing else. */
    private fun Shop.travelTime(): Double =
        cart.fracTimeMoving.withinReplicationStatistic.weightedAverage * HORIZON

    private fun runShop(cartLength: Double?, loopZone: Double = 12.0): Shop {
        val m = Model("PhysLen")
        val shop = Shop(m, cartLength, loopZone)
        m.numberOfReplications = 1
        m.lengthOfReplication = HORIZON
        m.simulate()
        return shop
    }

    @Test
    @DisplayName("Backing out of a dead end is shorter by the transporter's own length")
    fun reversingOutOfASpurCostsItsLengthLess() {
        // Down the 60-unit spur and back at velocity 10: 6.0 each way for a point, 12.0 in all.
        val point = runShop(cartLength = null)
        assertEquals(12.0, point.travelTime(), 1.0e-9, "a transporter sized in zones is a point")

        // A body six long has already covered six of the spur, so the way out is 54 rather than 60.
        val body = runShop(cartLength = 6.0)
        assertEquals(11.4, body.travelTime(), 1.0e-9, "the credit should be exactly the length / velocity")
        assertEquals(0.6, point.travelTime() - body.travelTime(), 1.0e-9)
    }

    @Test
    @DisplayName("The credit is spent once and only on the way out, not on the way in")
    fun theCreditIsAsymmetric() {
        // If the credit applied both ways the saving would be 1.2 rather than 0.6. That it is 0.6
        // is what makes this a body driving into a dead end rather than a shorter spur.
        val body = runShop(cartLength = 6.0)
        val shorter = runShop(cartLength = null)
        assertTrue(
            shorter.travelTime() - body.travelTime() < 1.2 - 1.0e-9,
            "the credit was applied on the way in as well as on the way out"
        )
    }

    @Test
    @DisplayName("A transporter sized by length must fit within a zone, and is told so if it does not")
    fun aLengthMustFitAZone() {
        val m = Model("TooLongModel")
        val e = assertFailsWith<IllegalArgumentException> {
            val shop = Shop(m, cartLength = null)
            GuidedTransporter(
                shop.system, TransporterPlacement.At("Depot"), ConstantRV(10.0), 1,
                StartOfZoneControl(), "TooLong", physicalLength = 25.0
            )
        }
        assertTrue(
            e.message!!.contains("sized in zones"),
            "the refusal should say what to do instead: ${e.message}"
        )
    }

    @Test
    @DisplayName("Without a physical length nothing changes, and the two sizings cannot be mixed")
    fun sizingIsOneWayOrTheOther() {
        val sizedInZones = runShop(cartLength = null)
        assertEquals(12.0, sizedInZones.travelTime(), 1.0e-9, "point-to-point travel is unchanged")

        val m = Model("Bad")
        val e = assertFailsWith<IllegalArgumentException> {
            val shop = Shop(m, cartLength = null)
            GuidedTransporter(
                shop.system, TransporterPlacement.At("Depot"), ConstantRV(10.0), 2,
                StartOfZoneControl(), "Both", physicalLength = 6.0
            )
        }
        assertTrue(
            e.message!!.contains("one way or the other"),
            "the refusal should say why both cannot be given: ${e.message}"
        )
    }

    @Test
    @DisplayName("A physical length must be a length")
    fun lengthMustBePositive() {
        val m = Model("Bad2")
        assertFailsWith<IllegalArgumentException> {
            val shop = Shop(m, cartLength = null)
            GuidedTransporter(
                shop.system, TransporterPlacement.At("Depot"), ConstantRV(10.0), 1,
                StartOfZoneControl(), "Zero", physicalLength = 0.0
            )
        }
    }
}
