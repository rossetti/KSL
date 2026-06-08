package ksl.utilities.io

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Phase 3b — Map round trip for [ExcelUtil.writeToExcel] / [ExcelUtil.readToMap].
 *
 * Exercises ordinary doubles plus the three "problematic" double values
 * (NaN, +Infinity, -Infinity) that the implementation encodes as strings.
 * Also covers header on/off and empty maps.
 */
class ExcelUtilMapRoundTripTest {

    @Test
    fun `round trip ordinary doubles no header`(@TempDir tmp: Path) {
        val map = linkedMapOf("alpha" to 1.5, "beta" to -2.25, "gamma" to 0.0)
        ExcelUtil.writeToExcel(map, sheetName = "MapTest", wbDirectory = tmp, header = false)

        val back = ExcelUtil.readToMap("MapTest", pathToWorkbook = tmp.resolve("MapTest.xlsx"))

        assertEquals(map.size, back.size)
        for ((k, v) in map) {
            assertEquals(v, back[k]!!, 0.0, "value for key $k")
        }
    }

    @Test
    fun `round trip with header skips header on read`(@TempDir tmp: Path) {
        val map = mapOf("x" to 42.0, "y" to 7.0)
        ExcelUtil.writeToExcel(map, sheetName = "MapHeader", wbDirectory = tmp, header = true)

        val back = ExcelUtil.readToMap(
            "MapHeader",
            pathToWorkbook = tmp.resolve("MapHeader.xlsx"),
            skipFirstRow = true
        )

        assertEquals(map, back)
    }

    @Test
    fun `round trip preserves NaN and infinities`(@TempDir tmp: Path) {
        val map = linkedMapOf(
            "nan" to Double.NaN,
            "posInf" to Double.POSITIVE_INFINITY,
            "negInf" to Double.NEGATIVE_INFINITY,
            "ordinary" to 3.14
        )
        ExcelUtil.writeToExcel(map, "SpecialDoubles", wbDirectory = tmp)
        val back = ExcelUtil.readToMap("SpecialDoubles", pathToWorkbook = tmp.resolve("SpecialDoubles.xlsx"))

        assertEquals(4, back.size)
        assertTrue(back["nan"]!!.isNaN(), "NaN must round trip as NaN")
        assertEquals(Double.POSITIVE_INFINITY, back["posInf"])
        assertEquals(Double.NEGATIVE_INFINITY, back["negInf"])
        assertEquals(3.14, back["ordinary"]!!, 0.0)
    }

    @Test
    fun `empty map round trips to empty map`(@TempDir tmp: Path) {
        ExcelUtil.writeToExcel(emptyMap(), "Empty", wbDirectory = tmp)
        val back = ExcelUtil.readToMap("Empty", pathToWorkbook = tmp.resolve("Empty.xlsx"))
        assertTrue(back.isEmpty())
    }

    @Test
    fun `missing sheet returns empty map`(@TempDir tmp: Path) {
        ExcelUtil.writeToExcel(mapOf("a" to 1.0), "WrittenSheet", wbDirectory = tmp)
        val back = ExcelUtil.readToMap(
            "NoSuchSheet",
            pathToWorkbook = tmp.resolve("WrittenSheet.xlsx")
        )
        assertTrue(back.isEmpty())
    }
}
