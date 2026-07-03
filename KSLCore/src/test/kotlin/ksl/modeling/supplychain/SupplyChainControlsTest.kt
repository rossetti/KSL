package ksl.modeling.supplychain

import ksl.modeling.supplychain.inventory.Inventory
import ksl.modeling.supplychain.inventory.InventoryPolicyAbstract
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Controls-annotation coverage for the supply-chain package (the supplychain-controls
 * plan). Per tier: a discovery test (the expected keys appear via model.controls()) and
 * a round-trip test (setting by key writes through to the property, with numeric bound
 * clamping). Controls configure replication INITIAL conditions; current values are
 * re-seeded from initials at beforeReplication().
 */
class SupplyChainControlsTest {

    /** A no-op policy — never requests replenishment. */
    private class NullPolicy(parent: ModelElement) :
        InventoryPolicyAbstract(parent, "NullPolicy") {
        override fun setInitialPolicyParameters(parameters: DoubleArray) {
            myInitialPolicyParameters = parameters
        }
        override fun checkInventory() { /* never order */ }
        override fun setPolicyParameters(parameters: DoubleArray) { /* no-op */ }
        override fun getPolicyParameters(): DoubleArray = DoubleArray(0)
    }

    private data class Fixture(
        val model: Model,
        val sc: SupplyChainModel,
        val item: ItemType,
        val inv: Inventory,
    )

    private fun fixture(): Fixture {
        val m = Model("SupplyChainControlsTest")
        val sc = SupplyChainModel(m)
        val item = ItemType(sc, name = "SKU")
        val policy = NullPolicy(sc)
        val inv = Inventory(sc, item, policy, initialOnHand = 10, name = "Inv")
        return Fixture(m, sc, item, inv)
    }

    // ── Tier 2: Inventory + ItemType ──────────────────────────────────────────

    @Test
    @DisplayName("Tier 2: inventory and item-type control keys are discoverable")
    fun tier2KeysDiscoverable() {
        val f = fixture()
        val keys = f.model.controls().controlKeys()
        val expected = listOf(
            "Inv.initialOnHand",
            "Inv.permitPartialFilling",
            "Inv.permitBackLogging",
            "Inv.mayPartiallyFillDemands",
            "SKU.unitCost"
        )
        for (key in expected) {
            assertTrue(key in keys) { "expected control key '$key'; got $keys" }
        }
        // Deliberately excluded: derived from backlog-policy attachment.
        assertFalse(keys.any { it.endsWith(".allowBackLogging") }) {
            "allowBackLogging must not be a control (derived state)"
        }
    }

    @Test
    @DisplayName("Tier 2: controls write through to properties and clamp to bounds")
    fun tier2RoundTripAndClamping() {
        val f = fixture()
        val controls = f.model.controls()

        // Numeric write-through and clamping (initialOnHand has lowerBound = 0).
        val onHand = controls.control("Inv.initialOnHand")
        assertNotNull(onHand)
        onHand!!.value = 25.0
        assertEquals(25, f.inv.initialOnHand)
        onHand.value = -5.0   // clamps, does not throw
        assertEquals(0, f.inv.initialOnHand)

        // unitCost clamps at 0.
        val cost = controls.control("SKU.unitCost")
        assertNotNull(cost)
        cost!!.value = 7.5
        assertEquals(7.5, f.item.unitCost, 0.0)
        cost.value = -1.0
        assertEquals(0.0, f.item.unitCost, 0.0)

        // Booleans are numeric controls set via 1.0 / 0.0.
        val backLog = controls.control("Inv.permitBackLogging")
        assertNotNull(backLog)
        backLog!!.value = 0.0
        assertFalse(f.inv.permitBackLogging)
        backLog.value = 1.0
        assertTrue(f.inv.permitBackLogging)

        val partial = controls.control("Inv.mayPartiallyFillDemands")
        assertNotNull(partial)
        partial!!.value = 0.0
        assertFalse(f.inv.mayPartiallyFillDemands)
    }

    // ── Tier 3: demand generation ─────────────────────────────────────────────

    @Test
    @DisplayName("Tier 3: demand-generation booleans are controls; EventGenerator controls are inherited")
    fun tier3DemandGenerationControls() {
        val f = fixture()
        val generator = ksl.modeling.supplychain.inventory.DemandGenerator(
            f.sc, f.item,
            timeUntilFirstRV = ksl.utilities.random.rvariable.ExponentialRV(1.0, 1),
            timeBtwEventsRV = ksl.utilities.random.rvariable.ExponentialRV(1.0, 2),
            name = "DGen"
        )
        val orderCreator = ksl.modeling.supplychain.inventory.RandomOrderCreator(f.sc, name = "OrderMaker")
        val controls = f.model.controls()
        val keys = controls.controlKeys()
        val expected = listOf(
            "DGen.unitDemandOnly",
            "DGen.permitBackLogging",
            "DGen.permitPartialFilling",
            "OrderMaker.permitBackLogging",
            // inherited from EventGenerator — must be present without re-annotation
            "DGen.initialMaximumNumberOfEvents",
            "DGen.initialEndingTime",
            "DGen.startOnInitializeOption"
        )
        for (key in expected) {
            assertTrue(key in keys) { "expected control key '$key'; got $keys" }
        }
        // round-trip one of each element
        controls.control("DGen.unitDemandOnly")!!.value = 1.0
        assertTrue(generator.unitDemandOnly)
        controls.control("OrderMaker.permitBackLogging")!!.value = 0.0
        assertFalse(orderCreator.permitBackLogging)
    }

