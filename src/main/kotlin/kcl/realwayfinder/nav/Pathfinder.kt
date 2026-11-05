package kcl.realwayfinder.nav

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.sqrt

data class RouteStep(val node: Node, val kind: EdgeKind)

/** A point on the drawn curve. x/z are world coordinates, y is the feet block. */
data class CurvePoint(val x: Double, val y: Double, val z: Double, val kind: EdgeKind)

data class Route(
	val steps: List<RouteStep>,
	val complete: Boolean,
	val curve: List<CurvePoint> = emptyList()
) {
	val nodes: List<Node> get() = steps.map { it.node }
}

private data class Link(val from: Node, val kind: EdgeKind)

fun findPath(
	grid: Grid,
	start: Node,
	goals: Set<Node>,
	limit: Int = 120_000,
	penalty: (Node, Node) -> Double = { _, _ -> 0.0 },
	ceiling: Int? = null
): Route? {
	val allowed = goals.filterTo(HashSet()) { occupiable(grid, it) && (ceiling == null || it.y < ceiling) }
	if (allowed.isEmpty()) return null
	if (start in allowed) return Route(listOf(RouteStep(start, EdgeKind.WALK)), true)
	val work = PathSearch(grid, start, allowed, limit, penalty, ceiling)
	while (!work.finished) work.step(8_000)
	return work.route
}

/**
 * A* split into slices so one client tick never walks the whole grid.
 * Land is searched first. A partial land path is used as-is, so a hard stretch still moves
 * the player closer instead of freezing while a second water search runs.
 */
class PathSearch(
	private val grid: Grid,
	private val start: Node,
	private val goals: Set<Node>,
	private val limit: Int,
	private val penalty: (Node, Node) -> Double,
	private val ceiling: Int? = null
) {
	var finished: Boolean = false
		private set
	var route: Route? = null
		private set

	private var allowWater = false
	private var landPartial: Route? = null
	private var expanded = 0
	private var closest = start
	private var closestH = Double.MAX_VALUE
	private val goalList = goals.toList()
	private val gScore = HashMap<Node, Double>()
	private val came = HashMap<Node, Link>()
	private val closed = HashSet<Node>()
	private val order = intArrayOf(0)
	private val open = PriorityQueue<Open>(compareBy<Open> { it.f }.thenBy { it.seq })
	private val motion = Motion(grid)
	private var primed = false

	fun step(budget: Int): Boolean {
		if (finished) return true
		if (!primed) {
			primed = true
			closestH = heuristic(start, goalList)
			gScore[start] = 0.0
			open.add(Open(start, closestH, order[0]++))
		}
		var left = budget.coerceAtLeast(1)
		while (left > 0 && open.isNotEmpty() && expanded < limit) {
			val current = open.poll().node
			if (!closed.add(current)) continue
			expanded++
			left--
			val h = heuristic(current, goalList)
			if (h < closestH) {
				closestH = h
				closest = current
			}
			if (current in goals) {
				finish(Route(rebuild(current, start, came), true))
				return true
			}
			val base = gScore[current] ?: continue
			for (edge in edges(grid, current, motion, allowWater)) {
				if (ceiling != null && edge.to.y >= ceiling) continue
				if (edge.to in closed) continue
				val next = base + travelCost(current, edge) + penalty(current, edge.to)
				if (next >= (gScore[edge.to] ?: Double.MAX_VALUE)) continue
				gScore[edge.to] = next
				came[edge.to] = Link(current, edge.kind)
				open.add(Open(edge.to, next + heuristic(edge.to, goalList), order[0]++))
			}
		}
		if (open.isNotEmpty() && expanded < limit) return false
		val partial = if (closest == start) null else Route(rebuild(closest, start, came), false)
		if (!allowWater) {
			landPartial = partial
			allowWater = true
			open.clear()
			closed.clear()
			gScore.clear()
			came.clear()
			expanded = 0
			closest = start
			closestH = heuristic(start, goalList)
			gScore[start] = 0.0
			open.add(Open(start, closestH, order[0]++))
			return false
		}
		val water = partial
		val chosen = when {
			water?.complete == true -> water
			landPartial?.complete == true -> landPartial
			(water?.steps?.size ?: 0) >= (landPartial?.steps?.size ?: 0) -> water
			else -> landPartial
		}
		finish(chosen)
		return true
	}

	private fun finish(found: Route?) {
		finished = true
		route = when {
			found == null -> null
			found.complete && !allowWater -> smoothRoute(grid, found)
			else -> smoothRoute(grid, found.copy(steps = straightenWater(found.steps)))
		}
	}
}

