package ksl.book.mcp

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * stdout is the MCP protocol channel; a single log line on it corrupts the
 * session. Guards that the bundled logback config only targets stderr.
 */
class LoggingConfigTest {

    @Test
    fun `bundled logback config exists and is stderr-only`() {
        val xml = javaClass.getResourceAsStream("/logback-ksl-book-mcp.xml")
            ?.bufferedReader()?.readText()
        assertNotNull(xml, "logback-ksl-book-mcp.xml missing from resources")
        assertTrue(xml.contains("<target>System.err</target>"), "appender must target System.err")
        assertTrue(!xml.contains("System.out"), "config must not reference System.out")
        // every ConsoleAppender must declare an explicit target (default is System.out)
        val appenders = Regex("<appender\\b[^>]*ConsoleAppender[^>]*>(.*?)</appender>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).toList()
        assertTrue(appenders.isNotEmpty())
        appenders.forEach {
            assertTrue(it.groupValues[1].contains("<target>System.err</target>"),
                "ConsoleAppender without explicit System.err target")
        }
    }
}
