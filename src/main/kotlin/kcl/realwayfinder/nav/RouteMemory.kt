package kcl.realwayfinder.nav

/**
 * Edges that kept failing on the way to a goal. One failure adds a small cost.
 * A second failure makes that edge much worse, so the next search takes another path.
 * After that other path reaches the goal, the costs are saved and reused.
 */
object RouteMemory {
	private val counts = HashMap<String, Int>()

	fun clear() {
		counts.clear()
	}

	fun penalty(goalX: Int, goalZ: Int, ax: Int, az: Int, bx: Int, bz: Int): Double {
		val n = counts[key(goalX, goalZ, ax, az, bx, bz)] ?: return 0.0
		return when {
			n >= 2 -> 80.0
			n == 1 -> 20.0
			else -> 0.0
		}
	}

	fun revision(goalX: Int, goalZ: Int): Int {
		val prefix = "$goalX,$goalZ|"
		var sum = 0
		for ((edge, count) in counts) {
			if (edge.startsWith(prefix)) sum += count
		}
		return sum
	}

	fun hasFailure(goalX: Int, goalZ: Int): Boolean = revision(goalX, goalZ) > 0

	/** @return true when a lesson is strong enough to store */
	fun noteFailure(goalX: Int, goalZ: Int, cells: List<Pair<Int, Int>>): Boolean {
		var save = false
		for (i in 0 until cells.size - 1) {
			val from = cells[i]
			val to = cells[i + 1]
			if (from == to) continue
			val id = key(goalX, goalZ, from.first, from.second, to.first, to.second)
			val next = ((counts[id] ?: 0) + 1).coerceAtMost(6)
			counts[id] = next
			if (next >= 2) save = true
		}
		return save
	}

	fun export(): List<String> {
		val lines = ArrayList<String>()
		lines.add("# realwayfinder-route-memory 1")
		for ((edge, count) in counts) {
			if (count <= 0) continue
			lines.add("$edge $count")
		}
		return lines
	}

	fun load(lines: List<String>) {
		counts.clear()
		for (line in lines) {
			val text = line.trim()
			if (text.isEmpty() || text.startsWith("#")) continue
			val parts = text.split(' ')
			if (parts.size != 2) continue
			val count = parts[1].toIntOrNull() ?: continue
			if (count <= 0 || '|' !in parts[0] || '>' !in parts[0]) continue
			counts[parts[0]] = count.coerceAtMost(6)
		}
	}

	private fun key(goalX: Int, goalZ: Int, ax: Int, az: Int, bx: Int, bz: Int) =
		"$goalX,$goalZ|$ax,$az>$bx,$bz"
}

fun stuckCells(route: Route, px: Int, pz: Int): List<Pair<Int, Int>> {
	val nodes = route.nodes
	if (nodes.size < 2) return emptyList()
	var start = nodes.indexOfFirst { kotlin.math.abs(it.x - px) <= 4 && kotlin.math.abs(it.z - pz) <= 4 }
	if (start < 0) start = 0
	return nodes.drop(start).take(8).map { it.x to it.z }
}