private fun straightenWater(steps: List<RouteStep>): List<RouteStep> {
	if (steps.size < 3) return steps
	val out = ArrayList<RouteStep>()
	out.add(steps[0])
	var i = 1
	while (i < steps.size) {
		if (steps[i].kind != EdgeKind.FLOAT) {
			out.add(steps[i])
			i++
			continue
		}
		val dx = steps[i].node.x - steps[i - 1].node.x
		val dz = steps[i].node.z - steps[i - 1].node.z
		var j = i
		while (j + 1 < steps.size && steps[j + 1].kind == EdgeKind.FLOAT) {
			val ndx = steps[j + 1].node.x - steps[j].node.x
			val ndz = steps[j + 1].node.z - steps[j].node.z
			if (ndx != dx || ndz != dz) break
			j++
		}
		out.add(steps[j])
		i = j + 1
	}
	return out
}

private data class Open(val node: Node, val f: Double, val seq: Int)

private data class Edge(val to: Node, val kind: EdgeKind)

private val DIRS = arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
private val DIAG = arrayOf(1 to 1, 1 to -1, -1 to 1, -1 to -1)

/**
 * A land step inside the 3x3x3 cube that is not on the route the player is stuck against.
 * Water is only used when the cube has no land exit.
 */
fun escapeStep(grid: Grid, from: Node, blocked: Set<Pair<Int, Int>>): Node? {
	if (!isLand(grid, from.x, from.y, from.z)) return null
	val motion = Motion(grid)
	val land = ArrayList<Edge>()
	landEdges(grid, from, motion, land, allowWater = false)
	val free = land.map { it.to }.filter { stepInCube(from, it) && (it.x to it.z) !in blocked }
	if (free.isNotEmpty()) return free.first()
	val water = ArrayList<Edge>()
	landEdges(grid, from, motion, water, allowWater = true)
	return water.map { it.to }.firstOrNull { it.mode == Mode.FLOAT && stepInCube(from, it) && (it.x to it.z) !in blocked }
}

private fun stepInCube(from: Node, to: Node): Boolean =
	abs(to.x - from.x) <= 1 && abs(to.y - from.y) <= 1 && abs(to.z - from.z) <= 1 &&
		(to.x != from.x || to.z != from.z)

/** True when every step off this cell is water. Land, lily, jump, and fall do not count. */
fun mustLeaveOnWater(grid: Grid, node: Node): Boolean {
	if (node.mode != Mode.LAND || !isLand(grid, node.x, node.y, node.z)) return true
	val out = ArrayList<Edge>()
	landEdges(grid, node, Motion(grid), out, allowWater = false)
	return out.isEmpty()
}

private fun edges(grid: Grid, node: Node, motion: Motion, allowWater: Boolean): List<Edge> {
	val out = ArrayList<Edge>(8)
	when (node.mode) {
		Mode.LAND -> landEdges(grid, node, motion, out, allowWater)
		Mode.FLOAT -> if (allowWater) surfaceEdges(grid, node, out)
		Mode.SWIM -> if (allowWater) swimEdges(grid, node, out)
	}
	return out
}

private fun landEdges(grid: Grid, node: Node, motion: Motion, out: MutableList<Edge>, allowWater: Boolean) {
	for ((dx, dz) in DIRS) {
		val nx = node.x + dx
		val nz = node.z + dz
		if (isLand(grid, nx, node.y, nz)) {
			out.add(Edge(Node(nx, node.y, nz, Mode.LAND), EdgeKind.WALK))
		} else {
			fallFrom(grid, node, nx, nz, allowWater)?.let { out.add(it) }
			if (isLand(grid, nx, node.y + 1, nz) && motion.jumpReaches(node, Node(nx, node.y + 1, nz, Mode.LAND), sprint = false)) {
				out.add(Edge(Node(nx, node.y + 1, nz, Mode.LAND), EdgeKind.JUMP))
			}
			if (allowWater) {
				val surface = floatNode(grid, nx, nz)
				if (surface != null && surface.y <= node.y + 1 && surface.y >= node.y - 1) {
					out.add(Edge(surface, EdgeKind.FLOAT))
				}
				for (dy in intArrayOf(0, -1, -2)) {
					if (isSwim(grid, nx, node.y + dy, nz)) {
						out.add(Edge(Node(nx, node.y + dy, nz, Mode.SWIM), EdgeKind.SWIM))
					}
				}
			}
		}
	}
	for ((dx, dz) in DIAG) {
		val nx = node.x + dx
		val nz = node.z + dz
		if (!isLand(grid, nx, node.y, nz)) continue
		if (!isLand(grid, nx, node.y, node.z) || !isLand(grid, node.x, node.y, nz)) continue
		out.add(Edge(Node(nx, node.y, nz, Mode.LAND), EdgeKind.WALK))
	}
}

