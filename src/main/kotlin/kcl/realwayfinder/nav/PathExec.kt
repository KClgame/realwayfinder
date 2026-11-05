package kcl.realwayfinder.nav

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Follows a route one block move at a time.
 * The index is the step being entered. Feet on that cell advance it.
 * A player more than 3 blocks off the current move, or stuck on it, fails the path.
 */
class PathExec {
	var index: Int = 1
		private set
	private var ticksHere = 0
	private var originX = Double.NaN
	private var originZ = Double.NaN
	private var failed = false
	private val visits = HashMap<String, Int>()
	private var lastCell = ""
	private var jumps = 0
	private var sawJump = false
	private var jumpX = 0.0
	private var jumpZ = 0.0

	fun reset() {
		index = 1
		ticksHere = 0
		originX = Double.NaN
		originZ = Double.NaN
		failed = false
		visits.clear()
		lastCell = ""
		jumps = 0
		sawJump = false
	}

	fun takeFailed(): Boolean {
		val hit = failed
		failed = false
		return hit
	}

	fun tick(
		steps: List<RouteStep>,
		px: Double,
		py: Double,
		pz: Double,
		onGround: Boolean,
		lookAhead: Double = 6.0,
		inWater: Boolean = false
	): MoveOrder {
		if (steps.size < 2) return MoveOrder.done()
		val fx = floor(px).toInt()
		val fy = floor(py + 1.0e-4).toInt()
		val fz = floor(pz).toInt()
		var stand = index - 1
		for (i in (index - 1).coerceAtLeast(0) until steps.size) {
			val exactEnd = i == steps.lastIndex
			if (standsOn(steps[i].node, fx, fy, fz, exactY = exactEnd)) stand = i
		}
		if (stand >= index) {
			index = stand + 1
			ticksHere = 0
			originX = Double.NaN
		}
		if (index >= steps.size) return MoveOrder.done()
		val src = steps[index - 1].node
		val dest = steps[index].node
		val kind = steps[index].kind
		val flat = hypot(px - (dest.x + 0.5), pz - (dest.z + 0.5))
		val water = kind == EdgeKind.FLOAT || kind == EdgeKind.SWIM || inWater
		val off = segmentDistance(px, pz, src, dest)
		// On water the line can drift. Getting ashore at the planned land cell is enough.
		if (!water && off > 8.0 && kind != EdgeKind.FALL) {
			failed = true
			return MoveOrder.failed()
		}
		val visitKey = "$fx,$fy,$fz"
		if (visitKey != lastCell) {
			lastCell = visitKey
			val seen = (visits[visitKey] ?: 0) + 1
			visits[visitKey] = seen
			if (!water && seen >= 4) {
				failed = true
				return MoveOrder.failed()
			}
		}
		if (originX.isNaN()) {
			originX = px
			originZ = pz
		}
		ticksHere++
		val crept = hypot(px - originX, pz - originZ)
		if (!water && ticksHere > 100 && crept < 0.2 && kind != EdgeKind.FALL) {
			failed = true
			return MoveOrder.failed()
		}
		if (kind == EdgeKind.JUMP && shouldJump(kind, flat, onGround, dest.y - py)) {
			if (!sawJump) {
				sawJump = true
				jumpX = px
				jumpZ = pz
			}
		} else if (sawJump && onGround) {
			if (hypot(px - jumpX, pz - jumpZ) < 0.35) {
				jumps++
				if (jumps >= 4) {
					jumps = 0
					sawJump = false
					failed = true
					return MoveOrder.failed()
				}
			} else {
				jumps = 0
			}
			sawJump = false
		}
		val dive = kind == EdgeKind.SWIM || dest.mode == Mode.SWIM
		val aimX: Double
		val aimZ: Double
		val pitch: Float
		val aim = straightAim(steps, index)
		aimX = aim.first
		aimZ = aim.second
		if (dive) {
			val node = steps[index].node
			val dx = aimX - px
			val dy = (node.y + 1.0) - (py + 1.62)
			val dz = aimZ - pz
			val flatAim = hypot(dx, dz).coerceAtLeast(1.0e-3)
			pitch = Math.toDegrees(-atan2(dy, flatAim)).toFloat().coerceIn(-75f, 75f)
		} else {
			pitch = 0f
		}
		val yaw = Math.toDegrees(atan2(-(aimX - px), aimZ - pz)).toFloat()
		val descend = dive && dest.y <= floor(py) - 1
		val jump = when {
			dive -> dest.y > py + 0.3
			else -> kind == EdgeKind.FLOAT || shouldJump(kind, flat, onGround, dest.y - py)
		}
		val sprint = !water
		return MoveOrder(true, jump, sprint, yaw, pitch, done = false, failed = false, water = water, descend = descend, dive = dive)
	}
}

