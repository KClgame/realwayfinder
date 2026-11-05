package kcl.realwayfinder.nav

enum class Mode {
	LAND,
	FLOAT,
	SWIM
}

enum class EdgeKind {
	WALK,
	JUMP,
	SPRINT_JUMP,
	FALL,
	FLOAT,
	SWIM,
	SHORE
}

data class Node(val x: Int, val y: Int, val z: Int, val mode: Mode)

fun Cell.blocksBody(): Boolean = this == Cell.SOLID || this == Cell.TALL || this == Cell.UNKNOWN

/** Vanilla lily pad collision is 1.5 pixels. Feet rest on this, inside the pad's own cell. */
const val LILY_HEIGHT = 1.5 / 16.0

fun isLand(grid: Grid, x: Int, y: Int, z: Int): Boolean {
	if (grid.get(x, y, z) == Cell.LILY || grid.get(x, y, z) == Cell.SLAB) {
		return !grid.get(x, y + 1, z).blocksBody() &&
			!tallReaches(grid, x, y, z) &&
			!tallReaches(grid, x, y + 1, z)
	}
	return grid.get(x, y, z) == Cell.AIR &&
		grid.get(x, y + 1, z) == Cell.AIR &&
		grid.get(x, y - 1, z) == Cell.SOLID &&
		!tallReaches(grid, x, y, z) &&
		!tallReaches(grid, x, y + 1, z)
}

/** A tall block in the cell below sticks 0.5 into this cell. */
fun tallReaches(grid: Grid, x: Int, y: Int, z: Int): Boolean = grid.get(x, y - 1, z) == Cell.TALL

/** Body interval (y0, y1) overlaps this cell's collision. A fence occupies [y, y + 1.5). */
fun bodyHits(grid: Grid, bx: Int, by: Int, bz: Int, y0: Double, y1: Double): Boolean {
	val (bottom, top) = when (grid.get(bx, by, bz)) {
		Cell.SOLID, Cell.UNKNOWN -> by.toDouble() to by + 1.0
		Cell.TALL -> by.toDouble() to by + 1.5
		Cell.LILY -> by.toDouble() to by + LILY_HEIGHT
		Cell.SLAB -> by.toDouble() to by + 0.5
		else -> return false
	}
	return y1 > bottom && y0 < top
}

fun pointBlocked(grid: Grid, x: Double, y: Double, z: Double): Boolean {
	val bx = kotlin.math.floor(x).toInt()
	val by = kotlin.math.floor(y).toInt()
	val bz = kotlin.math.floor(z).toInt()
	return bodyHits(grid, bx, by, bz, y, y + 1.0e-4) ||
		bodyHits(grid, bx, by - 1, bz, y, y + 1.0e-4)
}

/** A fishing stance: dry ground. The feet are not in a water block, and not on a lily over water. */
fun dryCastCell(grid: Grid, x: Int, y: Int, z: Int): Boolean {
	if (!isLand(grid, x, y, z)) return false
	if (grid.get(x, y, z) == Cell.WATER || grid.get(x, y, z) == Cell.LILY) return false
	if (grid.get(x, y + 1, z) == Cell.WATER) return false
	return true
}

/** Top water cell in the column, or null when the column has no water. */
fun topWater(grid: Grid, x: Int, z: Int): Int? {
	var top: Int? = null
	var y = grid.originY + grid.sizeY - 1
	val bottom = grid.originY
	while (y >= bottom) {
		if (grid.get(x, y, z) == Cell.WATER) {
			top = y
			break
		}
		y--
	}
	if (top == null) return null
	var above = top + 1
	while (grid.get(x, above, z) == Cell.WATER) {
		top = above
		above++
	}
	return top
}

/** Water-surface Y minus the bottom of the water column. Swim needs this to be greater than 2. */
fun waterDepth(grid: Grid, x: Int, z: Int): Int {
	val top = topWater(grid, x, z) ?: return 0
	var bottom = top
	while (grid.get(x, bottom - 1, z) == Cell.WATER) bottom--
	return (top + 1) - bottom
}

fun floatNode(grid: Grid, x: Int, z: Int): Node? {
	val top = topWater(grid, x, z) ?: return null
	val surface = top + 1
	if (grid.get(x, surface, z) != Cell.AIR || tallReaches(grid, x, surface, z)) return null
	if (grid.get(x, surface + 1, z).blocksBody() || tallReaches(grid, x, surface + 1, z)) return null
	return Node(x, surface, z, Mode.FLOAT)
}

fun isSwim(grid: Grid, x: Int, y: Int, z: Int): Boolean =
	grid.get(x, y, z) == Cell.WATER && grid.get(x, y + 1, z) == Cell.WATER

/**
 * The water touching this column is walled in on every side, including above.
 * Air, a lily, or a slab is an opening. Water that runs off the searched box is not sealed.
 */
fun sealedWater(grid: Grid, x: Int, y: Int, z: Int, radius: Int = 12): Boolean {
	var startY = y
	while (startY >= grid.originY && grid.get(x, startY, z) != Cell.WATER) startY--
	if (grid.get(x, startY, z) != Cell.WATER) return false
	val seen = HashSet<Long>()
	val queue = ArrayDeque<IntArray>()
	queue.add(intArrayOf(x, startY, z))
	var count = 0
	while (queue.isNotEmpty()) {
		val cell = queue.removeFirst()
		val cx = cell[0]
		val cy = cell[1]
		val cz = cell[2]
		val key = (cx.toLong() shl 42) xor (cy.toLong() shl 21) xor cz.toLong()
		if (!seen.add(key)) continue
		count++
		if (count > 400) return false
		if (kotlin.math.abs(cx - x) > radius || kotlin.math.abs(cy - startY) > radius || kotlin.math.abs(cz - z) > radius) return false
		for (dir in SEALED_NEIGHBORS) {
			val nx = cx + dir[0]
			val ny = cy + dir[1]
			val nz = cz + dir[2]
			when (grid.get(nx, ny, nz)) {
				Cell.WATER -> queue.add(intArrayOf(nx, ny, nz))
				Cell.SOLID, Cell.TALL -> Unit
				else -> return false
			}
		}
	}
	return seen.isNotEmpty()
}

private val SEALED_NEIGHBORS = arrayOf(
	intArrayOf(1, 0, 0), intArrayOf(-1, 0, 0),
	intArrayOf(0, 1, 0), intArrayOf(0, -1, 0),
	intArrayOf(0, 0, 1), intArrayOf(0, 0, -1)
)

fun anchor(grid: Grid, x: Int, y: Int, z: Int): Node? {
	if (isLand(grid, x, y, z)) return Node(x, y, z, Mode.LAND)
	if (isSwim(grid, x, y, z)) return Node(x, y, z, Mode.SWIM)
	floatNode(grid, x, z)?.let { if (it.y == y) return it }
	for (dy in 1..6) {
		if (isLand(grid, x, y - dy, z)) return Node(x, y - dy, z, Mode.LAND)
	}
	return floatNode(grid, x, z)
}
