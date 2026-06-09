package ksl.modeling.supplychain

import ksl.modeling.supplychain.inventory.*
import ksl.modeling.supplychain.transport.*
import ksl.modeling.supplychain.network.*

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class StatusCodeTest {

    @Test
    fun `DemandStatusCode enumerates all ten legacy values in order`() {
        val expected = listOf(
            "NoStatus",
            "ImmediateFill",
            "WillBeBacklogged",
            "FillerUnavailable",
            "ItemTypeMismatch",
            "NonPartialDemandToPartialFillingReceiver",
            "NonBackloggableDemandToBackloggingReceiverNotImmediateFill",
            "NonBackloggableDemandToNonBackloggingReceiverNotImmediateFill",
            "BackloggableDemandToNonBackloggingReceiverNotImmediateFill",
            "OrderRejected",
        )
        assertEquals(expected, DemandStatusCode.entries.map { it.name })
    }

    @Test
    fun `OrderStatusCode enumerates all five legacy values in order`() {
        val expected = listOf(
            "NoStatus",
            "DemandRejected",
            "ImmediateFill",
            "WillBeBacklogged",
            "FillerUnavailable",
        )
        assertEquals(expected, OrderStatusCode.entries.map { it.name })
    }

    @Test
    fun `enum round-trips through valueOf`() {
        assertEquals(DemandStatusCode.ImmediateFill, DemandStatusCode.valueOf("ImmediateFill"))
        assertEquals(OrderStatusCode.DemandRejected, OrderStatusCode.valueOf("DemandRejected"))
    }
}
