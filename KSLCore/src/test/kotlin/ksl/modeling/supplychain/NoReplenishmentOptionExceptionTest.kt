package ksl.modeling.supplychain

import ksl.modeling.supplychain.inventory.*
import ksl.modeling.supplychain.transport.*
import ksl.modeling.supplychain.network.*

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class NoReplenishmentOptionExceptionTest {

    @Test
    fun `default message`() {
        val e = NoReplenishmentOptionException()
        assertEquals("NoReplenishmentOptionException", e.message)
        assertNull(e.cause)
    }

    @Test
    fun `custom message`() {
        val e = NoReplenishmentOptionException("no supplier for SKU-X")
        assertEquals("no supplier for SKU-X", e.message)
    }

    @Test
    fun `with cause`() {
        val cause = RuntimeException("upstream")
        val e = NoReplenishmentOptionException("rejected", cause)
        assertEquals("rejected", e.message)
        assertSame(cause, e.cause)
    }
}
