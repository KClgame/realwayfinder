package kcl.realwayfinder.nav

import kotlin.math.abs
import kotlin.math.max

data class ScanCol(val x: Int, val z: Int, val y: Int, val water: Boolean)

/** One air-exposed block in a column. [y] is the block itself, not the feet. */
data class SurfaceSample(val y: Int, val water: Boolean)

/**
 * Drops the sky roof when a lower surface exists, then keeps the surface nearest the spawn floor.
 * A lone roof far above the spawn is ignored. Coverage later ignores walls.
 */
fun pickInterior(samples: List<SurfaceSample>, spawnY: Int): SurfaceSample? {
	if (samples.isEmpty()) return null
	val sorted = samples.sortedByDescending { it.y }
	val withoutRoof = if (sorted.size >= 2 && !sorted.first().water) sorted.drop(1) else sorted
	val near = withoutRoof.filter { it.y <= spawnY + 16 }
	if (near.isEmpty()) return null
	return near.minBy { kotlin.math.abs(it.y - (spawnY - 1)) }
}

private fun colKey(x: Int, z: Int): Long = x.toLong() shl 32 or (z.toLong() and 0xffffffffL)

/**
 * Picks land stops, starting at [spawn], whose 60-block circles cover still shore water
 * and enclosed inland water. The walk between stops is ordinary pathfinding.
 */
fun grottoScanStops(
	spawn: ScanPoint,
	cols: List<ScanCol>,
	minX: Int,
	maxX: Int,
	minZ: Int,
	maxZ: Int,
	radius: Double = 60.0,
	maxStops: Int = 8,
	visited: Set<Long>? = null
): List<ScanPoint> {
	val land = HashMap<Long, Int>()
	val water = ArrayList<Pair<Int, Int>>()
	for (col in cols) {
		val key = colKey(col.x, col.z)
		if (col.water) water.add(col.x to col.z) else land[key] = col.y
	}
	if (water.isEmpty()) return listOf(spawn)
	val reachable = reachableLand(spawn, land)
	val waterAt = HashSet<Long>()
	for ((x, z) in water) waterAt.add(colKey(x, z))
	val outside = ArrayDeque<Long>()
	val seen = HashSet<Long>()
	for ((x, z) in water) {
		if (!opensOutside(x, z, waterAt, land, visited, minX, maxX, minZ, maxZ)) continue
		val key = colKey(x, z)
		if (seen.add(key)) outside.add(key)
	}
	while (outside.isNotEmpty()) {
		val key = outside.removeFirst()
		val x = (key shr 32).toInt()
		val z = key.toInt()
		for (dx in intArrayOf(1, -1)) {
			val next = colKey(x + dx, z)
			if (next in waterAt && seen.add(next)) outside.add(next)
		}
		for (dz in intArrayOf(1, -1)) {
			val next = colKey(x, z + dz)
			if (next in waterAt && seen.add(next)) outside.add(next)
		}
	}
	val targets = ArrayList<Pair<Int, Int>>()
	val included = HashSet<Long>()
	for ((x, z) in water) {
		if (!nearLand(x, z, reachable)) continue
		val key = colKey(x, z)
		if (key in seen) {
			if (included.add(key)) targets.add(x to z)
			continue
		}
		val pond = ArrayDeque<Long>()
		if (!included.add(key)) continue
		pond.add(key)
		targets.add(x to z)
		while (pond.isNotEmpty()) {
			val at = pond.removeFirst()
			val px = (at shr 32).toInt()
			val pz = at.toInt()
			for (dx in intArrayOf(1, -1)) {
				val next = colKey(px + dx, pz)
				if (next in waterAt && next !in seen && included.add(next)) {
					pond.add(next)
					targets.add((px + dx) to pz)
				}
			}
			for (dz in intArrayOf(1, -1)) {
				val next = colKey(px, pz + dz)
				if (next in waterAt && next !in seen && included.add(next)) {
					pond.add(next)
					targets.add(px to (pz + dz))
				}
			}
		}
	}
	if (targets.isEmpty()) return listOf(spawn)
	val candidates = ArrayList<ScanPoint>()
	for ((key, y) in land) {
		if (key !in reachable) continue
		val x = (key shr 32).toInt()
		val z = key.toInt()
		if ((x and 7) == 0 && (z and 7) == 0) candidates.add(ScanPoint(x, y, z))
	}
	for ((x, z) in targets) {
		for (dx in -2..2) {
			for (dz in -2..2) {
				if (max(abs(dx), abs(dz)) > 2) continue
				val key = colKey(x + dx, z + dz)
				if (key !in reachable) continue
				val y = land[key] ?: continue
				candidates.add(ScanPoint(x + dx, y, z + dz))
			}
		}
	}
	return cover(spawn, targets, candidates, radius * radius, maxStops)
}

