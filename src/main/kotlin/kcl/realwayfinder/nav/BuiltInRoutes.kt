package kcl.realwayfinder.nav

import kotlin.math.hypot

data class ScanPoint(val x: Int, val y: Int, val z: Int)

data class GrottoMapRoute(
	val name: String,
	val spawn: ScanPoint,
	val minX: Int? = null,
	val maxX: Int? = null,
	val minZ: Int? = null,
	val maxZ: Int? = null
) {
	fun contains(px: Double, pz: Double, margin: Int = 32): Boolean {
		val x0 = minX ?: return false
		val x1 = maxX ?: return false
		val z0 = minZ ?: return false
		val z1 = maxZ ?: return false
		return px >= x0 - margin && px <= x1 + margin && pz >= z0 - margin && pz <= z1 + margin
	}
}

/**
 * Island rings stay fixed. A temperate grotto entry is only the spawn and the land footprint
 * used to recognize the map. Scan stops are chosen from loaded terrain.
 */
object BuiltInRoutes {
	val islandSpawns = mapOf(
		"I1" to ScanPoint(-155, 150, 260),
		"I2" to ScanPoint(1871, 150, 684),
		"I3" to ScanPoint(1860, 151, 1684),
		"I5" to ScanPoint(-192, 151, 1921)
	)

	private val islands = mapOf(
		"I1" to listOf(
			ScanPoint(-207, 154, 153),
			ScanPoint(-328, 151, 210),
			ScanPoint(-383, 156, 291),
			ScanPoint(-352, 157, 314),
			ScanPoint(-298, 150, 283),
			ScanPoint(-264, 150, 342),
			ScanPoint(-206, 153, 382)
		),
		"I2" to listOf(
			ScanPoint(1871, 151, 684),
			ScanPoint(1816, 150, 687),
			ScanPoint(1739, 150, 676),
			ScanPoint(1696, 150, 782),
			ScanPoint(1770, 150, 885),
			ScanPoint(1834, 150, 802),
			ScanPoint(1891, 152, 792),
			ScanPoint(1915, 152, 732)
		),
		"I3" to listOf(
			ScanPoint(1860, 151, 1684),
			ScanPoint(1903, 150, 1733),
			ScanPoint(1886, 152, 1829),
			ScanPoint(1869, 150, 1840),
			ScanPoint(1786, 151, 1884),
			ScanPoint(1740, 158, 1840),
			ScanPoint(1686, 150, 1827),
			ScanPoint(1655, 151, 1737)
		),
		"I4" to listOf(
			ScanPoint(778, 152, 1758),
			ScanPoint(869, 150, 1798),
			ScanPoint(862, 150, 1864),
			ScanPoint(787, 150, 1877),
			ScanPoint(747, 150, 1902),
			ScanPoint(720, 156, 1896),
			ScanPoint(672, 153, 1896),
			ScanPoint(625, 150, 1791),
			ScanPoint(623, 150, 1760),
			ScanPoint(720, 155, 1752),
			ScanPoint(639, 150, 1683),
			ScanPoint(696, 155, 1680),
			ScanPoint(672, 161, 1584),
			ScanPoint(709, 150, 1633),
			ScanPoint(708, 150, 1613),
			ScanPoint(831, 159, 1621),
			ScanPoint(816, 150, 1656),
			ScanPoint(799, 150, 1692),
			ScanPoint(861, 151, 1675),
			ScanPoint(901, 150, 1707),
			ScanPoint(942, 150, 1751),
			ScanPoint(901, 150, 1754)
		),
		"I5" to listOf(
			ScanPoint(-192, 151, 1921),
			ScanPoint(-150, 150, 1914),
			ScanPoint(-243, 150, 1680),
			ScanPoint(-279, 149, 1945),
			ScanPoint(-388, 150, 1807)
		)
	)

	private val grottos = listOf(
		GrottoMapRoute("grotto_temperate_1", ScanPoint(-7467, 42, -4462)),
		GrottoMapRoute("grotto_temperate_2", ScanPoint(2803, 74, -6449)),
		// Land footprint read from saves/grotto_temperate_3 (mcc:fishing-2).
		GrottoMapRoute(
			"grotto_temperate_3",
			ScanPoint(1779, 74, -6449),
			minX = 1710,
			maxX = 1847,
			minZ = -6513,
			maxZ = -6360
		),
		// Land footprint read from saves/grotto_temperate_4 (mcc:fishing-2).
		GrottoMapRoute(
			"grotto_temperate_4",
			ScanPoint(695, 41, -6432),
			minX = 590,
			maxX = 838,
			minZ = -6573,
			maxZ = -6290
		)
	)

	fun island(shortId: String): List<ScanPoint>? = islands[shortId.uppercase()]

	fun matchGrotto(px: Double, pz: Double, maxDist: Double = 2500.0): GrottoMapRoute? {
		val inside = grottos.filter { it.contains(px, pz) }
		if (inside.size == 1) return inside[0]
		val pool = if (inside.isNotEmpty()) inside else grottos
		var best: GrottoMapRoute? = null
		var bestD = maxDist
		for (map in pool) {
			val dist = hypot(map.spawn.x + 0.5 - px, map.spawn.z + 0.5 - pz)
			if (dist <= bestD) {
				bestD = dist
				best = map
			}
		}
		return best
	}
}

/** Net movement over the window. Back-and-forth cancels out. Land only, 3 seconds, 2 blocks. */
fun landBarelyMoved(dx: Double, dy: Double, dz: Double, ticks: Int): Boolean {
	if (ticks < 60) return false
	return kotlin.math.abs(dx) + kotlin.math.abs(dy) + kotlin.math.abs(dz) <= 2.0
}
