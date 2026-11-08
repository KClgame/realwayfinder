package kcl.realwayfinder.nav

data class MapStart(val mapX: Int, val mapZ: Int, val x: Int, val y: Int, val z: Int)

private val grottoDiscovery = Regex(
	"""You've discovered a (Temperate|Tropical) Grotto! Travel there through your navigator\.""",
	RegexOption.IGNORE_CASE
)

/** Temperate or Tropical, matching the discovery sentence with that climate name. */
fun discoveredGrottoClimate(text: String): String? =
	grottoDiscovery.find(text)?.groupValues?.get(1)?.replaceFirstChar { it.uppercase() }

fun temperateDiscovery(text: String): Boolean = discoveredGrottoClimate(text).equals("Temperate", ignoreCase = true)

fun tropicalDiscovery(text: String): Boolean = discoveredGrottoClimate(text).equals("Tropical", ignoreCase = true)

/** Action bar telling the player to type /hub. */
fun hubPrompt(text: String): Boolean {
	val plain = text.replace(Regex("§."), "")
	return plain.contains("Enter /hub", ignoreCase = true)
}

/** The grotto instance ended. Island hour refreshes do not match. */
fun grottoClosed(text: String): Boolean {
	val lower = text.lowercase()
	if ("grotto" !in lower) return false
	return "clos" in lower || "collaps" in lower
}

/**
 * The grotto threw the player out. This ends the visit, so its scanned mark is no longer valid.
 * "Your Grotto has become unstable, teleporting you back to safety..."
 */
fun grottoUnstable(text: String): Boolean {
	val plain = text.replace(Regex("§."), "")
	return plain.contains("grotto has become unstable", ignoreCase = true)
}

/** Picks the grotto start whose map anchor is nearest to the player, within maxDist blocks. */
fun matchMapStart(points: List<MapStart>, px: Double, pz: Double, maxDist: Double = 2000.0): MapStart? {
	var best: MapStart? = null
	var bestD = maxDist * maxDist
	for (point in points) {
		val dx = point.mapX - px
		val dz = point.mapZ - pz
		val dist = dx * dx + dz * dz
		if (dist <= bestD) {
			bestD = dist
			best = point
		}
	}
	return best
}