/** Open water touches a column we actually read and found empty, or the grotto border. Unloaded neighbors stay closed. */
private fun opensOutside(
	x: Int,
	z: Int,
	waterAt: Set<Long>,
	land: Map<Long, Int>,
	visited: Set<Long>?,
	minX: Int,
	maxX: Int,
	minZ: Int,
	maxZ: Int
): Boolean {
	val steps = arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
	for ((dx, dz) in steps) {
		val nx = x + dx
		val nz = z + dz
		if (nx < minX || nx > maxX || nz < minZ || nz > maxZ) return true
		val key = colKey(nx, nz)
		if (key in waterAt || key in land) continue
		if (visited != null && key !in visited) continue
		return true
	}
	return false
}

/** Land you can walk to from the spawn. A step may not cross the y=50 border. */
private fun reachableLand(spawn: ScanPoint, land: Map<Long, Int>): Map<Long, Int> {
	var start: Long? = null
	var best = Int.MAX_VALUE
	for (dx in -4..4) {
		for (dz in -4..4) {
			val key = colKey(spawn.x + dx, spawn.z + dz)
			val y = land[key] ?: continue
			if (!sameSideOfBorder(y, spawn.y)) continue
			val dist = max(abs(dx), abs(dz))
			if (dist < best) {
				best = dist
				start = key
			}
		}
	}
	val origin = start ?: return emptyMap()
	val seen = HashSet<Long>()
	val queue = ArrayDeque<Long>()
	seen.add(origin)
	queue.add(origin)
	while (queue.isNotEmpty()) {
		val key = queue.removeFirst()
		val x = (key shr 32).toInt()
		val z = key.toInt()
		val y = land[key] ?: continue
		for (dx in -1..1) {
			for (dz in -1..1) {
				if (dx == 0 && dz == 0) continue
				val next = colKey(x + dx, z + dz)
				if (next in seen) continue
				val ny = land[next] ?: continue
				if (!sameSideOfBorder(ny, spawn.y) || !sameSideOfBorder(ny, y)) continue
				if (ny - y > 1) continue
				seen.add(next)
				queue.add(next)
			}
		}
	}
	val out = HashMap<Long, Int>()
	for (key in seen) land[key]?.let { out[key] = it }
	return out
}

private fun sameSideOfBorder(y: Int, other: Int): Boolean = grottoSameFloor(y, other)

/** y=50 splits a temperate grotto. A step onto the other side has to pass y>50 when the spawn is below it. */
fun grottoSameFloor(y: Int, other: Int): Boolean = (y <= 50) == (other <= 50)

fun grottoColKey(x: Int, z: Int): Long = colKey(x, z)

/** Land columns reachable from the spawn without crossing y=50 or jumping up more than one block. */
fun reachableSpawnLand(spawn: ScanPoint, landY: Map<Long, Int>): Set<Long> = reachableLand(spawn, landY).keys

/** True when this water body is on the spawn floor and beside land reached from the spawn. */
fun waterTouchesSpawnLand(spawnY: Int, waterY: Map<Long, Int>, land: Set<Long>): Boolean {
	for ((key, y) in waterY) {
		if (!grottoSameFloor(y, spawnY)) continue
		val x = (key shr 32).toInt()
		val z = key.toInt()
		for (dx in -1..1) {
			for (dz in -1..1) {
				if (grottoColKey(x + dx, z + dz) in land) return true
			}
		}
	}
	return false
}

private fun nearLand(x: Int, z: Int, land: Map<Long, Int>): Boolean {
	for (dx in -4..4) {
		for (dz in -4..4) {
			if (max(abs(dx), abs(dz)) > 4) continue
			if (colKey(x + dx, z + dz) in land) return true
		}
	}
	return false
}

private fun cover(
	spawn: ScanPoint,
	targets: List<Pair<Int, Int>>,
	candidates: List<ScanPoint>,
	radius2: Double,
	maxStops: Int
): List<ScanPoint> {
	val open = targets.toMutableList()
	val stops = ArrayList<ScanPoint>()
	stops.add(spawn)
	fun coveredBy(point: ScanPoint, cell: Pair<Int, Int>): Boolean {
		val dx = (cell.first - point.x).toDouble()
		val dz = (cell.second - point.z).toDouble()
		return dx * dx + dz * dz <= radius2
	}
	open.removeAll { coveredBy(spawn, it) }
	while (open.isNotEmpty() && stops.size < maxStops) {
		var best: ScanPoint? = null
		var bestCount = 0
		var bestDist = Double.MAX_VALUE
		val from = stops.last()
		for (candidate in candidates) {
			var count = 0
			for (cell in open) {
				if (coveredBy(candidate, cell)) count++
			}
			if (count == 0) continue
			val dx = (candidate.x - from.x).toDouble()
			val dz = (candidate.z - from.z).toDouble()
			val dist = dx * dx + dz * dz
			if (count > bestCount || (count == bestCount && dist < bestDist)) {
				best = candidate
				bestCount = count
				bestDist = dist
			}
		}
		val chosen = best ?: break
		stops.add(chosen)
		open.removeAll { coveredBy(chosen, it) }
	}
	return stops
}
