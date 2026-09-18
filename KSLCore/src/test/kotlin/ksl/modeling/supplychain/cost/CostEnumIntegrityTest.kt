package ksl.modeling.supplychain.cost

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Integrity guard for the `all` registries on [CostLine] and
 * [NodeTier].  These are sealed classes whose companion `all` list
 * references their nested `object`s; built eagerly, the companion can
 * capture a `null` entry when a nested object is the first symbol
 * touched (a sealed-class initialization-order trap).  The matrix audit
 * surfaced exactly that — a `null` Loading entry left
 * `byLineResponse(Loading)` returning null.  These assertions pin that
 * `all` is complete, null-free, and matches the declared object set.
 */
class CostEnumIntegrityTest {

    @Test
    fun `CostLine all is complete and null-free`() {
        assertEquals(
            CostLine::class.sealedSubclasses.mapNotNull { it.objectInstance }.toSet(),
            CostLine.all.toSet(),
            "CostLine.all must contain exactly the declared line objects",
        )
    }

    @Test
    @DisplayName("every cost line declares a basis, and exactly four are rates")
    fun costLineBasisAssignmentsArePinned() {
        // Pinned by enumeration rather than by counting, so that adding a line
        // fails here and forces a deliberate choice. A line that silently
        // defaulted to the wrong basis would produce a wrong total with no
        // error anywhere -- that is the failure this guards.
        assertEquals(
            setOf(
                CostLine.Holding,
                CostLine.InTransit,
                CostLine.Backorder,
                CostLine.ShipmentBuilderHolding,
            ),
            CostLine.all.filter { it.basis == CostBasis.PerUnitTime }.toSet(),
            "the per-unit-time lines are exactly the four formed as a " +
                "time-weighted average times a rate",
        )
        assertEquals(
            CostLine.all.size - 4,
            CostLine.all.count { it.basis == CostBasis.PerReplication },
            "every remaining line must be a per-replication total",
        )
    }

    @Test
    fun `NodeTier all is complete and null-free`() {
        assertEquals(
            NodeTier::class.sealedSubclasses.mapNotNull { it.objectInstance }.toSet(),
            NodeTier.all.toSet(),
            "NodeTier.all must contain exactly the declared tier objects",
        )
    }
}
