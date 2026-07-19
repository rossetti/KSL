package ksl.server.tray

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InstallPathsTest {

    @AfterEach
    fun clearProps() {
        System.clearProperty("ksl.suite.launcher")
        System.clearProperty("ksl.server.launcher")
    }

    @Test
    @DisplayName("resolves the explicit -Dksl.suite.launcher property when the file exists")
    fun resolvesExplicitProperty(@TempDir tmp: Path) {
        val f = Files.createFile(tmp.resolve("ksl-suite"))
        System.setProperty("ksl.suite.launcher", f.toString())
        assertEquals(f, InstallPaths.suiteLauncher())
    }

    @Test
    @DisplayName("is null when the property points at a missing file and there is no jar sibling (dev run)")
    fun nullWhenMissing() {
        System.setProperty("ksl.suite.launcher", "/no/such/dir/ksl-suite")
        // Tests run from compiled classes (no jar), so the sibling fallback also yields nothing.
        assertNull(InstallPaths.suiteLauncher())
    }
}