private fun fallFrom(grid: Grid, node: Node, nx: Int, nz: Int, allowWater: Boolean): Edge? {
	if (grid.get(nx, node.y, nz).blocksBody() || grid.get(nx, node.y + 1, nz).blocksBody()) return null
	var y = node.y - 1
	while (y >= grid.originY) {
		val cell = grid.get(nx, y, nz)
		if (cell == Cell.UNKNOWN || cell == Cell.TALL) return null
		if (cell == Cell.WATER) {
			if (!allowWater) return null
			val surface = floatNode(grid, nx, nz) ?: return null
			return Edge(surface, EdgeKind.FLOAT)
		}
		if (isLand(grid, nx, y, nz)) return Edge(Node(nx, y, nz, Mode.LAND), EdgeKind.FALL)
		if (cell == Cell.SOLID) return null
		y--
	}
	return null
}

private fun swimEdges(grid: Grid, node: Node, out: MutableList<Edge>) {
	for (dy in intArrayOf(1, -1)) {
		if (isSwim(grid, node.x, node.y + dy, node.z)) {
			out.add(Edge(Node(node.x, node.y + dy, node.z, Mode.SWIM), EdgeKind.SWIM))
		}
	}
	val here = floatNode(grid, node.x, node.z)
	if (here != null && here.y >= node.y) out.add(Edge(here, EdgeKind.FLOAT))
	for ((dx, dz) in DIRS) {
		val nx = node.x + dx
		val nz = node.z + dz
		for (dy in intArrayOf(0, 1, -1)) {
			if (isSwim(grid, nx, node.y + dy, nz)) {
				out.add(Edge(Node(nx, node.y + dy, nz, Mode.SWIM), EdgeKind.SWIM))
			}
		}
		val surface = floatNode(grid, nx, nz)
		if (surface != null && abs(surface.y - node.y) <= 2) out.add(Edge(surface, EdgeKind.FLOAT))
		for (dy in 0..2) {
			if (isLand(grid, nx, node.y + dy, nz)) {
				out.add(Edge(Node(nx, node.y + dy, nz, Mode.LAND), EdgeKind.SHORE))
			}
		}
	}
}

private fun surfaceEdges(grid: Grid, node: Node, out: MutableList<Edge>) {
	for ((dx, dz) in DIRS) {
		val next = floatNode(grid, node.x + dx, node.z + dz)
		if (next != null && abs(next.y - node.y) <= 1) out.add(Edge(next, EdgeKind.FLOAT))
		if (isLand(grid, node.x + dx, node.y, node.z + dz)) {
			out.add(Edge(Node(node.x + dx, node.y, node.z + dz, Mode.LAND), EdgeKind.SHORE))
		}
		val lower = node.y - 1
		if (lower - node.y <= 0 && isLand(grid, node.x + dx, lower, node.z + dz)) {
			out.add(Edge(Node(node.x + dx, lower, node.z + dz, Mode.LAND), EdgeKind.SHORE))
		}
	}
	for ((dx, dz) in DIAG) {
		val dest = floatNode(grid, node.x + dx, node.z + dz) ?: continue
		if (abs(dest.y - node.y) > 1) continue
		val sideA = floatNode(grid, node.x + dx, node.z) ?: continue
		val sideB = floatNode(grid, node.x, node.z + dz) ?: continue
		if (abs(sideA.y - node.y) > 1 || abs(sideB.y - node.y) > 1) continue
		out.add(Edge(dest, EdgeKind.FLOAT))
	}
}

private fun travelCost(from: Node, edge: Edge): Double = cost(edge.kind)

private fun cost(kind: EdgeKind): Double = when (kind) {
	EdgeKind.WALK -> 1.0
	EdgeKind.JUMP -> 1.7
	EdgeKind.SPRINT_JUMP -> 8.0
	EdgeKind.FALL -> 0.8
	EdgeKind.FLOAT -> 1.0
	EdgeKind.SWIM -> 1.0
	EdgeKind.SHORE -> 2.0
}

