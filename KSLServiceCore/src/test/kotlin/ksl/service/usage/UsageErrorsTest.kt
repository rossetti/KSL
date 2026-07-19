package ksl.service.usage

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UsageErrorsTest {

    @Test
    @DisplayName("classify maps common throwables to coarse buckets; null for cancellation")
    fun classifies() {
        assertEquals("NOT_FOUND", UsageErrors.classify(NoSuchElementException("x")))
        assertEquals("NOT_FOUND", UsageErrors.classify(java.io.FileNotFoundException("x")))
        assertEquals("INVALID_INPUT", UsageErrors.classify(IllegalArgumentException("x")))
        assertEquals("TIMEOUT", UsageErrors.classify(java.util.concurrent.TimeoutException("x")))
        assertEquals("UNAVAILABLE", UsageErrors.classify(java.io.IOException("x")))
        assertEquals("INTERNAL", UsageErrors.classify(RuntimeException("x")))
        assertNull(UsageErrors.classify(kotlin.coroutines.cancellation.CancellationException("x")))
    }
}
