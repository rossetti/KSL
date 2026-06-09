package ksl.modeling.supplychain

import ksl.modeling.supplychain.inventory.*
import ksl.modeling.supplychain.transport.*
import ksl.modeling.supplychain.network.*

import ksl.simulation.Model
import ksl.utilities.random.rvariable.ExponentialRV
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class ItemTypeTest {

    @Test
    fun `defaults match the Java reference`() {
        val m = Model("ItemTypeTest")
        val item = ItemType(m, name = "SKU-1")
        assertEquals(1.0, item.unitCost)
        assertEquals(1.0, item.weight)
        assertEquals(1.0, item.cube)
        assertNull(item.leadTime)
        assertEquals("SKU-1", item.toString())
    }

    @Test
    fun `constructor validates weight`() {
        val m = Model("ItemTypeTest")
        assertThrows<IllegalArgumentException> { ItemType(m, weight = 0.0) }
        assertThrows<IllegalArgumentException> { ItemType(m, weight = -1.0) }
    }

    @Test
    fun `constructor validates cube`() {
        val m = Model("ItemTypeTest")
        assertThrows<IllegalArgumentException> { ItemType(m, cube = -0.01) }
    }

    @Test
    fun `cube zero is allowed but weight zero is not`() {
        val m = Model("ItemTypeTest")
        val ok = ItemType(m, weight = 1.0, cube = 0.0)
        assertEquals(0.0, ok.cube)
    }

    @Test
    fun `unitCost setter rejects negative`() {
        val m = Model("ItemTypeTest")
        val item = ItemType(m)
        item.unitCost = 5.5
        assertEquals(5.5, item.unitCost)
        assertThrows<IllegalArgumentException> { item.unitCost = -0.01 }
    }

    @Test
    fun `leadTime is held but not drawn from`() {
        val m = Model("ItemTypeTest")
        val rv = ExponentialRV(mean = 2.0, streamNum = 1)
        val item = ItemType(m, leadTime = rv)
        assertSame(rv, item.leadTime)
    }
}