    // ── Tier 4.1: transport & load formation ─────────────────────────────────

    @Test
    @DisplayName("Tier 4.1: carrier and load-builder controls are discoverable and write through")
    fun tier41TransportControls() {
        val f = fixture()
        val demandCarrier = ksl.modeling.supplychain.transport.TimeBasedDemandCarrier(f.sc, name = "DCarrier")
        val orderCarrier = ksl.modeling.supplychain.transport.TimeBasedOrderCarrier(f.sc, name = "OCarrier")
        val loadCarrier = ksl.modeling.supplychain.transport.TimeBasedLoadCarrier(f.sc, name = "LCarrier")
        val typeCarrier = ksl.modeling.supplychain.transport
            .TimeBasedTypeLocationIndependentDemandCarrier(f.sc, name = "TCarrier")
        val networkCarrier = ksl.modeling.supplychain.transport
            .TimeBasedNetworkDemandCarrier(f.sc, name = "NCarrier")
        val byTimeCarrier = ksl.modeling.supplychain.transport
            .NetworkDemandCarrierByTime(f.sc, name = "BCarrier")
        val builder = ksl.modeling.supplychain.transport.DemandLoadBuilder(f.sc, name = "Builder")

        val controls = f.model.controls()
        val keys = controls.controlKeys()
        val expected = listOf(
            "DCarrier.immediateTransportFlag",
            "OCarrier.immediateTransportFlag",
            // inherited from TimeBasedDemandCarrier — not re-annotated on the subclass
            "LCarrier.immediateTransportFlag",
            "LCarrier.reactToLoadBuildersFlag",
            "TCarrier.immediateTransportFlag",
            "NCarrier.immediateTransportFlag",
            "BCarrier.demandGeneratorImmediateTransportFlag",
            "BCarrier.externalSupplierImmediateTransportFlag",
            "Builder.countLimit"
        )
        for (key in expected) {
            assertTrue(key in keys) { "expected control key '$key'; got $keys" }
        }

        // round-trip: inherited flag on the load carrier, and countLimit clamping at 1
        controls.control("LCarrier.immediateTransportFlag")!!.value = 1.0
        assertTrue(loadCarrier.immediateTransportFlag)
        val countLimit = controls.control("Builder.countLimit")
        assertNotNull(countLimit)
        countLimit!!.value = 12.0
        assertEquals(12, builder.countLimit)
        countLimit.value = 0.0   // clamps to the lower bound of 1
        assertEquals(1, builder.countLimit)

        // the flags that default true can be turned off by key
        controls.control("BCarrier.demandGeneratorImmediateTransportFlag")!!.value = 0.0
        assertFalse(byTimeCarrier.demandGeneratorImmediateTransportFlag)
        // silence unused warnings for elements exercised only via discovery
        assertNotNull(demandCarrier)
        assertNotNull(orderCarrier)
        assertNotNull(typeCarrier)
        assertNotNull(networkCarrier)
    }

    // ── Tier 1: inventory policy decision variables ───────────────────────────

    @Test
    @DisplayName("Tier 1: policy decision-variable keys are discoverable (r/SDelta parameterization)")
    fun tier1PolicyKeysDiscoverable() {
        val f = fixture()
        ksl.modeling.supplychain.inventory.InventoryPolicyReorderPointOrderUpToLevel(
            f.sc, reorderPoint = 10, orderUpToPoint = 20, name = "RS")
        ksl.modeling.supplychain.inventory.InventoryPolicyReorderPointOrderUpToLevelPeriodic(
            f.sc, reorderPoint = 10, orderUpToPoint = 20, reviewPeriod = 5.0, name = "RSP")
        ksl.modeling.supplychain.inventory.InventoryPolicyReorderPointReorderQuantity(
            f.sc, reorderPoint = 2, reorderQty = 4, name = "RQ")
        val keys = f.model.controls().controlKeys()
        val expected = listOf(
            "RS.initialReorderPoint", "RS.initialOrderUpToPointDelta",
            "RSP.initialReorderPoint", "RSP.initialOrderUpToPointDelta", "RSP.initialReviewPeriod",
            "RQ.initialReorderPointDelta", "RQ.initialReorderQty", "RQ.separateBatchOrders"
        )
        for (key in expected) {
            assertTrue(key in keys) { "expected control key '$key'; got $keys" }
        }
    }

