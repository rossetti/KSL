package ksl.modeling.supplychain

import ksl.modeling.supplychain.inventory.*
import ksl.modeling.supplychain.transport.*
import ksl.modeling.supplychain.network.*

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DemandMessageTest {

    @Test
    fun `default-only construction produces a 'no info' message`() {
        val msg = DemandMessage(demandFiller = TestFiller(), timeStamp = 3.5)
        assertEquals(3.5, msg.timeStamp)
        assertFalse(msg.canFillItemType)
        assertEquals(DemandStatusCode.NoStatus, msg.requestStatus)
        assertEquals(0, msg.requestFillAmount)
        assertNull(msg.inventory)
        assertFalse(msg.mayPartiallyFillDemands)
        assertFalse(msg.mayBackLogDemands)
    }

    @Test
    fun `fill-amount validation`() {
        assertThrows<IllegalArgumentException> {
            DemandMessage(
                demandFiller = TestFiller(),
                timeStamp = 0.0,
                requestFillAmount = -1,
            )
        }
    }

    @Test
    fun `data-class equality and copy`() {
        val filler = TestFiller()
        val a = DemandMessage(filler, 1.0, canFillItemType = true,
            requestStatus = DemandStatusCode.ImmediateFill, requestFillAmount = 4)
        val b = DemandMessage(filler, 1.0, canFillItemType = true,
            requestStatus = DemandStatusCode.ImmediateFill, requestFillAmount = 4)
        assertEquals(a, b)
        val c = a.copy(requestFillAmount = 5)
        assertEquals(5, c.requestFillAmount)
        assertEquals(4, a.requestFillAmount)
    }

    @Test
    fun `populated message reflects all fields`() {
        val filler = TestFiller()
        val msg = DemandMessage(
            demandFiller = filler,
            timeStamp = 7.25,
            canFillItemType = true,
            requestStatus = DemandStatusCode.WillBeBacklogged,
            requestFillAmount = 12,
            inventory = TestInventory(onHand = 3, onOrder = 5),
            mayPartiallyFillDemands = true,
            mayBackLogDemands = true,
        )
        assertTrue(msg.canFillItemType)
        assertEquals(12, msg.requestFillAmount)
        assertEquals(3, msg.inventory!!.amountOnHand)
        assertEquals(5, msg.inventory!!.amountOnOrder)
        assertTrue(msg.mayPartiallyFillDemands)
    }

    private class TestFiller : NoOpDemandFiller(name = "TestFiller")

    private class TestInventory(
        private val onHand: Int,
        private val onOrder: Int,
    ) : InventoryIfc {
        override val amountOnHand: Int get() = onHand
        override val amountOnOrder: Int get() = onOrder
        override val backLogInfo: BackLogInfoIfc = object : BackLogInfoIfc {
            override val name = "tbl"
            override val id = 0
            override var label: String? = null
            override val amountBackLogged = 0
            override val numberOfDemandsBackLogged = 0
        }
        override val inventoryPosition: Int get() = onHand + onOrder
    }
}
