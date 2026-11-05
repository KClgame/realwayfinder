package kcl.realwayfinder.nav

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Keeps a walkable centerline, then bends it into a flowing curve.
 * The tangent can sit at any angle. Jump, fall, and shore stays are straight.
 */
fun smoothRoute(grid: Grid, route: Route): Route {
	if (route.steps.size < 2) return route
	return route.copy(curve = weave(grid, route.steps))
}

private const val SPACING = 0.32
private const val TAU = PI * 2.0

private fun EdgeKind.soft(): Boolean = this == EdgeKind.WALK

private data class V(val x: Double, val z: Double)

private fun weave(grid: Grid, steps: List<RouteStep>): List<CurvePoint> {
	val out = ArrayList<CurvePoint>()
	var i = 0
	while (i < steps.lastIndex) {
		val nextKind = steps[i + 1].kind
		val sameLayer = steps[i + 1].node.y == steps[i].node.y &&
			steps[i + 1].node.mode == steps[i].node.mode
		if (!nextKind.soft() || !sameLayer) {
			addCenter(out, steps[i].node, if (out.isEmpty()) EdgeKind.WALK else out.last().kind)
			addCenter(out, steps[i + 1].node, nextKind)
			i++
			continue
		}
		var end = i + 1
		while (
			end < steps.lastIndex &&
			steps[end + 1].kind.soft() &&
			steps[end + 1].node.mode == steps[i].node.mode &&
			steps[end + 1].node.y == steps[i].node.y
		) {
			end++
		}
		val nodes = ArrayList<Node>(end - i + 1)
		for (k in i..end) nodes.add(steps[k].node)
		for (point in weaveRun(grid, nodes, steps[i + 1].kind)) {
			if (out.isEmpty() || hypot(out.last().x - point.x, out.last().z - point.z) > 0.04) out.add(point)
		}
		i = end
	}
	return out
}

private fun weaveRun(grid: Grid, nodes: List<Node>, kind: EdgeKind): List<CurvePoint> {
	val ref = nodes[0]
	val centerline = skeleton(grid, nodes, kind)
	val samples = resample(centerline)
	if (samples.size < 2) return listOf(centerOf(ref, kind))
	val total = samples.last().s.coerceAtLeast(1.0e-4)
	val waved = ArrayList<CurvePoint>(samples.size)
	var previous = V(samples[0].x, samples[0].z)
	for (index in samples.indices) {
		val sample = samples[index]
		val base = V(sample.x, sample.z)
		val normal = normalAt(samples, index)
		val offset = wave(sample.s, total)
		val desired = V(base.x + normal.x * offset, base.z + normal.z * offset)
		val placed = project(grid, ref, base, desired, if (index == 0) null else previous)
		previous = placed
		waved.add(CurvePoint(placed.x, ref.y.toDouble(), placed.z, kind))
	}
	val relaxed = relax(grid, ref, waved)
	val start = centerOf(nodes.first(), kind)
	val goal = centerOf(nodes.last(), kind)
	relaxed[0] = start
	relaxed[relaxed.lastIndex] = goal
	return relaxed
}

/** Two overlapping bends, faded out at the run's ends so the start and the goal stay put. */
private fun wave(distance: Double, total: Double): Double {
	val fade = sin(PI * (distance / total).coerceIn(0.0, 1.0))
	val broad = sin(distance * TAU / 11.0) * 2.6
	val tight = sin(distance * TAU / 5.6 + 0.8) * 1.15
	return (broad + tight) * fade
}

private fun skeleton(grid: Grid, nodes: List<Node>, kind: EdgeKind): List<Node> {
	if (nodes.size < 3) return nodes
	val steps = ArrayList<RouteStep>(nodes.size)
	steps.add(RouteStep(nodes[0], EdgeKind.WALK))
	for (index in 1 until nodes.size) steps.add(RouteStep(nodes[index], kind))
	return pull(grid, steps).map { it.node }
}

private data class Sample(val x: Double, val z: Double, val s: Double)

private fun resample(nodes: List<Node>): List<Sample> {
	val raw = nodes.map { V(it.x + 0.5, it.z + 0.5) }
	if (raw.size < 2) return listOf(Sample(raw[0].x, raw[0].z, 0.0))
	val out = ArrayList<Sample>()
	out.add(Sample(raw[0].x, raw[0].z, 0.0))
	var covered = 0.0
	var target = SPACING
	for (index in 1 until raw.size) {
		val from = raw[index - 1]
		val to = raw[index]
		val segment = hypot(to.x - from.x, to.z - from.z)
		if (segment < 1.0e-8) continue
		while (covered + segment >= target - 1.0e-8) {
			val t = ((target - covered) / segment).coerceIn(0.0, 1.0)
			out.add(Sample(from.x + (to.x - from.x) * t, from.z + (to.z - from.z) * t, target))
			target += SPACING
		}
		covered += segment
	}
	val end = raw.last()
	if (hypot(out.last().x - end.x, out.last().z - end.z) > 0.02) {
		out.add(Sample(end.x, end.z, covered))
	}
	return out
}

private fun normalAt(samples: List<Sample>, index: Int): V {
	val from = samples[(index - 4).coerceAtLeast(0)]
	val to = samples[(index + 4).coerceAtMost(samples.lastIndex)]
	val tx = to.x - from.x
	val tz = to.z - from.z
	val length = hypot(tx, tz)
	if (length < 1.0e-6) return V(0.0, 0.0)
	return V(-tz / length, tx / length)
}