    @Test
    @DisplayName("Tier 1: the delta parameterization makes control writes order-independent")
    fun tier1DeltaParameterizationOrderIndependence() {
        // Under a direct (r, S) parameterization with a per-setter r < S check, moving
        // (10, 20) to the valid destination (30, 40) would throw when r is set first.
        // The delta form must accept either order.
        val f = fixture()
        val policy = ksl.modeling.supplychain.inventory.InventoryPolicyReorderPointOrderUpToLevel(
            f.sc, reorderPoint = 10, orderUpToPoint = 20, name = "RS")
        val controls = f.model.controls()

        controls.control("RS.initialReorderPoint")!!.value = 30.0        // r first — must not throw
        controls.control("RS.initialOrderUpToPointDelta")!!.value = 10.0
        assertEquals(30, policy.initialReorderPoint)
        assertEquals(10, policy.initialOrderUpToPointDelta)
        assertEquals(40, policy.initialOrderUpToPoint, "S must derive as r + SDelta")

        // reverse order works too
        controls.control("RS.initialOrderUpToPointDelta")!!.value = 3.0
        controls.control("RS.initialReorderPoint")!!.value = -2.0
        assertEquals(1, policy.initialOrderUpToPoint, "S = -2 + 3 = 1")

        // SDelta clamps at its lower bound of 1 rather than throwing
        controls.control("RS.initialOrderUpToPointDelta")!!.value = 0.0
        assertEquals(1, policy.initialOrderUpToPointDelta)
    }

    @Test
    @DisplayName("Tier 1: programmatic (r, S) writes keep the SDelta control in sync")
    fun tier1ProgrammaticWritesSyncDelta() {
        val f = fixture()
        val policy = ksl.modeling.supplychain.inventory.InventoryPolicyReorderPointOrderUpToLevel(
            f.sc, reorderPoint = 10, orderUpToPoint = 20, name = "RS")
        policy.setInitialPolicyParameters(5, 12)
        assertEquals(7, policy.initialOrderUpToPointDelta,
            "SDelta must resync after a programmatic (r, S) write")
        // and a subsequent r change preserves the gap
        f.model.controls().control("RS.initialReorderPoint")!!.value = 6.0
        assertEquals(13, policy.initialOrderUpToPoint)
    }

    /** Probe that attempts to change an initial policy parameter mid-replication. */
    private class MidRunChangeProbe(
        parent: ModelElement,
        private val attempt: () -> Unit
    ) : ModelElement(parent) {
        var gateThrew: Boolean = false
            private set

        override fun initialize() {
            schedule(this::tryChange, 1.0)
        }

        private fun tryChange(event: ksl.simulation.KSLEvent<Nothing>) {
            try {
                attempt()
            } catch (_: IllegalArgumentException) {
                gateThrew = true
            }
        }
    }

    @Test
    @DisplayName("Tier 1: initial policy parameters cannot be changed during a replication")
    fun tier1InitialSettersGatedMidReplication() {
        val f = fixture()
        val policy = ksl.modeling.supplychain.inventory.InventoryPolicyReorderPointOrderUpToLevel(
            f.sc, reorderPoint = 10, orderUpToPoint = 20, name = "RS")
        // pre-run changes are allowed (controls configure initial conditions)
        policy.initialReorderPoint = 12
        assertEquals(12, policy.initialReorderPoint)

        val probe = MidRunChangeProbe(f.sc) { policy.initialReorderPoint = 99 }
        f.model.lengthOfReplication = 2.0
        f.model.numberOfReplications = 1
        f.model.simulate()
        assertTrue(probe.gateThrew,
            "changing an initial policy parameter mid-replication must be rejected")
        assertEquals(12, policy.initialReorderPoint,
            "the rejected mid-run change must not alter the initial value")
    }

    @Test
    @DisplayName("Tier 1: a clamped review period of 0 fails fast when applied at replication start")
    fun tier1ReviewPeriodValidatedAtApplyTime() {
        val f = fixture()
        val policy = ksl.modeling.supplychain.inventory.InventoryPolicyReorderPointOrderUpToLevelPeriodic(
            f.sc, reorderPoint = 10, orderUpToPoint = 20, reviewPeriod = 5.0, name = "RSP")
        // The control write clamps to the bound (0.0) without throwing...
        f.model.controls().control("RSP.initialReviewPeriod")!!.value = -2.0
        assertEquals(0.0, policy.initialReviewPeriod, 0.0)
        // ...and the invalid value is rejected when the initials are applied at
        // replication start, before any simulation effort is spent.
        f.model.lengthOfReplication = 2.0
        f.model.numberOfReplications = 1
        var failed = false
        try {
            f.model.simulate()
        } catch (e: Exception) {
            failed = true
            assertTrue(e.message?.contains("review period") == true ||
                e.cause?.message?.contains("review period") == true) {
                "expected the review-period validation message; got ${e.message}"
            }
        }
        assertTrue(failed, "an invalid review period must fail at replication start")
    }
}