private fun heuristic(node: Node, goals: List<Node>): Double {
	var best = Double.MAX_VALUE
	for (goal in goals) {
		val dx = (node.x - goal.x).toDouble()
		val dy = (node.y - goal.y).toDouble()
		val dz = (node.z - goal.z).toDouble()
		val dist = sqrt(dx * dx + dy * dy + dz * dz)
		if (dist < best) best = dist
	}
	// Diagonal walk costs 1 for sqrt(2) blocks. A tighter guide reaches past 150 blocks
	// before the expansion limit, and still does not exceed that step cost.
	return best * 0.70710678118
}

/**
 * Land cells that count as standing on a scan marker.
 * The marker's own height is the only floor. A cell under the player's feet on a different floor is not a goal.
 */
fun scanStandGoals(x: Int, y: Int, z: Int, isLand: (Int, Int, Int) -> Boolean): Set<Node> {
	val goals = LinkedHashSet<Node>()
	for (span in intArrayOf(1, 2)) {
		for (dx in -span..span) {
			for (dz in -span..span) {
				for (dy in -3..3) {
					val cx = x + dx
					val cy = y + dy
					val cz = z + dz
					if (isLand(cx, cy, cz)) goals.add(Node(cx, cy, cz, Mode.LAND))
				}
			}
		}
		if (goals.isNotEmpty()) break
	}
	return goals
}

/** A stand the body can occupy. A cell inside a wall is not a goal. */
fun occupiable(grid: Grid, node: Node): Boolean = when (node.mode) {
	Mode.LAND -> isLand(grid, node.x, node.y, node.z)
	Mode.FLOAT -> floatNode(grid, node.x, node.z)?.y == node.y
	Mode.SWIM -> isSwim(grid, node.x, node.y, node.z)
}

/**
 * The standable land closest to this column.
 * Horizontal distance wins, then the smaller height difference. Cells inside walls are skipped.
 */
fun nearestStand(grid: Grid, x: Int, y: Int, z: Int, radius: Int = 16, ceiling: Int? = null): Node? {
	var best: Node? = null
	var bestDist = Int.MAX_VALUE
	var bestDy = Int.MAX_VALUE
	for (dx in -radius..radius) {
		for (dz in -radius..radius) {
			val dist = dx * dx + dz * dz
			if (dist > bestDist) continue
			for (dy in -6..6) {
				val ny = y + dy
				if (ceiling != null && ny >= ceiling) continue
				if (!isLand(grid, x + dx, ny, z + dz)) continue
				val ady = abs(dy)
				if (dist < bestDist || (dist == bestDist && ady < bestDy)) {
					bestDist = dist
					bestDy = ady
					best = Node(x + dx, ny, z + dz, Mode.LAND)
				}
			}
		}
	}
	return best
}

/**
 * A point toward the stored spot that still lies on a loaded chunk.
 * The server view distance can hide the spot itself; the next walk only needs the visible stretch.
 */
fun loadedApproach(
	px: Int,
	pz: Int,
	sx: Int,
	sz: Int,
	reach: Double,
	loaded: (Int, Int) -> Boolean
): Pair<Int, Int> {
	var left = reach
	while (left >= 8.0) {
		val aim = approachPoint(px, pz, sx, sz, left)
		if (loaded(aim.first, aim.second)) return aim
		left -= 8.0
	}
	return approachPoint(px, pz, sx, sz, 8.0)
}

/** A point at most [reach] blocks from the player, on the line toward the spot. */
fun approachPoint(px: Int, pz: Int, sx: Int, sz: Int, reach: Double): Pair<Int, Int> {
	val dx = (sx - px).toDouble()
	val dz = (sz - pz).toDouble()
	val dist = hypot(dx, dz)
	if (dist <= reach || dist < 1.0e-6) return sx to sz
	val scale = reach / dist
	return (px + round(dx * scale).toInt()) to (pz + round(dz * scale).toInt())
}

private fun rebuild(end: Node, start: Node, came: Map<Node, Link>): List<RouteStep> {
	val nodes = ArrayList<Node>()
	val kinds = ArrayList<EdgeKind>()
	var cursor = end
	while (cursor != start) {
		val link = came[cursor] ?: break
		nodes.add(cursor)
		kinds.add(link.kind)
		cursor = link.from
	}
	nodes.add(start)
	nodes.reverse()
	kinds.reverse()
	val steps = ArrayList<RouteStep>(nodes.size)
	steps.add(RouteStep(nodes[0], EdgeKind.WALK))
	for (i in 1 until nodes.size) {
		steps.add(RouteStep(nodes[i], kinds[i - 1]))
	}
	return steps
}
