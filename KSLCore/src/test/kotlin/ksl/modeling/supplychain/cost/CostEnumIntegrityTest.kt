package ksl.modeling.supplychain.cost

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
    fun `NodeTier all is complete and null-free`() {
        assertEquals(
            NodeTier::class.sealedSubclasses.mapNotNull { it.objectInstance }.toSet(),
            NodeTier.all.toSet(),
            "NodeTier.all must contain exactly the declared tier objects",
        )
    }
}