private fun relax(grid: Grid, ref: Node, points: List<CurvePoint>): MutableList<CurvePoint> {
	var current = points
	repeat(4) {
		val next = ArrayList(current)
		for (index in 1 until current.lastIndex) {
			val left = current[index - 1]
			val right = current[index + 1]
			val blended = V(
				current[index].x * 0.4 + (left.x + right.x) * 0.3,
				current[index].z * 0.4 + (left.z + right.z) * 0.3
			)
			val placed = project(
				grid,
				ref,
				V(current[index].x, current[index].z),
				blended,
				V(next[index - 1].x, next[index - 1].z)
			)
			next[index] = current[index].copy(x = placed.x, z = placed.z)
		}
		current = next
	}
	return current.toMutableList()
}

/** Slides from [base] toward [desired] and keeps the body on walkable ground. */
private fun project(grid: Grid, ref: Node, base: V, desired: V, previous: V?): V {
	if (fits(grid, ref, desired) && (previous == null || segmentFits(grid, ref, previous, desired))) return desired
	var low = 0.0
	var high = 1.0
	var best = base
	repeat(10) {
		val mid = (low + high) * 0.5
		val point = V(base.x + (desired.x - base.x) * mid, base.z + (desired.z - base.z) * mid)
		if (fits(grid, ref, point) && (previous == null || segmentFits(grid, ref, previous, point))) {
			best = point
			low = mid
		} else {
			high = mid
		}
	}
	return best
}

private fun segmentFits(grid: Grid, ref: Node, from: V, to: V): Boolean {
	val distance = hypot(to.x - from.x, to.z - from.z)
	val count = (distance / 0.2).toInt().coerceAtLeast(1)
	for (step in 0..count) {
		val t = step / count.toDouble()
		val point = V(from.x + (to.x - from.x) * t, from.z + (to.z - from.z) * t)
		if (!fits(grid, ref, point)) return false
	}
	return true
}

private fun fits(grid: Grid, ref: Node, point: V): Boolean {
	val minX = floor(point.x - 0.3).toInt()
	val maxX = floor(point.x + 0.299).toInt()
	val minZ = floor(point.z - 0.3).toInt()
	val maxZ = floor(point.z + 0.299).toInt()
	for (x in minX..maxX) {
		for (z in minZ..maxZ) {
			if (!stands(grid, ref, x, z)) return false
		}
	}
	return true
}

private fun pull(grid: Grid, steps: List<RouteStep>): List<RouteStep> {
	val kept = ArrayList<RouteStep>(steps.size)
	kept.add(steps[0])
	var i = 0
	while (i < steps.lastIndex) {
		if (!steps[i + 1].kind.soft()) {
			kept.add(steps[i + 1])
			i++
			continue
		}
		var endRun = i + 1
		while (
			endRun < steps.lastIndex &&
			steps[endRun + 1].kind.soft() &&
			steps[endRun + 1].node.mode == steps[i].node.mode &&
			steps[endRun + 1].node.y == steps[i].node.y
		) {
			endRun++
		}
		var best = i + 1
		for (j in endRun downTo (i + 2)) {
			if (canConnect(grid, steps[i].node, steps[j].node)) {
				best = j
				break
			}
		}
		kept.add(steps[best])
		i = best
	}
	return kept
}

internal fun canConnect(grid: Grid, a: Node, b: Node): Boolean {
	if (a == b) return true
	if (a.mode != b.mode || a.y != b.y) return false
	if (!occupiable(grid, a) || !occupiable(grid, b)) return false
	val dx = (b.x - a.x).toDouble()
	val dz = (b.z - a.z).toDouble()
	val length = hypot(dx, dz)
	val count = (length * 4.0).toInt().coerceAtLeast(1)
	for (step in 0..count) {
		val t = step / count.toDouble()
		val x = a.x + 0.5 + dx * t
		val z = a.z + 0.5 + dz * t
		if (!footprintOpen(grid, a, x, z)) return false
	}
	return true
}

/** The player box around this point stays out of walls. */
private fun footprintOpen(grid: Grid, ref: Node, x: Double, z: Double): Boolean {
	val minX = floor(x - 0.3).toInt()
	val maxX = floor(x + 0.299).toInt()
	val minZ = floor(z - 0.3).toInt()
	val maxZ = floor(z + 0.299).toInt()
	for (cx in minX..maxX) {
		for (cz in minZ..maxZ) {
			if (!stands(grid, ref, cx, cz)) return false
		}
	}
	return true
}

private fun stands(grid: Grid, ref: Node, x: Int, z: Int): Boolean = when (ref.mode) {
	Mode.LAND -> isLand(grid, x, ref.y, z)
	Mode.FLOAT -> floatNode(grid, x, z)?.y == ref.y
	Mode.SWIM -> isSwim(grid, x, ref.y, z)
}

private fun addCenter(out: MutableList<CurvePoint>, node: Node, kind: EdgeKind) {
	val point = centerOf(node, kind)
	if (out.isEmpty() || hypot(out.last().x - point.x, out.last().z - point.z) > 0.04) out.add(point)
}

private fun centerOf(node: Node, kind: EdgeKind) =
	CurvePoint(node.x + 0.5, node.y.toDouble(), node.z + 0.5, kind)
