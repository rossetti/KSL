package ksl.modeling.spatial

import ksl.simulation.ModelElement
import ksl.utilities.to2DArray
import java.awt.Shape
import java.awt.geom.GeneralPath
import java.awt.geom.Line2D
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D

/**
 * Creates a grid in the 2D plane. The grid is based on the standard user
 * coordinate system, with (x,y) = (0,0) being the upper left most corner
 * point, with the x-axis going from left to right and the y-axis going from
 * the top down
 *
 * @param upperX The x coordinate of the upper left most corner point
 * @param upperY The y coordinate of the upper left most corner point
 * @param width The width (along the x-axis) of the grid
 * @param height The height (along the y-axis) of the grid
 * @param numRows The number of rows in the grid (0-based)
 * @param numCols The number of columns in the grid (0-based)
 */
class RectangularGridSpatialModel2D(
    modelElement: ModelElement,
    width: Double = Double.MAX_VALUE,
    height: Double = Double.MAX_VALUE,
    numRows: Int = 1,
    numCols: Int = 1,
    upperX: Double = 0.0,
    upperY: Double = 0.0,
) : SpatialModel(modelElement) {

    init {
        require(numRows >= 1) { "The number of rows must be >=1" }
        require(numCols >= 1) { "The number of rows must be >=1" }
        require(width > 0.0) { "The width must be > 0.0" }
        require(height > 0.0) { "The height must be > 0.0" }
    }

    /**
     * The width of the grid in user dimensions
     */
    private val myWidth = width

    /**
     * The height of the grid in user dimensions
     */
    private val myHeight = height

    /**
     * A reference to the rectangle that forms the outer edge of the grid
     */
    private val myOuterRectangle: Rectangle2D = Rectangle2D.Double(upperX, upperY, width, height)

    /**
     * The number of rows in the grid, the grid is zero based (the first row is
     * the 0th row)
     */
    private val myNumRows = numRows

    /**
     * The number of columns in the grid, the grid is zero based (the first
     * column is the 0th column)
     */
    private val myNumCols = numCols

    /**
     * The width of the cells in the grid
     */
    private val myCellWidth = myWidth / numCols

    /**
     * The height of the cells in the grid
     */
    private val myCellHeight = myHeight / numRows

    /**
     * An 2-d array of points forming the grid, point[0][0] = left upper corner
     * point
     */
    private var myPoints: Array<Array<Point2D.Double>>

    init {
        var y = upperY
        val oList = mutableListOf<MutableList<Point2D.Double>>()
        for (i in 0..myNumRows) {
            var x = upperX
            val list = mutableListOf<Point2D.Double>()
            for (j in 0..myNumCols) {
                list.add(Point2D.Double(x, y))
                x = x + myCellWidth
            }
            y = y + myCellHeight
            oList.add(list)
        }
        myPoints = to2DArray(oList)
    }

    /**
     * The upper left corner point for the grid
     */
    private var myUpperLeftCornerPt: Point2D = myPoints[0][0]

    /**
     * The lower left corner point for the grid
     */
    private var myLowerLeftCornerPt: Point2D = myPoints[myNumRows][0]

    /**
     * The upper right corner point for the grid
     */
    private var myUpperRightCornerPt: Point2D = myPoints[0][myNumCols]

    /**
     * The lower right corner point for the grid
     */
    private var myLowerRightCornerPt: Point2D = myPoints[myNumRows][myNumCols]

    /**
     * The line at the top of the grid
     */
    private var myTopLine: Line2D = Line2D.Double(myUpperLeftCornerPt, myUpperRightCornerPt)

    /**
     * The line at the bottom of the grid
     */
    private var myBottomLine: Line2D = Line2D.Double(myLowerLeftCornerPt, myLowerRightCornerPt)

    /**
     * The line at the right side of the grid
     */
    private var myRightLine: Line2D = Line2D.Double(myUpperRightCornerPt, myLowerRightCornerPt)

    /**
     * The line at the left side of the grid
     */
    private var myLeftLine: Line2D = Line2D.Double(myUpperLeftCornerPt, myLowerLeftCornerPt)

    /**
     * A reference to the path that forms the grid including all line segments
     */
    private var myPath: GeneralPath = GeneralPath()

    /**
     * An 2-d array of the horizontal line segments in the grid hline[0][0] =
     * point[0][0] -- point[0][1] and so forth
     */
    private var myHorzLines: Array<Array<Line2D>> = Array(myNumRows + 1) { i ->
        Array(myNumCols) { j ->
            Line2D.Double(myPoints[i][j], myPoints[i][j + 1])
        }
    }

    /**
     * An 2-d array of the vertical line segments in the grid vline[0][0] =
     * point[0][0] -- point[1][0] and so forth
     */
    private var myVertLines: Array<Array<Line2D>> = Array(myNumCols + 1) { j ->
        Array(myNumRows) { i ->
            Line2D.Double(myPoints[i][j], myPoints[i + 1][j])
        }
    }

    /**
     * An 2-d array of the cells forming the grid cell[0][0] = upper left most
     * cell with left corner point point[0][0]
     */
    private var myCells: Array<Array<RectangularCell2D>> = Array(myNumRows) { row ->
        Array(myNumCols) { col ->
            RectangularCell2D(row, col)
        }
    }

    /**
     * A List of the cells in the grid formed row by row
     */
    private var myCellList: ArrayList<RectangularCell2D> = ArrayList(myNumRows * myNumCols)

    init {
        // make the vertical line segments
        // make the cells from the points and lines
        for (i in 0 until myNumRows) {
            for (j in 0 until myNumCols) {
                myCellList.add(myCells[i][j])
            }
        }
        // set the initial point on the path
        myPath.moveTo(myUpperLeftCornerPt.x.toFloat(), myUpperLeftCornerPt.y.toFloat())
        // add the horizontal lines
        for (i in 0 until myNumRows + 1) {
            for (j in 0 until myNumCols) {
                myPath.append(myHorzLines[i][j], false)
            }
        }
        // add the vertical lines
        for (j in 0 until myNumCols + 1) {
            for (i in 0 until myNumRows) {
                myPath.append(myVertLines[i][j], false)
            }
        }
        // close the path
        myPath.closePath()

    }

    /**
     * Checks if the x and y values are in the grid
     *
     * @param x the x-coordinate
     * @param y the y-coordinate
     * @return true if in the grid
     */
    fun contains(x: Double, y: Double): Boolean {
        val x0 = myOuterRectangle.x
        val y0 = myOuterRectangle.y
        return (x >= x0) && (y >= y0) && (x <= (x0 + myWidth)) && (y <= (y0 + myHeight))
    }

    /**
     * Returns the AWT shape representation
     *
     * @return
     */
    fun shape(): Shape {
        return myPath
    }

    /** The row major index is row(number of columns) + col + 1
     * Labeling starts at 1 and goes by row (across columns). For example
     * for a 3 by 3 grid
     * [1, 2, 3]
     * [4, 5, 6]
     * [7, 8, 9]
     * @param row the row
     * @param col the column
     * @return the row major index of the cell
     */
    fun getRowMajorIndex(row: Int, col: Int): Int {
        require((row < 0 || (row) < myNumRows)) { "row was < 0 or >= #rows" }
        require((col < 0 || (col) < myNumCols)) { "col was < 0 or >= #cols" }
        return row * myNumCols + col + 1
    }

    override fun distance(fromLocation: LocationIfc, toLocation: LocationIfc): Double {
        TODO("Not yet implemented")
    }

    override fun compareLocations(firstLocation: LocationIfc, secondLocation: LocationIfc): Boolean {
        TODO("Not yet implemented")
    }


    /**
     * The cell at this row, col. Null is returned if
     * the row or column is outside the grid.
     *
     * @param row the row
     * @param col the column
     * @return the cell or null
     */
    fun cell(row: Int, col: Int): RectangularCell2D? {
        return if ((row < 0) || (row >= myNumRows)) {
            null
        } else if ((col < 0) || (col >= myNumCols)) {
            null
        } else {
            myCells[row][col]
        }
    }

    /**
     * Returns an array with the 1st order Moore neighborhood for the
     * given core cell.
     *
     * set the top row of the neighborhood neighborhood[0][0] = getCell(i-1,
     * j-1) neighborhood[0][1] = getCell(i-1, j) neighborhood[0][2] =
     * getCell(i-1, j+1)
     *
     * set the middle row of the neighborhood neighborhood[1][0] = getCell(i,
     * j-1) neighborhood[1][1] = getCell(i, j) neighborhood[1][2] = getCell(i,
     * j+1)
     *
     * set the bottom row of the neighborhood neighborhood[2][0] =
     * getCell(i+1,j-1) neighborhood[2][1] = getCell(i+1, j) neighborhood[2][2]
     * = getCell(i+1, j+1)
     *
     * @param coreCell
     */
    fun mooreNeighborhood(coreCell: RectangularCell2D): Array<Array<RectangularCell2D?>> {
        // get the core cell's indices
        val i: Int = coreCell.rowIndex
        val j: Int = coreCell.columnIndex
        val n = arrayOf(
            arrayOf(cell(i - 1, j - 1), cell(i - 1, j), cell(i - 1, j + 1)),
            arrayOf(cell(i, j - 1), cell(i, j), cell(i, j + 1)),
            arrayOf(cell(i + 1, j - 1), cell(i + 1, j), cell(i + 1, j + 1))
        )
        return n
    }

    /**
     * Fills the supplied array with the 1st order Moore neighborhood for the
     * given core cell.
     *
     * set the top row of the neighborhood neighborhood[0][0] = getCell(i-1,
     * j-1) neighborhood[0][1] = getCell(i-1, j) neighborhood[0][2] =
     * getCell(i-1, j+1)
     *
     * set the middle row of the neighborhood neighborhood[1][0] = getCell(i,
     * j-1) neighborhood[1][1] = getCell(i, j) neighborhood[1][2] = getCell(i,
     * j+1)
     *
     * set the bottom row of the neighborhood neighborhood[2][0] =
     * getCell(i+1,j-1) neighborhood[2][1] = getCell(i+1, j) neighborhood[2][2]
     * = getCell(i+1, j+1)
     *
     * @param coreCell
     * @param neighborhood
     */
    fun getMooreNeighborhood(
        coreCell: RectangularCell2D,
        neighborhood: Array<Array<RectangularCell2D?>>
    ) {
        require(neighborhood.size >= 3) { "Row size of the array must be 3." }
        for (i in neighborhood.indices) {
            require(neighborhood[i].size >= 3) { "Column size of the array must be 3." }
        }

        // nullify the neighborhood
        for (i in 0..2) {
            for (j in 0..2) {
                neighborhood[i][j] = null
            }
        }

        // get the core cell's indices
        val i: Int = coreCell.rowIndex
        val j: Int = coreCell.columnIndex

        // set the top row of the neighborhood

        // set the top row of the neighborhood
        neighborhood[0][0] = cell(i - 1, j - 1)
        neighborhood[0][1] = cell(i - 1, j)
        neighborhood[0][2] = cell(i - 1, j + 1)

        // set the middle row of the neighborhood

        // set the middle row of the neighborhood
        neighborhood[1][0] = cell(i, j - 1)
        neighborhood[1][1] = cell(i, j)
        neighborhood[1][2] = cell(i, j + 1)

        // set the bottom row of the neighborhood

        // set the bottom row of the neighborhood
        neighborhood[2][0] = cell(i + 1, j - 1)
        neighborhood[2][1] = cell(i + 1, j)
        neighborhood[2][2] = cell(i + 1, j + 1)
    }

//    /**
//     * Returns a list containing the 1st Moore neighborhood for the cell at row,
//     * col in the grid
//     *
//     * @param coreCell the core cell
//     * @param includeCore true includes the core in the list, false does not
//     * @return the list
//     */
//    fun getMooreNeighborhoodAsList(
//        coreCell: RectangularCell2D?,
//        includeCore: Boolean
//    ): List<RectangularCell2D> {
//        return getMooreNeighborhoodAsList(getMooreNeighborhood(coreCell!!), includeCore)
//    }
//
//    /**
//     * Finds the cell that has the least number of spatial elements
//     *
//     * @param coreCell the core cell
//     * @param includeCore true includes the core in the list, false does not
//     * @return the minimum cell or null
//     */
//    fun findCellWithMinimumElementsInNeighborhood(
//        coreCell: RectangularCell2D?, includeCore: Boolean
//    ): RectangularCell2D {
//        return findCellWithMinimumElements(getMooreNeighborhoodAsList(coreCell, includeCore))
//    }

    inner class RectangularCell2D(row: Int, col: Int) {
        /**
         * @return Returns the cell's Row Index.
         */
        val rowIndex: Int = row

        /**
         * @return Returns the column index
         */
        val columnIndex: Int = col

        /**
         * @return Returns the cell's Width.
         */
        val width: Double = myCellWidth

        /**
         * @return Returns the height of the cell
         */
        val height: Double = myCellHeight

        private val myRectangle: Rectangle2D =
            Rectangle2D.Double(myPoints[row][col].x, myPoints[row][col].y, width, height)

        /**
         * Can be used to check if the cell is available or not. For example, this
         * can be used to see if the cell is available for traversal.
         *
         * @return true means available
         */
        var isAvailable: Boolean = false
            private set

        /**
         *
         * @return the number of spatial elements in the cell
         */
        val numSpatialElements: Int
            get() = mySpatialElements.size

        private val mySpatialElements: MutableList<SpatialElementIfc> = mutableListOf()

    }

    companion object {
        /**
         * Finds the cell that has the least number of spatial elements
         *
         * @param cells the cells to search, must not be empty
         * @return the minimum cell
         */
        fun findCellWithMinimumElements(cells: List<RectangularCell2D>): RectangularCell2D {
            require(cells.isNotEmpty()) { "The supplied list of cells was empty!" }
            var min = Int.MAX_VALUE
            var minCell: RectangularCell2D? = null
            for (cell in cells) {
                if (cell.numSpatialElements < min) {
                    min = cell.numSpatialElements
                    minCell = cell
                }
            }
            return minCell!!
        }

        /**
         * Across all the cells, what is the minimum number of elements in cells
         *
         * @param cells cells to search, must not be empty
         * @return the minimum
         */
        fun findMinimumNumberOfElements(cells: List<RectangularCell2D>): Int {
            require(cells.isNotEmpty()) { "The supplied list of cells was empty!" }
            var min = Int.MAX_VALUE
            for (cell in cells) {
                if (cell.numSpatialElements < min) {
                    min = cell.numSpatialElements
                }
            }
            return min
        }

        /**
         * Across all the cells, what is the maximum number of elements in cells
         *
         * @param cells cells to search, must not be empty
         * @return the maximum
         */
        fun findMaximumNumberOfElements(cells: List<RectangularCell2D>): Int {
            require(cells.isNotEmpty()) { "The supplied list of cells was empty!" }
            var max = Int.MIN_VALUE
            for (cell in cells) {
                if (cell.numSpatialElements > max) {
                    max = cell.numSpatialElements
                }
            }
            return max
        }

        /**
         * Across all the cells, which cells have the minimum number of elements in
         * cells
         *
         * @param cells the cells to search, must not be empty
         * @return a list of cells that have the minimum number of elements
         */
        fun findCellsWithMinimumElements(cells: List<RectangularCell2D>): List<RectangularCell2D> {
            require(cells.isNotEmpty()) { "The supplied list of cells was empty!" }
            val list = mutableListOf<RectangularCell2D>()
            val min = findMinimumNumberOfElements(cells)
            for (cell in cells) {
                if (cell.numSpatialElements == min) {
                    list.add(cell)
                }
            }
            return list
        }

        /**
         * Across all the cells, which cells have the maximum number of elements in
         * cells
         *
         * @param cells the cells to search, must not be empty
         * @return a list of cells that have the maximum number of elements
         */
        fun findCellsWithMaximumElements(cells: List<RectangularCell2D>): List<RectangularCell2D> {
            require(cells.isNotEmpty()) { "The supplied list of cells was empty!" }
            val list = mutableListOf<RectangularCell2D>()
            val max = findMaximumNumberOfElements(cells)
            for (cell in cells) {
                if (cell.numSpatialElements == max) {
                    list.add(cell)
                }
            }
            return list
        }

        /**
         * A comparator based on the number of elements in the cell
         *
         * @return A comparator based on the number of elements in the cell
         */
        fun numElementsComparator(): NumElementsComparator {
            return NumElementsComparator()
        }

        /**
         * Returns list of the cells sorted from smallest to largest based on
         * the number of spacial elements in the cells
         *
         * @param cells the cells to sort
         * @return a new list of the sorted cells
         */
        fun sortCellsByNumElements(cells: List<RectangularCell2D>): List<RectangularCell2D> {
            return cells.sortedWith(numElementsComparator())
        }

    }
}

class NumElementsComparator : Comparator<RectangularGridSpatialModel2D.RectangularCell2D> {
    override fun compare(
        o1: RectangularGridSpatialModel2D.RectangularCell2D,
        o2: RectangularGridSpatialModel2D.RectangularCell2D
    ): Int {
        if (o1.numSpatialElements < o2.numSpatialElements) {
            return -1
        }
        return if (o1.numSpatialElements > o2.numSpatialElements) {
            1
        } else 0
    }
}