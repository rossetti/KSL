package ksl.utilities.io

import java.io.PrintStream
import org.junit.jupiter.api.Test
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [StdoutCapture].  These mutate process-wide `System.out`
 * and `System.err`, so they MUST restore originals via the
 * [AfterTest] hook.  JUnit runs methods in this class sequentially
 * by default (no `@TestMethodOrder` parallelism), but if a future
 * test suite adds parallelism, this class needs `@Execution(SAME_THREAD)`.
 */
class StdoutCaptureTest {

    private val originalOut: PrintStream = System.out
    private val originalErr: PrintStream = System.err

    @AfterTest
    fun restoreStreams() {
        // Belt-and-suspenders: always restore originals after each test,
        // even if a test bombed before calling uninstall().
        StdoutCapture.uninstall()
        System.setOut(originalOut)
        System.setErr(originalErr)
    }

    @Test
    fun `install and uninstall round-trip restores the original streams`() {
        assertFalse(StdoutCapture.isInstalled())
        StdoutCapture.install { _, _ -> /* sink */ }
        assertTrue(StdoutCapture.isInstalled())
        // System.out is now the tee, not the original.
        assertFalse(System.out === originalOut)
        StdoutCapture.uninstall()
        assertFalse(StdoutCapture.isInstalled())
        assertTrue(System.out === originalOut, "uninstall must restore original System.out")
        assertTrue(System.err === originalErr, "uninstall must restore original System.err")
    }

    @Test
    fun `sink receives stdout lines with fromErr=false`() {
        val captured = mutableListOf<Pair<String, Boolean>>()
        StdoutCapture.install { text, fromErr -> captured += text to fromErr }
        System.out.println("hello stdout")
        StdoutCapture.uninstall()
        assertTrue(
            captured.any { it.first == "hello stdout" && !it.second },
            "should capture stdout line with fromErr=false; got $captured"
        )
    }

    @Test
    fun `sink receives stderr lines with fromErr=true`() {
        val captured = mutableListOf<Pair<String, Boolean>>()
        StdoutCapture.install { text, fromErr -> captured += text to fromErr }
        System.err.println("hello stderr")
        StdoutCapture.uninstall()
        assertTrue(
            captured.any { it.first == "hello stderr" && it.second },
            "should capture stderr line with fromErr=true; got $captured"
        )
    }

    @Test
    fun `re-installing replaces the previous sink without leaking tees`() {
        val first = mutableListOf<String>()
        val second = mutableListOf<String>()
        StdoutCapture.install { text, _ -> first += text }
        StdoutCapture.install { text, _ -> second += text }
        System.out.println("after-replace")
        StdoutCapture.uninstall()

        assertEquals(0, first.size, "first sink should not receive lines after replacement")
        assertTrue(
            second.any { it == "after-replace" },
            "second sink should receive lines; got $second"
        )
        // Critically, after uninstall System.out is the original, not
        // a tee-wrapping-a-tee — confirms re-install uninstalled first.
        assertTrue(System.out === originalOut)
    }

    @Test
    fun `uninstall is idempotent`() {
        // No-op when nothing installed.
        StdoutCapture.uninstall()
        StdoutCapture.uninstall()
        assertFalse(StdoutCapture.isInstalled())
        assertTrue(System.out === originalOut)
    }
}
