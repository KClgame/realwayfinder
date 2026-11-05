package kcl.realwayfinder.nav

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/** Horizontal slack around the drawn route. Water may sag one block under the surface. */
const val CORRIDOR_XZ = 4.0
const val WATER_DROP = 1.0
const val LAND_DROP = 0.6
const val ROUTE_RISE = 1.7

data class TrackPoint(val x: Double, val y: Double, val z: Double, val water: Boolean)

fun trackOf(route: Route): List<TrackPoint> {
	if (route.curve.size >= 2) {
		return route.curve.map { point ->
			TrackPoint(point.x, point.y, point.z, point.kind == EdgeKind.FLOAT || point.kind == EdgeKind.SWIM)
		}
	}
	return route.steps.map { step ->
		val node = step.node
		TrackPoint(node.x + 0.5, node.y.toDouble(), node.z + 0.5, node.mode != Mode.LAND)
	}
}

/**
 * True when the player has left the route.
 * On a water segment, feet may sit up to one block under the surface.
 */
fun offRoute(px: Double, py: Double, pz: Double, track: List<TrackPoint>): Boolean {
	val near = nearest(px, pz, track) ?: return false
	if (near.horizontal > CORRIDOR_XZ) return true
	val drop = if (near.water) WATER_DROP else LAND_DROP
	return py < near.y - drop || py > near.y + ROUTE_RISE
}

data class RouteNear(val horizontal: Double, val y: Double, val water: Boolean, val index: Int)

fun nearest(px: Double, pz: Double, track: List<TrackPoint>): RouteNear? {
	if (track.isEmpty()) return null
	var bestH = Double.MAX_VALUE
	var bestY = track[0].y
	var bestWater = track[0].water
	var bestIndex = 0
	if (track.size == 1) {
		val only = track[0]
		return RouteNear(hypot(px - only.x, pz - only.z), only.y, only.water, 0)
	}
	for (i in 0 until track.lastIndex) {
		val a = track[i]
		val b = track[i + 1]
		val dx = b.x - a.x
		val dz = b.z - a.z
		val len2 = dx * dx + dz * dz
		val t = if (len2 < 1.0e-8) 0.0 else ((px - a.x) * dx + (pz - a.z) * dz) / len2
		val clamped = t.coerceIn(0.0, 1.0)
		val x = a.x + dx * clamped
		val z = a.z + dz * clamped
		val horizontal = hypot(px - x, pz - z)
		if (horizontal < bestH) {
			bestH = horizontal
			bestY = a.y + (b.y - a.y) * clamped
			bestWater = if (clamped < 0.5) a.water else b.water
			bestIndex = if (clamped < 0.85) i else min(i + 1, track.lastIndex)
		}
	}
	return RouteNear(bestH, bestY, bestWater, bestIndex)
}

fun lookahead(px: Double, pz: Double, track: List<TrackPoint>, distance: Double = 5.0): TrackPoint? {
	val near = nearest(px, pz, track) ?: return null
	if (track.size == 1 || near.horizontal > CORRIDOR_XZ) return track[near.index]
	var left = distance
	var index = near.index.coerceIn(0, track.lastIndex)
	var x = track[index].x
	var z = track[index].z
	while (index < track.lastIndex && left > 0.0) {
		val next = track[index + 1]
		val step = hypot(next.x - x, next.z - z)
		if (step < 1.0e-4) {
			index++
			continue
		}
		if (step >= left) {
			val t = left / step
			return TrackPoint(x + (next.x - x) * t, next.y, z + (next.z - z) * t, next.water)
		}
		left -= step
		x = next.x
		z = next.z
		index++
	}
	return track.last()
}

fun wrapDegrees(delta: Float): Float {
	var value = delta
	while (value > 180f) value -= 360f
	while (value < -180f) value += 360f
	return value
}

fun approachAngle(current: Float, target: Float, step: Float): Float {
	val delta = wrapDegrees(target - current)
	if (abs(delta) <= step) return target
	return current + if (delta > 0f) step else -step
}