/**
 * True while the route itself is a dive. Submersion here is the plan,
 * so the surface escape and the shore-only view stay off.
 */
fun plannedDive(steps: List<RouteStep>, px: Double, py: Double, pz: Double): Boolean {
	if (steps.size < 2) return false
	val fx = floor(px).toInt()
	val fy = floor(py + 1.0e-4).toInt()
	val fz = floor(pz).toInt()
	var here = 0
	for (i in steps.indices) {
		val node = steps[i].node
		if (abs(node.x - fx) <= 1 && abs(node.z - fz) <= 1 && abs(node.y - fy) <= 2) here = i
	}
	if (steps[here].kind == EdgeKind.SWIM || steps[here].node.mode == Mode.SWIM) return true
	val next = steps.getOrNull(here + 1) ?: return false
	return next.kind == EdgeKind.SWIM || next.node.mode == Mode.SWIM
}

data class AimPoint(val x: Double, val y: Double, val z: Double)

/** A point [distance] blocks along the path, in three dimensions. Used so a dive looks into the tunnel. */
fun aimAheadPoint(steps: List<RouteStep>, index: Int, distance: Double): AimPoint {
	var left = distance.coerceAtLeast(1.0)
	var i = index.coerceIn(1, steps.lastIndex)
	var x = steps[i - 1].node.x + 0.5
	var y = steps[i - 1].node.y + 1.0
	var z = steps[i - 1].node.z + 0.5
	while (i < steps.size && left > 0.0) {
		val nextX = steps[i].node.x + 0.5
		val nextY = steps[i].node.y + 1.0
		val nextZ = steps[i].node.z + 0.5
		val step = hypot(hypot(nextX - x, nextZ - z), nextY - y)
		if (step < 1.0e-4) {
			i++
			continue
		}
		if (step >= left) {
			val t = left / step
			return AimPoint(x + (nextX - x) * t, y + (nextY - y) * t, z + (nextZ - z) * t)
		}
		left -= step
		x = nextX
		y = nextY
		z = nextZ
		i++
	}
	val last = steps.last().node
	return AimPoint(last.x + 0.5, last.y + 1.0, last.z + 0.5)
}

/** Look along the next straight cells only. A turn is not looked through. */
fun straightAim(steps: List<RouteStep>, index: Int): Pair<Double, Double> {
	var i = index.coerceIn(1, steps.lastIndex)
	val dx = steps[i].node.x - steps[i - 1].node.x
	val dz = steps[i].node.z - steps[i - 1].node.z
	var extra = 0
	while (extra < 2 && i + 1 < steps.size) {
		val next = steps[i + 1].node
		val prev = steps[i].node
		if (next.x - prev.x != dx || next.z - prev.z != dz || next.y != prev.y) break
		i++
		extra++
	}
	val node = steps[i].node
	return (node.x + 0.5) to (node.z + 0.5)
}

/** The first land cell after this water run, so the view aims at the shore. */
fun waterExit(steps: List<RouteStep>, index: Int): Pair<Double, Double>? {
	var i = index.coerceIn(0, steps.lastIndex)
	var sawWater = false
	while (i < steps.size && (steps[i].kind == EdgeKind.FLOAT || steps[i].kind == EdgeKind.SWIM)) {
		sawWater = true
		i++
	}
	if (!sawWater || i >= steps.size) return null
	val node = steps[i].node
	return (node.x + 0.5) to (node.z + 0.5)
}

