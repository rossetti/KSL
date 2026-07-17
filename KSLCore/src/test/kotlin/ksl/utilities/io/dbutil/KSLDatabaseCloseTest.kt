package ksl.utilities.io.dbutil

import ksl.utilities.io.KSLFileUtil
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * close() must release an embedded KSL database's resources so its files can be deleted or
 * replaced. This is the Windows failure mode a full test run exposes: a database left open holds
 * a file handle, and Windows refuses to delete a temp dir that still contains an open file (Unix
 * silently tolerates it, which is why the leak is invisible on macOS/Linux).
 */
class KSLDatabaseCloseTest {

    @TempDir lateinit var dir: Path

    @Test
    fun `closing an SQLite database closes its connection and releases the file`() {
        val db = KSLDatabase.createSQLiteKSLDatabase("sqliteClose", dir)
        KSLDatabase(db)                                   // exercises the real schema path
        assertFalse(db.longLastingConnection.isClosed, "the connection is open before close()")
        db.close()
        assertTrue(db.longLastingConnection.isClosed, "the connection is closed after close()")   // all platforms
        assertTrue(Files.deleteIfExists(dir.resolve("sqliteClose")), "the file is deletable after close()") // Windows
    }

    @Test
    fun `closing a Derby database shuts the engine and releases its files`() {
        val db = KSLDatabase.createEmbeddedDerbyKSLDatabase("derbyClose", dir)
        KSLDatabase(db)
        db.close()
        val dbDir = dir.resolve("derbyClose")             // Derby stores a database as a directory
        KSLFileUtil.deleteDirectory(dbDir.toFile())
        assertTrue(!Files.exists(dbDir), "the Derby database dir is removable after close()")
    }

    @Test
    fun `close is idempotent and reachable through the KSLDatabase delegate`() {
        val kdb = KSLDatabase(KSLDatabase.createSQLiteKSLDatabase("idem", dir))
        kdb.close()   // KSLDatabase.close() delegates to the underlying Database via DatabaseIOIfc
        kdb.close()   // second call must be a no-op, not throw
    }
}
