package ksl.modeling.supplychain

import ksl.modeling.supplychain.inventory.*
import ksl.simulation.Model
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression for the cross-dock upstream-rejection leak (audit
 * finding A).  [InventoryCrossDock.receive] parks the original demand
 * in IN_PROCESS and forwards a clone upstream.  Previously the
 * forwarded-request listener handled only RECEIVED, and the round-trip
 * completion fired only on DELIVERED — so an upstream rejection
 * stranded the original in IN_PROCESS forever and every cross-dock
 * statistic silently undercounted.
 *
 * The fix adds a `reject` (and `setStatus`) transition out of
 * IN_PROCESS in the demand state machine, and a REJECTED arm in the
 * cross-dock's forwarded-request listener that un-parks the original
 * by propagating the failure.
 *
 * Setup: a cross-dock whose upstream filler is a [LeadTimeDemandFiller]
 * with the item type unregistered, so it rejects every forwarded
 * request with `ItemTypeMismatch`.  A demand generator feeds the
 * cross-dock; its `demandRejected` is overridden to record (not throw)
 * so the run continues.  Every generated demand must end up REJECTED
 * (un-parked), none stranded.
 */
class CrossDockRejectionTest {

    @Test
    fun `upstream rejection un-parks the original instead of stranding it`() {
        val m = Model("CDReject")
        val sc = SupplyChainModel(m, name = "SC")
        val item = ItemType(sc, name = "X")

        // Upstream that rejects every forwarded request (item never
        // registered → ItemTypeMismatch).
        val rejecter = LeadTimeDemandFiller(sc, name = "Rejecter")

        val cd = InventoryCrossDock(sc, name = "CD")
        cd.demandFiller = rejecter

        val rejectedOriginals = mutableListOf<SupplyChainModel.Demand>()
        val rejectedStates = mutableSetOf<DemandStateId>()
        val dg = object : DemandGenerator(
            supplyChainModel = sc, itemType = item,
            timeUntilFirstRV = ConstantRV(1.0),
            timeBtwEventsRV = ConstantRV(1.0),
            name = "DG",
        ) {
            override fun demandRejected(demand: SupplyChainModel.Demand) {
                rejectedOriginals += demand
                rejectedStates += demand.demandState.stateId
            }
        }
        dg.demandFiller = cd

        m.numberOfReplications = 1
        m.lengthOfReplication = 10.5
        m.simulate()  // must not throw (no strand, no unhandled state)

        // The cross-dock recorded the failed round trips and zero
        // successes.
        assertTrue(cd.numberOfDemandsRejectedResponse.value > 0.0,
            "expected rejected round trips, got " +
                "${cd.numberOfDemandsRejectedResponse.value}")
        assertEquals(0.0, cd.numberOfDemandsCrossDockedResponse.value,
            "no round trip should have completed successfully")

        // Every original that was rejected reached the REJECTED state
        // (un-parked from IN_PROCESS), not stranded.
        assertTrue(rejectedOriginals.isNotEmpty(),
            "the demand generator should have seen rejected originals")
        assertTrue(rejectedStates == setOf(DemandStateId.Rejected),
            "every un-parked original must be in REJECTED, got $rejectedStates")

        // The rejected-count matches the originals the generator saw.
        assertEquals(rejectedOriginals.size.toDouble(),
            cd.numberOfDemandsRejectedResponse.value, 1e-9)
    }
}
