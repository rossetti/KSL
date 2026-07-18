package ksl.app.servers

import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.awt.GraphicsEnvironment
import javax.swing.SwingUtilities
import kotlin.test.assertNotNull

class ServerManagerFrameTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun skipIfHeadless() {
            Assumptions.assumeFalse(
                GraphicsEnvironment.isHeadless(),
                "Headless JVM — Swing frame smoke test requires a display",
            )
        }
    }

    @Test
    @DisplayName("the manager frame constructs and disposes without throwing")
    fun frameConstructsWithoutThrowing() {
        var frame: ServerManagerFrame? = null
        try {
            SwingUtilities.invokeAndWait { frame = ServerManagerFrame(ServerManagerController("SmokeTest")) }
            assertNotNull(frame)
        } finally {
            SwingUtilities.invokeAndWait { frame?.dispose() }
        }
    }
}
