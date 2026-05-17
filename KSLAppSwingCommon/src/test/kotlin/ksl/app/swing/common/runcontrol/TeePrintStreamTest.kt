package ksl.app.swing.common.runcontrol

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Unit tests for [TeePrintStream] — the line-completing tee used by
 * [StdoutCapture] to mirror System.out / System.err into a sink.
 *
 * No process-wide stream mutation here; the tests construct their
 * own [PrintStream] as the "original" so they're isolated from JUnit
 * stdout capture.
 */
class TeePrintStreamTest {

    @Test
    fun `each newline-terminated chunk produces one line in the sink`() {
        val originalBytes = ByteArrayOutputStream()
        val original = PrintStream(originalBytes, true, StandardCharsets.UTF_8)
        val captured = mutableListOf<String>()
        val tee = TeePrintStream(original) { line -> captured += line }

        tee.println("alpha")
        tee.println("beta")
        tee.println("gamma")

        assertEquals(listOf("alpha", "beta", "gamma"), captured)
        // Original stream still received the bytes verbatim.
        val mirrored = String(originalBytes.toByteArray(), StandardCharsets.UTF_8)
        assertEquals("alpha\nbeta\ngamma\n", mirrored.replace("\r\n", "\n"))
    }

    @Test
    fun `bytes accumulate across multiple writes until newline closes the line`() {
        val original = PrintStream(ByteArrayOutputStream())
        val captured = mutableListOf<String>()
        val tee = TeePrintStream(original) { line -> captured += line }

        tee.print("partial-")
        tee.print("then-")
        // No sink call yet — no newline.
        assertEquals(emptyList(), captured)
        tee.println("complete")
        assertEquals(listOf("partial-then-complete"), captured)
    }

    @Test
    fun `multiple newlines in a single write all become separate sink calls`() {
        val original = PrintStream(ByteArrayOutputStream())
        val captured = mutableListOf<String>()
        val tee = TeePrintStream(original) { line -> captured += line }

        tee.print("one\ntwo\nthree\n")
        assertEquals(listOf("one", "two", "three"), captured)
    }

    @Test
    fun `trailing CR before LF is stripped from delivered lines`() {
        val original = PrintStream(ByteArrayOutputStream())
        val captured = mutableListOf<String>()
        val tee = TeePrintStream(original) { line -> captured += line }

        tee.print("windows\r\nstyle\r\n")
        assertEquals(listOf("windows", "style"), captured)
    }

    @Test
    fun `empty lines surface as empty strings`() {
        val original = PrintStream(ByteArrayOutputStream())
        val captured = mutableListOf<String>()
        val tee = TeePrintStream(original) { line -> captured += line }

        tee.print("a\n\nb\n")
        assertEquals(listOf("a", "", "b"), captured)
    }
}
