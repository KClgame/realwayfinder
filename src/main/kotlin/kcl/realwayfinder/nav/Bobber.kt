package kcl.realwayfinder.nav

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

data class CastSolution(
	val stance: Node,
	val yaw: Float,
	val pitch: Float,
	val landX: Double,
	val landZ: Double
)

/**
 * 1.1.2 throw. Yaw faces the water under the text display.
 * Pitch is the simulated landing closest to that point, inside the radius-1 disk.
 */
private const val BOBBER_HALF = 0.125
private const val BOBBER_HEIGHT = 0.25
private const val CLEARANCE = 0.15
fun solveCast(grid: Grid, stance: Node, centerX: Double, centerZ: Double): CastSolution? =
	solveCastFrom(grid, stance, stance.x + 0.5, stance.y + 1.62, stance.z + 0.5, centerX, centerZ)

/** Same throw, from an exact eye position. Lily pads do not sit on the block center. */
fun solveCastFrom(
	grid: Grid,
	stance: Node,
	eyeX: Double,
	eyeY: Double,
	eyeZ: Double,
	centerX: Double,
	centerZ: Double,
	tried: List<Pair<Float, Float>> = emptyList()
): CastSolution? {
	val baseYaw = Math.toDegrees(atan2(-(centerX - eyeX), centerZ - eyeZ)).toFloat()
	var best: CastSolution? = null
	var bestDist = Double.MAX_VALUE
	for (yawOffset in intArrayOf(0, -8, 8, -16, 16)) {
		val yaw = baseYaw + yawOffset
		var pitch = -15
		while (pitch <= 75) {
			val pitched = pitch.toFloat()
			if (tried.any { kotlin.math.abs(wrapDegrees(yaw - it.first)) < 4f && kotlin.math.abs(pitched - it.second) < 4f }) {
				pitch += 3
				continue
			}
			val land = fly(grid, eyeX, eyeY, eyeZ, yaw, pitched, centerX, centerZ)
			if (land != null) {
				val dist = hypot(land.first - centerX, land.second - centerZ)
				if (dist < bestDist) {
					bestDist = dist
					best = CastSolution(stance, yaw, pitched, land.first, land.second)
				}
			}
			pitch += 3
		}
	}
	return best
}

/**
 * Eye at stance center + 1.62 must reach the text-display point without entering a solid or a fence.
 * Water does not block. The endpoint itself is not tested, so the display can sit against a block face.
 */
fun clearLook(grid: Grid, stance: Node, targetX: Double, targetY: Double, targetZ: Double): Boolean {
	val x0 = stance.x + 0.5
	val y0 = stance.y + 1.62
	val z0 = stance.z + 0.5
	val dx = targetX - x0
	val dy = targetY - y0
	val dz = targetZ - z0
	val length = sqrt(dx * dx + dy * dy + dz * dz)
	if (length < 1.0e-4) return true
	val steps = (length * 8.0).toInt().coerceAtLeast(1)
	for (i in 1 until steps) {
		val t = i / steps.toDouble()
		if (pointBlocked(grid, x0 + dx * t, y0 + dy * t, z0 + dz * t)) return false
	}
	return true
}

fun findCasts(
	grid: Grid,
	centerX: Double,
	centerY: Double,
	centerZ: Double,
	lookX: Double = centerX,
	lookY: Double = centerY,
	lookZ: Double = centerZ,
	radius: Int = 12
): List<CastSolution> {
	val found = ArrayList<CastSolution>()
	val cx = floor(centerX).toInt()
	val cz = floor(centerZ).toInt()
	val cy = floor(centerY).toInt()
	for (x in cx - radius..cx + radius) {
		for (z in cz - radius..cz + radius) {
			for (y in cy - 6..cy + 8) {
				if (!dryCastCell(grid, x, y, z)) continue
				val stance = Node(x, y, z, Mode.LAND)
				if (!clearLook(grid, stance, lookX, lookY, lookZ)) continue
				val solved = solveCast(grid, stance, centerX, centerZ) ?: continue
				found.add(solved)
			}
		}
	}
	return found.sortedBy { hypot(it.landX - centerX, it.landZ - centerZ) }
}