fun aimAhead(steps: List<RouteStep>, index: Int, distance: Double): Pair<Double, Double> {
	var left = distance.coerceAtLeast(1.0)
	var i = index.coerceIn(1, steps.lastIndex)
	var x = steps[i - 1].node.x + 0.5
	var z = steps[i - 1].node.z + 0.5
	while (i < steps.size && left > 0.0) {
		val nextX = steps[i].node.x + 0.5
		val nextZ = steps[i].node.z + 0.5
		val step = hypot(nextX - x, nextZ - z)
		if (step < 1.0e-4) {
			i++
			continue
		}
		if (step >= left) {
			val t = left / step
			return (x + (nextX - x) * t) to (z + (nextZ - z) * t)
		}
		left -= step
		x = nextX
		z = nextZ
		i++
	}
	val last = steps.last().node
	return (last.x + 0.5) to (last.z + 0.5)
}

data class MoveOrder(
	val forward: Boolean,
	val jump: Boolean,
	val sprint: Boolean,
	val yaw: Float,
	val pitch: Float,
	val done: Boolean,
	val failed: Boolean,
	val water: Boolean = false,
	val descend: Boolean = false,
	val dive: Boolean = false
) {
	companion object {
		fun done() = MoveOrder(false, false, false, 0f, 0f, done = true, failed = false)
		fun failed() = MoveOrder(false, false, false, 0f, 0f, done = false, failed = true)
	}
}

fun standsOn(node: Node, fx: Int, fy: Int, fz: Int, exactY: Boolean = false): Boolean =
	node.x == fx && node.z == fz && if (exactY) node.y == fy else abs(node.y - fy) <= 1

/** Fishing arrival. Defaults are one block on x, one block on z, and two blocks of height. */
fun nearPlannedEnd(
	end: Node,
	fx: Int,
	fy: Int,
	fz: Int,
	xSlop: Int = 1,
	zSlop: Int = 1,
	ySlop: Int = 2
): Boolean = abs(end.x - fx) <= xSlop && abs(end.z - fz) <= zSlop && abs(end.y - fy) <= ySlop

/** The body is inside the fishing arrival box of this stance. */
fun bodyAtStance(
	px: Double,
	py: Double,
	pz: Double,
	stance: Node,
	xSlop: Int = 1,
	zSlop: Int = 1,
	ySlop: Int = 2
): Boolean {
	val fx = floor(px).toInt()
	val fy = floor(py + 1.0e-4).toInt()
	val fz = floor(pz).toInt()
	return nearPlannedEnd(stance, fx, fy, fz, xSlop, zSlop, ySlop)
}

/** Jump only once the body is close enough to land on the next block. */
fun shouldJump(kind: EdgeKind, flatDistance: Double, onGround: Boolean, rise: Double): Boolean {
	if (kind == EdgeKind.FLOAT || kind == EdgeKind.SHORE) return true
	if (!onGround) return false
	return when (kind) {
		EdgeKind.JUMP -> flatDistance < 1.8 && rise > 0.2
		EdgeKind.SPRINT_JUMP -> flatDistance < 2.3
		else -> false
	}
}

private fun segmentDistance(px: Double, pz: Double, src: Node, dest: Node): Double {
	val ax = src.x + 0.5
	val az = src.z + 0.5
	val bx = dest.x + 0.5
	val bz = dest.z + 0.5
	val dx = bx - ax
	val dz = bz - az
	val len2 = dx * dx + dz * dz
	val t = if (len2 < 1.0e-8) 0.0 else (((px - ax) * dx + (pz - az) * dz) / len2).coerceIn(0.0, 1.0)
	return hypot(px - (ax + dx * t), pz - (az + dz * t))
}
