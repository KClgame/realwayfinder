package kcl.realwayfinder.nav

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * One player tick using the 26.2 ground formula:
 * jump strength 0.42, gravity 0.08, movement speed 0.1 (sprint 0.13),
 * block friction 0.6, air drag 0.91, vertical drag 0.98.
 * Step height is 0.6. Body is 0.6 wide and 1.8 tall.
 */
class Motion(private val grid: Grid) {
	var x = 0.0
	var y = 0.0
	var z = 0.0
	var vx = 0.0
	var vy = 0.0
	var vz = 0.0
	var onGround = true

	fun jumpReaches(from: Node, to: Node, sprint: Boolean): Boolean {
		x = from.x + 0.5
		y = when (grid.get(from.x, from.y, from.z)) {
			Cell.LILY -> from.y + LILY_HEIGHT
			Cell.SLAB -> from.y + 0.5
			else -> from.y.toDouble()
		}
		z = from.z + 0.5
		vx = 0.0
		vy = 0.0
		vz = 0.0
		onGround = true
		val yaw = Math.toDegrees(atan2(-(to.x - from.x).toDouble(), (to.z - from.z).toDouble()))
		repeat(48) {
			tick(yaw, sprint)
			if (onGround && standingOn(to)) return true
		}
		return false
	}

	private fun standingOn(node: Node): Boolean =
		floor(x).toInt() == node.x &&
			floor(z).toInt() == node.z &&
			floor(y + 1.0e-3).toInt() == node.y

	private fun tick(yawDeg: Double, sprint: Boolean) {
		val yaw = Math.toRadians(yawDeg)
		val wishX = -sin(yaw)
		val wishZ = cos(yaw)
		val startedOnGround = onGround
		if (startedOnGround && needsJump(wishX, wishZ)) {
			vy = max(0.42, vy)
			if (sprint) {
				vx += wishX * 0.2
				vz += wishZ * 0.2
			}
		}
		val speed = if (sprint) 0.13 else 0.1
		val blockFriction = if (startedOnGround) 0.6 else 1.0
		val accel = if (blockFriction > 0.6) {
			speed * (0.21600002 / (blockFriction * blockFriction * blockFriction))
		} else {
			speed
		}
		vx += wishX * accel
		vz += wishZ * accel
		stepHorizontal(vx, vz)
		stepVertical(vy)
		val drag = blockFriction * 0.91
		vx *= drag
		vy = (vy - 0.08) * 0.98
		vz *= drag
	}

	private fun needsJump(wishX: Double, wishZ: Double): Boolean {
		val aheadX = floor(x + wishX * 0.45).toInt()
		val aheadZ = floor(z + wishZ * 0.45).toInt()
		val feet = floor(y + 1.0e-3).toInt()
		if (grid.get(aheadX, feet, aheadZ).blocksBody()) return true
		return grid.get(aheadX, feet - 1, aheadZ) != Cell.SOLID
	}

	private fun blocked(px: Double, py: Double, pz: Double): Boolean {
		val minX = floor(px - 0.3).toInt()
		val maxX = floor(px + 0.299).toInt()
		val minY = floor(py).toInt()
		val maxY = floor(py + 1.799).toInt()
		val minZ = floor(pz - 0.3).toInt()
		val maxZ = floor(pz + 0.299).toInt()
		for (bx in minX..maxX) {
			for (bz in minZ..maxZ) {
				for (by in (minY - 1)..maxY) {
					if (bodyHits(grid, bx, by, bz, py, py + 1.8)) return true
				}
			}
		}
		return false
	}

	private fun stepHorizontal(dx: Double, dz: Double) {
		val ox = x
		val oy = y
		val oz = z
		val og = onGround
		if (slide(dx, dz)) return
		x = ox
		y = oy
		z = oz
		onGround = og
		vx = 0.0
		vz = 0.0
		if (!og) return
		var raised = 0.0
		while (raised < 0.6 - 1.0e-6) {
			val step = min(0.05, 0.6 - raised)
			if (blocked(x, y + step, z)) break
			y += step
			raised += step
			if (slide(dx, dz)) {
				settle(raised)
				onGround = true
				return
			}
		}
		x = ox
		y = oy
		z = oz
		onGround = og
	}

	private fun slide(dx: Double, dz: Double): Boolean {
		if (!shift(dx, 0.0, 0.0)) {
			vx = 0.0
			return false
		}
		if (!shift(0.0, 0.0, dz)) {
			vz = 0.0
			return false
		}
		return true
	}

	private fun stepVertical(dy: Double) {
		if (!shift(0.0, dy, 0.0)) {
			if (dy < 0.0) onGround = true
			vy = 0.0
		} else if (dy > 0.0) {
			onGround = false
		}
	}

	private fun shift(dx: Double, dy: Double, dz: Double): Boolean {
		val amount = abs(dx) + abs(dy) + abs(dz)
		if (amount == 0.0) return true
		val sx = if (dx == 0.0) 0.0 else if (dx > 0) 1.0 else -1.0
		val sy = if (dy == 0.0) 0.0 else if (dy > 0) 1.0 else -1.0
		val sz = if (dz == 0.0) 0.0 else if (dz > 0) 1.0 else -1.0
		var left = amount
		while (left > 1.0e-8) {
			val step = min(0.05, left)
			val nx = x + sx * step * (abs(dx) / amount)
			val ny = y + sy * step * (abs(dy) / amount)
			val nz = z + sz * step * (abs(dz) / amount)
			if (blocked(nx, ny, nz)) return false
			x = nx
			y = ny
			z = nz
			left -= step
		}
		return true
	}

	private fun settle(limit: Double) {
		var dropped = 0.0
		while (dropped < limit - 1.0e-6) {
			val step = min(0.05, limit - dropped)
			if (blocked(x, y - step, z)) {
				onGround = true
				return
			}
			y -= step
			dropped += step
		}
	}
}