private fun fly(
	grid: Grid,
	eyeX: Double,
	eyeY: Double,
	eyeZ: Double,
	yaw: Float,
	pitch: Float,
	centerX: Double,
	centerZ: Double
): Pair<Double, Double>? {
	val yCos = cos(-yaw * (Math.PI / 180.0) - Math.PI)
	val ySin = sin(-yaw * (Math.PI / 180.0) - Math.PI)
	val xCos = -cos(-pitch * (Math.PI / 180.0))
	val xSin = sin(-pitch * (Math.PI / 180.0))
	var px = eyeX - ySin * 0.3
	var py = eyeY
	var pz = eyeZ - yCos * 0.3
	val rawY = (-(xSin / xCos)).coerceIn(-5.0, 5.0)
	var vx = -ySin
	var vy = rawY
	var vz = -yCos
	val dist = kotlin.math.sqrt(vx * vx + vy * vy + vz * vz)
	if (dist < 1.0e-6) return null
	val scale = 0.6 / dist + 0.5
	vx *= scale
	vy *= scale
	vz *= scale
	repeat(80) {
		if (castPathBlocked(grid, px, py, pz, centerX, centerZ)) return null
		if (bobberTouchesWater(grid, px, py, pz) && onTargetSurface(grid, px, py, pz, centerX, centerZ)) return px to pz
		vy -= 0.03
		val nx = px + vx
		val ny = py + vy
		val nz = pz + vz
		if (castPathCrosses(grid, px, py, pz, nx, ny, nz, centerX, centerZ)) return null
		px = nx
		py = ny
		pz = nz
		vx *= 0.92
		vy *= 0.92
		vz *= 0.92
	}
	return null
}

private fun bobberInsideDisk(x: Double, z: Double, centerX: Double, centerZ: Double): Boolean =
	hypot(x - centerX, z - centerZ) + BOBBER_HALF <= 1.0

/** The thrown bobber is inside one block of the water under the text display. */
fun bobberOnSpot(x: Double, z: Double, waterX: Double, waterZ: Double): Boolean =
	hypot(x - waterX, z - waterZ) <= 1.0

private fun bobberBlocked(grid: Grid, x: Double, y: Double, z: Double): Boolean {
	val xs = doubleArrayOf(x - CLEARANCE, x, x + CLEARANCE)
	val ys = doubleArrayOf(y + 0.001, y + BOBBER_HEIGHT * 0.5, y + BOBBER_HEIGHT - 0.001)
	val zs = doubleArrayOf(z - CLEARANCE, z, z + CLEARANCE)
	for (sx in xs) for (sy in ys) for (sz in zs) {
		if (pointBlocked(grid, sx, sy, sz)) return true
	}
	return false
}

private fun bobberTouchesWater(grid: Grid, x: Double, y: Double, z: Double): Boolean {
	val py = y + 0.04
	val xs = doubleArrayOf(x - BOBBER_HALF + 0.001, x + BOBBER_HALF - 0.001)
	val zs = doubleArrayOf(z - BOBBER_HALF + 0.001, z + BOBBER_HALF - 0.001)
	for (sx in xs) for (sz in zs) {
		if (grid.get(floor(sx).toInt(), floor(py).toInt(), floor(sz).toInt()) == Cell.WATER) return true
	}
	return false
}

/** Solids, flowing water, a water column, or any water that is not the target surface. */
private fun castPathBlocked(grid: Grid, x: Double, y: Double, z: Double, centerX: Double, centerZ: Double): Boolean {
	if (bobberBlocked(grid, x, y, z)) return true
	val xs = doubleArrayOf(x - BOBBER_HALF + 0.001, x + BOBBER_HALF - 0.001)
	val ys = doubleArrayOf(y + 0.001, y + BOBBER_HEIGHT * 0.5)
	val zs = doubleArrayOf(z - BOBBER_HALF + 0.001, z + BOBBER_HALF - 0.001)
	for (sx in xs) for (sy in ys) for (sz in zs) {
		val bx = floor(sx).toInt()
		val by = floor(sy).toInt()
		val bz = floor(sz).toInt()
		val cell = grid.get(bx, by, bz)
		if (cell == Cell.FLOW) return true
		if (cell != Cell.WATER) continue
		val above = grid.get(bx, by + 1, bz)
		if (above == Cell.WATER || above == Cell.FLOW) return true
		if (!onTargetSurface(grid, sx, sy, sz, centerX, centerZ)) return true
	}
	return false
}

private fun onTargetSurface(grid: Grid, x: Double, y: Double, z: Double, centerX: Double, centerZ: Double): Boolean {
	val bx = floor(x).toInt()
	val by = floor(y).toInt()
	val bz = floor(z).toInt()
	if (grid.get(bx, by, bz) != Cell.WATER) return false
	val above = grid.get(bx, by + 1, bz)
	if (above == Cell.WATER || above == Cell.FLOW) return false
	return bobberInsideDisk(x, z, centerX, centerZ)
}

private fun castPathCrosses(
	grid: Grid,
	x0: Double,
	y0: Double,
	z0: Double,
	x1: Double,
	y1: Double,
	z1: Double,
	centerX: Double,
	centerZ: Double
): Boolean {
	val steps = 4
	for (i in 1..steps) {
		val t = i / steps.toDouble()
		if (castPathBlocked(grid, x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, z0 + (z1 - z0) * t, centerX, centerZ)) return true
	}
	return false
}
