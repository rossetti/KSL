/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.utilities.io

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets

/**
 * [PrintStream] that mirrors every byte to a wrapped [delegate] *and*
 * buffers the bytes into a line-completion buffer.  On every newline
 * boundary the buffered text is decoded as UTF-8 and passed to
 * [lineSink].  A trailing `\r` (Windows line ending) is stripped before
 * the sink call.
 *
 * Thread-safe: writes synchronize on an internal lock so concurrent
 * writes from multiple threads cannot interleave bytes inside the line
 * buffer.  [lineSink] is invoked from whichever thread completed the
 * line.  The sink is responsible for any further dispatching it needs
 * (e.g. onto a UI thread).
 *
 * Substrate-level JVM utility — usable by any host that needs to tee
 * a [PrintStream] line-by-line.  Pairs with [StdoutCapture] for the
 * common `System.out` / `System.err` capture case.
 */
class TeePrintStream(
    private val delegate: PrintStream,
    private val lineSink: (String) -> Unit
) : PrintStream(TeeOutputStream(delegate, lineSink), true /* autoFlush */, StandardCharsets.UTF_8) {

    override fun close() {
        // Flush any pending partial line before closing.
        flush()
        super.close()
    }

    /**
     * Composite [OutputStream] that splits each `write` between
     * the wrapped [delegate] (verbatim) and a [lineBuf] that
     * accumulates bytes until a newline, at which point the
     * decoded line is handed to [lineSink].
     */
    private class TeeOutputStream(
        private val delegate: PrintStream,
        private val lineSink: (String) -> Unit
    ) : OutputStream() {

        private val lock: Any = Any()
        private val lineBuf: ByteArrayOutputStream = ByteArrayOutputStream(256)

        override fun write(b: Int) {
            synchronized(lock) {
                delegate.write(b)
                if (b == '\n'.code) {
                    flushLine()
                } else {
                    lineBuf.write(b)
                }
            }
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            synchronized(lock) {
                delegate.write(b, off, len)
                var i = off
                val end = off + len
                while (i < end) {
                    val nl = findNewline(b, i, end)
                    if (nl < 0) {
                        // No newline in this remaining segment; buffer it.
                        lineBuf.write(b, i, end - i)
                        break
                    } else {
                        // Append bytes up to (but not including) the newline.
                        if (nl > i) lineBuf.write(b, i, nl - i)
                        flushLine()
                        i = nl + 1
                    }
                }
            }
        }

        override fun flush() {
            // Do not flush partial lines — wait for the actual newline.
            // Delegate is already flushed by the parent PrintStream.
            delegate.flush()
        }

        private fun findNewline(b: ByteArray, from: Int, end: Int): Int {
            for (i in from until end) if (b[i] == '\n'.code.toByte()) return i
            return -1
        }

        private fun flushLine() {
            val bytes = lineBuf.toByteArray()
            lineBuf.reset()
            // Strip trailing \r (CRLF line endings).
            val effectiveLen =
                if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1
                else bytes.size
            val text = String(bytes, 0, effectiveLen, StandardCharsets.UTF_8)
            // Invoke sink outside the lock would be cleaner, but the
            // synchronized block here is the same one that ordered the
            // write — keeping it inside guarantees lines are delivered
            // in the order they were written.  Sinks are expected to be
            // cheap (e.g. a UI-dispatcher invokeLater call).
            lineSink(text)
        }
    }
}
